package app.ciranda.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
        const val CHANNEL_CONNECTION = "connection"

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
        Native.start(this, data.path, cacheDir.path)
        Engine.send("hello", "locale" to Locale.getDefault().language)
        Engine.send("resume")
        scope.launch {
            Engine.events.collect { e ->
                when (e) {
                    is Event.Picture -> Pictures.onEvent(e)
                    is Event.InCall -> if (e.on) VoiceService.start(this@CirandaApp)
                    is Event.Mention -> Notifier.show(this@CirandaApp, e)
                    else -> {}
                }
            }
        }
        // Mentions with the app closed: the engine listens while the setting is on, and the
        // connection service keeps it running while there is a server signed in to.
        scope.launch {
            combine(Prefs.notify, Engine.state) { on, st -> Triple(on, st.version.isNotEmpty(), st.saved.any { it.signedIn }) }
                .distinctUntilChanged()
                .collect { (on, known, signedIn) ->
                    Engine.send("notifications", "on" to on)
                    when {
                        !on || (known && !signedIn) -> ConnectionService.stop(this@CirandaApp)
                        known -> ConnectionService.start(this@CirandaApp)
                    }
                }
        }
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
            NotificationChannel(
                CHANNEL_CONNECTION, if (pt) "Conexão em segundo plano" else "Background connection", NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = if (pt) {
                    "O aviso fixo que mantém as menções chegando. Pode esconder."
                } else {
                    "The steady notice that keeps mentions coming. Safe to hide."
                }
                setShowBadge(false)
            },
        )
    }
}
