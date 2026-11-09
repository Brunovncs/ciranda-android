package app.ciranda.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Whether a newer Ciranda for Android is out, asked of GitHub's API as the desktop's updater does
 * (`core/update.rs`): the release carries `ciranda-<version>-android.apk` and its SHA-256. The APK
 * is downloaded, checked, and handed to the system's installer, which keeps the data and only
 * takes a package signed with the same key.
 */
object Updater {
    const val REPO = "Brunovncs/ciranda-android"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val EVERY_MS = 6L * 60 * 60 * 1000

    data class Release(val version: String, val notes: String, val page: String, val apk: String, val sha: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val fraction: Float) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var loop: Job? = null
    private var notified: String? = null

    private val pt get() = Locale.getDefault().language == "pt"

    fun start(ctx: Context) {
        loop?.cancel()
        loop = CirandaApp.scope.launch {
            delay(5_000)
            while (isActive) {
                if (Engine.state.value.settings.checkUpdates) check(ctx, quiet = true)
                delay(EVERY_MS)
            }
        }
    }

    /** `CIRANDA_PRETEND_VERSION`'s counterpart: debug builds can claim an older version. */
    private fun current(): String = BuildConfig.VERSION_NAME

    fun isNewer(candidate: String, current: String): Boolean {
        fun nums(v: String) = v.trimStart('v').split('.', '-', '+').map { it.toIntOrNull() }.takeWhile { it != null }.map { it!! }
        val (a, b) = nums(candidate) to nums(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun check(ctx: Context, quiet: Boolean = false) {
        val s = _state.value
        if (s is State.Checking || s is State.Downloading) return
        if (s is State.Ready && quiet) return
        _state.value = State.Checking
        _state.value = try {
            val r = withContext(Dispatchers.IO) { latest() }
            when {
                r == null || !isNewer(r.version, current()) -> State.UpToDate
                else -> {
                    if (quiet) notifyAvailable(ctx, r)
                    State.Available(r)
                }
            }
        } catch (e: Exception) {
            if (quiet) State.Idle else State.Failed(e.message ?: e.toString())
        }
    }

    private fun latest(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Ciranda-Android/${current()}")
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw Exception((if (pt) "o GitHub respondeu " else "GitHub answered ") + c.responseCode)
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = o.getString("tag_name")
            val assets = o.optJSONArray("assets")
            var apk: String? = null
            var sha: String? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (name.endsWith("-android.apk")) apk = url
                if (name.endsWith("-android.apk.sha256")) sha = url
            }
            if (apk == null || sha == null) return null
            return Release(tag.trimStart('v'), o.optString("body").trim(), o.optString("html_url"), apk, sha)
        } finally {
            c.disconnect()
        }
    }

    fun download(ctx: Context, r: Release) {
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(r, 0f)
        CirandaApp.scope.launch {
            _state.value = try {
                val file = withContext(Dispatchers.IO) { fetch(ctx, r) }
                State.Ready(r, file)
            } catch (e: Exception) {
                State.Failed(e.message ?: e.toString(), r)
            }
            (_state.value as? State.Ready)?.let { install(ctx, it.file) }
        }
    }

    private fun get(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "Ciranda-Android/${current()}")
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        if (c.responseCode !in 200..299) throw Exception("${c.url.host}: ${c.responseCode}")
        return c
    }

    private fun fetch(ctx: Context, r: Release): File {
        val expected = get(r.sha).inputStream.bufferedReader().readText().trim().split(Regex("\\s+")).first().lowercase()
        if (expected.length != 64) throw Exception(if (pt) "não foi possível ler o checksum" else "the checksum could not be read")
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "ciranda-${r.version}.apk")
        val c = get(r.apk)
        val total = c.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")
        var got = 0L
        c.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    got += n
                    if (total > 0) _state.value = State.Downloading(r, got.toFloat() / total)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw Exception(if (pt) "o download não confere com o checksum" else "the download does not match its checksum")
        }
        return file
    }

    /** Hands the APK to the system's installer; the first time, Android asks to allow Ciranda to install apps. */
    fun install(ctx: Context, file: File) {
        val pm = ctx.packageManager
        if (!pm.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun notifyAvailable(ctx: Context, r: Release) {
        if (notified == r.version || CirandaApp.visible) return
        notified = r.version
        val open = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, MainActivity::class.java).putExtra("update", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CirandaApp.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (pt) "Ciranda ${r.version} está disponível" else "Ciranda ${r.version} is out")
            .setContentText(if (pt) "Toque para atualizar." else "Tap to update.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify("update", 1, n)
        } catch (_: SecurityException) {
        }
    }
}
