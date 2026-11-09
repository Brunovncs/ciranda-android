package app.ciranda.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.ciranda.android.ui.CirandaTheme
import app.ciranda.android.ui.Root
import app.ciranda.android.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : FragmentActivity() {
    private var afterMic: (() -> Unit)? = null
    private var uploadTo: Long? = null
    private var uploadBody: String = ""

    /** Set when a notification asked for a channel or the update screen. */
    val pending = MutableStateFlow<Intent?>(null)

    /** Counts coming back to the app, so a screen can look again at what was changed elsewhere. */
    val resumes = MutableStateFlow(0)

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            afterMic?.invoke()
        } else {
            Toast.makeText(this, tr("Ciranda needs the microphone for voice.", "Ciranda precisa do microfone para a voz."), Toast.LENGTH_LONG).show()
        }
        afterMic = null
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val channel = uploadTo ?: return@registerForActivityResult
        if (uri != null) upload(channel, uri, uploadBody)
        uploadTo = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Once: not again when the activity is made again, nor from the recent apps.
        val fresh = savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        if (fresh) {
            pending.value = intent
            takeInvite(intent)
            takeMention(intent)
        }
        savedInstanceState?.let {
            uploadTo = it.getLong("uploadTo", -1).takeIf { id -> id >= 0 }
            uploadBody = it.getString("uploadBody") ?: ""
        }
        setContent {
            CirandaTheme {
                Root(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pending.value = intent
        takeInvite(intent)
        takeMention(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The file picker may outlive this process; what it was for comes back with it.
        uploadTo?.let { outState.putLong("uploadTo", it) }
        outState.putString("uploadBody", uploadBody)
    }

    override fun onStart() {
        super.onStart()
        CirandaApp.visible = true
        Engine.send("visible", "on" to true)
        AppLock.onStart()
        applyPrivacy()
    }

    override fun onResume() {
        super.onResume()
        AppLock.onResume()
        resumes.value += 1
        // A file that finished downloading while away opens now; Android would not let it then.
        waitingFile?.let {
            waitingFile = null
            openFile(it)
        }
    }

    private var waitingFile: Event.File? = null

    /**
     * Something that would end the voice call (another server, an invite): asked first while in
     * one. The question shows in `Root`.
     */
    val confirmLeave = MutableStateFlow<(() -> Unit)?>(null)

    fun leavingCall(go: () -> Unit) {
        if (Engine.voice.value != null) confirmLeave.value = go else go()
    }

    /** With the lock on, the app's picture in the recent apps is blank too. */
    fun applyPrivacy() {
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!Prefs.lock.value)
    }

    // Whatever the app opens itself (a picker, the share sheet, a setting) is come back from
    // without the lock asking again.
    override fun startActivity(intent: Intent, options: Bundle?) {
        AppLock.expectReturn()
        super.startActivity(intent, options)
    }

    @Deprecated("Still what the activity result launchers call")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        AppLock.expectReturn()
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onStop() {
        AppLock.onStop(isChangingConfigurations)
        CirandaApp.visible = false
        Engine.send("visible", "on" to false)
        super.onStop()
    }

    /** `ciranda://join?...`, as the invite page opens it; the engine reads it. */
    private fun takeInvite(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "ciranda") return
        val link = data.toString()
        leavingCall { Engine.send("invite", "link" to link) }
        // Handled: a configuration change must not open it again.
        intent.data = null
    }

    /** A mention's notification, tapped: its channel, on its server. */
    private fun takeMention(intent: Intent?) {
        if (intent?.action != Notifier.OPEN) return
        val server = intent.getStringExtra(Notifier.SERVER) ?: return
        val channel = intent.getLongExtra(Notifier.CHANNEL, -1).takeIf { it >= 0 }
        val username = intent.getStringExtra(Notifier.USERNAME)
        val here = Engine.state.value.session?.let { it.url.trimEnd('/').equals(server.trimEnd('/'), true) && it.me.nickname.equals(username, true) } == true
        val go = { Engine.send("openAt", "server" to server, "username" to username, "channelId" to channel) }
        if (here) go() else leavingCall(go)
    }

    /** The share sheet, with an invite to the server on screen. */
    fun shareInvite(e: Event.InviteLink) {
        val text = tr("Come to ${e.name} on Ciranda: ${e.link}", "Vem pro ${e.name} no Ciranda: ${e.link}")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, tr("Invite friends", "Convidar amigos")))
    }

    /** A downloaded attachment, opened with whatever app takes its kind. */
    fun openFile(e: Event.File) {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            waitingFile = e
            return
        }
        val f = File(e.path)
        val ext = f.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(i, e.name))
        } catch (_: Exception) {
            Toast.makeText(this, tr("No app here opens that file.", "Nenhum app aqui abre esse arquivo."), Toast.LENGTH_LONG).show()
        }
    }

    /** Runs `f` once the microphone may be used, asking for it the first time. */
    fun withMic(f: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            f()
            return
        }
        afterMic = f
        mic.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun join(channelId: Long, password: String? = null) {
        withMic {
            if (password == null) Engine.send("join", "channelId" to channelId) else Engine.send("join", "channelId" to channelId, "password" to password)
        }
    }

    fun pickAttachment(channelId: Long, body: String) {
        uploadTo = channelId
        uploadBody = body
        pick.launch("*/*")
    }

    /** Copies the file the user picked where the engine can read it, then sends it. */
    private fun upload(channelId: Long, uri: Uri, body: String) {
        val resolver = contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "file"
        val mime = resolver.getType(uri) ?: ""
        CirandaApp.scope.launch {
            val copy = withContext(Dispatchers.IO) {
                try {
                    val dir = File(cacheDir, "uploads").apply { mkdirs() }
                    val f = File(dir, "${System.nanoTime()}-${name.replace('/', '_')}")
                    resolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: return@withContext null
                    f
                } catch (e: Exception) {
                    null
                }
            }
            if (copy == null) {
                Toast.makeText(this@MainActivity, tr("That file could not be read.", "Não foi possível ler esse arquivo."), Toast.LENGTH_LONG).show()
                return@launch
            }
            Engine.send("upload", "channelId" to channelId, "path" to copy.path, "name" to name, "mime" to mime, "body" to body)
        }
    }
}
