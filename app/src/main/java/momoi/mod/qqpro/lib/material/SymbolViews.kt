package momoi.mod.qqpro.lib.material

import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import momoi.mod.qqpro.lib.dp

/**
 * Convenience builders for using [MaterialSymbol] icons in place of emoji / text glyphs.
 */

/** An [ImageView] showing a Material Symbol [path], tinted, at [sizeDp]² (0 = let layout size it). */
fun symbolImage(ctx: Context, path: String, tint: Int = M3.onSurface, sizeDp: Int = 20): ImageView =
    ImageView(ctx).apply {
        setImageDrawable(MaterialSymbol(path, tint))
        if (sizeDp > 0) layoutParams = android.view.ViewGroup.LayoutParams(sizeDp.dp, sizeDp.dp)
    }

/** Put a Material Symbol [path] as this TextView's leading (start) icon, sized to [sizeDp], with [gap] padding. */
fun <T : TextView> T.leadingSymbol(path: String, tint: Int = M3.onSurface, sizeDp: Int = 18, gap: Int = 6): T = apply {
    val d = MaterialSymbol(path, tint).apply { setBounds(0, 0, sizeDp.dp, sizeDp.dp) }
    setCompoundDrawables(d, null, null, null)
    compoundDrawablePadding = gap.dp
}
