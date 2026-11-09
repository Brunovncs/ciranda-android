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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.ciranda.android.ui.CirandaTheme
import app.ciranda.android.ui.Root
import app.ciranda.android.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var afterMic: (() -> Unit)? = null
    private var uploadTo: Long? = null
    private var uploadBody: String = ""

    /** Set when a notification asked for a channel or the update screen. */
    val pending = MutableStateFlow<Intent?>(null)

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
        pending.value = intent
        setContent {
            CirandaTheme {
                Root(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pending.value = intent
    }

    override fun onStart() {
        super.onStart()
        CirandaApp.visible = true
        Engine.send("visible", "on" to true)
    }

    override fun onStop() {
        CirandaApp.visible = false
        Engine.send("visible", "on" to false)
        super.onStop()
    }

    /** A downloaded attachment, opened with whatever app takes its kind. */
    fun openFile(e: Event.File) {
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
