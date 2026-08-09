package momoi.mod.qqpro.lib

import android.widget.TextView

fun <T : TextView> T.textSize(size: Float) = apply {
    textSize = size
}

fun <T : TextView> T.textColor(color: Int) = apply {
    setTextColor(color)
}
fun <T : TextView> T.textColor(color: Long) = textColor(color.toInt())
fun <T : TextView> T.gravity(value: Int) = apply {
    gravity = value
}

fun <T : TextView> T.text(text: CharSequence) = apply {
    if (text != this.text) {
        this.text = text
    }
}