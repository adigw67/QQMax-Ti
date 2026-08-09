package momoi.mod.qqpro.hook

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.tencent.qqnt.watch.mainframe.MainActivity
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils

/**
 * Optional: show the system status bar (time / battery) instead of the app's fullscreen window.
 *
 * MainActivity uses `@style/FullscreenTheme` (`windowFullscreen=true` → FLAG_FULLSCREEN hides the bar).
 * When [Settings.showStatusBar] is on we clear that flag and reveal the status bar via the platform
 * [WindowInsetsController], then pad the content view down by the status-bar inset so app UI isn't drawn
 * behind it. Done the edge-to-edge way (`setDecorFitsSystemWindows(false)` + inset padding), so it's
 * correct on Android 15+ (API 35) where edge-to-edge is enforced and `setStatusBarColor` is a no-op.
 *
 * Re-asserted in onResume (watch UIs tend to re-hide system bars on resume). Toggling the setting needs
 * an app relaunch to fully take effect. Chains with the other MainActivity @Mixins (更新检查/屏蔽返回键/滚轮适配).
 */
@Mixin
class StatusBarOption : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.showStatusBar.value) applyShowStatusBar(this)
    }

    override fun onResume() {
        super.onResume()
        if (Settings.showStatusBar.value) applyShowStatusBar(this)
    }
}

/**
 * Reveal the system status bar and inset the content below it. Public + top-level so the @Mixin body
 * touches only public members (avoids the IllegalAccessError from helpers copied into the patched class).
 */
fun applyShowStatusBar(act: Activity) {
    if (Build.VERSION.SDK_INT < 30) return // WindowInsetsController / setDecorFitsSystemWindows are API 30+
    runCatching {
        val w = act.window
        @Suppress("DEPRECATION")
        w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        w.setDecorFitsSystemWindows(false)
        w.insetsController?.apply {
            show(WindowInsets.Type.statusBars())
            // Light (white) icons over the app's dark window background.
            setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        }
        val content = act.findViewById<View>(android.R.id.content) ?: return
        content.setOnApplyWindowInsetsListener { v, insets ->
            // Inset content clear of BOTH bars — status bar (top) and navigation bar (bottom), plus any
            // side/cutout insets — so nothing is drawn behind the system UI on Android 15+ edge-to-edge.
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            if (v.paddingLeft != bars.left || v.paddingTop != bars.top ||
                v.paddingRight != bars.right || v.paddingBottom != bars.bottom
            ) {
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        content.requestApplyInsets()
    }.onFailure { Utils.log("StatusBarOption: apply failed: $it") }
}
