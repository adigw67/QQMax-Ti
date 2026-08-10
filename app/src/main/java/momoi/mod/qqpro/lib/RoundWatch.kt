package momoi.mod.qqpro.lib

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import momoi.mod.qqpro.Settings

/**
 * MD3e 圆形手表 UI（实验性）：圆屏安全区 + 表盘遮罩。
 *  - [addRoundMask]：在页面最上层盖一个「表盘边框」——内切圆之外画成黑色表圈，
 *    内圈加一圈柔和阴影（expressive 深色渐变环），四角内容不再溢出圆屏。
 *  - [roundSafePadding]：给滚动内容加圆屏安全区 padding（顶/底角不被圆边裁切）。
 * 全部受 [Settings.md3eRound] 总开关控制。
 */
object RoundWatch {
    private const val TAG = "qqpro_round_mask"

    /** 页面根视图盖圆表遮罩（幂等；仅在 md3eRound 开启时生效）。 */
    fun apply(root: View) {
        if (!Settings.md3eRound.value) return
        if (root.findViewWithTag<View>(TAG) != null) return
        root.post {
            if (root.findViewWithTag<View>(TAG) != null) return@post
            val overlay = FrameLayout(root.context).apply {
                tag = TAG
                isClickable = false
                isFocusable = false
                background = RoundMaskDrawable()
            }
            if (root is FrameLayout) {
                root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
    }

    /** 圆屏安全区：顶部/底部留出圆边裁切区（约 (高-宽)/2），左右不变。 */
    fun safeTopBottomPx(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        val inset = (dm.heightPixels - dm.widthPixels) / 2
        return if (inset > 0) inset * 18 / 100 else 0  // 留 18% 缓冲，避免内容贴边
    }
}

/** 表盘遮罩：内切圆透明，圆外黑色表圈，内圈柔和阴影环。 */
private class RoundMaskDrawable : Drawable() {
    private val path = Path()
    private val ring = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(44, 0, 0, 0)
    }
    override fun draw(canvas: Canvas) {
        val r = minOf(bounds.width(), bounds.height()) / 2f
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        path.reset()
        path.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path, Region.Op.DIFFERENCE)
        canvas.drawColor(Color.BLACK)
        canvas.restore()
        // 内圈阴影：圆边内侧一圈半透明黑，模拟表盘厚度（expressive 深度）。
        canvas.drawCircle(cx, cy, r - 2f, ring)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
