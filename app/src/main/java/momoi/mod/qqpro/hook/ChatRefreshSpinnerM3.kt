package momoi.mod.qqpro.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import com.tencent.biz.qui.dragrefresh.QUIDragRefreshView
import com.tencent.qqnt.chats.view.RefreshView
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3ProgressDrawable
import momoi.mod.qqpro.util.Utils

/**
 * Materialize the main chat list's pull-to-refresh indicator. It's a [QUIDragRefreshView] (the
 * rotating tray/chevron icon) driven directly by the chat list's TwoLevel header — NOT through
 * AnimRefreshHeaderView.s() (which is never called), so we hook the view's own drawing: @Mixin
 * [QUIDragRefreshView.onDraw] to paint the MD3 indeterminate arc instead of its native
 * bitmap/polar-light render, self-animating via postInvalidateOnAnimation while it's on screen.
 */
@Mixin
class QUIDragRefreshViewM3(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
    QUIDragRefreshView(context, attrs, defStyleAttr, defStyleRes) {
    override fun onDraw(canvas: Canvas) {
        QUIDragM3.draw(this, canvas)
    }
}

/**
 * The other chat-list refresh variant (quiLoadingSwitch off): [RefreshView] is an ImageView that
 * loads a Lottie via a(). Override a() to set our self-animating [M3ProgressDrawable] instead; all
 * its other methods null-check the (now-absent) Lottie, so they safely no-op.
 */
@Mixin
class RefreshViewM3(context: Context, listener: RefreshView.OnLoadDrawableListener?, nightMode: Boolean) :
    RefreshView(context, listener, nightMode) {
    // a() loads the lottie — replace it with a determinate M3 drawable (fills as you pull).
    override fun a(fileName: String?, listener: RefreshView.OnLoadDrawableListener?) {
        val d = M3ProgressDrawable().apply { indeterminate = false; progress = 0f }
        setImageDrawable(d)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    // Drag: native progress runs ~0..0.5 as you pull; fill the ring 0 -> 100 with the pull.
    override fun setProgress(progress: Float) {
        (drawable as? M3ProgressDrawable)?.apply {
            indeterminate = false
            this.progress = (progress / 0.5f).coerceIn(0f, 1f)
        }
    }

    // Released to refresh: spin (indeterminate).
    override fun b(): Boolean {
        Utils.log("RefreshViewM3: b() -> spin")
        (drawable as? M3ProgressDrawable)?.indeterminate = true
        return false
    }

    // Finished/stopped: reset the determinate ring.
    override fun c(): Boolean {
        Utils.log("RefreshViewM3: c() -> stop")
        (drawable as? M3ProgressDrawable)?.apply { indeterminate = false; progress = 0f }
        return true
    }
}

object QUIDragM3 {
    private var logged = false
    private val strokePx = 3.dp.toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokePx
    }
    private val oval = RectF()

    fun draw(view: View, canvas: Canvas) {
        if (!logged) { logged = true; Utils.log("QUIDragM3: onDraw size=${view.width}x${view.height}") }
        val w = view.width
        val h = view.height
        val d = minOf(w, h)
        if (d <= 0) return
        paint.color = M3.primary
        val pad = strokePx / 2f + 1f
        // Center a square arc; the QUI view is square but be safe.
        val r = d / 2f - pad
        val cx = w / 2f
        val cy = h / 2f
        oval.set(cx - r, cy - r, cx + r, cy + r)

        val now = SystemClock.uptimeMillis()
        val sweepPhase = (now % 1100L) / 1100f
        val baseRotation = (now % 960L) / 960f * 360f
        val grow = if (sweepPhase < 0.5f) sweepPhase * 2f else (1f - sweepPhase) * 2f
        val sweep = 20f + grow * 280f
        val start = baseRotation + sweepPhase * 720f
        canvas.drawArc(oval, start, sweep, false, paint)

        // Keep spinning while the view is on screen (the native render loop is bypassed).
        view.postInvalidateOnAnimation()
    }
}
