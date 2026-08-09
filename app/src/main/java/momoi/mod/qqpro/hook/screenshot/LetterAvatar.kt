package momoi.mod.qqpro.hook.screenshot

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import momoi.mod.qqpro.lib.material.M3

/**
 * Anonymization for the chat-screenshot feature (when 显示昵称与头像 is off): each distinct sender is
 * mapped, in first-seen order, to a letter (A, B, C, …) and a stable color, used as the nick text and a
 * generated letter-circle avatar. [reset] is called at the start of each capture so letters restart.
 */
object LetterAvatar {
    // A spread of distinct, legible hues for the letter circles.
    private val palette = intArrayOf(
        0xFF_E57373.toInt(), 0xFF_64B5F6.toInt(), 0xFF_81C784.toInt(), 0xFF_FFB74D.toInt(),
        0xFF_BA68C8.toInt(), 0xFF_4DB6AC.toInt(), 0xFF_F06292.toInt(), 0xFF_A1887F.toInt(),
        0xFF_9575CD.toInt(), 0xFF_4FC3F7.toInt(),
    )
    private val order = LinkedHashMap<String, Int>()

    fun reset() = order.clear()

    private fun indexOf(uid: String): Int = order.getOrPut(uid) { order.size }

    /** Letter for a sender: A, B, …, Z, then AA, AB, … (rare past 26). */
    fun letterFor(uid: String): String {
        var n = indexOf(uid)
        val sb = StringBuilder()
        do { sb.insert(0, ('A' + (n % 26))); n = n / 26 - 1 } while (n >= 0)
        return sb.toString()
    }

    fun colorFor(uid: String): Int = palette[indexOf(uid) % palette.size]

    fun drawableFor(uid: String): Drawable = LetterAvatarDrawable(letterFor(uid), colorFor(uid))
}

/** A filled circle with a centered letter — a generated stand-in avatar. */
class LetterAvatarDrawable(private val letter: String, private val bg: Int) : Drawable() {
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = M3.onColor(bg)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(cx, cy, r, circlePaint)
        textPaint.textSize = r * 1.0f
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(letter, cx, baseline, textPaint)
    }

    override fun setAlpha(alpha: Int) { circlePaint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { circlePaint.colorFilter = cf }
    @Deprecated("deprecated in API")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
