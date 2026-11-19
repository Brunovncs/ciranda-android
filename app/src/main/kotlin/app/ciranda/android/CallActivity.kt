package app.ciranda.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.ciranda.android.ui.CallScreen
import app.ciranda.android.ui.CirandaTheme
import app.ciranda.android.ui.tr

/**
 * A private call, on a screen of its own: ringing (answer or decline) and under way. It shows over
 * the lock screen and turns the screen on, as a phone call does, so it holds only the call: who it
 * is with and its buttons, never the conversations, which stay behind the app's own lock.
 */
class CallActivity : ComponentActivity() {
    private var afterMic: (() -> Unit)? = null

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            afterMic?.invoke()
        } else {
            Toast.makeText(this, tr("Ciranda needs the microphone for calls.", "O Ciranda precisa do microfone para chamadas."), Toast.LENGTH_LONG).show()
        }
        afterMic = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        if (savedInstanceState == null) take(intent)
        setContent {
            CirandaTheme {
                CallScreen(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onStart() {
        super.onStart()
        showing = true
        Ringer.screenStarted()
    }

    override fun onStop() {
        showing = false
        Ringer.screenStopped()
        super.onStop()
    }

    /** The notification's Answer. */
    private fun take(intent: Intent?) {
        if (intent?.action == ANSWER) {
            intent.action = null
            answer()
        }
    }

    fun answer() {
        val r = Engine.state.value.ring ?: return
        withMic { Engine.send("callAnswer", "server" to r.server, "username" to r.username, "conversationId" to r.conversationId) }
    }

    fun decline() {
        val r = Engine.state.value.ring ?: return
        Engine.send("callDecline", "server" to r.server, "username" to r.username, "conversationId" to r.conversationId)
    }

    /** The conversation the call is in, in the app (behind its lock, if it has one). */
    fun openConversation(conversationId: Long) {
        val s = Engine.state.value.session ?: return
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Notifier.OPEN)
                .putExtra(Notifier.SERVER, s.url)
                .putExtra(Notifier.USERNAME, s.me.nickname)
                .putExtra(Notifier.CONVERSATION, conversationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun withMic(f: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            f()
            return
        }
        afterMic = f
        mic.launch(Manifest.permission.RECORD_AUDIO)
    }

    companion object {
        const val ANSWER = "app.ciranda.android.ANSWER_CALL"

        /** Whether the call screen is up. */
        @Volatile
        var showing = false

        fun intent(ctx: Context): Intent = Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun open(ctx: Context) {
            try {
                ctx.startActivity(intent(ctx))
            } catch (e: Exception) {
                android.util.Log.w("ciranda", "call screen not opened: $e")
            }
        }
    }
}
