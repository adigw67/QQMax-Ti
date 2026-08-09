package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.tencent.qqnt.watch.ui.componet.AbsItem
import com.tencent.qqnt.watch.ui.componet.select.SelectDialogFragment
import com.tencent.qqnt.watch.troop.ui.member.ui.operate.KickMemberItem
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * Group member-list long-press popup: swap the kick ("移出本群") row's icon from the native
 * [R.drawable.icon_delete_group_member] to a Material `person_remove` glyph.
 *
 * The popup is a [SelectDialogFragment] built from a `List<AbsItem>` (field `b`); its `onCreateView`
 * inflates each item's row (via the FINAL `AbsItem.createView`, which we therefore can NOT override —
 * doing so throws LinkageError) into the `setting_container` LinearLayout, one child per item in
 * order. So after the view is built we walk those rows and, for any whose backing item is a
 * [KickMemberItem], replace that row's `R.id.icon` drawable with a themed [MaterialSymbol]. Other
 * SelectDialogFragment popups (which carry no KickMemberItem) are left untouched. The row layout
 * applies no tint, so the symbol's own colour shows; red signals the destructive kick action.
 *
 * Forwarding constructor only (no field initialisers — @Mixin constructor-hook rule). Field `b` is
 * the public item list on SelectDialogFragment.
 */
@Mixin
class KickMemberIconFix(
    item: List<AbsItem>,
    onDismiss: Function0<Any?>?,
    pageId: String?,
    flags: Int,
) : SelectDialogFragment(item, onDismiss, pageId, flags) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        runCatching {
            val items = b ?: return
            if (items.none { it is KickMemberItem }) return
            val ctx = view.context
            val contId = ctx.resources.getIdentifier("setting_container", "id", ctx.packageName)
            val iconId = ctx.resources.getIdentifier("icon", "id", ctx.packageName)
            val container = (if (contId != 0) view.findViewById<ViewGroup>(contId) else null) ?: return
            for (i in 0 until minOf(items.size, container.childCount)) {
                if (items[i] !is KickMemberItem) continue
                val icon = container.getChildAt(i)?.findViewById<ImageView>(iconId) ?: continue
                icon.setImageDrawable(MaterialSymbol(MaterialSymbols.person_remove, 0xFFE5484D.toInt()))
                Utils.log("KickMemberIconFix: kick icon -> person_remove (row $i)")
            }
        }.onFailure { Utils.log("KickMemberIconFix: swap failed: $it") }
    }
}
