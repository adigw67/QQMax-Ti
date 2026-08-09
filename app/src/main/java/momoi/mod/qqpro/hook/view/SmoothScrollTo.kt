package momoi.mod.qqpro.hook.view

import android.animation.ValueAnimator
import android.os.Build
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.view.View
import androidx.recyclerview.widget.AIOLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import momoi.mod.qqpro.lib.material.M3

// How many rows of real animation a jump is allowed to play. Anything farther than this from the
// current viewport is teleported to within this many rows of the target first, then the short hop is
// animated — so the user always sees a quick glide into place instead of a long crawl over hundreds
// of messages they can't perceive anyway.
private const val JUMP_ANIM_MAX_ROWS = 8

// Glide speed for jump animations (lower = faster). LinearSmoothScroller's default is 25f/inch, which
// feels sluggish for a short hop; this is snappier while still reading as a scroll.
private const val JUMP_SCROLL_MS_PER_INCH = 12f

/**
 * Smooth-scroll so [position] snaps to the top. For far targets this teleports to within
 * [JUMP_ANIM_MAX_ROWS] of the target (instantly, over the unseen stretch) and then animates only the
 * final short hop — fast jump, but the user still sees a brief glide into the destination. Near
 * targets animate the whole way.
 *
 * This is the ONLY scroll helper jump features need: every programmatic UP-jump (reply source,
 * jump-to-first-unread, chat search, forward, nav) goes through it. Go-to-bottom is NOT here — it
 * delegates to the native QQ JumpBottom click (see BubbleTextView.goToBottom), which loads the latest
 * page and lands on the true visual bottom past the input-bar footer. A previous custom go-to-bottom
 * glide oscillated forever at the bottom (canScrollVertically never reads false there because the footer
 * sits below the newest message), so it was removed in favour of delegating to native.
 */
fun RecyclerView.smoothScrollToStart(position: Int) {
    val n = adapter?.itemCount ?: 0
    if (n <= 0) return
    val target = position.coerceIn(0, n - 1)
    val first = firstVisiblePosition()
    val last = lastVisiblePosition()
    if (first >= 0 && last >= 0 &&
        (target < first - JUMP_ANIM_MAX_ROWS || target > last + JUMP_ANIM_MAX_ROWS)
    ) {
        // Land JUMP_ANIM_MAX_ROWS short of the target on the side we're coming from, then animate in.
        val pre = (if (target < first) target + JUMP_ANIM_MAX_ROWS else target - JUMP_ANIM_MAX_ROWS)
            .coerceIn(0, n - 1)
        scrollToPosition(pre)
        post { startSnapToStart(target) }
        return
    }
    startSnapToStart(target)
}

// Peak alpha (0..255) of the accent flash painted over a jumped-to message row, and how long the
// whole pulse (fade in → out → in → out) lasts. Two short pulses read clearly as "flash" without
// lingering long enough to feel like a stuck highlight.
private const val FLASH_PEAK_ALPHA = 0x66
private const val FLASH_DURATION_MS = 1100L
// Max frames to wait for the jump to settle and the target row to attach before giving up on the flash.
private const val FLASH_MAX_TRIES = 90

/**
 * Like [smoothScrollToStart], but once the list comes to rest it briefly flashes a translucent accent
 * over the target message's row so the eye can find where the jump landed. Used by the chat-message
 * jumps (reply source, jump chip) — NOT the nav/contact lists, which scroll the same way but have no
 * message worth highlighting.
 */
fun RecyclerView.smoothScrollToStartAndFlash(position: Int) {
    val n = adapter?.itemCount ?: 0
    if (n <= 0) return
    val target = position.coerceIn(0, n - 1)
    smoothScrollToStart(target)
    // The jump can teleport-then-animate, so the target isn't attached on the first frame. Poll until
    // the scroll is idle AND the target's ViewHolder exists, then flash it.
    waitForSettleThenFlash(target, FLASH_MAX_TRIES)
}

