package momoi.mod.qqpro

import com.tencent.qqnt.kernel.nativeinterface.GroupMemberListResult
import com.tencent.qqnt.msg.KernelServiceUtil

object QQNT {
    val groupService get() = KernelServiceUtil.b()!!

    object Group {
        inline fun getMemberList(
            groupId: Long,
            force: Boolean = true,
            crossinline callback: (GroupMemberListResult) -> Unit
        ) {
            groupService.getAllMemberList(groupId, force) { _, _, result ->
                callback(result)
            }
        }
    }
}