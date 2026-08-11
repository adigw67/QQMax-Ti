package momoi.mod.qqpro.hook.aio_cell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.parseHexColor

/**
 * Replace the native chat-bubble background (a stretched nine-patch set via
 * `setBackgroundResource(R.drawable.bubble_*_bg_new)` on the long-click wrapper) with a
 * rounded rectangle whose corner radius is configurable in settings.
 *
 * The fill color and the nine-patch's content padding are captured once per
 * [AIOCellGroupWidget.getLocationType] (guest/host) from the original bubble. The padding is
 * re-applied (plus a little extra so text clears the rounded corners) because a plain
 * GradientDrawable carries none, which would otherwise let the text spill past the corners.
 * Applied after every bind because the native cell re-sets the nine-patch on rebind.
 */
object BubbleCorner {
    private class Style(val color: Int, val pad: Rect)

    // locationType -> sampled bubble style
    private val styles = HashMap<Int, Style>()

    /**
     * The effective bubble fill color for a side: the user's per-side override, else the Material
     * token (loc==0 → other → surfaceContainerLow; else → self → tonalSolid——primaryContainer 的
     * 不透明版本，叠加在 surface 上）。MD3 对话气泡用 tonal 容器色：对方 surfaceContainerLow、
     * 自己 primaryContainer；自己一侧必须不透明，否则聊天背景图上气泡透底发暗。Shared so the
     * message text color can auto-contrast against it (see [AIOCell.applyMsgTextStyle]).
     */
    fun resolvedBubbleColor(loc: Int): Int {
        val override = if (loc == 0) Settings.bubbleColorOther else Settings.bubbleColorSelf
        return parseHexColor(override.value) ?: (if (loc == 0) M3.surfaceContainerLow else M3.tonalSolid)
    }

    fun apply(widget: AIOCellGroupWidget) {
        val wrapper = runCatching { widget.getLongClickWrapper<View>() }.getOrNull()
        Utils.log("BubbleCorner: wrapper=${wrapper?.javaClass?.simpleName} bg=${wrapper?.background?.javaClass?.simpleName} loc=${runCatching { widget.locationType }.getOrNull()}")
        if (wrapper == null) return
        val bg = wrapper.background ?: return
        // Already replaced by us (native didn't re-set the nine-patch this bind) — leave it.
        if (bg is GradientDrawable) return
        val loc = widget.locationType
        val style = styles.getOrPut(loc) {
            val pad = Rect()
            bg.getPadding(pad)
            Style(sampleColor(bg), pad)
        }
        // Per-side color override from settings; blank/invalid falls back to the Material token the
        // 气泡颜色 settings preview shows (other→surfaceContainer, self→primary) so chat matches it.
        val color = resolvedBubbleColor(loc)
        val r = Settings.bubbleCornerRadius.value.dpf
        // MD3 聊天气泡：远离发送方的一侧用大圆角，发送侧用小圆角。
        // other/guest（对方，气泡在左）：大圆角在右；self/host（自己，气泡在右）：大圆角在左。
        val small = (r * 0.4f).coerceAtLeast(0f)
        wrapper.background = if (loc == 0) {
            roundCornerDrawable(color, small, r, r, small)
        } else {
            roundCornerDrawable(color, r, small, small, r)
        }
        // Keep the original text inset and add ~half the radius horizontally so glyphs near
        // the corners aren't clipped by the rounded edge.
        val extra = (r * 0.5f).toInt()
        wrapper.setPadding(
            style.pad.left + extra, style.pad.top,
            style.pad.right + extra, style.pad.bottom
        )
    }

    /**
     * Force the bubble fill to the color for [loc], even if our rounded drawable is already applied
     * (normal [apply] short-circuits then). Keeps the current padding. Used by the screenshot renderer
     * to re-theme a self bubble as the "other" side after [AIOCellGroupWidget.setLocation].
     */
    fun forceColor(widget: AIOCellGroupWidget, loc: Int) {
        val wrapper = runCatching { widget.getLongClickWrapper<View>() }.getOrNull() ?: return
        val r = Settings.bubbleCornerRadius.value.dpf
        val pl = wrapper.paddingLeft; val pt = wrapper.paddingTop
        val pr = wrapper.paddingRight; val pb = wrapper.paddingBottom
        val color = resolvedBubbleColor(loc)
        val small = (r * 0.4f).coerceAtLeast(0f)
        wrapper.background = if (loc == 0) {
            roundCornerDrawable(color, small, r, r, small)
        } else {
            roundCornerDrawable(color, r, small, small, r)
        }
        wrapper.setPadding(pl, pt, pr, pb)
    }

    /** Sample the fill color by rendering the drawable to a small bitmap and reading its center. */
    private fun sampleColor(d: Drawable): Int {
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 40
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 88
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val old = Rect(d.bounds)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        d.bounds = old
        val color = runCatching { bmp.getPixel(w / 2, h / 2) }.getOrDefault(M3.surfaceContainer)
        bmp.recycle()
        return color
    }
}
