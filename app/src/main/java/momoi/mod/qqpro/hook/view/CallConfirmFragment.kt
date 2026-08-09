package momoi.mod.qqpro.hook.view

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import momoi.mod.qqpro.lib.FILL
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
import momoi.mod.qqpro.util.Utils

private val ACCENT get() = M3.primary

/**
 * Full-screen confirmation shown before starting a voice / video call, to avoid accidental
 * triggers from the chat "+" panel. Mirrors [LinkOpenFragment]. On confirm it replays the
 * original click on [action] (the call item's icon), which carries the native call logic — so
 * we don't have to re-implement goToAVScene. Passing a View (not a lambda) keeps this safe to
 * construct from the @Mixin panel method.
 */
class CallConfirmFragment(
    private val title: String,
    private val action: View,
) : MyDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx)
            .vertical()
            .padding(20.dp)
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(M3.surface)

        root.content {
            add<TextView>()
                .text(title)
                .textSize(16f)
                .textColor(M3.onSurface)
                .gravity(Gravity.CENTER)
                .padding(bottom = 16.dp)

            button("确定", ACCENT, M3.onPrimary) {
                Utils.log("call confirm: confirmed")
                dismiss()
                action.performClick()
            }
            button("取消", M3.surfaceContainerHigh, M3.onSurface) {
                dismiss()
            }
        }
        return swipeBackWrap(root)
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
