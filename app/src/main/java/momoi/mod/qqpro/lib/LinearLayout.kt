package momoi.mod.qqpro.lib

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

class LinearScope(group: LinearLayout) : GroupScope(group) {
    fun <T : View> T.weight(value: Float) = apply {
        (layoutParams as? LinearLayout.LayoutParams)?.weight = value
    }
}

fun <T : LinearLayout> T.vertical(): T = apply {
    orientation = LinearLayout.VERTICAL
}
fun <T : LinearLayout> T.gravity(value: Int) = apply {
    gravity = value
}
fun LinearLayout.content(block: LinearScope.()->Unit) = apply {
    LinearScope(this).apply(block)
}