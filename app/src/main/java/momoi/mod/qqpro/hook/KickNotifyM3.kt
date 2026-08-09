package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.tencent.qqnt.account.kick.ui.KickNotifyFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.forEachAll
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

/**
 * The forced-offline / 被踢下线 notice ([KickNotifyFragment], shown when the account is kicked by a
 * login elsewhere). Native layout is a bare ConstraintLayout: a centered message ([R.string]
 * token_expired) + a red full-width confirm button that returns to the login entry. Materialize it in
 * place (same approach as [stylePermissionDialog]): M3 surface, M3 message text, and an M3 error pill
 * for the confirm button — the native click listener (set by super.Y) is left untouched. Gated by
 * [Settings.useM3Settings].
 */
@Mixin
class KickNotifyM3 : KickNotifyFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        if (Settings.useM3Settings.value) styleKickNotify(root)
        return root
    }
}

/** Top-level so the @Mixin body stays free of helper closures. */
fun styleKickNotify(root: View) {
    runCatching {
        val res = root.resources
        val confirmId = res.getIdentifier("confirm", "id", root.context.packageName)

        root.setBackgroundColor(M3.surface)
        (root as? ViewGroup)?.forEachAll { v ->
            when {
                v is Button && v.id == confirmId -> {
                    // Keep the red affordance (it's a sign-out) but as a clean M3 error pill.
                    v.background = M3.rounded(M3.error, M3.radiusPill)
                    v.backgroundTintList = null
                    v.setTextColor(M3.onColor(M3.error))
                    // Give the full-width button a little breathing room from the screen edge.
                    (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                        it.leftMargin = 16.dp; it.rightMargin = 16.dp
                    }
                }
                v is TextView -> {
                    v.setTextColor(M3.onSurface)
                    v.gravity = Gravity.CENTER
                }
            }
        }
        Utils.log("KickNotifyM3: kick notice materialized")
    }.onFailure { Utils.log("KickNotifyM3 failed: $it") }
}
