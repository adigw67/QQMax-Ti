package momoi.mod.qqpro.hook.view

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import momoi.mod.qqpro.lib.FILL
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

/**
 * Full-screen confirmation shown when a bare number (6–15 digits) is tapped in a
 * message. Confirming runs [onConfirm], which opens the add-friend/group search
 * pad prefilled with the number. Mirrors [LinkOpenFragment] (a windowed
 * AlertDialog renders broken on the round watch screen).
 */
class SearchNumberFragment(
    private val number: String,
    private val onConfirm: () -> Unit
) : MyDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        // Whole page scrolls: number + four action buttons can exceed the round watch screen.
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(M3.surface)
        }
        val root = LinearLayout(ctx)
            .vertical()
            .padding(20.dp)
        root.gravity = Gravity.CENTER
        scroll.addView(root, ViewGroup.LayoutParams(FILL, WRAP))

        root.content {
            add<TextView>()
                .text("搜索该号码")
                .textSize(16f)
                .textColor(M3.onSurface)
                .gravity(Gravity.CENTER)
                .padding(bottom = 10.dp)
            add<TextView>()
                .text(number)
                .textSize(15f)
                .textColor(M3.onSurfaceVariant)
                .gravity(Gravity.CENTER)
                .padding(bottom = 14.dp)

            button("搜索好友/群", ACCENT, M3.onPrimary) {
                onConfirm()
                dismiss()
            }
            button("拨打号码", 0xFF_4CAF50.toInt(), 0xFF_000000.toInt()) {
                momoi.mod.qqpro.util.Utils.dialNumber(number)
                dismiss()
            }
            button("复制号码", M3.outline, M3.onSurface) {
                momoi.mod.qqpro.util.Utils.copyToClipboard(ctx, number, "已复制号码")
                dismiss()
            }
            button("取消", M3.surfaceContainerHigh, M3.onSurface) {
                dismiss()
            }
        }
        return swipeBackWrap(scroll)
    }

    private fun momoi.mod.qqpro.lib.LinearScope.button(
        label: String,
        bg: Int,
        fg: Int,
        onClick: () -> Unit
    ) {
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
            .margin(top = 6.dp)
            .clickable(onClick)
    }
}
