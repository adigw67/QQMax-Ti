package momoi.mod.qqpro.hook.call

import android.app.Activity
import android.content.Context
import android.view.View
import com.tencent.av.camera.AndroidCamera
import com.tencent.av.camera.CameraUtils
import com.tencent.qav.thread.ThreadManager
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * Cycle the in-call camera through OFF → FRONT → BACK → OFF on a single Material call button.
 *
 * Primitives (all stock QQ, exercised the way the native UI does):
 * - **on/off**: click the native camera toggle ([QQNTC2CWatchActivity] `p`); this is what signals
 *   `setLocalHasVideo` to the peer, so we must go through it rather than opening the camera directly.
 * - **front/back**: post [CameraUtils]'s `SwitchCameraRunnable] (`cu.i`) on QQ's camera handler
 *   ([ThreadManager.b]). The runnable itself has no can-switch gate (that lives in C2COperatorImpl.a),
 *   it just flips [AndroidCamera] `k` (1=front, 2=back) and restarts preview.
 *
 * State is read from the hardware each tap ([AndroidCamera.a] = opened, [CameraUtils.c] = isFront) so
 * it can't drift. On OFF→FRONT we force `k=1` first so a persisted "back" facing doesn't reopen back.
 * Only used inside [MaterialCallUi] (materializeCall), so no separate toggle is needed.
 */
object CameraCycle {
    private const val FRONT = 1
    private const val BACK = 2

    private fun cu(ctx: Context): CameraUtils? = runCatching { CameraUtils.b(ctx) }.getOrNull()
    private fun isOn(): Boolean = runCatching { AndroidCamera.a }.getOrDefault(false)
    private fun isFront(ctx: Context): Boolean = runCatching { cu(ctx)?.c() ?: true }.getOrDefault(true)

    /** Advance the camera one step in the cycle, driven by the real current state. */
    fun advance(activity: Activity, nativeToggle: View) {
        // While screen sharing, the button is a "stop share" control instead of a camera cycle.
        if (ScreenShare.sharing) { ScreenShare.toggle(activity); return }
        runCatching {
            val ctx: Context = activity
            val on = isOn()
            val front = isFront(ctx)
            when {
                !on -> {                          // OFF → FRONT
                    runCatching { cu(ctx)?.c?.k = FRONT }   // force front on next open
                    nativeToggle.performClick()
                    Utils.log("CameraCycle: OFF → FRONT")
                }
                front -> {                        // FRONT → BACK
                    switch(ctx)
                    Utils.log("CameraCycle: FRONT → BACK")
                }
                else -> {                         // BACK → OFF
                    nativeToggle.performClick()
                    Utils.log("CameraCycle: BACK → OFF")
                }
            }
            // on/off also refreshes via the native onVideo observer; the switch has no such event,
            // so refresh once the runnable has had time to flip facing + restart preview.
            activity.window.decorView.postDelayed({ refreshIcon(activity) }, 450)
        }.onFailure { Utils.log("CameraCycle: advance failed: $it") }
    }

    private fun switch(ctx: Context) {
        runCatching {
            val c = cu(ctx) ?: return
            if (c.d <= 1) { Utils.log("CameraCycle: only one camera, cannot switch"); return }
            val runnable = c.i ?: return
            ThreadManager.b.post(runnable)
        }.onFailure { Utils.log("CameraCycle: switch failed: $it") }
    }

    /** Set the camera button icon to match the actual state: front / back / off. */
    fun refreshIcon(activity: Activity) {
        val iv = MaterialCallUi.camIcon(activity) ?: return
        val ctx: Context = activity
        val on = isOn()
        val front = isFront(ctx)
        val glyph = when {
            ScreenShare.sharing -> MaterialSymbols.stop
            !on -> MaterialSymbols.videocam_off
            front -> MaterialSymbols.videocam
            else -> MaterialSymbols.flip_camera_android
        }
        val alpha = if (on || ScreenShare.sharing) 1f else 0.6f
        iv.post {
            iv.setImageDrawable(MaterialSymbol(glyph, M3.onSurface))
            iv.alpha = alpha
        }
    }
}
