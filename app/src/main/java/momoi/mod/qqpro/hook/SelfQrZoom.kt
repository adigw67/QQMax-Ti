package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.tencent.qqnt.watch.selftab.ui.SelfQrFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.util.Utils

/**
 * Self tab → my QR code screen. The QR (R.id.qr_code, a ColorfulQRWithBackgroundView) is rendered
 * very small on a watch face, so tap it to blow it up to (almost) full screen for scanning, then
 * tap anywhere to dismiss — same behaviour as the login-QR zoom ([LoginQrZoom]).
 */
@Mixin
class SelfQrZoom : SelfQrFragment() {
    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = super.Y(inflater, container, savedInstanceState)
        // Defer to a helper so the click-listener SAM impls are generated in this (hook) package
        // rather than inside this @Mixin method body copied into QQ's package.
        if (root != null) SelfQrZoomHelper.attach(root)
        return root
    }
}

// Must be public (not private): the @Mixin's Y() body is copied into QQ's package and references
// this class, so it has to be accessible there — a private object triggers IllegalAccessError.
object SelfQrZoomHelper {
    fun attach(root: View) {
        try {
            val ctx = root.context
            val id = ctx.resources.getIdentifier("qr_code", "id", ctx.packageName)
            val qr = if (id != 0) root.findViewById<View>(id) else null
            if (qr == null) {
                Utils.log("SelfQrZoom: qr_code not found")
                return
            }
            LoginQrZoomHelper.attachTapZoom(qr)
            Utils.log("SelfQrZoom: attached zoom to self QR view")
        } catch (e: Throwable) {
            Utils.log("SelfQrZoom: attach failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
