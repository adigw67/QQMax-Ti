package momoi.mod.qqpro.util

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.widget.ImageView
import momoi.mod.qqpro.Settings
import java.io.File

/**
 * Custom chat background image picked by the user in settings. The picked image is
 * copied into the app's private files dir (so we don't depend on a content-uri
 * permission surviving), then loaded — downsampled to the screen — as a
 * [BitmapDrawable] with a black overlay whose alpha is driven by
 * [Settings.chatBgDarken] for readability.
 */
object ChatBackground {
    private val file: File
        get() = File(Utils.application.filesDir, "chat_bg.img")

    fun isSet(): Boolean = file.exists()

    /** Copy the image behind [uri] into our private storage. Returns true on success. */
    fun save(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return false
                file.outputStream().use { output -> input.copyTo(output) }
            }
            Utils.log("ChatBackground saved from $uri -> ${file.absolutePath} (${file.length()} bytes)")
            true
        } catch (e: Exception) {
            Utils.log("ChatBackground save failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun clear() {
        if (file.exists()) file.delete()
        Utils.log("ChatBackground cleared")
    }

    /** The picked image with the configured darken overlay, or null if none is set. */
    fun loadDrawable(): Drawable? {
        if (!isSet()) return null
        return try {
            val metrics = Utils.application.resources.displayMetrics
            val reqW = metrics.widthPixels
            val reqH = metrics.heightPixels

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            val image = BitmapDrawable(Utils.application.resources, bitmap)

            val darken = Settings.chatBgDarken.value.coerceIn(0f, 0.95f)
            if (darken <= 0f) {
                image
            } else {
                val overlay = ColorDrawable(Color.argb((darken * 255).toInt(), 0, 0, 0))
                LayerDrawable(arrayOf<Drawable>(image, overlay))
            }
        } catch (e: Exception) {
            Utils.log("ChatBackground load failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Apply the custom background to a [WatchFragment] background ImageView, or do
     * nothing if no image is set (leaving the app's default background intact).
     */
    fun applyTo(bgView: ImageView?) {
        if (bgView == null) return
        val drawable = loadDrawable() ?: return
        bgView.scaleType = ImageView.ScaleType.CENTER_CROP
        bgView.setImageDrawable(drawable)
    }

    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (reqW <= 0 || reqH <= 0) return sample
        var halfW = w / 2
        var halfH = h / 2
        while (halfW / sample >= reqW && halfH / sample >= reqH) {
            sample *= 2
        }
        return sample
    }
}
