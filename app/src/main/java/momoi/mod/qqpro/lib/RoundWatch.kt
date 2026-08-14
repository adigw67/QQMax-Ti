package momoi.mod.qqpro.lib

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout

/**
 * 圆屏模式（全面适配）统一工具层。
 *
 * 在圆形（或圆角方形）儿童手表上，矩形界面有四类问题，这里各给一个统一解法：
 *  1. 四角内容被圆边/表圈裁掉 —— [apply] 盖表盘遮罩，[horizontalInsetPx]/[safeTopBottomPx] 留安全区；
 *  2. 列表/网格行首行尾在圆边贴边 —— [applySafePadding] 给滚动容器加安全 padding；
 *  3. 点击目标太小、在圆边易误触 —— [hitTargetPx]（M3 组件据此放大可点击尺寸）；
 *  4. 方形背景图盖住圆屏四角 —— [circleClip] 按内切圆裁剪。
 *
 * 本分支（round-screen）**强制开启**圆屏模式：统一走 [enabled]，始终为 true。
 * 全部 API 19 安全（无 RippleDrawable/Outline/PathInterpolator 等 21+ API）。
 */
object RoundWatch {
    private const val TAG = "qqpro_round_mask"
    private const val PAD_KEY = 0x7f0f_0001

    /** 圆屏模式是否生效。本分支强制开启：始终 true，忽略硬件检测与设置开关。 */
    val enabled: Boolean get() = true

    /** 屏幕内切圆半径（px）。 */
    fun radiusPx(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        return minOf(dm.widthPixels, dm.heightPixels) / 2
    }

    /**
     * 圆形内切矩形的**统一安全边距**（px），四边等值。公式（等价于 r·(1 − 1/√2) ≈ 0.293r）：
     *
     *     usable = (int)(min(screenWidth, screenHeight) / √2)
     *     padding = (min(screenWidth, screenHeight) − usable) / 2
     *
     * 即「内切正方形」到屏幕边缘的距离——内容整体按这个值对称缩进，就绝不会被圆边裁掉。
     * 这是全项目唯一的圆屏缩进基准；不做任何硬编码像素值。
     */
    fun insetPx(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        val screenMin = minOf(dm.widthPixels, dm.heightPixels)
        val usable = (screenMin / Math.sqrt(2.0)).toInt()
        return (screenMin - usable) / 2
    }

    /** 兼容别名：圆角处最大裁切距离 = [insetPx]（同一值）。 */
    fun cornerInsetPx(ctx: Context): Int = insetPx(ctx)

    /** 水平安全边距 = 统一 [insetPx]（对称缩进，左右与顶/底一致）。 */
    fun horizontalInsetPx(ctx: Context): Int = insetPx(ctx)

    /** 顶/底安全区 = 统一 [insetPx]（对称缩进）。 */
    fun safeTopBottomPx(ctx: Context): Int = insetPx(ctx)

    /** 圆屏安全区四边（left/top/right/bottom，px），四边等值（对称）。 */
    fun safeInsets(ctx: Context): IntArray {
        val p = insetPx(ctx)
        return intArrayOf(p, p, p, p)
    }

    /**
     * 给滚动容器（ScrollView / RecyclerView / 普通 ViewGroup）叠加圆屏安全 padding，
     * 与调用方已有 padding 相加（不覆盖）。幂等（用 keyed tag 标记已处理）。仅 [enabled] 时生效。
     */
    fun applySafePadding(view: View) {
        if (!enabled) return
        if (view.getTag(PAD_KEY) == java.lang.Boolean.TRUE) return
        view.setTag(PAD_KEY, java.lang.Boolean.TRUE)
        val ins = safeInsets(view.context)
        view.setPadding(
            view.paddingLeft + ins[0],
            view.paddingTop + ins[1],
            view.paddingRight + ins[2],
            view.paddingBottom + ins[3],
        )
    }

    /**
     * 给根布局设置**对称**的圆形内切矩形缩进：`setPadding(p, p, p, p)`。用于顶栏/底栏/输入框
     * 等需要整体内收进内切正方形的根容器（不做单向偏移）。
     */
    fun applyUniformInset(view: View) {
        if (!enabled) return
        val p = insetPx(view.context)
        view.setPadding(p, p, p, p)
    }

    /** 圆屏上建议的最小可点击命中尺寸（px），取 48dp，减少小按钮在圆边的误触。 */
    fun hitTargetPx(ctx: Context): Int = (48f * ctx.resources.displayMetrics.density).toInt()

    /**
     * 居中弹窗/菜单（长按菜单、选项列表、附件面板等）在圆屏安全区内的最大高度（px）。
     * 内容超高时据此封顶，配合内部滚动，保证全部条目都能滚到、不被圆边/屏幕边缘裁掉。
     */
    fun popupMaxHeightPx(ctx: Context): Int {
        val dm = ctx.resources.displayMetrics
        return (dm.heightPixels - 2 * safeTopBottomPx(ctx)).coerceAtLeast(dm.heightPixels / 2)
    }

    /** 页面根视图盖圆表遮罩（幂等；仅在 [enabled] 时生效）。 */
    fun apply(root: View) {
        if (!enabled) return
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

    /** 把内层 drawable 裁剪到所在 bounds 的内切圆（圆表安全区），供背景图等使用。 */
    fun circleClip(inner: Drawable): Drawable = CircleClipDrawable(inner)
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
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
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
