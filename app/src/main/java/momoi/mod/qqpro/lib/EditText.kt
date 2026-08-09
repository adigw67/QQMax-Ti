package momoi.mod.qqpro.lib

import android.widget.EditText

fun <T : EditText> T.hint(value: String) = apply {
    hint = value
}