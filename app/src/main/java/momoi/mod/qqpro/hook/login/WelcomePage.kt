package momoi.mod.qqpro.hook.login

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.qqnt.account.login.ui.LoginWithoutStatePage
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.util.Utils

/**
 * First-start welcome/landing page ([LoginWithoutStatePage], `pg_watch_welcome`, shown on a fresh
 * install before the QR login). Native layout = app avatar (`icon_avatar_qq`) + "欢迎来到QQ" + a
 * "登录" button that navigates to `qr_login_fragment`.
 *
 * When `materializeLogin` is on, replace it with a from-scratch M3 page rebranded to **QQ Max** + the
 * app icon. The native "登录" button (with its navigation wiring) is kept off-screen and click-delegated
 * so we don't reimplement the NavController call. Falls back to the native page on any failure.
 */
@Mixin
class WelcomePage : LoginWithoutStatePage() {
    // W() = "fragment provides its own background" → true suppresses the base WatchFragment's
    // full-screen background ImageView so our M3 surface shows edge-to-edge.
    override fun W(): Boolean = if (Settings.materializeLogin.value) true else super.W()

    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val native = super.Y(inflater, container, savedInstanceState)
        if (Settings.materializeLogin.value) {
            WelcomeM3.build(native)?.let { return it }
        }
        return native
    }
}

object WelcomeM3 {
    fun build(native: View?): View? {
        val root = native ?: return null
        return try {
            val ctx = root.context
            val loginId = ctx.resources.getIdentifier("login", "id", ctx.packageName)
            val nativeLoginBtn = if (loginId != 0) root.findViewById<View>(loginId) else null
            if (nativeLoginBtn == null) {
                Utils.log("WelcomeM3: native login button not found, keep native")
                return root
            }

            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val ph = 24.dp
                setPadding(ph, 24.dp, ph, 24.dp)
            }

            // App icon
            val iconId = ctx.resources.getIdentifier("icon", "drawable", ctx.packageName)
            if (iconId != 0) {
                val icon = ImageView(ctx).apply { runCatching { setImageDrawable(ctx.getDrawable(iconId)) } }
                column.addView(icon, LinearLayout.LayoutParams(64.dp, 64.dp).apply { bottomMargin = 14.dp })
            }
            // QQMax-Ti wordmark
            column.addView(TextView(ctx).apply {
                text = "QQMax-Ti"
                setTextColor(M3.onSurface)
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            column.addView(TextView(ctx).apply {
                text = "欢迎使用 QQMax-Ti"
                setTextColor(M3.onSurfaceVariant)
                textSize = 13f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6.dp; bottomMargin = 26.dp })

            // M3 login button → delegate to the native button's navigation.
            column.addView(M3Button(ctx).apply {
                text = "登录"
                variant(M3Button.Variant.FILLED)
                minWidth = 140.dp
                setOnClickListener {
                    runCatching { nativeLoginBtn.performClick() }
                        .onFailure { Utils.log("WelcomeM3: login delegate failed: $it") }
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
            Utils.log("WelcomeM3: build failed (${e.javaClass.simpleName}: ${e.message}), keep native")
            root
        }
    }
}
