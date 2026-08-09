package momoi.mod.qqpro.hook

import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import com.tencent.qqnt.watch.notification.NotifyProcessor
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils

/**
 * The native [NotifyProcessor.a] is QQ's new-message alert: it fires both a vibration and the
 * `R.raw.office` tone, gated ONLY by the legacy `allow_vibrate` ("wearqq") pref — it ignores QQPro's
 * 震动/提示音 modes entirely.
 *
 * When `allow_notification` is on, QQPro posts its own notification ([NotificationReply]) whose
 * channel produces the 系统 sound/vibration (and [NotificationAlert] the 应用内 one) per the modes,
 * so this native alert is redundant — suppress it to avoid any double-buzz. When off, the base app
 * uses its own (ROM broadcast / native notification) path; leave its alert as the base app intended
 * by delegating to the original implementation.
 */
@Mixin
class NotifyProcessorAlert : NotifyProcessor() {
    override fun a(msgRecord: RecentContactInfo) {
        if (Settings.allowNotification.value) {
            Utils.log("NotifyProcessor.a suppressed (QQPro NotificationReply/channel drives the alert)")
            return
        }
        super.a(msgRecord)
    }
}
