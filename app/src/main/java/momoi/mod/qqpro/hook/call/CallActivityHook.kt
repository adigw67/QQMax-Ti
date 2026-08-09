package momoi.mod.qqpro.hook.call

import android.os.Bundle
import com.tencent.activitys.QQNTC2CWatchActivity
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings

/**
 * Lifecycle hook on the active-call activity. Drives 通话音频输出 ([CallAudio] / [CallOutputSelector]):
 * stock QQ on this watch can only play call audio through the speaker, so — gated on
 * [Settings.callBluetoothRoute] — we add an output selector and can auto-route to a connected Bluetooth
 * headset. The selector button is only added on the native screen; when the full M3 call UI
 * (materializeCall) is on it provides its own selector, so we skip ours to avoid duplicates.
 *
 * All routing goes through the plain [CallOutputSelector]/[CallAudio] helpers (not inline here) so the
 * click lambdas / receivers stay out of this @Mixin body.
 */
@Mixin
class CallActivityHook : QQNTC2CWatchActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.materializeCall.value) MaterialCallUi.prepActive(this)
    }

    override fun onResume() {
        super.onResume()
        if (Settings.callNotifyFix.value) {
            CallNotification.cancelIncoming(this)
            HeadsetAnswer.stop() // call is active now — release the ring media session
        }
        if (Settings.callBluetoothRoute.value) {
            // Attach the native-screen button first (registers the icon mirror) so CallAudio.begin's
            // applied default paints correctly. The M3 UI has its own selector, so skip the button then.
            if (!Settings.materializeCall.value) CallOutputSelector.attach(this)
            CallAudio.begin(this, autoBluetooth = true)
        }
    }

    override fun onDestroy() {
        // The call is gone — stop any active screen share so its mediaProjection foreground service
        // (and its lingering notification) doesn't outlive the call.
        ScreenShare.ensureStopped(this)
        if (Settings.callBluetoothRoute.value) CallAudio.release(this)
        super.onDestroy()
    }
}
