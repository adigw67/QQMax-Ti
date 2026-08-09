package momoi.mod.qqpro.hook.style

import android.content.Context
import android.content.res.Resources
import android.widget.ImageView

class MyImageView(context: Context) : ImageView(context) {
    init {
        adjustViewBounds = true
        scaleType = ScaleType.FIT_CENTER
        maxHeight = heightLimit.toInt()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = heightLimit.toInt()
        val d = drawable
        val ih = d?.intrinsicHeight ?: 0
        val iw = d?.intrinsicWidth ?: 0
        if (ih <= 0 || iw <= 0) {
            // No drawable yet (still loading, e.g. an undownloaded image/video thumbnail).
            // Reserve the final size up-front from the element's known pixel dimensions (set as
            // layoutParams width/height by the caller via .size(pic.picWidth, pic.picHeight)),
            // aspect-fitted to heightLimit, so the placeholder doesn't start collapsed and jump
            // when the bitmap arrives. Mirrors the live-chat 表情包显示大小限制 height cap.
            val lw = layoutParams?.width ?: 0
            val lh = layoutParams?.height ?: 0
            if (lw > 0 && lh > 0) {
                var w = lw
                var h = lh
                if (h > cap) {
                    w = (w.toFloat() * cap / h).toInt()
                    h = cap
                }
                setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
                return
            }
            // Unknown dimensions: just cap the height.
            maxHeight = cap
            maxWidth = Int.MAX_VALUE
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        maxHeight = cap
        maxWidth = (heightLimit / ih * iw).toInt()
        if (maxWidth < maxHeight) maxWidth = maxHeight
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        )
    }
}