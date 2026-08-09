package momoi.mod.qqpro.lib

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.RectF
import android.text.TextPaint
import android.text.style.ReplacementSpan


class RadiusBackgroundSpan : ReplacementSpan {
    private val margin: Int
    private val radius: Int
    private val textColor: Int
    private val bgColor: Int
    private val maxHeight: Int

    constructor(
        margin: Int = 0,
        radius: Int = 12.dp,
        textColor: Int,
        bgColor: Int,
        maxHeight: Int = Int.MAX_VALUE
    ) {
        this.margin = margin
        this.radius = radius
        this.textColor = textColor
        this.bgColor = bgColor
        this.maxHeight = maxHeight
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: FontMetricsInt?
    ): Int {
        val newPaint = getCustomTextPaint(paint)
        // Drive the line height from THIS (possibly down-scaled) tag's own metrics plus the pill's
        // vertical padding, so a tag sitting on its own line collapses to the pill height instead of
        // the full nick line height (which made the background look far too tall).
        if (fm != null) {
            val pm = newPaint.fontMetricsInt
            fm.ascent = pm.ascent - V_PAD
            fm.top = pm.top - V_PAD
            fm.descent = pm.descent + V_PAD
            fm.bottom = pm.bottom + V_PAD
        }
        return newPaint.measureText(text, start, end).toInt() + margin * 2 + H_PAD * 2
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val newPaint = getCustomTextPaint(paint)
        val textWidth = newPaint.measureText(text, start, end)

        // Pill hugs the scaled text height (clamped to maxHeight), centred on the line box — never
        // the full line box itself, so it looks right whether the tag is alone on a line or inline
        // beside the name.
        val fmF = newPaint.fontMetrics
        val pillHeight = minOf((fmF.descent - fmF.ascent) + V_PAD * 2, maxHeight.toFloat())
        val centerY = (top + bottom) / 2f

        val rect = RectF(
            x + margin,
            centerY - pillHeight / 2f + margin,
            x + margin + textWidth + H_PAD * 2,
            centerY + pillHeight / 2f - margin
        )
        paint.color = bgColor
        canvas.drawRoundRect(rect, radius.toFloat(), radius.toFloat(), paint)

        // Baseline that vertically centres the glyphs inside the pill.
        newPaint.color = textColor
        val baseline = centerY - (fmF.ascent + fmF.descent) / 2f
        canvas.drawText(text, start, end, rect.left + H_PAD, baseline, newPaint)
    }

    private fun getCustomTextPaint(srcPaint: Paint?): TextPaint = TextPaint(srcPaint)

    companion object {
        // Horizontal text padding inside the pill (each side) and vertical padding above/below.
        private val H_PAD = 6.dp
        private val V_PAD = 3.dp
    }
}
