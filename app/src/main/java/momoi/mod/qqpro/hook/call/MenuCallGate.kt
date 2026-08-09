package momoi.mod.qqpro.hook.call

import com.tencent.mobileqq.qroute.QRoute
import com.tencent.qqnt.watch.IWatchQavFacade
import com.tencent.qqnt.watch.camera.CameraUtils
import com.tencent.qqnt.watch.ui.componet.toast.WatchToast
import com.tencent.watch.aio_impl.ui.frames.MenuFrame
import momoi.anno.mixin.StaticHook
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import mqq.app.MobileQQ

/**
 * The chat "+" panel funnels both 语音通话 / 视频通话 taps through the static `MenuFrame.b0(frame, isVideo)`,
 * which rejects a video call on a cameraless watch with "当前设备不支持" before it ever reaches the QAV
 * facade. Reimplement it to drop that gate when 无摄像头也可视频通话 is on (the facade
 * [com.tencent.qqnt.watch.impl.WatchQavFacadeImpl.goToAVScene] hook then starts the receive-only video
 * call). With the setting off, the original gate + behaviour is preserved exactly.
 *
 * Mirrors the native body: read the chat args bundle, resolve [IWatchQavFacade] via [QRoute], and call
 * goToAVScene with `isVideo` as `openCamera`. Top-level [StaticHook] fun (the current project pattern),
 * name matches the target method; the original is not called (we replace it).
 */
@StaticHook(MenuFrame::class)
fun b0(menuFrame: MenuFrame, z: Boolean) {
    if (!Settings.callCameralessVideo.value && !CameraUtils.a.a() && z) {
        WatchToast.g(MobileQQ.sMobileQQ, 1, "当前设备不支持", 0).l()
        return
    }
    runCatching {
        val args = menuFrame.requireArguments()
        val uin = args.getString("key_bundle_chat_uin") ?: return
        val pid = args.getString("key_bundle_peer_id") ?: return
        val nick = args.getString("key_bundle_chat_nick") ?: return
        QRoute.api(IWatchQavFacade::class.java).goToAVScene(
            menuFrame.requireContext(), menuFrame.requireParentFragment(), uin, pid, nick, z,
        )
    }.onFailure { Utils.log("MenuCallGate: b0 start call failed: $it") }
}
