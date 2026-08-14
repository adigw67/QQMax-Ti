package momoi.mod.qqpro.hook.view

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import com.tencent.qqnt.msg.api.impl.MsgUtilApiImpl
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi

/**
 * 快捷回复：长按消息 → 快捷回复 → 从用户自定义短语列表选一条，
 * 以“引用原消息 + 文本”发送。文本按设置原样发送（保留空格，不做 trim）。
 */
class QuickReplyFragment(
    private val msg: MsgRecord,
    private val replies: List<String>,
) : MyDialogFragment() {

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).vertical()
        root.setBackgroundColor(M3.surface)

        root.addView(TextView(ctx).apply {
            text = "快捷回复"
            textSize = 15f
            setTextColor(M3.onSurface)
            gravity = Gravity.CENTER
            setPadding(0, 12.dp, 0, 8.dp)
        }, LinearLayout.LayoutParams(FILL, WRAP))

        val scroll = ScrollView(ctx).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(FILL, 0, 1f))
        val col = LinearLayout(ctx).vertical()
        col.setPadding(12.dp, 4.dp, 12.dp, 16.dp)
        scroll.addView(col)

        replies.forEach { reply ->
            col.addView(row(ctx, reply), LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = 6.dp })
        }
        return swipeBackWrap(root)
    }

    private fun row(ctx: android.content.Context, reply: String): View =
        TextView(ctx).apply {
            text = reply
            textSize = 14f
            setTextColor(M3.onSurface)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(M3.surfaceContainerHigh)
                cornerRadius = 12.dp.toFloat()
            }
            setOnClickListener { send(reply) }
        }

    private fun send(reply: String) {
        val contact = runCatching { Contact(msg.chatType, msg.peerUid, "") }.getOrNull()
        if (contact == null) {
            Utils.toast(requireContext(), "发送失败")
            dismiss()
            return
        }
        val elements = ArrayList<MsgElement>()
        runCatching {
            if (msg.msgId != 0L) {
                MsgUtilApiImpl.instance.createReplyElement(msg.msgId)?.let { elements.add(it) }
            }
            elements.add(MsgElement().apply {
                textElement = TextElement().apply { content = reply }
            })
        }.onFailure { Utils.log("QuickReply: build failed: $it") }
        if (elements.isEmpty()) {
            Utils.toast(requireContext(), "发送失败")
            dismiss()
            return
        }
        runCatching {
            MsgUtil.msgService.sendMsg(contact, 0L, elements, IOperateCallback { code, err ->
                Utils.log("QuickReply: send code=$code err=$err reply=${reply.take(20)}")
                runOnUi {
                    if (code == 0) Utils.toast(requireContext(), "已发送")
                    else Utils.toast(requireContext(), "发送失败")
                    dismiss()
                }
            })
        }.onFailure {
            Utils.log("QuickReply: send threw: $it")
            Utils.toast(requireContext(), "发送失败")
            dismiss()
        }
    }
}
