package momoi.mod.qqpro.lib.material

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import momoi.mod.qqpro.lib.dp

/**
 * Drawable form of the MD3 indeterminate circular spinner (a rounded arc that rotates while its
 * sweep grows/shrinks), used to replace QQ's animated APNG loading drawables (LoadingUtil.b — the
 * grey pull-to-refresh spinners and the colorful camera/loading/QR ones) in one place. Self-animates
 * via [scheduleSelf] while visible (the host ImageView/background toggles visibility), so it spins
 * exactly like the APNG it replaces. Color follows the live [M3] theme accent.
 */
class M3ProgressDrawable(
    private val color: Int = M3.primary,
    private val strokePx: Float = 3.dp.toFloat(),
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokePx
        color = this@M3ProgressDrawable.color
    }
    private val oval = RectF()

    /** When false, draws a static determinate arc from the top sized to [progress] (0..1). */
    var indeterminate: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (isVisible && value) { lastTick = 0L; scheduleSelf(ticker, SystemClock.uptimeMillis()) }
            else if (!value) unscheduleSelf(ticker)
            invalidateSelf()
        }

    /** Determinate fraction 0..1 (used only when [indeterminate] is false). */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (!indeterminate) invalidateSelf()
        }

    private var sweepPhase = 0f   // 0..1 within the grow/shrink cycle
    private var baseRotation = 0f // degrees, continuous drift
    private var lastTick = 0L

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastTick == 0L) 16L else (now - lastTick).coerceIn(1L, 64L)
            lastTick = now
            sweepPhase = (sweepPhase + dt / 1100f) % 1f
            baseRotation = (baseRotation + dt * 0.36f) % 360f
            invalidateSelf()
            if (isVisible) scheduleSelf(this, now + 16)
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val pad = strokePx / 2f + 1f
        oval.set(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad)
        if (!indeterminate) {
            // Determinate: arc from the top (12 o'clock) sweeping clockwise to [progress].
            canvas.drawArc(oval, -90f, progress * 360f, false, paint)
            return
        }
        val grow = if (sweepPhase < 0.5f) sweepPhase * 2f else (1f - sweepPhase) * 2f
        val sweep = 20f + grow * 280f
        val start = baseRotation + sweepPhase * 360f * 2f
        canvas.drawArc(oval, start, sweep, false, paint)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible && indeterminate) {
            lastTick = 0L
            unscheduleSelf(ticker)
            scheduleSelf(ticker, SystemClock.uptimeMillis())
        } else {
            unscheduleSelf(ticker)
        }
        return changed
    }

    private val intrinsic = 24.dp
    override fun getIntrinsicWidth() = intrinsic
    override fun getIntrinsicHeight() = intrinsic

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
