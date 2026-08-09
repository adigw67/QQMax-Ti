package momoi.mod.qqpro.hook.login

import android.content.Context
import com.tencent.qqnt.watch.ui.qrcode.ChangeColorParams
import com.tencent.qqnt.watch.ui.qrcode.QUIColorfulQRCodeView
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.material.M3

/**
 * Make QQ's colorful QR (login QR, self-tab QR) follow the M3 theme instead of its fixed blue.
 *
 * QQ builds the QR-module color from a single base color: `QUIColorfulQRCodeView.b(color, params)`
 * takes `Hct(color).hue` and applies the tone/chroma ramp in [ChangeColorParams] (see `c()`, the QR
 * gradient shader). `QrLoginFragment$handleLoadSuccess` calls this with `Color.parseColor("#1B9AF7")`
 * on EVERY QR (re)load — which is why setting the shader ourselves afterwards was overwritten.
 *
 * Intercepting `b()` at the source is order-independent: we swap the incoming base color for the M3
 * primary so the whole ramp shifts to the theme hue (the tone stays QQ's own scannable value, so the
 * code remains readable regardless of how light/dark the chosen color is). Gated by [Settings.materializeLogin]
 * (the materialized-login feature that owns the QR chrome); off = QQ's original blue.
 */
@Mixin
class QrCodeThemeColor(context: Context) : QUIColorfulQRCodeView(context) {
    override fun b(color: Int, changeColorParams: ChangeColorParams) {
        val themed = if (Settings.materializeLogin.value) M3.primary else color
        super.b(themed, changeColorParams)
    }
}
