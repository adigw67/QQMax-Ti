package momoi.mod.qqpro.hook

import android.view.View
import androidx.fragment.app.Fragment
import com.tencent.qqnt.watch.selftab.item.AboutItem
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.hook.view.AboutFragment
import momoi.mod.qqpro.showDialog
import momoi.mod.qqpro.util.Utils

/** The mod's own versionName, read from the patched APK's manifest (set via apkMixin.versionName). */
val versionName: String
    get() = runCatching {
        Utils.application.packageManager
            .getPackageInfo(Utils.application.packageName, 0).versionName
    }.getOrNull() ?: ""

/**
 * Hooks the dedicated "关于" (About) entry on the self tab. Tapping it shows our own full-screen
 * [AboutFragment] (version + credits + 检查更新 button) instead of the native TipsUtils dialog,
 * which renders with oversized margins on the round watch screen.
 */
@Mixin
class AboutItemHook(fragment: Fragment) : AboutItem(fragment) {
    override fun onClick(v: View?) {
        runCatching { v?.showDialog(AboutFragment()) }
            .onFailure { Utils.log("AboutItemHook: failed to show about dialog: $it") }
    }
}
