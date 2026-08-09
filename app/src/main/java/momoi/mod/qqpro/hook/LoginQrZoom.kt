package momoi.mod.qqpro.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.tencent.qqnt.account.login.ui.QrLoginFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.login.LoginM3
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi

/**
 * Login screen hook. Two behaviours, both delegating to helpers (so the SAM/lambda classes they
 * generate live in a `momoi.*` package, not inside this @Mixin body copied into QQ's package):
 *
 *  - **Always**: tap the QR code to blow it up to (almost) full screen for easy scanning, tap to
 *    dismiss ([LoginQrZoomHelper]).
 *  - **When `materializeLogin` is on**: replace the whole page with a from-scratch Material 3 layout
 *    (QQ Max brand, framed QR, M3 status line + post-scan identity, refresh/phone-login buttons),
 *    built by [LoginM3] around the live native QR. The login *engine* (QR gen/refresh, the
 *    `LoginQrCode` state machine) is untouched; we just re-skin and drive a status line from the
 *    callbacks below. Falls back to the native page if the rebuild throws.
 */
@Mixin
class LoginQrZoom : QrLoginFragment() {
    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = super.Y(inflater, container, savedInstanceState)
        if (Settings.materializeLogin.value) {
            LoginM3.build(this, root)?.let { return it }
        }
        // Non-materialized: native page with just the tap-to-zoom affordance.
        LoginQrZoomHelper.attach(root)
        return root
    }

    // ── LoginQrCodeStateCallback hooks ───────────────────────────────────────────────────────────
    // Each calls super (keeps the native engine working) then nudges the M3 status line. When the
    // page isn't materialized, LoginM3's refs are null so its methods are harmless no-ops. The
    // QR-changed states (scanned/expired) also dismiss any stale enlarged-QR zoom overlay.

    // P(byte[]) = a fresh QR bitmap is ready to be scanned.
    override fun P(picBuf: ByteArray?) {
        super.P(picBuf)
        LoginM3.onQrReady()
    }

    override fun Q() {
        super.Q()
        LoginQrZoomHelper.dismiss()
        LoginM3.onScanned()
    }

    // e(account, accountType, sigCreateTime) = login confirmed on the phone; `account` is the uin.
    override fun e(account: String, accountType: Int, sigCreateTime: Long) {
        super.e(account, accountType, sigCreateTime)
        LoginM3.onConfirmed(account)
    }

    // t() = QR fetch/refresh in progress.
    override fun t() {
        super.t()
        LoginM3.onLoading()
    }

    override fun k() {
        super.k()
        LoginQrZoomHelper.dismiss()
        LoginM3.onExpired()
    }

    // r(ret, errMsg) = login failed; m(ret, errMsg) = QR fetch errored.
    override fun r(ret: Int, errMsg: String?) {
        super.r(ret, errMsg)
        LoginM3.onError(errMsg)
    }

    override fun m(ret: Int, errMsg: String?) {
        super.m(ret, errMsg)
        LoginM3.onError(errMsg)
    }

    override fun onDestroy() {
        LoginM3.clear()
        super.onDestroy()
    }
}

// Must be public (not `private`/package-private): the @Mixin's Y() body is copied
// into QQ's package and references this class, so it has to be accessible there —
// a private object triggers IllegalAccessError at runtime.
object LoginQrZoomHelper {
    // The currently-shown zoom overlay (login or self QR), so a QR state change can dismiss it.
    @Volatile
    private var current: FrameLayout? = null

    /** Close the zoom overlay if one is open (safe to call from any thread / when none is open). */
    fun dismiss() {
        val o = current ?: return
        runOnUi {
            (o.parent as? ViewGroup)?.removeView(o)
            if (current === o) current = null
        }
    }

    fun attach(root: View) {
        try {
            val ctx = root.context
            val id = ctx.resources.getIdentifier("qr_code_container", "id", ctx.packageName)
            val qr = if (id != 0) root.findViewById<View>(id) else null
            if (qr == null) {
                Utils.log("LoginQrZoom: qr_code_container not found")
                return
            }
            attachTapZoom(qr)
            Utils.log("LoginQrZoom: attached zoom to QR view")
        } catch (e: Throwable) {
            Utils.log("LoginQrZoom: attach failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Tap [qr] to blow up a snapshot of it to (almost) full screen, tap again to dismiss.
     * Shared by the login-QR and self-QR hooks; the self QR is rendered tiny on a watch face,
     * so this makes it scannable. Public so @Mixin method bodies (copied into QQ's package) can
     * reach it.
     */
    fun attachTapZoom(qr: View) {
        qr.setOnClickListener { showZoom(qr) }
    }

    private fun showZoom(qr: View) {
        if (qr.width == 0 || qr.height == 0) return
        val activity = qr.context.findActivity() ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // Snapshot the rendered QR (white card + code + centre logo) so we can show
        // it enlarged without fighting the login layout's constraints.
        val snapshot = Bitmap.createBitmap(qr.width, qr.height, Bitmap.Config.ARGB_8888)
        qr.draw(Canvas(snapshot))

        val dm = activity.resources.displayMetrics
        val side = minOf(dm.widthPixels, dm.heightPixels)

        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(0xEE_000000.toInt())
            isClickable = true
        }
        val image = ImageView(activity).apply {
            setImageBitmap(snapshot)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        overlay.addView(image, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        overlay.setOnClickListener {
            content.removeView(overlay)
            if (current === overlay) current = null
        }
        content.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        current = overlay
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
