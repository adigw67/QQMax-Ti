package momoi.mod.qqpro.hook.call

import android.content.ComponentName
import android.os.IBinder
import com.tencent.activitys.QQNTC2CWatchActivity
import com.tencent.activitys.`QQNTC2CWatchActivity$mServiceConnection$1`
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils

/**
 * QQ wires + re-tints the active-call control buttons (camera/mic/hangup) white/red inside the call
 * activity's service-connection callback. We hook it so that, after the native wiring runs, our Material 3
 * control styling ([MaterialCallUi.styleActiveControls]) is applied last and wins. `b` is the outer
 * [QQNTC2CWatchActivity]; the constructor param forwards to the anonymous class's super ctor (like
 * MenuPanelLayout). Compiles thanks to the EnclosingMethod-stripped stub (see build.gradle.kts).
 */
@Mixin
class CallConnHook(p0: QQNTC2CWatchActivity) : `QQNTC2CWatchActivity$mServiceConnection$1`(p0) {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        // Guard a native crash: onServiceConnected does Long.parseLong(peerUin), which throws
        // NumberFormatException (hard-crashing the app to CrashReportActivity) when the Qav service
        // (re)connects before peerUin is populated — a race the screen-share consent activity's
        // lifecycle churn can trigger. Swallow it so the call survives; peerUin fills in shortly after.
        runCatching { super.onServiceConnected(name, service) }
            .onFailure { Utils.log("CallConnHook: onServiceConnected guarded: $it") }
        if (Settings.materializeCall.value) MaterialCallUi.rebuildActive(b)
    }
}
