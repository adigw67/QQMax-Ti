package momoi.mod.qqpro.lib

import android.widget.Switch

fun <T : Switch> T.doAfterSwitch(onSwitch :(Boolean)->Unit) = apply {
    setOnCheckedChangeListener { buttonView, isChecked ->
        onSwitch(isChecked)
    }
}
fun <T : Switch> T.checked(checked : Boolean) = apply {
    isChecked = checked
}
