package momoi.mod.qqpro.hook.aio_cell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import momoi.mod.qqpro.safeCacheDir
import momoi.mod.qqpro.showDialog
import momoi.mod.qqpro.util.Utils
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Market-face (sticker / saved image-emoji) cells render their image straight into an ImageView and
 * carry only a `marketFaceElement` — no `picElement` — so the native code (and our pic-based
 * share/copy helpers) can't act on them: tapping does nothing and the long-press menu has no
 * copy-image / system-share. This object captures the rendered ImageView per message so we can
 * (a) open it fullscreen on tap and (b) hand the rendered bitmap to the long-press menu actions.
 */
object MarketFaceImage {
    // msgId -> the cell's ImageView. Weak so recycled cells don't leak; resolved lazily at action
    // time, by which point the async image load has finished.
    private val views = ConcurrentHashMap<Long, WeakReference<ImageView>>()
    // msgId -> the descrambled, directly-playable GIF (when this sticker has an animated source).
    // Used both for fullscreen play and for save/copy/share so those keep the animation.
    private val gifFiles = ConcurrentHashMap<Long, File>()

    /**
     * @param animatedPath the market-face element's `dynamicFacePath` (the local animated GIF file).
     *   When it resolves to a playable GIF we open/save THAT so the sticker keeps its animation; the
     *   cell's drawable is only a single rendered frame, so snapshotting it would be frozen.
     */
    fun onBind(msgId: Long, root: View, animatedPath: String? = null) {
        val iv = findImageView(root) ?: return
        views[msgId] = WeakReference(iv)
        val gif = animatedPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
            ?.let { playableGif(it, root.context) }
        if (gif != null) gifFiles[msgId] = gif else gifFiles.remove(msgId)
        iv.setOnClickListener(FullscreenClick(gif))
    }

    /**
     * A file for save/copy/share. Prefers the real animated GIF so 保存/系统分享/收藏 keep the animation;
     * falls back to rendering the current (static) frame to a cache PNG when there's no animated source.
     */
    fun fileFor(msgId: Long): File? {
        gifFiles[msgId]?.takeIf { it.exists() && it.length() > 0 }?.let { return it }
        val iv = views[msgId]?.get() ?: return null
        val bmp = drawableToBitmap(iv.drawable) ?: return null
        val dir = iv.context.safeCacheDir ?: return null
        val f = File(dir, "marketface_$msgId.png")
        return runCatching {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            f
        }.onFailure { Utils.log("marketface render failed: $it") }.getOrNull()
    }

    private fun findImageView(v: View): ImageView? {
        if (v is ImageView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findImageView(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun drawableToBitmap(d: Drawable?): Bitmap? {
        d ?: return null
        if (d is BitmapDrawable) d.bitmap?.let { return it }
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: d.bounds.width().takeIf { it > 0 } ?: return null
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: d.bounds.height().takeIf { it > 0 } ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val old = Rect(d.bounds)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        d.bounds = old
        return bmp
    }

    /**
     * Resolve a directly-decodable GIF file from the market-face element's `dynamicFacePath`.
     *
     * QQ store stickers (`.emotionsm/<pkg>/<md5>`) are saved as a GIF whose **13-byte header is
     * scrambled** — the 6-byte signature AND the 7-byte logical-screen descriptor — with XOR `0x01`
     * on the odd byte offsets; the colour table and frames are plaintext. The native emoticon decoder
     * unscrambles this at display time, which is why the cell animates but a raw decode does not.
     *
     * Crucially the scramble flips the screen width/height high bytes (`01`→`00`), so undoing only the
     * signature left the logical canvas at 456×456 while the frames are 200×200 — the image then sits
     * in the TOP-LEFT corner (the bug seen in our viewer AND any gallery on the saved file). We undo
     * the whole 13-byte header into a cache copy so any decoder shows it at the correct size. Returns
     * the original file when it is already a real GIF, or null when it is neither.
     */
    private fun playableGif(src: File, ctx: android.content.Context): File? {
        val bytes = runCatching { src.readBytes() }.getOrNull()?.takeIf { it.size >= 13 } ?: return null
        fun isGif(b: ByteArray) = b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()
        if (isGif(bytes)) return src
        // Undo the header scramble: XOR 0x01 on every odd offset of the 13-byte GIF header
        // (offsets 1,3,5,7,9,11 — sig + logical-screen width/height/flags). Body stays as-is.
        for (i in 1..11 step 2) bytes[i] = (bytes[i].toInt() xor 1).toByte()
        if (!isGif(bytes)) { Utils.log("MarketFaceImage: ${src.name} not a (scrambled) GIF, magic=${bytes.copyOf(6).joinToString(" ") { "%02x".format(it) }}"); return null }
        val dir = ctx.safeCacheDir ?: return null
        // v2 prefix: the old (signature-only) descramble produced a same-length but wrong-canvas file,
        // so a length check alone would reuse the stale broken copy — bump the key to rewrite.
        val out = File(dir, "mfgifv2_${src.name}.gif")
        if (out.exists() && out.length() == src.length().toLong()) return out
        return runCatching { out.writeBytes(bytes); out }
            .onFailure { Utils.log("MarketFaceImage: descramble write failed: $it") }.getOrNull()
    }

    // Named listener class (not an inline lambda) so it's safe to reference from anywhere. Prefers
    // the real animated [gif] file so GIF stickers PLAY fullscreen; only falls back to a single-frame
    // snapshot of the cell's drawable when no playable animated file is available.
    private class FullscreenClick(private val gif: File?) : View.OnClickListener {
        override fun onClick(v: View) {
            val iv = v as? ImageView ?: return
            if (gif != null && gif.exists() && gif.length() > 0) {
                Utils.log("MarketFaceImage: open animated ${gif.name}")
                // A trivial kernel-style loader: the file is already on disk, decode it directly.
                iv.showDialog(BigImageFragment(kernelLoad = { _, onDone -> onDone(gif.path) }))
                return
            }
            // No playable animated source — show the current frame (static), the old behavior.
            val bmp = MarketFaceImage.drawableToBitmap(iv.drawable) ?: return
            Utils.log("MarketFaceImage: snapshot frame fallback")
            iv.showDialog(BigImageFragment(bmp = bmp))
        }
    }
}
