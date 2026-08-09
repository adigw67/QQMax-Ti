package momoi.mod.qqpro.hook

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.tencent.watch.qzone_impl.frame.QZoneMineFragment
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * Materializes the per-user QZone page ([QZoneMineFragment], whose view is built programmatically in
 * its `Y`/onCreateView, a [ConstraintLayout] with [back, emptyTips, smartRefreshLayout]):
 *
 *  - **Empty indicator** ("当前没有动态") — natively pins its icon+text to the BOTTOM of a full-screen
 *    layout, so it reads as mis-aligned. We swap it for a centered Material column and re-point the
 *    fragment's `mEmptyTipsLayout` field (smali `j`) at our view so the native show/hide still drives it.
 *  - **No top bar** — the native layout reserves a top strip for the "返回" text button (the list is
 *    constrained below it). We drop that: the list fills the full height and the back button becomes a
 *    floating Material icon button with a tonal circular background, overlaid top-left.
 *
 * Public with no inline/anonymous classes — safe to call from the @Mixin [QZoneMineFragmentHook] body.
 */
object QZoneEmptyState {

    fun materialize(fragment: QZoneMineFragment, root: View) {
        if (root !is ViewGroup) return
        swapEmptyIndicator(fragment, root)
        runCatching { fillListFullHeight(fragment) }.onFailure { Utils.log("QZoneEmptyState: list: $it") }
        runCatching { hideNativeTopBar(root) }.onFailure { Utils.log("QZoneEmptyState: back: $it") }
    }

    private fun field(name: String, fragment: QZoneMineFragment): Any? =
        QZoneMineFragment::class.java.getDeclaredField(name).apply { isAccessible = true }.get(fragment)

    private fun swapEmptyIndicator(fragment: QZoneMineFragment, root: ViewGroup) {
        runCatching {
            val nativeField = QZoneMineFragment::class.java.getDeclaredField("j").apply { isAccessible = true }
            val native = nativeField.get(fragment) as? View ?: return
            val idx = root.indexOfChild(native)
            if (idx < 0) return
            val material = buildEmptyView(root.context).apply {
                visibility = native.visibility
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            root.removeViewAt(idx)
            root.addView(material, idx)
            material.bringToFront()           // sit above the now-full-height list
            // Native code toggles this.mEmptyTipsLayout's visibility; point it at our view.
            nativeField.set(fragment, material)
            Utils.log("QZoneEmptyState: empty indicator materialized (was vis=${native.visibility})")
        }.onFailure { Utils.log("QZoneEmptyState: empty swap failed: $it") }
    }

    private fun buildEmptyView(ctx: Context): View {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        column.addView(
            ImageView(ctx).apply {
                setImageDrawable(MaterialSymbol(MaterialSymbols.chat_bubble, M3.onSurfaceVariant))
            },
            LinearLayout.LayoutParams(48.dp, 48.dp),
        )
        column.addView(
            TextView(ctx).apply {
                text = "当前没有动态"
                setTextColor(M3.onSurfaceVariant)
                textSize = 13f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 10.dp },
        )
        // Full-size frame so the column sits dead-centre (the native version bottom-pinned its content).
        return FrameLayout(ctx).apply {
            addView(
                column,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
                ),
            )
        }
    }

    /** Drop the reserved top strip: constrain the feed list to the top of the parent (full height). */
    private fun fillListFullHeight(fragment: QZoneMineFragment) {
        val list = field("i", fragment) as? View ?: return
        val lp = list.layoutParams as? ConstraintLayout.LayoutParams ?: return
        lp.topToBottom = ConstraintLayout.LayoutParams.UNSET     // was: below the back button
        lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        list.layoutParams = lp
        Utils.log("QZoneEmptyState: list set to full height")
    }

    /**
     * Hide the native "返回" text button: with the list now full-height there's no top strip, and the
     * page is dismissable by the native right-swipe-back gesture, so a visible button isn't needed (a
     * floating one is barely legible top-left on the clipped round screen anyway).
     */
    private fun hideNativeTopBar(root: ViewGroup) {
        val nativeBack = (0 until root.childCount)
            .map { root.getChildAt(it) }
            .firstOrNull { it is TextView && it.text?.toString() == "返回" } as? TextView ?: return
        nativeBack.visibility = View.GONE
        Utils.log("QZoneEmptyState: native top bar hidden (swipe-back only)")
    }
}
