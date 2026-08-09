package momoi.mod.qqpro.hook.style

import androidx.recyclerview.widget.RecyclerView
import com.tencent.watch.aio_impl.coreImpl.intent.AIOMsgListMviIntent
import com.tencent.watch.aio_impl.coreImpl.vb.`WatchAIOListVB$onCreateView$7`
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.util.Utils

/**
 * Optional "keep the input bar visible while scrolling chat history" (Settings.keepInputBarOnScroll).
 *
 * Natively, [WatchAIOListVB.onCreateView]'s scroll listener (`$7`) only shows the input bar while the
 * list is pinned to the bottom (as a list **footer**, state 1) and collapses it to just the up-arrow
 * (state 0) once you scroll up, hiding the inline EditText.
 *
 * The bar's other home is a **floating overlay** (state 2) that's a sibling of the RecyclerView and
 * stays pinned over the chat regardless of scroll (see input-bar-footer-vs-float). When the option is
 * on we just keep the bar in that floating mode at all times: emit the native `ListScrollDistance`
 * intent (drives the unread-bubble / scroll VMs), then pop the float via the up-arrow listener
 * `m.onClick` (runs showFlowInput → state 2, NO keyboard). The `state != 2` guard makes it a one-shot —
 * once floating it stays put, so the list scrolls freely behind a pinned bar. Default off → untouched.
 */
@Mixin
class KeepInputBarOnScroll : `WatchAIOListVB$onCreateView$7`() {
    // Gate the float-pop until the chat's initial open-scroll has settled. When a chat opens at an unread
    // position the framework fires a PROGRAMMATIC scroll-to-unread; popping the float during it reparents
    // the input bar into an overlay that lays out before its view tree is ready and ends up detached &
    // invisible (the "input bar disappeared on open" bug). IDLE means that open-scroll finished; DRAGGING
    // means a genuine user touch — either way it's safe to float. SETTLING is deliberately NOT counted, so
    // the programmatic open-scroll (itself a SETTLING) can't re-enable popping mid-flight.
    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        super.onScrollStateChanged(recyclerView, newState)
        if (!Settings.keepInputBarOnScroll.value) return
        if (newState == RecyclerView.SCROLL_STATE_IDLE || newState == RecyclerView.SCROLL_STATE_DRAGGING) {
            CurrentMsgList.scrollSettledSinceOpen = true
        }
        // Starting-in-float is handled structurally by InputBarAlwaysFloat (redirecting native
        // showSliverInput to the float overlay), not from here — an IDLE-settle pop never fires on a
        // chat that opens statically at the bottom (the list never leaves IDLE, so no callback).
    }

    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
        if (!Settings.keepInputBarOnScroll.value) {
            super.onScrolled(recyclerView, dx, dy)
            return
        }
        runCatching {
            val vb = a  // captured outer WatchAIOListVB; G = focus-bottom handler, J = InputBarController
            vb.L(AIOMsgListMviIntent.ListScrollDistance(dx, dy, vb.G.f))
            val ctrl = vb.J
            // Only float the bar when the list is actually scrolled UP — i.e. it can still scroll
            // down (canScrollVertically(1)) to reach the newest message. At the bottom, where the bar
            // lives as the list footer, leave it in footer mode. Popping the float here — notably on
            // the initial scroll-to-bottom when a chat first opens — abandons the touchable footer bar
            // for a float overlay that lays out off-screen (rootTop ≈ -179, EditText not even in the
            // view tree), leaving the whole input row visible-but-dead until a reply/@/edit re-pops it.
            val scrolledUp = recyclerView.canScrollVertically(1)
            // m = showArrowListener; onClick runs showFlowInput → floating overlay (state 2), pinned over
            // the chat. Guarded so it animates in once and then stays floating while scrolling.
            // 全员禁言: don't pop the floating input bar for a muted non-admin — it would surface the
            // EditText that the footer hint hides. Leave the native collapse-to-arrow behavior instead.
            // scrollSettledSinceOpen: never float during the initial programmatic open-scroll — the
            // overlay would detach and the input bar vanishes (see onScrollStateChanged).
            if (scrolledUp && ctrl.g != 2 && CurrentMsgList.scrollSettledSinceOpen && !isWholeMutedForSelf()) {
                ctrl.m.onClick(recyclerView)
                Utils.log("KeepInputBarOnScroll: float popped (state=${ctrl.g})")
            }
        }.onFailure { Utils.log("KeepInputBarOnScroll: failed: $it") }
    }
}
