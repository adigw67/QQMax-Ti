package momoi.mod.qqpro.api

import com.tencent.qqnt.kernel.nativeinterface.GroupMemberListResult
import com.tencent.qqnt.kernel.nativeinterface.IGroupMemberListCallback
import com.tencent.qqnt.msg.KernelServiceUtil

object GroupAPI {
    inline fun getMemberInfo(
        groupId: Long,
        uid: Long,
        crossinline callback: (GroupMemberListResult) -> Unit
    ) {
        KernelServiceUtil.b()?.getMemberInfoForMqq(
            groupId,
            arrayListOf(uid.toString()),
            false
        ) { _, _, result ->
            callback(result)
        }
    }
}