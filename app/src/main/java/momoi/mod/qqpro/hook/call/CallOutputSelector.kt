package momoi.mod.qqpro.hook.call

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * The audio-output selector button on the NATIVE call screen (the M3 call UI has its own selector). A
 * plain object, not a @Mixin body, so its view code + click lambda are safe on this ROM.
 *
 * It is a thin view over [CallAudio]: the button tap calls [CallAudio.cycle], and the icon is driven by
 * [CallAudio.setRouteListener] so it always reflects the ACTUALLY applied route (incl. auto-default to
 * Bluetooth and mid-call headset hotplug). No route state is tracked here.
 */
object CallOutputSelector {

    private const val TAG = "qqpro_call_output_btn"

    fun attach(activity: Activity) {
        val root = content(activity) ?: return
        if (root.findViewWithTag<View>(TAG) != null) {
            // Re-bind the icon updater to the existing button (activity may have re-resumed).
            bindListener(activity)
            return
        }
        runCatching {
            val dm = activity.resources.displayMetrics
            val minDim = minOf(dm.widthPixels, dm.heightPixels)
            val sizePx = (minDim * 0.16f).toInt()
            val iconPx = (sizePx * 0.5f).toInt()
            val pad = (sizePx - iconPx) / 2
            val btn = ImageView(activity).apply {
                tag = TAG
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(M3.surfaceContainerHigh)
                }
                setOnClickListener { CallAudio.cycle(activity) }
                // Long-press opens the call-volume selector (the watch has no volume rocker in-call).
                setOnLongClickListener { CallVolume.toggle(activity); true }
            }
            val lp = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            lp.topMargin = (minDim * 0.06f).toInt()
            root.addView(btn, lp)
            bindListener(activity)
            Utils.log("CallOutputSelector: button attached")
        }.onFailure { Utils.log("CallOutputSelector: attach failed: $it") }
    }

    private fun bindListener(activity: Activity) {
        CallAudio.setRouteListener { route ->
            (content(activity)?.findViewWithTag<ImageView>(TAG))
                ?.setImageDrawable(MaterialSymbol(icon(route), M3.onSurface))
        }
    }

    private fun content(activity: Activity): ViewGroup? =
        activity.findViewById<View>(android.R.id.content) as? ViewGroup

    private fun icon(route: CallAudio.Route) = when (route) {
        CallAudio.Route.BLUETOOTH -> MaterialSymbols.bluetooth
        CallAudio.Route.SPEAKER -> MaterialSymbols.volume_up
        CallAudio.Route.EARPIECE -> MaterialSymbols.phone_in_talk
    }
}
