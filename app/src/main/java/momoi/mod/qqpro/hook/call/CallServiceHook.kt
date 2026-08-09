package momoi.mod.qqpro.hook.call

import android.content.Intent
import com.tencent.service.QavManageService
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import mqq.app.Foreground

/**
 * 来电通知修复 — post the incoming-call notification from the QAV service start. `QavManageService` is
 * started for every call; its intent carries `key_peer_uin` / `key_peer_nick` (resolved by
 * JumpActivityHelper) and `key_is_only_audio`, and `key_is_receiver` (absent → default true) marks an
 * INCOMING call vs an outgoing one (`goToAVScene` sets it false). So this is the cleanest place to get
 * the caller's name for the notification.
 *
 * We keep all native behaviour via `super.onStartCommand`, then — for an incoming call while the app is
 * backgrounded (foreground already shows the native answer screen) — post [CallNotification].
 */
@Mixin
class CallServiceHook : QavManageService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        if (Settings.callNotifyFix.value && intent != null &&
            intent.getBooleanExtra("key_is_receiver", true)
        ) {
            runCatching {
                val uin = intent.getStringExtra("key_peer_uin") ?: ""
                val nick = intent.getStringExtra("key_peer_nick")
                val onlyAudio = intent.getBooleanExtra("key_is_only_audio", true)
                // Let a Bluetooth/wired headset hook button answer the ring (works whether the native
                // answer screen is foreground or we're backgrounded).
                HeadsetAnswer.startRinging(this, uin, onlyAudio)
                // Notification takeover only matters when backgrounded (foreground shows the native screen).
                if (!Foreground.isCurrentProcessForeground()) {
                    // 使用全屏来电 + overlay permission → take over the screen directly (real call takeover).
                    // Otherwise fall back to the heads-up notification (which carries its own full-screen
                    // intent for the lock-screen case, plus the caller name/avatar + 接听/拒绝 buttons).
                    val launched = Settings.callFullScreenIntent.value &&
                        CallNotification.launchFullScreen(this, uin, onlyAudio)
                    if (!launched) CallNotification.postIncoming(this, uin, nick, onlyAudio)
                }
            }.onFailure { Utils.log("CallServiceHook: incoming handling failed: $it") }
        }
        return result
    }
}
