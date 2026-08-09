package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.tencent.qqnt.watch.ui.componet.tips.LoadingFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.forEachAll
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3CircularProgress
import momoi.mod.qqpro.util.Utils

/**
 * Materialize the app-wide native loading screen ([LoadingFragment], shown via NavController by
 * TipsUtils for the group member list, etc.). The native screen draws a colorful animated bitmap into
 * its `loading_icon` ImageView; replace that with our themed [M3CircularProgress] spinner so every
 * "loading…" reads as one MD3 system. Keeping the spinner under the SAME view id means the "tips"
 * text constrained below the icon stays positioned.
 */
@Mixin
class LoadingFragmentMaterial : LoadingFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        runCatching {
            val ctx = view.context
            view.setBackgroundColor(M3.surface)
            val iconId = ctx.resources.getIdentifier("loading_icon", "id", ctx.packageName)
            // Replace the native animated bitmap spinner with our themed M3 spinner (kept under the same
            // id so the tips text constrained below it stays positioned).
            val icon = (if (iconId != 0) view.findViewById<View>(iconId) else null) as? ImageView
            if (icon != null) {
                (icon.parent as? ViewGroup)?.let { parent ->
                    val lp = icon.layoutParams
                    val idx = parent.indexOfChild(icon)
                    parent.removeView(icon)
                    parent.addView(M3CircularProgress(ctx).apply { id = iconId }, idx, lp)
                }
            }
            // Theme the rest: the loading screen has a full-screen background ImageView that draws dark
            // OVER the fragment surface (so view.setBackgroundColor alone isn't enough) — clear any
            // leftover ImageView to the surface, and recolor the tips text to onSurface.
            (view as? ViewGroup)?.forEachAll { child ->
                when (child) {
                    is ImageView -> { child.setImageDrawable(null); child.setBackgroundColor(M3.surface) }
                    is TextView -> child.setTextColor(M3.onSurface)
                }
            }
            Utils.log("LoadingFragmentMaterial: themed loading screen (surface + spinner + text)")
        }.onFailure { Utils.log("LoadingFragmentMaterial: $it") }
    }
}
