package momoi.mod.qqpro.hook.qzone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.watch.qzone_impl.alert.ui.QZoneUnreadFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.forEachAll
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.onChildAttached
import momoi.mod.qqpro.util.Utils

/**
 * Re-theme the QZone interaction-notifications page ([QZoneUnreadFragment], the 通知 list of likes /
 * comments / @mentions on your feeds) to the M3 color scheme when [Settings.materializeQzone] is on.
 *
 * Light touch (not a from-scratch rebuild): recolor the page + row text to the theme tokens, give
 * rows an M3 press ripple, and swap the native 赞 indicator for a Material icon. The native list /
 * adapter / data path are untouched. Recoloring is applied per-row on attach (which fires after each
 * bind), so recycled rows stay themed.
 */
@Mixin
class QZoneNotifyMaterial : QZoneUnreadFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        if (Settings.materializeQzone.value)
            runCatching { styleQzoneNotify(root) }.onFailure { Utils.log("QZoneNotifyMaterial: $it") }
        return root
    }
}

/** Map a row's labelled view id to its M3 text role. */
private fun roleColor(id: String?): Int? = when (id) {
    "nickname", "content", "title" -> M3.onSurface
    "summary" -> M3.onSurfaceVariant
    "time" -> M3.onSurfaceTip
    else -> null
}

private fun idName(v: View): String? = runCatching {
    if (v.id == View.NO_ID) null else v.resources.getResourceEntryName(v.id)
}.getOrNull()

/** setTextColor on a TextView or a custom widget (e.g. SingleLineTextView) via reflection. */
private fun setTextColorAny(v: View, color: Int) {
    if (v is TextView) { v.setTextColor(color); return }
    runCatching { v.javaClass.getMethod("setTextColor", Int::class.javaPrimitiveType).invoke(v, color) }
}

private fun recolorTexts(v: View) {
    (v as? ViewGroup)?.forEachAll { c -> roleColor(idName(c))?.let { setTextColorAny(c, it) } }
    roleColor(idName(v))?.let { setTextColorAny(v, it) }
}

fun styleQzoneNotify(root: View) {
    root.setBackgroundColor(M3.surface)
    recolorTexts(root) // static title / header text outside the list
    val rv = (root as? ViewGroup)?.findAll { it is RecyclerView } as? RecyclerView ?: return
    rv.setBackgroundColor(M3.surface)
    rv.onChildAttached { row -> themeRow(row) }
    Utils.log("QZoneNotifyMaterial: notify list themed")
}

private fun themeRow(row: View) {
    // Flat row that follows the page surface, with an M3 press ripple for touch feedback.
    row.background = M3.ripple(null)
    recolorTexts(row)
    // Swap the native 赞 (like) indicator for a Material icon tinted to the accent.
    val favId = row.resources.getIdentifier("fav_icon", "id", row.context.packageName)
    if (favId != 0) {
        (row.findViewById<View>(favId) as? ImageView)?.let { iv ->
            if (iv.visibility == View.VISIBLE) {
                iv.setImageDrawable(MaterialSymbol(MaterialSymbols.thumb_up, M3.primary))
            }
        }
    }
}
