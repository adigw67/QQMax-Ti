package momoi.mod.qqpro.hook.style

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.tencent.watch.aio_impl.coreImpl.vb.InputBarController
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils

/**
 * "滚动时保持输入栏" / always-show: keep the chat input bar in the FLOATING overlay (state 2) at all
 * times instead of ever dropping into the list-footer (sliver, state 1).
 *
 * Why hook [InputBarController.f] (showSliverInput): device logs proved the bar was a SINGLE instance
 * (one InputBarController, one rootContainer) being reparented between the float container and the
 * footer sliver on nearly every layout pass — native `f()` kept yanking it back to the footer. In the
 * footer host our inline pill measures `rootH=0` (it collapses), so each footer flip briefly makes the
 * EditText vanish, and on chat open the bar starts in the footer and only pops to the float overlay —
 * with a visible "move up" animation — on the first scroll (via KeepInputBarOnScroll). Symptoms:
 * doesn't start floating, EditText disappears, per-frame footer/float thrash (log spam).
 *
 * Fix: when always-show is on (and not a muted non-admin, whose footer hint intentionally hides the
 * box), every call that would show the footer instead pops the floating overlay. `showFlowInput`'s own
 * `state != 2` guard makes it a one-shot, so the bar animates in ONCE (on open) and then stays float —
 * no reparent thrash, no first-scroll animation. When the option is off we fall through to native.
 *
 * `f(boolean)` is not `final` in the compile stub, so it's overridable; `g` (state) and `m`
 * (showArrowListener → showFlowInput) are public. The forwarding constructor only passes args to super
 * (no field initializers — @Mixin constructor-hook rule).
 */
@Mixin
class InputBarAlwaysFloat(
    context: Context,
    emotionClickEvent: () -> Unit,
    emotionLongClickEvent: (View) -> Unit,
    faceBubbleMoveEvent: (MotionEvent, Float, Float) -> Unit,
    pttDelegate: (View) -> Unit,
    imeClickEvent: () -> Unit,
) : InputBarController(
    context, emotionClickEvent, emotionLongClickEvent, faceBubbleMoveEvent, pttDelegate, imeClickEvent
) {
    override fun f(immediate: Boolean) {
        if (Settings.keepInputBarOnScroll.value && !isWholeMutedForSelf()) {
            if (g != 2) {
                Utils.log("InputBarAlwaysFloat: redirect showSliver(immediate=$immediate) -> float (state=$g)")
                // m = showArrowListener; onClick runs showFlowInput → state 2. Pass a real, always-built
                // view (the sliver container) so EventCollector inside onClick never sees a null view.
                m.onClick(d())
            }
            return
        }
        super.f(immediate)
    }
}
