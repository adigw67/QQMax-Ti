package momoi.mod.qqpro.hook.login

import com.tencent.qqnt.account.login.qrcode.`LoginQrCode$wtLoginObserver$1`
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings

/**
 * Intercept the QR-login poll observer to grab the logging-in account's uin at the SCANNED stage
 * (ret 53) — the native `d()` has it in scope but doesn't forward it to the fragment's `Q()` callback.
 * Capturing it here lets [LoginM3] show the account avatar/number on the watch before the user confirms
 * on their phone. Calls super so the original dispatch (Q/e/r) is unchanged.
 */
@Mixin
class LoginScanObserver : `LoginQrCode$wtLoginObserver$1`() {
    override fun d(account: String?, accountType: Int, sigCreateTime: Long, ret: Int, errMsg: String?) {
        if (ret == 53 && Settings.materializeLogin.value) {
            runCatching { LoginM3.captureScannedUin(account) }
        }
        super.d(account, accountType, sigCreateTime, ret, errMsg)
    }
}
