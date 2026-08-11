package momoi.mod.qqpro.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.widget.ImageView
import androidx.fragment.app.Fragment
import momoi.mod.qqpro.Settings
import java.io.File
import java.io.FileOutputStream

/**
 * 聊天页背景（实验性）：每个会话可单独设置背景图，另有一个全局背景兜底。
 *
 *  - 每群背景：files/chat_bg/bg_<peerHash>.img
 *  - 全局背景：files/chat_bg.img（旧的单背景文件，保持兼容）
 *  - 加载时按屏幕降采样，返回带「变暗遮罩」（[Settings.chatBgDarken]）的 Drawable，
 *    由调用方以 CENTER_CROP 铺满聊天内容区。
 *  - 图片在设置页经过强制裁剪（屏幕比例、可拖动调整位置）后以 [saveCropped] 保存。
 *  - [monetColor]：从图片提取主色，供设置页「用图片取色（莫奈）」设置 UI 主题色（与背景无关）。
 */
object ChatBackground {
    private fun dir(): File = File(Utils.application.filesDir, "chat_bg").apply { mkdirs() }
    private val globalFile: File get() = File(Utils.application.filesDir, "chat_bg.img")
    private fun peerFile(peerUid: String?): File =
        File(dir(), "bg_${(peerUid ?: "global").hashCode().toString(16)}.img")

    fun enabled(): Boolean = Settings.chatBgEnabled.value

    /** 其他界面（主界面/联系人/动态/我的）背景总开关：开启且已设置图片。 */
    fun pagesEnabled(): Boolean = Settings.pagesBgEnabled.value && peerFile("pages").exists()

    /** 该会话是否有背景可显示：会话独立背景优先，否则全局背景。peerUid 为 null = 只看全局。 */
    fun isSet(peerUid: String?): Boolean =
        peerFile(peerUid).exists() || (peerUid != null && globalFile.exists())

    fun peerSet(peerUid: String?): Boolean = peerFile(peerUid).exists()
    fun globalSet(): Boolean = globalFile.exists()
    fun pagesSet(): Boolean = peerFile("pages").exists()

    /** 保存裁剪好的位图（JPEG）。[peerUid] 为 null/空 = 全局背景。 */
    fun saveCropped(bitmap: Bitmap, peerUid: String?): Boolean = try {
        val out = peerFile(peerUid?.takeIf { it.isNotBlank() })
        FileOutputStream(out).use { fos -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos) }
        Utils.log("ChatBackground saved peer=${peerUid ?: "global"} -> ${out.absolutePath} (${out.length()} bytes)")
        true
    } catch (e: Exception) {
        Utils.log("ChatBackground save failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    fun clear(peerUid: String?) {
        val f = peerFile(peerUid?.takeIf { it.isNotBlank() })
        if (f.exists()) f.delete()
    }

    fun clearAll() {
        dir().listFiles()?.forEach { it.delete() }
        if (globalFile.exists()) globalFile.delete()
    }

    fun clearPages() {
        val f = peerFile("pages")
        if (f.exists()) f.delete()
    }

    /** 该会话要显示的背景文件（独立优先，其次全局），无则 null。 */
    private fun pickFile(peerUid: String?): File? {
        val peer = peerFile(peerUid?.takeIf { it.isNotBlank() })
        if (peer.exists()) return peer
        return if (peerUid != null && globalFile.exists()) globalFile else null
    }

    /** 解码 + 变暗遮罩；无背景返回 null。 */
    fun loadDrawable(peerUid: String?): Drawable? {
        if (!enabled()) return null
        val f = pickFile(peerUid) ?: return null
        return loadDrawableFrom(f)
    }

    /** 其他界面背景 drawable（透明度/变暗沿用聊天背景设置）。 */
    fun loadPagesDrawable(): Drawable? {
        if (!pagesEnabled()) return null
        return loadDrawableFrom(peerFile("pages"))
    }

    /** 解码 + 变暗遮罩（聊天背景与页面背景共用）。 */
    private fun loadDrawableFrom(f: File): Drawable? {
        return try {
            val metrics = Utils.application.resources.displayMetrics
            val reqW = metrics.widthPixels
            val reqH = metrics.heightPixels

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
            }
            val bitmap = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
            val image = BitmapDrawable(Utils.application.resources, bitmap)
            // 背景图自身半透明（独立开关）：关闭则完全不透明；开启时数值越小越透，露出下方 surface。
            image.alpha = if (Settings.chatBgTranslucent.value) {
                (Settings.chatBgAlpha.value.coerceIn(0.3f, 1f) * 255).toInt()
            } else 255

            val darken = Settings.chatBgDarken.value.coerceIn(0f, 0.95f)
            val base: Drawable = if (darken <= 0f) image
            else {
                val overlay = ColorDrawable(Color.argb((darken * 255).toInt(), 0, 0, 0))
                LayerDrawable(arrayOf<Drawable>(image, overlay))
            }
            // 圆表适配：按圆屏内切圆裁剪，四角露出 M3 surface，不被方图盖住。
            if (Settings.md3eRound.value) CircleClipDrawable(base) else base
        } catch (e: Exception) {
            Utils.log("ChatBackground load failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 应用到聊天页背景 ImageView（CENTER_CROP 铺满）。 */
    fun applyTo(bgView: ImageView?, peerUid: String?) {
        if (bgView == null) return
        val drawable = loadDrawable(peerUid) ?: return
        bgView.scaleType = ImageView.ScaleType.CENTER_CROP
        bgView.setImageDrawable(drawable)
    }

    /** 应用到页面背景 ImageView（CENTER_CROP 铺满）。 */
    fun applyToPages(bgView: ImageView?) {
        if (bgView == null) return
        val drawable = loadPagesDrawable() ?: return
        bgView.scaleType = ImageView.ScaleType.CENTER_CROP
        bgView.setImageDrawable(drawable)
    }

    /** 对主界面页面（继承 WatchFragment 的 MainInnerFragment）应用其他界面背景。 */
    fun applyToPages(fragment: Fragment?) {
        if (fragment == null || !pagesEnabled()) return
        runCatching {
            val d = fragment.javaClass.getField("d")
            (d.get(fragment) as? ImageView)?.let { applyToPages(it) }
        }.onFailure { Utils.log("ChatBackground.applyToPages failed: $it") }
    }

    /** 从位图提取主色（简单莫奈式）：缩到 32x32 后取平均色。 */
    fun monetColor(bitmap: Bitmap): Int {
        val bmp = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val px = IntArray(32 * 32)
        bmp.getPixels(px, 0, 32, 0, 0, 32, 32)
        if (bmp !== bitmap) bmp.recycle()
        var r = 0L; var g = 0L; var b = 0L
        for (c in px) {
            r += c shr 16 and 0xFF
            g += c shr 8 and 0xFF
            b += c and 0xFF
        }
        val n = px.size
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (reqW <= 0 || reqH <= 0) return sample
        var halfW = w / 2
        var halfH = h / 2
        while (halfW / sample >= reqW && halfH / sample >= reqH) sample *= 2
        return sample
    }
}

/** 把内层 drawable 裁剪到所在 bounds 的内切圆（圆表安全区）。 */
private class CircleClipDrawable(private val inner: Drawable) : Drawable() {
    private val clip = Path()
    override fun draw(canvas: Canvas) {
        val r = minOf(bounds.width(), bounds.height()) / 2f
        clip.reset()
        clip.addCircle(bounds.exactCenterX(), bounds.exactCenterY(), r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        inner.bounds = bounds
        inner.draw(canvas)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { inner.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { inner.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
