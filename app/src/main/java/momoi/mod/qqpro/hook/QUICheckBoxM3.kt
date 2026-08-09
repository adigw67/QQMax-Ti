package momoi.mod.qqpro.hook

import android.content.Context
import com.tencent.qqnt.watch.ui.componet.checkbox.QUICheckBox
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.material.M3CheckDrawable

/**
 * App-wide Material checkbox. [QUICheckBox] (extends CheckBox) renders its tick via its BACKGROUND
 * (a StateListDrawable installed in its private `d()`), with the button drawable set to null — so
 * swapping the button drawable left both the native circle AND our square showing. Instead, after the
 * native appearance is (re)applied we replace the background with our stateful [M3CheckDrawable], which
 * fills the view (no clipping) and reads checked/unchecked/disabled from the view's drawable state.
 *
 * `setEnabled` is the only overridable hook the native code routes its `d()` re-style through (the
 * selector adapter calls `setEnabled(!force)` on every bind), so re-applying here keeps every checkbox
 * M3 across binds and the disabled/force-selected state. Gated by [Settings.useM3Settings].
 */
@Mixin
class QUICheckBoxM3(context: Context) : QUICheckBox(context, null) {
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!Settings.useM3Settings.value) return
        runCatching {
            setButtonDrawable(null)
            val d = M3CheckDrawable()
            background = d
            d.state = drawableState
        }
    }
}
