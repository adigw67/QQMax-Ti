package momoi.mod.qqpro.hook.view

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.lib.material.M3Dialog
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.width
import momoi.mod.qqpro.util.Utils

/**
 * Full-screen confirmation shown when a link is tapped. A native AlertDialog is
 * unusable here — the app forces a tiny compile SDK and overrides the display
 * DPI, so a windowed dialog renders broken on the round watch screen. Built on
 * the shared [M3Dialog] scaffold so it matches every other QQPro dialog.
 */
class LinkOpenFragment(private val url: String) : MyDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        return swipeBackWrap(M3Dialog(ctx)
            .title("打开链接")
            .body {
                add<TextView>()
                    .text(url)
                    .textSize(12f)
                    .textColor(M3.onSurfaceVariant)
                    .gravity(Gravity.CENTER)
                    .width(FILL)
                    .padding(top = 4.dp, bottom = 6.dp)
            }
            .actions {
                action("浏览器打开", M3Button.Variant.FILLED) {
                    Utils.openUrl(url)
                    dismiss()
                }
                action("复制链接", M3Button.Variant.TONAL) {
                    Utils.copyToClipboard(ctx, url, "已复制链接")
                    dismiss()
                }
                action("取消", M3Button.Variant.TEXT) {
                    dismiss()
                }
            })
    }
}
