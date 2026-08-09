package momoi.mod.qqpro.hook.call

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.tencent.activitys.QQNTC2CWatchActivity
import com.tencent.qqnt.watch.camera.CameraUtils
import com.tencent.qqnt.watch.impl.WatchQavFacadeImpl
import com.tencent.qqnt.watch.ui.componet.permission.PermissionUtils
import com.tencent.service.QavManageService
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils

/**
 * 无摄像头也可发起视频通话 — start a receive-only video call on a watch that has no camera.
 *
 * QQ blocks a video call at two "当前设备不支持" gates that both test
 * [CameraUtils].a() (`Camera.getNumberOfCameras() > 0`): the "+" panel funnel `MenuFrame.b0`
 * (see [b0]) and [WatchQavFacadeImpl.goToAVScene]. We keep [CameraUtils.a] honest everywhere else
 * (拍照/录像 still detect "no camera") and only bypass these two call gates, then start the call
 * exactly as the native inner-most lambda does (mirroring `WatchQavFacadeImpl.goToAVScene`) but with a
 * video (`key_is_only_audio = false`) session — local camera controls are hidden in the call UI.
 *
 * A plain object (NOT a @Mixin body) so the permission callback lambda is safe (anonymous classes
 * inside a @Mixin method crash — see the project's mixin notes).
 */
object CallStart {

    /** True when we should bypass the camera gate for a video call: setting on, video, no camera. */
    fun shouldBypass(openCamera: Boolean): Boolean =
        Settings.callCameralessVideo.value && openCamera && !CameraUtils.a.a()

    /**
     * Start a receive-only video call to [peerUin]. Requests RECORD_AUDIO first (the mic is still
     * needed so the other side can hear us), then starts the QAV service + call activity — the same
     * pair the native path starts, with `key_is_only_audio = false` for a video session.
     */
    fun cameralessVideo(
        context: Context,
        fragment: Fragment,
        peerUin: String,
        peerId: String,
        showNick: String,
    ) {
        Utils.log("CallStart: cameraless video call to $peerUin ($showNick)")
        runCatching {
            PermissionUtils.a.a(
                "用于音视频通话", fragment, listOf("android.permission.RECORD_AUDIO"),
            ) { granted ->
                if (granted) startVideoSession(context, peerUin, peerId, showNick)
            }
        }.onFailure { Utils.log("CallStart: cameraless video failed: $it") }
    }

    private fun startVideoSession(context: Context, peerUin: String, peerId: String, showNick: String) {
        val intent = Intent(context, QavManageService::class.java).apply {
            putExtra("key_is_receiver", false)
            putExtra("key_peer_uin", peerUin)
            putExtra("key_peer_uid", peerId)
            putExtra("key_peer_nick", showNick)
            putExtra("key_is_only_audio", false) // video session (we receive video, send none)
        }
        context.startService(intent)
        context.startActivity(Intent(context, QQNTC2CWatchActivity::class.java))
    }
}

/**
 * Bypass the camera gate in [WatchQavFacadeImpl.goToAVScene] so a cameraless watch can start a video
 * call. Every entry point (the "+" panel via [b0], and any other caller such as a profile-card call
 * button) funnels through this facade, so hooking it here covers them all. When we can't/shouldn't
 * bypass, the original runs unchanged.
 */
@Mixin
class WatchQavFacadeHook : WatchQavFacadeImpl() {
    override fun goToAVScene(
        context: Context,
        fragment: Fragment,
        peerUin: String,
        peerId: String,
        showNick: String,
        openCamera: Boolean,
    ) {
        if (CallStart.shouldBypass(openCamera)) {
            CallStart.cameralessVideo(context, fragment, peerUin, peerId, showNick)
        } else {
            super.goToAVScene(context, fragment, peerUin, peerId, showNick, openCamera)
        }
    }
}
