package momoi.mod.qqpro.hook.contact

import com.tencent.qqnt.watch.contact.ui.item.GroupItem
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings

/**
 * Remove the trailing icon (icon_shuoshuo) that every group row shows at its far right edge.
 *
 * On a small/round watch screen that 28dp icon wastes name width, looks unbalanced against friend
 * rows (which have none), and sits where the screen corner gets clipped. With the "好友"/"群聊"
 * section headers ([ContactListAdapterHook]) telling groups apart, the per-row icon is redundant.
 * See [Settings.contactSections].
 */
@Mixin
class GroupItemHook(groupId: Long, groupName: String) : GroupItem(groupId, groupName) {
    // a() = the trailing ext_icon resource; -1 hides it.
    override fun a(): Int = if (Settings.contactSections.value) -1 else super.a()
}
