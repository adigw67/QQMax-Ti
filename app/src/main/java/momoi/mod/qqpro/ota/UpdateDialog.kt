package momoi.mod.qqpro.ota

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.GroupScope
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.margin
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.lib.width
import momoi.mod.qqpro.lib.material.M3

private val ACCENT get() = M3.primary
private val BG = M3.surface

/**
 * Watch-styled "update available" dialog, built to replace the framework [android.app.AlertDialog]
 * the OTA manager used to show. That default dialog renders with oversized margins / clipped buttons
 * on the round watch (the app forces a tiny compile SDK and overrides display DPI — same reason
 * [momoi.mod.qqpro.hook.view.AboutFragment] exists). This is a full-bleed dark [Dialog] with our own
 * padding, a scrollable changelog, and three stacked buttons; a left-to-right swipe dismisses it for
 * watches without a back button.
 *
 * Called from Java ([OTAManager2]); actions are [Runnable]s for Java interop.
 */
object UpdateDialog {
    @JvmStatic
    fun show(
        context: Context,
        version: String,
        changelog: String,
        onUpdate: Runnable,
        onDisable: Runnable
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context)
            .vertical()
            .padding(left = 20.dp, top = 16.dp, right = 20.dp, bottom = 16.dp)
        root.setBackgroundColor(BG)

        root.content {
            add<TextView>()
                .text("发现新版本 V$version")
                .textSize(17f)
                .textColor(M3.onSurface)
                .gravity(Gravity.CENTER)
                .width(FILL)
                .padding(top = 6.dp, bottom = 12.dp)

            add<TextView>()
                .text(changelog)
                .textSize(13f)
                .textColor(M3.onSurfaceVariant)
                .width(FILL)

            button("更新", ACCENT, M3.onPrimary) {
                dialog.dismiss()
                onUpdate.run()
            }
            button("稍后", M3.surfaceContainerHigh, M3.onSurface) {
                dialog.dismiss()
            }
            button("不再提醒", M3.surface, M3.onSurfaceTip) {
                dialog.dismiss()
                onDisable.run()
            }
        }

        // Whole dialog (title + changelog + buttons) scrolls as one so nothing gets
        // clipped off-screen on the round watch, no matter how long the changelog is.
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(FILL, WRAP))
        }

        val swipe = SwipeBackLayout(context).apply {
            addView(scroll, FILL, FILL)
            onSwipeBack = { dialog.dismiss() }
        }
        dialog.setContentView(swipe)
        dialog.setCancelable(true)
        dialog.window?.apply {
            // Transparent window background + full-screen layout so OUR padding controls the
            // insets, instead of the platform dialog's DPI-scaled margins.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
    }

    private fun GroupScope.button(label: String, bg: Int, fg: Int, onClick: () -> Unit) {
        add<TextView>()
            .text(label)
            .textSize(14f)
            .textColor(fg)
            .gravity(Gravity.CENTER)
            .width(FILL)
            .padding(top = 10.dp, bottom = 10.dp)
            .apply {
                background = GradientDrawable().apply {
                    setColor(bg)
                    cornerRadius = 22.dp.toFloat()
                }
            }
            .margin(top = 8.dp)
            .clickable(onClick)
    }
}
