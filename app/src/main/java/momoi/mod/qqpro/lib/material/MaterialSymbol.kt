package momoi.mod.qqpro.lib.material

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import momoi.mod.qqpro.lib.dp

/**
 * Renders an official Google Material Symbol (path data from [MaterialSymbols]) as a tintable,
 * infinitely-scalable [Drawable]. Replaces the project's hand-drawn icons and emoji-as-icons.
 *
 * Material Symbols use a `0 -960 960 960` SVG viewBox (x: 0..960, y: -960..0); the path is mapped
 * into the drawable bounds (square, centered) once per [onBoundsChange]. Tint with the constructor
 * color or [setTint]; the default is white so it reads on the dark watch theme.
 */
open class MaterialSymbol(
    pathData: String,
    tint: Int = 0xFF_FFFFFF.toInt(),
    private val insetFraction: Float = 0f,
) : Drawable() {

    private val src: Path = runCatching { PathParser.createPathFromPathData(pathData) }.getOrNull() ?: Path()
    private val rendered = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = tint
    }

    /** Recolor the glyph (the fill paint color). Named to avoid clashing with Drawable.setTint(Int). */
    fun recolor(color: Int) { paint.color = color; invalidateSelf() }

    override fun onBoundsChange(bounds: Rect) {
        val size = minOf(bounds.width(), bounds.height()).toFloat() * (1f - 2f * insetFraction)
        if (size <= 0f) return
        val scale = size / 960f
        val dx = bounds.left + (bounds.width() - size) / 2f
        val dy = bounds.top + (bounds.height() - size) / 2f
        val m = Matrix().apply {
            postTranslate(0f, 960f)   // y: [-960, 0] → [0, 960]
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
        src.transform(m, rendered)
    }

    // A sensible default size so plain ImageView/symbolImage usage renders even without explicit
    // bounds (compound-drawable usage still calls setBounds itself).
    override fun getIntrinsicWidth() = 24.dp
    override fun getIntrinsicHeight() = 24.dp

    override fun draw(canvas: Canvas) = canvas.drawPath(rendered, paint)
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** A Material Symbol [path] in [fg], centered on a filled circle of [bg] (for menu items). */
        fun circled(path: String, fg: Int, bg: Int): Drawable = object : Drawable() {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg; style = Paint.Style.FILL }
            private val glyph = MaterialSymbol(path, fg, insetFraction = 0.26f)
            override fun onBoundsChange(b: Rect) { glyph.bounds = b }
            override fun draw(canvas: Canvas) {
                val b = bounds
                canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) / 2f, bgPaint)
                glyph.draw(canvas)
            }
            override fun setAlpha(a: Int) { bgPaint.alpha = a }
            override fun setColorFilter(cf: ColorFilter?) { bgPaint.colorFilter = cf }
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }
}
