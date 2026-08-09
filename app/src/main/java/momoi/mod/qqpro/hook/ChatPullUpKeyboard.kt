package momoi.mod.qqpro.hook

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.asGroupOrNull
import momoi.mod.qqpro.forEachAll
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.util.Utils
import java.util.WeakHashMap

/**
 * "上拉打开键盘": while the chat list is already at its bottom (newest message visible), an extra
 * upward drag past a threshold is overscroll — there is nothing more to reveal — so we treat it as a
 * gesture to pop the inline keyboard. Only active when the inline EditText is enabled
 * ([Settings.inlineChatInput]) and registered ([InlineInput.isReady]); otherwise the drag is ignored
 * and the list behaves normally.
 *
 * Attached lazily from [InlineInput.register] (which runs whenever a chat's input pill is built): we
 * find the chat RecyclerView in the same window and install a non-consuming touch listener on it.
 */
object ChatPullUpKeyboard {
    // px of upward overscroll (past the bottom) required before we open the keyboard.
    private val threshold get() = 56.dp

    // RecyclerViews we've already wired, so re-registering a chat doesn't stack listeners.
    private val attached = WeakHashMap<RecyclerView, Boolean>()

    /** Called from the input-bar hook via [InlineInput.register]; [anchor] is the inline EditText. */
    fun attach(anchor: View) {
        if (!Settings.inlineChatInput.value) return
        anchor.post {
            val rv = findChatList(anchor.rootView) ?: return@post
            if (attached.put(rv, true) == true) return@post
            install(rv)
            Utils.log("ChatPullUpKeyboard: attached to chat list (h=${rv.height})")
        }
    }

    /** The chat list is the tallest vertically-scrollable RecyclerView in the window. */
    private fun findChatList(root: View): RecyclerView? {
        var best: RecyclerView? = null
        root.asGroupOrNull()?.forEachAll {
            if (it is RecyclerView && it.layoutManager?.canScrollVertically() == true) {
                if (best == null || it.height > best!!.height) best = it
            }
        }
        return best
    }

    private fun install(rv: RecyclerView) {
        // An OnItemTouchListener observing via onInterceptTouchEvent sees EVERY event (incl. MOVE at
        // the bottom where the RecyclerView itself never takes over the gesture). We always return
        // false so we never steal the touch — the list keeps scrolling/overscrolling as usual.
        rv.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            var baselineY = Float.NaN
            var fired = false

            override fun onInterceptTouchEvent(view: RecyclerView, ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        baselineY = Float.NaN
                        fired = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // canScrollVertically(1) == false ⇒ already at the bottom; any further
                        // upward drag is overscroll. Capture a baseline the moment we hit the bottom
                        // so we measure only the overscroll portion, not the whole scroll gesture.
                        if (view.canScrollVertically(1)) {
                            baselineY = Float.NaN
                        } else {
                            if (baselineY.isNaN()) baselineY = ev.y
                            if (!fired && baselineY - ev.y > threshold) {
                                fired = true
                                // 全员禁言: don't let a muted non-admin pull up the keyboard.
                                if (Settings.inlineChatInput.value && InlineInput.isReady &&
                                    !momoi.mod.qqpro.hook.style.isWholeMutedForSelf()
                                ) {
                                    Utils.log("ChatPullUpKeyboard: pull-up over threshold -> open keyboard")
                                    InlineInput.openKeyboard()
                                }
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        baselineY = Float.NaN
                        fired = false
                    }
                }
                return false
            }

            override fun onTouchEvent(view: RecyclerView, ev: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })
    }
}
