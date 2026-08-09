package momoi.mod.qqpro.hook

import com.tencent.qqnt.kernel.api.impl.GroupService
import com.tencent.qqnt.kernel.nativeinterface.GroupMemberShutUpInfo
import com.tencent.qqnt.kernel.nativeinterface.IKernelGroupService
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.watch.aio_impl.coreImpl.helper.GroupAIOHelper
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi

/**
 * Whole-group mute (全员禁言) — the ONE group-admin mute the watch product is authorised for. Per-member
 * 禁言 (`setMemberShutUp`) is server-blocked (`-10122 "Product does not have permission"`), so it is
 * intentionally not offered. See GROUP_ADMIN_AND_ONLINE_STATUS_PLAN.md for the full feasibility notes.
 *
 * READ: state comes from the same per-group [com.tencent.qqnt.kernel.nativeinterface.GroupDetailInfo]
 * cache the input-bar hint uses — `GroupAIOHelper.b[peerUid].shutUpAllTimestamp` (0 = not muted),
 * populated async on chat entry.
 *
 * WRITE: [IKernelGroupService.setGroupShutUp] — NOT re-exported on the wrapper `IGroupService`, so we
 * reach it by casting the wrapper to its impl [GroupService] and pulling the native kernel service off
 * `BaseService` (Kotlin `.service`, nullable). This exact call was device-verified to return `code=0`.
 */
object GroupMute {
    private fun nativeService(): IKernelGroupService? = runCatching {
        (KernelServiceUtil.b() as? GroupService)?.service
    }.getOrNull()

    /** Per-group detail cache entry (populated async on chat entry); null until it arrives. */
    private fun detail(peerUid: String) = runCatching { GroupAIOHelper.b[peerUid] }.getOrNull()

    /** Numeric group code for the native call: prefer the cached detail, fall back to the uid. */
    fun groupCode(peerUid: String): Long =
        detail(peerUid)?.groupCode?.takeIf { it != 0L } ?: peerUid.toLongOrNull() ?: 0L

    /** True when this group is currently under 全员禁言 (shutUpAllTimestamp != 0). */
    fun isMuted(peerUid: String): Boolean = (detail(peerUid)?.shutUpAllTimestamp ?: 0) != 0

    /**
     * Toggle whole-group mute. [onResult] fires on the UI thread with true on server accept
     * (`code == 0`). On success we also poke the local cache's `shutUpAllTimestamp` so the input-bar
     * hint and a re-opened settings page reflect the new state immediately (the kernel also pushes an
     * update of its own shortly after).
     */
    fun setMuted(peerUid: String, mute: Boolean, onResult: (Boolean) -> Unit) {
        val svc = nativeService()
        val code = groupCode(peerUid)
        if (svc == null || code == 0L) {
            Utils.log("GroupMute: setMuted skipped (svc=${svc != null} code=$code)")
            runOnUi { onResult(false) }
            return
        }
        runCatching {
            svc.setGroupShutUp(code, mute) { c, m ->
                Utils.log("GroupMute: setGroupShutUp code=$c msg=$m gc=$code mute=$mute")
                val ok = c == 0
                if (ok) runCatching {
                    detail(peerUid)?.shutUpAllTimestamp =
                        if (mute) (System.currentTimeMillis() / 1000).toInt() else 0
                }
                runOnUi { onResult(ok) }
            }
        }.onFailure {
            Utils.log("GroupMute: setGroupShutUp threw: $it")
            runOnUi { onResult(false) }
        }
    }

    /**
     * 禁言特定成员（OIDB 0x1253）。注意：手表产品可能被服务端拒绝
     * （-10122 "Product does not have permission"）——结果原样经 [onResult] 回报，由 UI 提示。
     */
    fun setMemberMuted(
        groupPeerUid: String,
        memberUid: String,
        seconds: Int,
        onResult: (Boolean, String) -> Unit,
    ) {
        val svc = nativeService()
        val code = groupCode(groupPeerUid)
        if (svc == null || code == 0L) {
            Utils.log("GroupMute: setMemberMuted skipped (svc=${svc != null} code=$code)")
            onResult(false, "服务不可用")
            return
        }
        runCatching {
            svc.setMemberShutUp(
                code,
                arrayListOf(
                    GroupMemberShutUpInfo().apply {
                        uid = memberUid
                        timeStamp = seconds
                    }
                ),
            ) { c, m ->
                Utils.log("GroupMute: setMemberShutUp code=$c msg=$m gc=$code uid=$memberUid sec=$seconds")
                onResult(c == 0, if (c == 0) "" else "$c $m")
            }
        }.onFailure {
            Utils.log("GroupMute: setMemberShutUp threw: $it")
            onResult(false, it.javaClass.simpleName)
        }
    }
}
