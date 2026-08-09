package momoi.mod.qqpro.hook

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import com.tencent.watch.aio_impl.ui.widget.AIOItemSendView
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.material.M3ProgressDrawable

/**
 * Materialize the per-message **sending** spinner (opt-in via [Settings.materializeChat]).
 *
 * Outgoing messages show [AIOItemSendView] (an ImageView) while sending: its `a()` sets the native
 * APNG (`R.drawable.common_loading6`) and starts it. This does NOT go through the already-hooked
 * `LoadingUtil.b`, so it needs its own hook. We @Mixin `a()` to swap in a self-animating
 * [M3ProgressDrawable] (the same MD3 arc the refresh/loading spinners use, see [ChatRefreshSpinnerM3]).
 *
 * Only the *loading* state is changed; the fail state (status == 0, red icon) is left native, since
 * `b()` only calls `a()` for the sending state. When the option is off, the original `a()` runs.
 */
@Mixin
class AIOItemSendViewM3(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
    AIOItemSendView(context, attrs, defStyleAttr, defStyleRes) {
    override fun a() {
        if (!Settings.materializeChat.value) { super.a(); return }
        // Default color is M3.primary (the live theme accent); self-animates while visible.
        val d = M3ProgressDrawable().apply { indeterminate = true }
        setImageDrawable(d)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        visibility = View.VISIBLE
    }
}
