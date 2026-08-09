package momoi.mod.qqpro.hook.login

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.qqnt.account.login.ui.LoginWithStateFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.util.Utils

/**
 * Post-logout quick-login page ([LoginWithStateFragment], shown after a manual sign-out while a stored
 * account is still remembered): native layout = the last account's avatar (`WatchAvatarView`) + its
 * nickname + a "登录" button (re-logs in that account via `app.login(accounts.first())`) and a
 * "切换账号" button (navigates to `qr_login_fragment`). Distinct from the fresh-install welcome page
 * ([momoi.mod.qqpro.hook.login.WelcomePage] on [com.tencent.qqnt.account.login.ui.LoginWithoutStatePage]).
 *
 * When `materializeLogin` is on, reskin it M3: keep the native engine (both button click wirings and
 * the `onViewCreated` avatar/nickname population), just REPARENT the live avatar + nickname views into
 * a from-scratch M3 column and drive two M3 buttons by delegating to the native buttons' clicks. Falls
 * back to the native page on any failure.
 */
@Mixin
class LoginState : LoginWithStateFragment() {
    // W() = "fragment provides its own background" → true suppresses the base WatchFragment's
    // full-screen background image (bg_blue2white) so our M3 surface shows edge-to-edge.
    override fun W(): Boolean = if (Settings.materializeLogin.value) true else super.W()

    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val native = super.Y(inflater, container, savedInstanceState)
        if (Settings.materializeLogin.value) {
            LoginStateM3.build(native)?.let { return it }
        }
        return native
    }
}

object LoginStateM3 {
    fun build(native: View?): View? {
        val root = native ?: return null
        return try {
            val ctx = root.context
            fun viewId(name: String) = ctx.resources.getIdentifier(name, "id", ctx.packageName)
            val avatar = root.findViewById<View>(viewId("avatar"))
            val nickname = root.findViewById<TextView>(viewId("nickname"))
            val nativeLogin = root.findViewById<View>(viewId("login"))
            val nativeMore = root.findViewById<View>(viewId("more"))
            if (avatar == null || nativeLogin == null || nativeMore == null) {
                Utils.log("LoginStateM3: required native views not found, keep native"); return root
            }

            // Reparent the live avatar + nickname so the native onViewCreated (which sets the bitmap
            // on the WatchAvatarView and the nickname text) still targets the views we display.
            (avatar.parent as? ViewGroup)?.removeView(avatar)
            (nickname?.parent as? ViewGroup)?.removeView(nickname)

            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val ph = 24.dp
                setPadding(ph, 24.dp, ph, 24.dp)
            }

            column.addView(avatar, LinearLayout.LayoutParams(84.dp, 84.dp).apply { bottomMargin = 14.dp })

            nickname?.apply {
                setTextColor(M3.onSurface)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                column.addView(this, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 28.dp })
            }

            // 登录 → re-login the stored account (delegate to the native login button).
            column.addView(M3Button(ctx).apply {
                text = "登录"
                variant(M3Button.Variant.FILLED)
                minWidth = 160.dp
                setOnClickListener {
                    runCatching { nativeLogin.performClick() }
                        .onFailure { Utils.log("LoginStateM3: login delegate failed: $it") }
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp })

            // 切换账号 → QR login for a different account (delegate to the native more button).
            column.addView(M3Button(ctx).apply {
                text = "切换账号"
                variant(M3Button.Variant.TONAL)
                minWidth = 160.dp
                setOnClickListener {
                    runCatching { nativeMore.performClick() }
                        .onFailure { Utils.log("LoginStateM3: switch-account delegate failed: $it") }
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            ScrollView(ctx).apply {
                isFillViewport = true
                setBackgroundColor(M3.surface)
                addView(column, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }
        } catch (e: Throwable) {
            Utils.log("LoginStateM3: build failed (${e.javaClass.simpleName}: ${e.message}), keep native")
            root
        }
    }
}
