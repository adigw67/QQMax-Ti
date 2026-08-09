package momoi.mod.qqpro.lib

import android.widget.SeekBar

/**
 * Non-inline so the anonymous [SeekBar.OnSeekBarChangeListener] is generated inside this lib
 * class rather than at the call site. ApkMixin copies @Mixin method bodies into the target
 * class (a different package), which makes call-site-generated anonymous classes inaccessible.
 */
fun <T : SeekBar> T.onProgressChanged(onChange: (progress: Int, fromUser: Boolean) -> Unit) = apply {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = onChange(p, fromUser)
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    })
}

fun <T : SeekBar> T.progressMax(value: Int) = apply { max = value }
