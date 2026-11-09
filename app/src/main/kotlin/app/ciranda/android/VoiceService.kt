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
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Keeps a voice call going with the screen off or another app in front: a foreground service
 * with the microphone, a wake lock and a Wi-Fi lock, and a notification to mute or leave.
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
                Engine.voice.collect { v -> if (v != null) show(v) }
            }
        }
        return START_NOT_STICKY
    }

    private fun show(v: VoiceState?) {
        val pt = Locale.getDefault().language == "pt"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(what: String) = PendingIntent.getBroadcast(
            this, what.hashCode(), Intent(this, Actions::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE,
        )
        val muted = v?.muted == true
        val title = if (pt) "Em voz" else "In voice"
        val text = buildString {
            append(v?.channel ?: "")
            if (v?.state == "reconnecting") append(if (pt) " · reconectando" else " · reconnecting")
            if (muted) append(if (pt) " · microfone desligado" else " · muted")
        }
        val n = NotificationCompat.Builder(this, CirandaApp.CHANNEL_VOICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, if (muted) (if (pt) "Ativar microfone" else "Unmute") else (if (pt) "Mutar" else "Mute"), action(if (muted) UNMUTE else MUTE))
            .addAction(0, if (pt) "Sair" else "Leave", action(LEAVE))
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    override fun onDestroy() {
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
        const val MUTE = "app.ciranda.android.MUTE"
        const val UNMUTE = "app.ciranda.android.UNMUTE"
        const val LEAVE = "app.ciranda.android.LEAVE"
    }
}
