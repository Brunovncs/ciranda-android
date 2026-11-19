package app.ciranda.android

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Keeps a voice call going with the screen off or another app in front, in a channel or a private
 * call: a foreground service with the microphone, a wake lock and a Wi-Fi lock, and a notification
 * to mute or leave (hang up, in a private call, with how long it has lasted).
 */
class VoiceService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        show(Engine.voice.value)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ciranda:voice").apply { acquire(12 * 60 * 60 * 1000L) }
            @Suppress("DEPRECATION")
            wifi = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ciranda:voice").apply { acquire() }
        }
        if (watch == null) {
            watch = CirandaApp.scope.launch {
                // The call's end is seen here rather than stopped from outside, which could land
                // before the service is in the foreground and crash it. A call refused before it
                // began leaves nothing to wait for either.
                var seen = Engine.voice.value != null
                launch {
                    kotlinx.coroutines.delay(15_000)
                    if (!seen) stopSelf()
                }
                Engine.voice
                    .map { v -> v?.let { listOf(it.channel, it.state, it.muted, it.ringing, it.since) } }
                    .distinctUntilChanged()
                    .collect { shown ->
                        if (shown == null) {
                            if (seen) stopSelf()
                        } else {
                            seen = true
                            show(Engine.voice.value)
                        }
                    }
            }
        }
        running = true
        return START_NOT_STICKY
    }

    private fun show(v: VoiceState?) {
        val pt = Locale.getDefault().language == "pt"
        val private = v?.private == true
        // A private call opens its own screen; a channel, the app.
        val open = if (private) {
            PendingIntent.getActivity(this, 0, CallActivity.intent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        fun action(what: String) = PendingIntent.getBroadcast(
            this, what.hashCode(), Intent(this, Actions::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE,
        )
        val muted = v?.muted == true
        val title = when {
            !private -> if (pt) "Em voz" else "In voice"
            v.ringing -> if (pt) "Chamando…" else "Calling…"
            else -> if (pt) "Em chamada" else "In a call"
        }
        val text = buildString {
            append((if (private) v.peerName else v?.channel) ?: "")
            if (v?.state == VoiceLink.Reconnecting) append(if (pt) " · reconectando" else " · reconnecting")
            if (muted) append(if (pt) " · microfone desligado" else " · muted")
        }
        val since = v?.since
        val n = NotificationCompat.Builder(this, CirandaApp.CHANNEL_VOICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .apply { if (private && since != null) setWhen(since).setShowWhen(true).setUsesChronometer(true) }
            .addAction(0, if (muted) (if (pt) "Ativar microfone" else "Unmute") else (if (pt) "Mutar" else "Mute"), action(if (muted) UNMUTE else MUTE))
            .addAction(0, if (private) (if (pt) "Desligar" else "Hang up") else (if (pt) "Sair" else "Leave"), action(LEAVE))
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    override fun onDestroy() {
        running = false
        watch?.cancel()
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    /** The notification's buttons. */
    class Actions : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MUTE -> Engine.send("mute", "on" to true)
                UNMUTE -> Engine.send("mute", "on" to false)
                LEAVE -> Engine.send("leave")
            }
        }
    }

    companion object {
        @Volatile
        var running = false

        /** Started for a call; it stops itself when the call ends. */
        fun start(ctx: Context) {
            if (running) return
            try {
                androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, VoiceService::class.java))
            } catch (e: Exception) {
                android.util.Log.w("ciranda", "voice service not started: $e")
            }
        }

        const val MUTE = "app.ciranda.android.MUTE"
        const val UNMUTE = "app.ciranda.android.UNMUTE"
        const val LEAVE = "app.ciranda.android.LEAVE"
    }
}
