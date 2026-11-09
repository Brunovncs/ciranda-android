package app.ciranda.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class CirandaApp : Application() {
    companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var app: CirandaApp
            private set

        const val CHANNEL_VOICE = "voice"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_UPDATES = "updates"

        /** Whether an activity of ours is on screen. */
        @Volatile
        var visible = false
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        channels()
        val data = File(filesDir, "ciranda").apply { mkdirs() }
        Native.start(this, data.path, cacheDir.path)
        Engine.send("hello", "locale" to Locale.getDefault().language)
        Engine.send("resume")
        scope.launch {
            Engine.events.collect { e ->
                when (e) {
                    is Event.Picture -> Pictures.onEvent(e)
                    is Event.InCall -> voiceService(e.on)
                    is Event.NewMessage -> notify(e)
                    else -> {}
                }
            }
        }
        Updater.start(this)
    }

    private fun voiceService(on: Boolean) {
        val i = Intent(this, VoiceService::class.java)
        if (on) ContextCompat.startForegroundService(this, i) else stopService(i)
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        val pt = Locale.getDefault().language == "pt"
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_VOICE, if (pt) "Em voz" else "In voice", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, if (pt) "Menções" else "Mentions", NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, if (pt) "Atualizações" else "Updates", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** A mention while Ciranda is not on screen. */
    private fun notify(e: Event.NewMessage) {
        if (!e.mention || visible) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            this, e.channelId.toInt(),
            Intent(this, MainActivity::class.java).putExtra("channelId", e.channelId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${e.author} · #${e.channel}")
            .setContentText(e.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.body))
            .setSubText(e.server)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify("mention", e.channelId.toInt(), n)
        } catch (_: SecurityException) {
        }
    }
}
