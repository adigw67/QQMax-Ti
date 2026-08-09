package momoi.mod.qqpro.lib.material

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import momoi.mod.qqpro.lib.dp

/**
 * A self-drawn Material 3 circular progress indicator (no com.google.android.material dependency —
 * the watch theme isn't MaterialComponents-based). Supports both modes:
 *  - **indeterminate** (default): a rounded arc that continuously rotates while its sweep grows and
 *    shrinks — the MD3 "loading" spinner. Use when the total isn't known (network fetch, decode…).
 *  - **determinate**: set [indeterminate]=false and drive [progress] (0..1) — a track ring plus a
 *    rounded progress arc from the top. Use for downloads/uploads with a known size.
 *
 * Colors come from the live [M3] tokens, so it follows the user's theme accent. Reuse everywhere a
 * loading state is shown (image/video, QZone, chat history, member list, swipe-to-refresh…), via the
 * [M3Progress] overlay helpers or directly as a view.
 *
 * Public on purpose (referenced from @Mixin bodies).
 */
class M3CircularProgress(ctx: Context) : View(ctx) {

    /** Indeterminate (spinning) when true; driven by [progress] when false. */
    var indeterminate: Boolean = true
        set(value) {
            field = value
            if (value) startAnim() else stopAnim()
            invalidate()
        }

    /** Determinate progress 0..1 (ignored while [indeterminate]). */
    var progress: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            field = v
            if (!indeterminate) animateProgressTo(v)
        }

    // The smoothly-animated value actually drawn, so progress steps glide instead of jumping.
    private var shownProgress = 0f
    private var progressAnim: ValueAnimator? = null

    private fun animateProgressTo(target: Float) {
        if (target == shownProgress) return
        progressAnim?.cancel()
        // Only animate forward jumps; snap backwards (e.g. reset to 0) instantly.
        if (target < shownProgress) { shownProgress = target; invalidate(); return }
        progressAnim = ValueAnimator.ofFloat(shownProgress, target).apply {
            duration = 250
            addUpdateListener { shownProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    var indicatorColor: Int = M3.primary
        set(value) { field = value; arcPaint.color = value; invalidate() }

    /** Track ring color (shown in determinate mode); 0 = no track. */
    var trackColor: Int = M3.outlineVariant
        set(value) { field = value; trackPaint.color = value; invalidate() }

    private val strokePx = 3.dp.toFloat()
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = strokePx; color = indicatorColor
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = strokePx; color = trackColor
    }
    private val oval = RectF()

    // Animation state (indeterminate): a base rotation plus a sweep that oscillates.
    private var baseRotation = 0f      // degrees, continuous
    private var sweepPhase = 0f        // 0..1 within the grow/shrink cycle
    private var anim: ValueAnimator? = null

    private val defaultSizePx = 28.dp

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(defaultSizePx, widthMeasureSpec),
            resolveSize(defaultSizePx, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val pad = strokePx / 2f + 1f
        oval.set(pad, pad, width - pad, height - pad)

        if (indeterminate) {
            // Sweep grows 20°→300°→20° across the cycle; combine with a continuous rotation.
            val grow = if (sweepPhase < 0.5f) sweepPhase * 2f else (1f - sweepPhase) * 2f
            val sweep = 20f + grow * 280f
            val start = baseRotation + sweepPhase * 360f * 2f
            canvas.drawArc(oval, start, sweep, false, arcPaint)
        } else {
            if (trackColor != 0) canvas.drawArc(oval, 0f, 360f, false, trackPaint)
            canvas.drawArc(oval, -90f, shownProgress * 360f, false, arcPaint)
        }
    }

    private fun startAnim() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                sweepPhase = t
                baseRotation = (baseRotation + 6f) % 360f   // steady drift so it never looks static
                invalidate()
            }
            start()
        }
    }

    private fun stopAnim() { anim?.cancel(); anim = null }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (indeterminate && isShown) startAnim()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnim()
        progressAnim?.cancel()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && indeterminate) startAnim() else if (visibility != VISIBLE) stopAnim()
    }
}
