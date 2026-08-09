package momoi.mod.qqpro.hook

import android.content.Context
import android.view.View
import com.tencent.qqnt.watch.camera.CameraGuideView
import momoi.anno.mixin.Mixin

/**
 * Remove the one-time "上下滑动切换镜头" (swipe up/down to switch camera) tutorial overlay.
 *
 * QQ's `CameraFragment` adds a [CameraGuideView] over the camera on first launch (gated by the MMKV flag
 * `is_first_camera`); its guide step shows `camera_switch_guide` + `fling_switch_camera`. On this watch
 * (single camera, nothing to switch) it's pointless. We make the guide hide itself the moment it attaches
 * so it never appears — the full-screen overlay is also `clickable`, so leaving it would swallow taps.
 *
 * Kept as a bare `visibility = GONE` (no lambda/anonymous class) so it's safe inside a @Mixin body.
 */
@Mixin
class CameraGuideHook(ctx: Context) : CameraGuideView(ctx, null, 0) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        visibility = View.GONE
    }
}
