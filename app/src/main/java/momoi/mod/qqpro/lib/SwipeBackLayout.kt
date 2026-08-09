package momoi.mod.qqpro.lib

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsSeekBar
import android.widget.FrameLayout
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import kotlin.math.abs

/**
 * Marker for views that own horizontal drags (sliders / seek bars). [SwipeBackLayout] won't grab a
 * swipe-back gesture that starts on one, so dragging the widget never dismisses the screen. Native
 * [AbsSeekBar] is recognised too; this covers our self-drawn ones (e.g. [momoi.mod.qqpro.lib.material.M3Slider]).
 */
interface HorizontalDragWidget

/**
 * Wraps a screen's content and detects a left-to-right "swipe back" drag, then invokes
 * [onSwipeBack] (typically `finish()`). Lets watches without a hardware back button leave a
 * plain [android.app.Activity] (e.g. the settings page) the same way the QQ fling framework
 * does for the main activity — but fully self-contained, so it doesn't depend on QQ's
 * WatchActivity / MobileQQ lifecycle wiring.
 *
 * Vertical drags pass through to children (e.g. a ScrollView) untouched; only a horizontal,
 * rightward drag is intercepted.
 */
class SwipeBackLayout(context: Context) : FrameLayout(context) {

    var onSwipeBack: (() -> Unit)? = null

    // When true, this instance ignores the global "屏蔽应用内右滑返回" setting and always allows
    // swipe-back. Used by the settings page itself so the user can't get trapped on a watch
    // without a hardware back button after turning the setting on.
    var ignoreDisableSetting = false

    // Optional dynamic gate, evaluated on each gesture's DOWN. When set and it returns false the
    // swipe is suppressed for that gesture. Media viewers use this to only allow swipe-back while
    // the image/video is fully zoomed out (so a horizontal drag pans the zoomed content instead).
    var canSwipe: (() -> Boolean)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    // Set on DOWN when the touch lands on a horizontal-drag widget (e.g. a settings SeekBar).
    // Those live inside a ScrollView, so they don't claim the gesture on DOWN — without this
    // guard our MOVE interception steals the drag before the slider can move.
    private var blockSwipe = false

    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        // A child that scrolls horizontally (e.g. ViewPager2 in the media gallery) calls this to lock
        // its ancestors out of intercepting. When our dynamic gate currently allows a swipe-back we
        // ignore that lock so we still get a shot at the gesture — our onInterceptTouchEvent only
        // grabs a rightward-dominant drag, so leftward paging / vertical scrolls still reach the child.
        if (disallow && canSwipe?.invoke() == true) {
            Utils.log("SBL: ignoring disallowIntercept (canSwipe=true)")
            return
        }
        super.requestDisallowInterceptTouchEvent(disallow)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Use raw (screen) coords: ev.x is relative to this view, so once we start
                // translating it the local space shifts and the offset oscillates/jumps.
                downX = ev.rawX
                downY = ev.rawY
                tracking = false
                // Disabled globally → never intercept (settings page opts out via ignoreDisableSetting).
                blockSwipe = (!ignoreDisableSetting && Settings.disableSwipeBack.value) ||
                    (canSwipe?.let { !it.invoke() } ?: false) ||
                    isOnHorizontalDragWidget(this, ev.rawX, ev.rawY)
                Utils.log("SBL: DOWN blockSwipe=$blockSwipe canSwipe=${canSwipe?.invoke()}")
            }
            MotionEvent.ACTION_MOVE -> {
                // Never grab a multi-touch gesture (a pinch-zoom would otherwise look like a drag).
                if (blockSwipe || ev.pointerCount > 1) return false
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                // Horizontal-dominant rightward drag → grab it for swipe-back.
                if (dx > touchSlop && dx > abs(dy) * 1.5f) {
                    Utils.log("SBL: intercept MOVE dx=$dx dy=$dy -> tracking")
                    tracking = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // We only land here for DOWN when NO child consumed it (e.g. an empty screen whose
                // only content is a centered TextView — no ScrollView to claim the touch). The
                // framework then routes every following MOVE/UP straight to our onTouchEvent and
                // never calls onInterceptTouchEvent again, so the intercept path can't start the
                // drag. Claim the gesture here (unless suppressed) so we keep receiving MOVE and can
                // begin tracking ourselves below. onInterceptTouchEvent already ran for this same
                // DOWN and set downX/downY/blockSwipe.
                Utils.log("SBL: onTouch DOWN claim=${!blockSwipe}")
                return !blockSwipe
            }
            MotionEvent.ACTION_MOVE -> {
                // Direct-dispatch path: start tracking the first time the drag turns horizontal.
                if (!tracking && !blockSwipe && ev.pointerCount == 1) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dx > touchSlop && dx > abs(dy) * 1.5f) {
                        Utils.log("SBL: onTouch start tracking dx=$dx dy=$dy")
                        tracking = true
                    }
                }
                if (tracking) {
                    val tx = (ev.rawX - downX).coerceAtLeast(0f)
                    translationX = tx
                    // Fade the screen out as it slides so the (black) backdrop dims up through it.
                    alpha = if (width > 0) (1f - 0.55f * (tx / width)).coerceIn(0.45f, 1f) else 1f
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    tracking = false
                    val dx = ev.rawX - downX
                    Utils.log("SBL: UP dx=$dx width=$width fire=${dx > width * 0.3f}")
                    if (ev.actionMasked == MotionEvent.ACTION_UP && dx > width * 0.3f) {
                        // Continue the slide off-screen (don't snap back to origin first — that looked
                        // like a bounce), fading out, THEN reset translation/alpha and fire. Resetting
                        // before the callback means a callback that swaps content in place (e.g. the
                        // settings detail→list navigation) lands the new content at the origin instead
                        // of stranded off-screen; a callback that finishes the activity just closes.
                        animate().translationX(width.toFloat()).alpha(0f).setDuration(180)
                            .withEndAction {
                                translationX = 0f
                                alpha = 1f
                                onSwipeBack?.invoke()
                            }.start()
                    } else {
                        animate().translationX(0f).alpha(1f).setDuration(150).start()
                    }
                }
            }
        }
        return tracking
    }

    /** True if [x],[y] (screen coords) fall on a visible horizontal-drag widget descendant of [v]. */
    private fun isOnHorizontalDragWidget(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v is AbsSeekBar || v is HorizontalDragWidget) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            // Pad the hit area so a drag started just beside the thumb still counts.
            val pad = touchSlop
            if (x >= loc[0] - pad && x <= loc[0] + v.width + pad &&
                y >= loc[1] - pad && y <= loc[1] + v.height + pad
            ) {
                return true
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (isOnHorizontalDragWidget(v.getChildAt(i), x, y)) return true
            }
        }
        return false
    }
}
