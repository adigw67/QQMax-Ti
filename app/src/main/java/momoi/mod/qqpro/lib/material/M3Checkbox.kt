package momoi.mod.qqpro.lib.material

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf

/**
 * A self-drawn Material 3 checkbox graphic, used as a [CompoundButton]'s button drawable so the native
 * checkbox keeps all of its selection logic but reads as M3 (no com.google.android.material dependency,
 * which renders wrong at the watch DPI). Colors come from the live [M3] tokens so it follows the theme.
 *
 * Three visual states (driven by the host's drawable state):
 *  - unchecked            → thin outlined rounded square
 *  - checked              → filled accent square with a white check
 *  - checked + disabled   → dimmed (38% / locked) accent square — e.g. a member who's force-selected
 *
 * Sized to its bounds (the selector's QUICheckBox is 16dp), so it never clips.
 */
class M3CheckDrawable : Drawable() {
    private var checked = false
    private var enabled = true
    private val rect = RectF()
    private val radius = 4.dp.toFloat()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f.dpf
    }
    private val check = MaterialSymbol(MaterialSymbols.check, M3.onPrimary, insetFraction = 0.2f)

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val c = state.any { it == android.R.attr.state_checked }
        val e = state.any { it == android.R.attr.state_enabled }
        if (c == checked && e == enabled) return false
        checked = c; enabled = e
        // Recolor the check glyph here (NOT in draw, which would invalidate-loop).
        check.recolor(if (enabled) M3.onPrimary else dim(M3.onPrimary, 0x99))
        invalidateSelf()
        return true
    }

    override fun onBoundsChange(b: Rect) { check.bounds = b }

    override fun getIntrinsicWidth() = 16.dp
    override fun getIntrinsicHeight() = 16.dp

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = stroke.strokeWidth
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        if (checked) {
            fill.color = if (enabled) M3.primary else dim(M3.primary, 0x61)
            canvas.drawRoundRect(rect, radius, radius, fill)
            check.draw(canvas)
        } else {
            stroke.color = if (enabled) M3.onSurfaceVariant else dim(M3.onSurfaceVariant, 0x61)
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
    }

    private fun dim(color: Int, alpha: Int) = (alpha shl 24) or (color and 0xFFFFFF)

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
