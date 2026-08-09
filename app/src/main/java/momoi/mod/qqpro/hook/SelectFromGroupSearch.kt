package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.View
import com.tencent.qqnt.kernel.nativeinterface.*
import com.tencent.qqnt.watch.fs.SelectFromGroupFragment
import com.tencent.qqnt.watch.fs.rv.GroupSelectItem
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.lib.ListSearchBar
import momoi.mod.qqpro.util.Utils

/**
 * 选择群聊搜索栏: adds the same reusable search bar to QQ's "从群聊中选择" screen
 * ([SelectFromGroupFragment]) — reached from the friend selector's 从群聊中选择 header row (create-group
 * flow). Its list is a [androidx.recyclerview.widget.ListAdapter] of [GroupSelectItem]; we filter by
 * the group title (stub jar exposes raw fields: a=groupCode:long, b=title:String).
 *
 * The fragment implements [IKernelGroupListener]. The stub jar's Kotlin metadata only declares the
 * one method it actually uses ([onGroupListUpdate], left untouched here); Kotlin therefore treats the
 * other 23 listener methods as unimplemented abstract members, so we must override them. All 23 are
 * empty no-ops in the real fragment, so re-injecting empty bodies via ApkMixin changes nothing.
 *
 * See [FriendSelectSearch] for the friend selector itself; both use [ListSearchBar].
 */
@Mixin
class SelectFromGroupSearch : SelectFromGroupFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Utils.log("SelectFromGroupSearch: onViewCreated (NEW build)")
        runCatching {
            ListSearchBar.install(
                view,
                hint = "搜索群聊",
                nameOf = { (it as? GroupSelectItem)?.b.orEmpty() },
            )
            momoi.mod.qqpro.lib.styleConfirmButton(view)
        }.onFailure { Utils.log("SelectFromGroupSearch: install failed: $it") }
    }

    // --- IKernelGroupListener no-ops (see class doc). onGroupListUpdate is intentionally NOT
    //     overridden so the fragment's real implementation keeps running. ---
    override fun onGetGroupBulletinListResult(p0: Long, p1: String?, p2: GroupBulletinListResult?) {}
    override fun onGroupAdd(p0: Long) {}
    override fun onGroupAllInfoChange(p0: GroupAllInfo?) {}
    override fun onGroupArkInviteStateResult(p0: Long, p1: GroupArkInviteStateInfo?) {}
    override fun onGroupBulletinChange(p0: Long, p1: GroupBulletin?) {}
    override fun onGroupBulletinRemindNotify(p0: Long, p1: RemindGroupBulletinMsg?) {}
    override fun onGroupBulletinRichMediaDownloadComplete(p0: BulletinFeedsDownloadInfo?) {}
    override fun onGroupBulletinRichMediaProgressUpdate(p0: BulletinFeedsDownloadInfo?) {}
    override fun onGroupConfMemberChange(p0: Long, p1: ArrayList<String>?) {}
    override fun onGroupDetailInfoChange(p0: GroupDetailInfo?) {}
    override fun onGroupExtListUpdate(p0: GroupExtListUpdateType?, p1: ArrayList<GroupExtInfo>?) {}
    override fun onGroupFirstBulletinNotify(p0: FirstGroupBulletinInfo?) {}
    override fun onGroupNotifiesUnreadCountUpdated(p0: Boolean, p1: Long, p2: Int) {}
    override fun onGroupNotifiesUpdated(p0: Boolean, p1: ArrayList<GroupNotifyMsg>?) {}
    override fun onGroupSingleScreenNotifies(p0: Boolean, p1: Long, p2: ArrayList<GroupNotifyMsg>?) {}
    override fun onGroupStatisticInfoChange(p0: Long, p1: GroupStatisticInfo?) {}
    override fun onGroupsMsgMaskResult(p0: ArrayList<GroupMsgMaskInfo>?) {}
    override fun onJoinGroupNoVerifyFlag(p0: Long, p1: Boolean, p2: Boolean) {}
    override fun onJoinGroupNotify(p0: JoinGroupNotifyMsg?) {}
    override fun onMemberInfoChange(p0: Long, p1: DataSource?, p2: HashMap<String, MemberInfo>?) {}
    override fun onMemberListChange(p0: GroupMemberListChangeInfo?) {}
    override fun onSearchMemberChange(p0: String?, p1: String?, p2: ArrayList<GroupMemberInfoListId>?, p3: HashMap<String, MemberInfo>?) {}
    override fun onShutUpMemberListChanged(p0: Long, p1: ArrayList<MemberInfo>?) {}
}
