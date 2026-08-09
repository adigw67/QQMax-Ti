package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.tencent.qqnt.watch.mainframe.MainFragment
import momoi.anno.mixin.Mixin

/**
 * Home/main page (conversation list) navigation hook. Delegates all rendering to [MainNav], which
 * replaces the native page-indicator's children with our own icon + unread-badge cells and applies
 * the 主页导航 settings (bottom position, height, square spread, all-page icons, unread badges).
 */
@Mixin
class MainPageNav : MainFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val root = view as? ViewGroup ?: return
        if (momoi.mod.qqpro.Settings.mainNavCustom.value) {
            MainNav.install(root)
        } else {
            // Custom nav off: keep the native indicator but still honor the top/bottom placement.
            MainNav.installNative(root)
        }
    }
}
