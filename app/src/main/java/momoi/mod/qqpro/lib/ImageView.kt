package momoi.mod.qqpro.lib

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.core.view.doOnLayout
import momoi.mod.qqpro.util.Utils
import java.io.File
import java.io.InputStream

// The hardware (RecordingCanvas) refuses to draw a bitmap larger than ~100MB, throwing
// "Canvas: trying to draw too large bitmap" from ImageView.onDraw. Decode paths below cap the
// result well under that so a full-resolution photo (e.g. 5650x5650 = 128MB) can't crash the viewer.
const val MAX_DECODE_BYTES = 64L * 1024 * 1024
const val SAFE_MAX_DIM = 4096

fun <T : ImageView> T.imageResource(resId: Int) = apply {
    setImageResource(resId)
}
fun <T : ImageView> T.scaleType(scaleType: ImageView.ScaleType) = apply {
    setScaleType(scaleType)
}
fun <T : ImageView> T.adjustViewBounds(adjust: Boolean = true) = apply {
    adjustViewBounds = adjust
}

/**
 * Sniff the first bytes for an animated container. ImageDecoder can play both animated GIF and
 * animated WebP (QQ market-face / 收藏表情 stickers are often WebP), so route both through it; a
 * static GIF/WebP just decodes to a normal drawable. Extension is ignored — QQ stores some GIFs
 * with a `.jpg` name, so only the magic bytes are trustworthy.
 */
private fun File.isAnimatableImage(): Boolean = try {
    inputStream().use { s ->
        val h = ByteArray(12)
        val n = s.read(h)
        fun b(i: Int, c: Char) = h[i] == c.code.toByte()
        when {
            // "GIF8" (GIF87a / GIF89a)
            n >= 4 && b(0, 'G') && b(1, 'I') && b(2, 'F') && b(3, '8') -> true
            // "RIFF"<size>"WEBP"
            n >= 12 && b(0, 'R') && b(1, 'I') && b(2, 'F') && b(3, 'F') &&
                b(8, 'W') && b(9, 'E') && b(10, 'B') && b(11, 'P') -> true
            else -> false
        }
    }
} catch (e: Exception) {
    false
}

fun ImageView.bitmapDecodeFile(file: File, onApplied: (() -> Unit)? = null) {
    // Animated GIFs/WebP must be decoded into an AnimatedImageDrawable, otherwise BitmapFactory
    // only yields the first static frame (no animation). API 28+ has ImageDecoder.
    val animatable = file.isAnimatableImage()
    Utils.log("bitmapDecodeFile: animatable=$animatable size=${file.length()} path=${file.name}")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && animatable) {
        try {
            val limit = maxHeight
            val src = ImageDecoder.createSource(file)
            val drawable = ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
                val h = info.size.height
                val w = info.size.width
                var tw = w
                var th = h
                if (h > 300 && h > limit && limit > 0) {
                    tw = (w.toFloat() / h * limit).toInt(); th = limit
                }
                // Absolute cap (independent of maxHeight) so a huge GIF can't blow the canvas limit.
                val maxSide = maxOf(tw, th)
                if (maxSide > SAFE_MAX_DIM) {
                    val s = SAFE_MAX_DIM.toFloat() / maxSide
                    tw = (tw * s).toInt(); th = (th * s).toInt()
                }
                if (tw != w || th != h) decoder.setTargetSize(tw.coerceAtLeast(1), th.coerceAtLeast(1))
            }
            // Set the drawable only once the view has a size. The native matrix viewer
            // (RFWMatrixImageView/PhotoView) computes its fit matrix from the view dimensions when the
            // drawable is set; a plain post() can run after the only layout pass, so for a synchronously
            // loaded GIF (market-face file already on disk) the fit was computed against a 0-size view
            // and the image stuck at native size in the top-left corner. doOnLayout fires immediately if
            // already laid out, else after the next layout — so the fit always sees a real size.
            doOnLayout {
                setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) drawable.start()
                Utils.log("bitmapDecodeFile GIF applied: intrinsic=${drawable.intrinsicWidth}x${drawable.intrinsicHeight} view=${width}x${height}")
                onApplied?.invoke() // fire only once the drawable is actually on screen (deferred for GIFs)
            }
            return
        } catch (e: Exception) {
            Utils.log("GIF decode failed, falling back to bitmap: ${e.message}")
        }
    }
    var s: InputStream? = null
    bitmapDecodeStream {
        s?.close()
        s = file.inputStream()
        s!!
    }
    s?.close()
    onApplied?.invoke() // static decode is synchronous — the bitmap is set by now
}

fun ImageView.bitmapDecodeAssets(path: String): ImageView = runCatching {
    Utils.application.assets.open(path).use {
        bitmapDecodeStream { reread ->
            if (reread) {
                it.reset()
                it
            } else {
                it.mark(0)
                it
            }
        }
    }
    this
}.getOrElse {
    // A missing bundled asset must never take down the app (this watch ROM is old and the mixin
    // build may legitimately ship without one) — log and leave the ImageView untouched.
    Utils.log("bitmapDecodeAssets: '$path' unavailable: $it")
    this
}

inline fun ImageView.bitmapDecodeStream(streamProvider: (reread: Boolean)->InputStream): Bitmap? {
    // Read the source dimensions from the bounds Options' outWidth/outHeight — NOT from a Rect.
    // decodeStream(is, outPadding, opts) writes the size into opts.outWidth/outHeight and only touches
    // the Rect for nine-patch padding; reading the Rect left srcW/H at 0 for normal photos, so every
    // downsample check below was skipped and a full-res image decoded straight into an onDraw crash.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(streamProvider(false), null, bounds)
    val srcW = bounds.outWidth
    val srcH = bounds.outHeight
    val bitmap = BitmapFactory.decodeStream(streamProvider(true), null, BitmapFactory.Options().apply {
        var sample = 1
        // Existing behavior: downsample toward maxHeight when the view has one set.
        if (srcH > 300 && maxHeight > 0 && srcH > maxHeight) {
            while (srcH / (sample * 2) >= maxHeight) sample *= 2
        }
        // Hard safety cap (independent of maxHeight): the full-screen viewer's ImageView has no
        // maxHeight, so without this a full-resolution photo decodes at full size and crashes onDraw
        // with "trying to draw too large bitmap". Raise the sample until it fits the byte budget AND
        // keeps the longest side within a GPU-safe texture size.
        while (srcW > 0 && srcH > 0 &&
            (srcW.toLong() / sample) * (srcH / sample) * 4 > MAX_DECODE_BYTES) {
            sample *= 2
        }
        while (srcW > 0 && srcH > 0 && maxOf(srcW / sample, srcH / sample) > SAFE_MAX_DIM) {
            sample *= 2
        }
        inSampleSize = sample
    })
    post {
        setImageBitmap(bitmap)
    }
    return bitmap
}
