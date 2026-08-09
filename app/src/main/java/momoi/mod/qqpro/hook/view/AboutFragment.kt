package momoi.mod.qqpro.hook.view

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import momoi.mod.qqpro.hook.versionName
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.lib.material.M3Dialog
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.ota.OTAManager2
import momoi.mod.qqpro.util.Utils

/**
 * About dialog for QQ Max. A full-screen [MyDialogFragment] rather than the native TipsUtils
 * dialog, which renders with oversized margins on the round watch screen (the app forces a tiny
 * compile SDK and overrides display DPI — see [LinkOpenFragment]). Shows the app icon, version
 * and credits (scrollable) plus a 检查更新 button that force-checks via [OTAManager2]. Built on the
 * shared [M3Dialog] scaffold, wrapped in [SwipeBackLayout] so a left-to-right swipe dismisses it.
 */
class AboutFragment : MyDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        // Swipe-to-dismiss handles closing, so no 关闭 button here (onClose = null).
        val dialog = buildAboutView(
            ctx,
            onCheckUpdate = { OTAManager2(ctx).checkUpdate(true); dismiss() },
        )
        return swipeBackWrap(dialog)
    }
}

/**
 * Build the shared About content (icon / version / build time / credits) with a 检查更新 button and,
 * when [onClose] is supplied, a 关闭 button. Reused by [AboutFragment] (swipe-to-dismiss, no close
 * button) and by the settings activity's 关于 entry (a raw [android.app.Dialog] that needs a close
 * button since it isn't swipe-wrapped).
 */
fun buildAboutView(
    ctx: android.content.Context,
    onCheckUpdate: () -> Unit,
    onClose: (() -> Unit)? = null,
): M3Dialog = M3Dialog(ctx)
    .body {
        val icon = add<ImageView>().apply {
            runCatching {
                setImageDrawable(ctx.packageManager.getApplicationIcon(ctx.packageName))
            }.onFailure { Utils.log("AboutFragment: icon load failed: $it") }
        }
        (icon.layoutParams as LinearLayout.LayoutParams).apply {
            width = 56.dp
            height = 56.dp
            bottomMargin = 8.dp
        }

        add<TextView>()
            .text("QQ Max")
            .textSize(18f)
            .textColor(M3.onSurface)
            .gravity(Gravity.CENTER)
        add<TextView>()
            .text(versionName)
            .textSize(12f)
            .textColor(M3.onSurfaceVariant)
            .gravity(Gravity.CENTER)
            .padding(bottom = 12.dp)

        add<TextView>()
            .text("构建于 ${momoi.mod.qqpro.BuildConfig.BUILD_TIME}")
            .textSize(11f)
            .textColor(M3.hint)
            .gravity(Gravity.CENTER)
            .padding(bottom = 12.dp)

        add<TextView>()
            .text("NWear QQ · 爅峫\nQQ Pro · java30433\nQQ Max · AILIFE")
            .textSize(13f)
            .textColor(M3.onSurfaceVariant)
            .gravity(Gravity.CENTER)
            .padding(bottom = 12.dp)
    }
    .actions {
        action("检查更新", M3Button.Variant.FILLED) { onCheckUpdate() }
        if (onClose != null) action("关闭", M3Button.Variant.TEXT) { onClose() }
    }
