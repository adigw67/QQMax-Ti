package momoi.mod.qqpro.hook

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.lib.material.leadingSymbol
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi

/**
 * 禁言成员专用整页。手表屏宽只有约 184dp，资料卡内联的「天/时/分+执行」横排会把执行按钮
 * 挤出屏幕外——所以资料卡上只放一个「禁言成员」按钮，点开这一页竖向输入时长并整行执行。
 */
object MemberMutePage {

    fun show(ctx: Context, memberUid: String, memberName: String) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 24.dp)
        }

        fun unit(t: String) = TextView(ctx).apply {
            text = t
            textSize = 13f
            setTextColor(M3.onSurfaceVariant)
            setPadding(3.dp, 0, 3.dp, 0)
        }
        fun num() = EditText(ctx).apply {
            textSize = 15f
            setTextColor(M3.onSurface)
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            setBackgroundColor(M3.surfaceContainer)
            layoutParams = LinearLayout.LayoutParams(40.dp, 36.dp)
        }

        panel.addView(
            TextView(ctx).apply {
                text = "禁言成员"
                textSize = 16f
                setTextColor(M3.onSurface)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 4.dp)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        panel.addView(
            TextView(ctx).apply {
                text = "$memberName（仅群主/管理员可操作）"
                textSize = 11f
                setTextColor(M3.onSurfaceVariant)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 18.dp)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val d = num(); row.addView(d); row.addView(unit("天"))
        val h = num(); row.addView(h); row.addView(unit("时"))
        val m = num(); row.addView(m); row.addView(unit("分"))
        panel.addView(row)
        panel.addView(
            TextView(ctx).apply {
                text = "提示：手表版服务端未开放“禁言单个成员”权限（-10122），执行大概率被拒；可改用「全员禁言」"
                textSize = 10f
                setTextColor(M3.hint)
                gravity = Gravity.CENTER
                setPadding(0, 10.dp, 0, 0)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        fun actionButton(label: String, variant: M3Button.Variant, onClick: () -> Unit): M3Button {
            val b = M3Button(ctx).apply {
                text = label
                this.variant(variant)
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 42.dp,
                ).apply { topMargin = 14.dp }
            }
            panel.addView(b)
            return b
        }

        val groupPeer = CurrentContact.peerUid
        actionButton("执行", M3Button.Variant.FILLED) {
            val days = d.text.toString().toIntOrNull() ?: 0
            val hours = h.text.toString().toIntOrNull() ?: 0
            val minutes = m.text.toString().toIntOrNull() ?: 0
            val seconds = (days * 24 + hours) * 60 + minutes * 60
            if (seconds <= 0) {
                Utils.toast(ctx, "请输入禁言时长")
                return@actionButton
            }
            GroupMute.setMemberMuted(groupPeer, memberUid, seconds) { ok, msg ->
                runOnUi {
                    Utils.toast(
                        ctx,
                        if (ok) "已禁言 ${days}天${hours}时${minutes}分" else "禁言失败 $msg",
                        longDuration = !ok,
                    )
                    if (ok) dialog.dismiss()
                }
            }
        }
        actionButton("取消", M3Button.Variant.TONAL) { dialog.dismiss() }

        // 键盘弹出时（ADJUST_RESIZE）把整页内容放进 ScrollView，按钮不会被顶出屏幕。
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(panel, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(M3.surface))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        dialog.show()
        d.requestFocus()
    }
}
