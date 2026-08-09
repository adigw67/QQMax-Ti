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
import momoi.mod.qqpro.lib.LinearScope
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

/**
 * Generic Material confirmation dialog (title/message + 确定/取消). Mirrors [CallConfirmFragment]
 * but takes a lambda instead of a View, so it works for any action. [destructive] makes the
 * confirm button red (e.g. delete).
 */
class ConfirmFragment(
    private val title: String,
    private val confirmLabel: String = "确定",
    private val destructive: Boolean = false,
    private val cancelLabel: String = "取消",
    private val onCancel: () -> Unit = {},
    // Info mode: show only the confirm button (e.g. a 返回/重试 acknowledgement dialog).
    private val singleButton: Boolean = false,
    private val onConfirm: () -> Unit,
) : MyDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).vertical().padding(20.dp)
        root.gravity = Gravity.CENTER
        root.content {
            add<TextView>()
                .text(title)
                .textSize(15f)
                .textColor(M3.onSurface)
                .gravity(Gravity.CENTER)
                .padding(bottom = 16.dp)
            val confirmBg = if (destructive) M3.error else M3.primary
            button(confirmLabel, confirmBg, M3.onColor(confirmBg)) {
                dismiss(); runCatching { onConfirm() }
            }
            if (!singleButton) button(cancelLabel, M3.surfaceContainerHigh, M3.onSurface) {
                dismiss(); runCatching { onCancel() }
            }
        }
        // Wrap so the message + buttons scroll on a small round screen.
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(M3.surface)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return swipeBackWrap(scroll)
    }

    private fun LinearScope.button(label: String, bg: Int, fg: Int, onClick: () -> Unit) {
        add<TextView>()
            .text(label)
            .textSize(14f)
            .textColor(fg)
            .gravity(Gravity.CENTER)
            .width(FILL)
            .padding(top = 10.dp, bottom = 10.dp)
            .apply {
                background = GradientDrawable().apply { setColor(bg); cornerRadius = 22.dp.toFloat() }
            }
            .margin(top = 6.dp)
            .clickable(onClick)
    }
}
