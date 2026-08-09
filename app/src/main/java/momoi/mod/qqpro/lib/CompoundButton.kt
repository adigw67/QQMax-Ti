package momoi.mod.qqpro.lib

import android.widget.CompoundButton

/**
 * Non-inline wrapper so the OnCheckedChangeListener anonymous class lives in this lib package
 * rather than at the @Mixin call site. Works for any CompoundButton (e.g. MaterialSwitch).
 */
fun <T : CompoundButton> T.onCheckedChange(onChange: (Boolean) -> Unit) = apply {
    setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
}