private fun RecyclerView.waitForSettleThenFlash(target: Int, tries: Int) {
    if (tries <= 0) return
    val settled = scrollState == RecyclerView.SCROLL_STATE_IDLE
    val vh = if (settled) findViewHolderForAdapterPosition(target) else null
    if (vh == null) {
        postOnAnimation { waitForSettleThenFlash(target, tries - 1) }
        return
    }
    flashRow(vh.itemView)
}

/** Pulse a translucent accent over [row]'s foreground twice, then restore whatever was there. */
private fun flashRow(row: View) {
    // View.setForeground/getForeground are API 23+; on API 19 (the watch) the property access throws
    // NoSuchMethodError on the main thread, which is the "tap reply card → crash/freeze" bug. Fall
    // back to an alpha pulse of the whole row so the jump still highlights the target.
    if (Build.VERSION.SDK_INT < 23) {
        ValueAnimator.ofFloat(1f, 0.45f, 1f, 0.45f, 1f).apply {
            duration = FLASH_DURATION_MS
            addUpdateListener { row.alpha = it.animatedValue as Float }
            start()
        }
        return
    }
    val overlay = ColorDrawable(M3.primary)
    val previous: Drawable? = row.foreground
    row.foreground = overlay
    ValueAnimator.ofInt(0, FLASH_PEAK_ALPHA, 0, FLASH_PEAK_ALPHA, 0).apply {
        duration = FLASH_DURATION_MS
        addUpdateListener { overlay.alpha = it.animatedValue as Int }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                row.foreground = previous
            }
        })
        start()
    }
}

private fun RecyclerView.startSnapToStart(position: Int) {
    layoutManager?.startSmoothScroll(
        object : LinearSmoothScroller(context) {
            init {
                targetPosition = position
            }

            override fun getVerticalSnapPreference(): Int {
                return SNAP_TO_START
            }

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                return JUMP_SCROLL_MS_PER_INCH / displayMetrics.densityDpi
            }
        })
}

/**
 * Animated go-to-bottom: teleport to within [JUMP_ANIM_MAX_ROWS] of the last item (the footer, == the
 * true visual bottom) then smooth-scroll the short remainder, snapping the footer to the viewport bottom.
 * Uses the framework's [LinearSmoothScroller] (which targets a fixed position and self-terminates) — NOT
 * a hand-rolled scrollBy loop, so it can't oscillate at the bottom the way the old custom glide did.
 */
fun RecyclerView.smoothScrollToEnd() {
    val n = adapter?.itemCount ?: 0
    if (n <= 0) return
    val target = n - 1
    val last = lastVisiblePosition()
    if (last in 0 until (target - JUMP_ANIM_MAX_ROWS)) {
        scrollToPosition((target - JUMP_ANIM_MAX_ROWS).coerceIn(0, target))
        post { startSnapToEnd(target) }
    } else {
        startSnapToEnd(target)
    }
}

private fun RecyclerView.startSnapToEnd(position: Int) {
    layoutManager?.startSmoothScroll(
        object : LinearSmoothScroller(context) {
            init {
                targetPosition = position
            }

            override fun getVerticalSnapPreference(): Int {
                return SNAP_TO_END
            }

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                return JUMP_SCROLL_MS_PER_INCH / displayMetrics.densityDpi
            }
        })
}

// First/last visible adapter positions. The chat list's runtime AIOLayoutManager is NOT a
// LinearLayoutManager (the cast returns null at runtime even though the compile stub says it extends
// one), so try it first, then fall back to a genuine LinearLayoutManager (contacts / nav lists).
private fun RecyclerView.firstVisiblePosition(): Int =
    (layoutManager as? AIOLayoutManager)?.findFirstVisibleItemPosition()
        ?: (layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()
        ?: RecyclerView.NO_POSITION

private fun RecyclerView.lastVisiblePosition(): Int =
    (layoutManager as? AIOLayoutManager)?.findLastVisibleItemPosition()
        ?: (layoutManager as? LinearLayoutManager)?.findLastVisibleItemPosition()
        ?: RecyclerView.NO_POSITION
