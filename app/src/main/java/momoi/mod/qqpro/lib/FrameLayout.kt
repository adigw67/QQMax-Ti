package momoi.mod.qqpro.lib

import android.view.View
import android.widget.FrameLayout

class FrameScope(group: FrameLayout) : GroupScope(group) {
    fun <T : View> T.layoutGravity(value: Int) = apply {
        (layoutParams as? FrameLayout.LayoutParams)?.gravity = value
    }
}