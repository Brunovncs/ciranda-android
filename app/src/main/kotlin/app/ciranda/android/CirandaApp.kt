package app.ciranda.android

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
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
        const val CHANNEL_PRIVATE = "private"
        const val CHANNEL_CALLS = "calls"

        /** Whether an activity of ours is on screen. */
        @Volatile
        var visible = false
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        Prefs.init(this)
        AppLock.onAppStart()
        channels()
        val data = File(filesDir, "ciranda").apply { mkdirs() }
        SealKey.get(this)?.let { key ->
            Native.sealWith(key)
            key.fill(0)
        }
        Native.start(this, data.path, cacheDir.path)
        Engine.send("hello", "locale" to Locale.getDefault().language)
        Engine.send("resume")
        scope.launch {
            Engine.events.collect { e ->
                when (e) {
                    is Event.Picture -> Pictures.onEvent(e)
                    is Event.InCall -> if (e.on) VoiceService.start(this@CirandaApp)
                    is Event.Mention -> Notifier.show(this@CirandaApp, e)
                    is Event.PrivateMessage -> Notifier.showPrivate(this@CirandaApp, e)
                    is Event.MissedCall -> Ringer.missed(this@CirandaApp, e)
                    else -> {}
                }
            }
        }
        scope.launch {
            Prefs.notify.collect { on -> Engine.send("notifications", "on" to on) }
        }
        Ringer.start(this)
        Updater.start(this)
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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PRIVATE, if (pt) "Mensagens privadas" else "Private messages", NotificationManager.IMPORTANCE_HIGH),
        )
        // Rings with the phone's ringtone, as a call does, and vibrates.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, if (pt) "Chamadas" else "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 1200)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.deleteNotificationChannel("connection")
    }
}
