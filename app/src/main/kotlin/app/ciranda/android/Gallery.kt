package app.ciranda.android

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Pictures saved from the chat into the phone's gallery, in an album of Ciranda's own. */
object Gallery {
    private const val ALBUM = "Ciranda"

    /** The picture's kind, read from the file itself: the name it was sent with may not say. */
    fun mime(f: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        return bounds.outMimeType ?: "image/jpeg"
    }

    private fun extension(mime: String) = when (mime) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        else -> "jpg"
    }

    /** The name it was sent with, or one made from its hash, with the extension its kind has. */
    private fun fileName(name: String?, hash: String, mime: String): String {
        val ext = extension(mime)
        val base = name?.substringAfterLast('/')?.substringAfterLast('\\')?.substringBeforeLast('.')?.trim()
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")?.takeIf { it.isNotEmpty() }
            ?: "ciranda-${hash.take(12)}"
        return "$base.$ext"
    }

    /** Copies [src] into the gallery; throws when it could not. */
    fun save(ctx: Context, src: File, name: String?, hash: String) {
        val mime = mime(src)
        val display = fileName(name, hash, mime)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, display)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("the gallery refused the picture")
            try {
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: error("the gallery gave nowhere to write")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM).apply { mkdirs() }
        var out = File(dir, display)
        var n = 1
        while (out.exists()) out = File(dir, "${display.substringBeforeLast('.')} (${n++}).${display.substringAfterLast('.')}")
        src.copyTo(out)
        MediaScannerConnection.scanFile(ctx, arrayOf(out.path), arrayOf(mime), null)
    }
}
