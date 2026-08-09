package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.View
import com.tencent.qqnt.watch.fs.GroupMemberSelectFragment
import com.tencent.qqnt.watch.fs.rv.FriendSelectItem
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.lib.ListSearchBar
import momoi.mod.qqpro.util.Utils

/**
 * 群成员选择器搜索栏: the 从群聊中选择 flow (friend selector → 从群聊中选择 → pick a group →) lands on
 * [GroupMemberSelectFragment], a DIFFERENT fragment than [FriendSelectFragment] /
 * [SelectFromGroupFragment]. It shares the same `fragment_friend_select` layout (RecyclerView id
 * `friend_list`) and the same [com.tencent.qqnt.watch.fs.rv.FriendSelectAdapter] of [FriendSelectItem]
 * as the plain friend selector, so we filter it identically — by [FriendSelectItem.c] (nickName).
 *
 * This was the missing piece: only [FriendSelectSearch] and [SelectFromGroupSearch] existed, so the
 * "select members from a group" screen had no search bar. See [ListSearchBar] for the shared install.
 */
@Mixin
class GroupMemberSelectSearch : GroupMemberSelectFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Utils.log("GroupMemberSelectSearch: onViewCreated")
        runCatching {
            ListSearchBar.install(
                view,
                hint = "搜索成员",
                nameOf = { (it as? FriendSelectItem)?.c.orEmpty() },
            )
            momoi.mod.qqpro.lib.styleConfirmButton(view)
        }.onFailure { Utils.log("GroupMemberSelectSearch: install failed: $it") }
    }
}
