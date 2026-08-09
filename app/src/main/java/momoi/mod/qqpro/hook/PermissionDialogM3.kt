package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.tencent.qqnt.watch.ui.componet.permission.PermissionRequestFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.forEachAll
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

/**
 * The single app-wide permission rationale dialog ([PermissionRequestFragment]) — shown before
 * requesting camera / storage / mic / location / SMS / bluetooth across the whole app. Material-style
 * it in one place (the same way the destructive-confirm dialog is centralized): recolor the surface,
 * tips text, icon and the confirm/cancel buttons to M3, in place so the native button text/visibility
 * and the ActivityResult launcher logic are untouched. Gated by [Settings.useM3Settings] (same switch
 * as the delete/confirm dialog).
 */
@Mixin
class PermissionDialogM3 : PermissionRequestFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Settings.useM3Settings.value) stylePermissionDialog(view)
    }
}

/** Top-level so the @Mixin body stays free of helper closures. */
fun stylePermissionDialog(view: View) {
    runCatching {
        val res = view.resources
        val pkg = view.context.packageName
        fun rid(name: String) = res.getIdentifier(name, "id", pkg)

        view.setBackgroundColor(M3.surface)
        view.findViewById<TextView>(rid("tips"))?.setTextColor(M3.onSurface)
        // The icon is a multicolor asset that can't be tinted to the theme — hide it for a clean
        // M3 surface rather than leaving it recolored/mismatched.
        view.findViewById<View>(rid("icon"))?.visibility = View.GONE

        // Style EVERY button so no state is left un-themed: the positive `confirm` (同意/请求) is the
        // filled accent; every other button — including the permanent-deny variants (拒绝 / 去设置) —
        // falls back to a tonal pill.
        val confirmId = rid("confirm")
        (view as? ViewGroup)?.forEachAll { v ->
            if (v is Button) {
                if (v.id == confirmId) pillButton(v, M3.primary, M3.onPrimary)
                else pillButton(v, M3.surfaceContainerHigh, M3.onSurface)
            }
        }
        Utils.log("PermissionDialogM3: permission dialog materialized")
    }.onFailure { Utils.log("PermissionDialogM3 failed: $it") }
}

/** Recolor a native [Button] into an M3 pill (replace its shaped bg + clear any tint). */
private fun pillButton(b: Button, bg: Int, fg: Int) {
    b.background = M3.rounded(bg, M3.radiusPill)
    b.backgroundTintList = null
    b.setTextColor(fg)
}
