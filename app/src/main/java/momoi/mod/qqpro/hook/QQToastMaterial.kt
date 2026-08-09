package momoi.mod.qqpro.hook
import momoi.mod.qqpro.lib.setElevationCompat

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.tencent.mobileqq.widget.QQToast
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

/**
 * Materializes the in-app toast (`QQToast`) when 界面风格 / M3 settings is enabled.
 *
 * We keep QQ's own reliable toast machinery (`ProtectedToast`, gravity/offset handling, the
 * cancel-on-touch plumbing) intact and only **restyle the inflated view in place** — swapping the
 * native pill background for an M3 rounded surface, recoloring the message to `onSurface`, and
 * tinting the semantic feedback icon to a neutral M3 token so it stays visible on both light and
 * dark surfaces. This follows the project's "restyle in place, never rebuild the tree" rule.
 *
 * [a] is the single builder both `l()` and `m()` funnel through, so hooking it covers every toast.
 */
@Mixin
class QQToastMaterial(ctx: Context) : QQToast(ctx) {
    override fun a(p0: Int): Toast {
        val toast = super.a(p0)
        if (Settings.useM3Settings.value) {
            runCatching { materializeQQToast(toast.view) }
                .onFailure { Utils.log("QQToastMaterial: failed to restyle toast: $it") }
        }
        return toast
    }
}

/** Retheme the native `qq_toast_main_layout` view tree to the M3 palette. */
fun materializeQQToast(root: View?) {
    root ?: return
    val ctx = root.context
    fun id(name: String) = ctx.resources.getIdentifier(name, "id", ctx.packageName)

    // The M3 pill lives on the dedicated background view; make the root transparent so it shows.
    val bg = root.findViewById<View>(id("toast_background"))
    if (bg != null && bg !== root) root.setBackgroundColor(0)
    (bg ?: root).apply {
        if (this is ImageView) setImageDrawable(null) // drop the native nine-patch, if any
        background = M3.rounded(M3.surfaceContainerHigh, M3.radiusPill)
        setElevationCompat(6.dp.toFloat())
    }

    root.findViewById<TextView>(id("toast_msg"))?.setTextColor(M3.onSurface)
    root.findViewById<ImageView>(id("toast_icon"))?.setColorFilter(M3.onSurfaceVariant)
}
