package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.View
import com.tencent.qqnt.watch.fs.FriendSelectFragment
import com.tencent.qqnt.watch.fs.rv.FriendSelectItem
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.lib.ListSearchBar
import momoi.mod.qqpro.util.Utils

/**
 * 联系人选择器搜索栏: adds a reusable search bar to QQ's friend selector
 * ([FriendSelectFragment]) — opened via
 * [com.tencent.qqnt.watch.contact.api.IContactRuntimeService.startFriendSelect], it backs the direct
 * "pick who to send to" flows:
 *   • 转发   ([Forward.forwardToFriends] / [Forward.forwardMsgRecord])
 *   • 分享到 ([ShareIn], incoming system shares)
 *   • 创建群聊 (native CreateGroupFragment, isSupportFromGroup=true)
 *   • 邀请   (native GroupMemberVM → InvitedToGroup)
 *
 * The 从群聊中选择 sub-flow navigates to OTHER fragments that share this layout but are separate
 * classes — [SelectFromGroupSearch] (选择群聊) and [GroupMemberSelectSearch] (群成员) — each hooked
 * on its own. All three reuse [ListSearchBar].
 *
 * The list is a [androidx.recyclerview.widget.ListAdapter] of [FriendSelectItem]; we filter by
 * [FriendSelectItem.nickName]. The header rows the fragment prepends for the group variants
 * (uid "-1" = 从群聊中选择, uid "-2" = 选择群聊) are pinned so search can't hide the entry point.
 * Selection state lives on the items / in the fragment's own selectItemSet, so filtering the
 * displayed list never disturbs what's already checked.
 */
@Mixin
class FriendSelectSearch : FriendSelectFragment() {
    // onViewCreated (not Y/onCreateView): WatchFragment caches its view and skips Y on reuse, so a
    // Y-return wrapper never appears for reused instances (e.g. the 转发 call site). onViewCreated
    // fires every time on the real attached view.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Utils.log("FriendSelectSearch: onViewCreated (NEW build)")
        runCatching {
            // The stub jar exposes raw obfuscated fields (no getters): a=uid, c=nickName.
            ListSearchBar.install(
                view,
                hint = "搜索",
                nameOf = { (it as? FriendSelectItem)?.c.orEmpty() },
                isPinned = { (it as? FriendSelectItem)?.a.let { uid -> uid == "-1" || uid == "-2" } },
            )
            momoi.mod.qqpro.lib.styleConfirmButton(view)
            momoi.mod.qqpro.lib.styleSelectorHeaderIcons(view)
        }.onFailure { Utils.log("FriendSelectSearch: install failed: $it") }
    }
}
