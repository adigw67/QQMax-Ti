package momoi.mod.qqpro.hook.call

import android.content.ComponentName
import android.os.IBinder
import com.tencent.activitys.BeInvitedActivity
import com.tencent.activitys.`BeInvitedActivity$mServiceConnection$1`
import com.tencent.qqnt.msg.KernelServiceUtil
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils

/**
 * Restyle the incoming-call answer/reject buttons as M3 (green answer + error reject) after QQ wires them
 * in the service-connection callback. `b` is the outer [BeInvitedActivity]; the constructor param forwards
 * to the anonymous class's super ctor. Compiles thanks to the EnclosingMethod-stripped stub.
 *
 * Also guards a native crash: `super.onServiceConnected` does `Long.parseLong(peerUin)` (field `g`) to load
 * the caller avatar, which throws NumberFormatException when the incoming intent carries no uin (observed on
 * phones). We backfill peerUin from the uid (field `i`) — or "0" as a last resort — before super, so QQ's
 * setup runs instead of crashing the ring screen. Same family as ProfileCard's parseLong("").
 */
@Mixin
class CallIncomingConnHook(p0: BeInvitedActivity) : `BeInvitedActivity$mServiceConnection$1`(p0) {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        runCatching {
            if (b.g.isNullOrEmpty()) {
                val uid = b.i
                b.g = uid?.let { KernelServiceUtil.f()?.uixConvertService?.y(it)?.takeIf { u -> u > 0L }?.toString() } ?: "0"
                Utils.log("CallIncomingConnHook: backfilled empty peerUin -> ${b.g} (uid=$uid)")
            }
        }.onFailure { Utils.log("CallIncomingConnHook: peerUin guard failed: $it") }
        super.onServiceConnected(name, service)
        if (Settings.materializeCall.value) MaterialCallUi.rebuildIncoming(b)
    }
}
