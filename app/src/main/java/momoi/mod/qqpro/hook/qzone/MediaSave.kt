package momoi.mod.qqpro.hook.qzone

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import momoi.mod.qqpro.util.Utils
import java.io.File

/**
 * Save a downloaded media file into the device gallery (Pictures/QZone or Movies/QZone) via MediaStore
 * (API 29+) or the legacy public-dir + media-scan path (< 29). Used by the QZone post ⋮ menu's
 * "下载图片/视频" so it actually saves to the gallery instead of just opening the viewer.
 */
object MediaSave {

    /**
     * Sniff an image file's real format from its magic bytes → (extension, mime). A downloaded GIF must
     * be saved as image/gif (not image/jpeg) or the gallery renders it as a single static frame. Defaults
     * to jpg for anything unrecognized.
     */
    fun imageTypeOf(file: File): Pair<String, String> = runCatching {
        file.inputStream().use { s ->
            val h = ByteArray(12)
            val n = s.read(h)
            fun c(i: Int, ch: Char) = n > i && h[i] == ch.code.toByte()
            fun u(i: Int, v: Int) = n > i && (h[i].toInt() and 0xFF) == v
            when {
                c(0, 'G') && c(1, 'I') && c(2, 'F') -> "gif" to "image/gif"
                c(0, 'R') && c(1, 'I') && c(2, 'F') && c(3, 'F') &&
                    c(8, 'W') && c(9, 'E') && c(10, 'B') && c(11, 'P') -> "webp" to "image/webp"
                u(0, 0x89) && c(1, 'P') && c(2, 'N') && c(3, 'G') -> "png" to "image/png"
                else -> "jpg" to "image/jpeg"
            }
        }
    }.getOrDefault("jpg" to "image/jpeg")

    fun toGallery(ctx: Context, src: File, displayName: String, mime: String, isVideo: Boolean, subDir: String = "QZone"): Boolean {
        if (!src.exists() || src.length() == 0L) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) saveModern(ctx, src, displayName, mime, isVideo, subDir)
            else saveLegacy(ctx, src, displayName, subDir)
        }.onFailure { Utils.log("MediaSave.toGallery: $it") }.getOrDefault(false)
    }

    private fun saveModern(ctx: Context, src: File, name: String, mime: String, isVideo: Boolean, subDir: String): Boolean {
        val resolver = ctx.contentResolver
        val dir = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + subDir
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return false
        resolver.openOutputStream(uri).use { out -> if (out == null) return false; src.inputStream().use { it.copyTo(out) } }
        values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Utils.log("MediaSave: saved $name -> $uri")
        return true
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(ctx: Context, src: File, name: String, subDir: String): Boolean {
        val pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = File(pics, subDir).apply { mkdirs() }
        val dst = File(dir, name)
        src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), null, null)
        Utils.log("MediaSave: legacy saved ${dst.absolutePath}")
        return true
    }
}
