package momoi.mod.qqpro.watchdog

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves a report/log to the device's Downloads folder so it can be retrieved off-watch (USB, a file
 * app, a companion phone) without `adb pull`. Shared by [CrashReportActivity] and [DebugActivity].
 *
 * Standalone (no [momoi.mod.qqpro.util.Utils] / lib helpers) so it is safe in the `:crash` process,
 * where the app singletons may be uninitialised. Returns a human-readable saved location for an
 * on-screen toast — important on a watch, where there is no easy file browser to find the file.
 */
object LogExporter {

    /** Result of a save attempt: the location to show the user, and whether it landed in Downloads. */
    data class Saved(val location: String, val inDownloads: Boolean)

    /**
     * Save an existing file (e.g. the full debug log) to Downloads by STREAMING it, so a multi-MB
     * log is never loaded whole into memory. Same destination strategy as [save].
     */
    fun saveFile(ctx: Context, baseName: String, src: File, ext: String = "log"): Saved? {
        if (!src.exists()) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${baseName}_$stamp.$ext"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                ctx.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                return Saved("下载/$fileName", true)
            }
        }

        runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, fileName)
            src.copyTo(file, overwrite = true)
            return Saved(file.absolutePath, true)
        }

        runCatching {
            val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val file = File(dir, fileName)
            src.copyTo(file, overwrite = true)
            return Saved(file.absolutePath, false)
        }

        return null
    }

    fun save(ctx: Context, baseName: String, content: String, ext: String = "txt"): Saved? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${baseName}_$stamp.$ext"
        val mime = when (ext) {
            "json" -> "application/json"
            "xml" -> "text/xml"
            else -> "text/plain"
        }

        // Android 10+: MediaStore Downloads — no storage permission needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                return Saved("下载/$fileName", true)
            }
        }

        // Pre-Q: write straight into the public Downloads dir (needs WRITE_EXTERNAL_STORAGE).
        runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(content)
            return Saved(file.absolutePath, true)
        }

        // Fallback (no permission / unavailable): the app's own external files dir, always writable.
        runCatching {
            val dir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(content)
            return Saved(file.absolutePath, false)
        }

        return null
    }
}
