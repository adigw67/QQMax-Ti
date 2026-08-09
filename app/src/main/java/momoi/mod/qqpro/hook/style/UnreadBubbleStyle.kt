package momoi.mod.qqpro.hook.style

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.tencent.aio.api.runtime.AIOContext
import com.tencent.mvi.base.mvi.MviIntent
import com.tencent.mvi.base.mvi.MviUIState
import com.tencent.mvi.mvvm.BaseVB
import com.tencent.watch.aio_impl.reserve1.AIOReserve1VB
import com.tencent.watch.aio_impl.reserve1.unreadbubble.UnreadBubbleVB
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.hook.view.BubbleTextView
import momoi.mod.qqpro.lib.dp

/**
 * Reposition and restyle the native chat unread bubble (top-right small circle) into a
 * large, easy-to-press pill at the bottom-right — see [BubbleTextView].
 */
@Mixin
class UnreadBubbleStyle : AIOReserve1VB() {
    override fun C(hostView: View): MutableList<BaseVB<out MviIntent, out MviUIState, AIOContext>> {
        val tv = BubbleTextView(hostView.context)
        tv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // Bottom-right, flush to the right edge (no right margin) so the pill is cut by the screen side.
            gravity = Gravity.BOTTOM or Gravity.RIGHT
            bottomMargin = 52.dp
        }
        return mutableListOf<BaseVB<out MviIntent, out MviUIState, AIOContext>>(UnreadBubbleVB(tv))
    }
}
