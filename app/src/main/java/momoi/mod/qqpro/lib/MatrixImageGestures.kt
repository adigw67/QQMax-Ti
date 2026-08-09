package momoi.mod.qqpro.lib

import android.content.Context
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.doOnLayout
import com.tencent.richframework.widget.matrix.RFWMatrixImageView
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils

/**
 * Shared gesture helpers for the native zoomable image view (`RFWMatrixImageView`, which draws via
 * `ScaleType.MATRIX` and exposes its live zoom/pan through `getImageMatrix()`). Used by every custom
 * photo viewer (chat `BigImageFragment`, the QZone/album `MediaPager`) so they all behave the same:
 * tap the empty area to dismiss, and swipe-back only while fully zoomed out.
 */

/** Displayed image rect in view coords, from the live image matrix (captures zoom/pan) + padding. */
fun ImageView.displayedImageRect(): RectF? {
    val d = drawable ?: return null
    val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
    imageMatrix.mapRect(r)
    r.offset(paddingLeft.toFloat(), paddingTop.toFloat())
    return r
}

/** True if [x],[y] (view coords) fall outside the currently displayed image. */
fun ImageView.isTapOutsideImage(x: Float, y: Float): Boolean {
    val r = displayedImageRect() ?: return false
    return x < r.left || x > r.right || y < r.top || y > r.bottom
}

/** True if the displayed image fits within the view width → no horizontal pan room (safe to swipe-back). */
fun ImageView.imageFitsHorizontally(): Boolean {
    val r = displayedImageRect() ?: return true
    return r.left >= -1f && r.right <= width + 1f
}

// A "long screenshot" is at least this many times taller than the screen (measured at width-fit).
private const val LONG_SHOT_MIN_RATIO = 2f
// Level 3 ("zoom in even more") = this many times the level-2 (match-width) scale.
private const val LONG_SHOT_L3_FACTOR = 2f

/**
 * Long-screenshot support for the custom full-screen viewers ([momoi.mod.qqpro.hook.aio_cell.BigImageFragment]
 * and [momoi.mod.qqpro.hook.view.MediaPager]), which both drive a native [RFWMatrixImageView] through
 * its public zoom API (no Mixin involved).
 *
 * When [Settings.longScreenshot] is on and this image is at least [LONG_SHOT_MIN_RATIO]x taller than
 * the screen, open it fitted to the view WIDTH and anchored to the TOP — so the content is readable
 * and scrolls up/down — instead of the native fit-whole-height (a thin centered column). Double-tap
 * then cycles three zoom levels:
 *   - level 1 = match height (whole image fits)
 *   - level 2 = match width, scroll up/down  ← the default we open at
 *   - level 3 = zoom in even more
 * No-op for normal images (keeps the native fit). Safe to call right after kicking off the image
 * load: it defers itself until the view is laid out and the bitmap has actually been applied.
 */
fun RFWMatrixImageView.applyLongScreenshotFit() {
    if (!Settings.longScreenshot.value) return
    // Some decode paths set the bitmap inside a posted runnable, so wait for layout AND let the
    // pending bitmap-set / base-fit-matrix work run before we read sizes and override the scale.
    doOnLayout { post { fitLongScreenshotNow() } }
}

private fun RFWMatrixImageView.fitLongScreenshotNow(retry: Int = 2) {
    val dr = drawable
    if (dr == null || width == 0 || height == 0) {
        if (retry > 0) post { fitLongScreenshotNow(retry - 1) } // drawable not applied yet — try again
        return
    }
    val iw = dr.intrinsicWidth.toFloat()
    val ih = dr.intrinsicHeight.toFloat()
    val vw = (width - paddingLeft - paddingRight).toFloat()
    val vh = (height - paddingTop - paddingBottom).toFloat()
    if (iw <= 0f || ih <= 0f || vw <= 0f || vh <= 0f) return
    // Relative scale (over the native FIT_CENTER base) that makes the image width fill the view.
    // For a tall image the base fit is height-fit, so this value also equals
    // (content height when width-fitted) / (view height): >= 2 ⇒ image is at least 2x screen-tall.
    val widthFitScale = (vw * ih) / (iw * vh)
    Utils.log("longScreenshot: intrinsic=${iw.toInt()}x${ih.toInt()} view=${vw.toInt()}x${vh.toInt()} widthFitScale=$widthFitScale")
    // PhotoViewAttacher fields d/e/f = min/medium/max scale (the three double-tap stops). Set them
    // directly so we bypass the round-screen Mixin's setMaximumScale(*3) override and keep exact levels.
    val att = attacher
    if (widthFitScale < LONG_SHOT_MIN_RATIO) {
        // Not a long screenshot → keep the native fit, and restore the default zoom stops in case
        // this RFWMatrixImageView was recycled (MediaPager) from a previous long screenshot.
        att.d = 1f; att.e = 1.75f; att.f = 3.5f
        return
    }
    att.d = 1f                                   // level 1: match height (the native base fit)
    att.e = widthFitScale                        // level 2: match width, scroll up/down
    att.f = widthFitScale * LONG_SHOT_L3_FACTOR  // level 3: zoom in even more
    // Open at level 2, anchored to the TOP edge (focusY = 0) so a long screenshot starts at its top.
    att.l(widthFitScale, width / 2f, 0f, false)
}

/**
 * A [FrameLayout] that observes single taps WITHOUT consuming touches, so a child zoomable image
 * keeps its own pinch / double-tap / pan. The detector is fed from [dispatchTouchEvent], which sees
 * every event regardless of which child consumes it. [onSingleTap] gets the tap in this view's coords.
 */
class TapObserverLayout(ctx: Context) : FrameLayout(ctx) {
    var onSingleTap: ((Float, Float) -> Unit)? = null

    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke(e.x, e.y)
            return false
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        runCatching { detector.onTouchEvent(ev) }
        return super.dispatchTouchEvent(ev)
    }
}
