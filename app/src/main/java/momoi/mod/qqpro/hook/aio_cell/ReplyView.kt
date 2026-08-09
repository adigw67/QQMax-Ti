package momoi.mod.qqpro.hook.aio_cell

import android.content.Context
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.ReplyElement
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Colors
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.fitEmojiSpans
import momoi.mod.qqpro.join
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.background
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.lib.marginHorizontal
import momoi.mod.qqpro.lib.paddingHorizontal
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.vertical

@Mixin
class ReplyElementEx : ReplyElement() {
    var senderName: String? = null
    var content: CharSequence? = null
}

class ReplyView(context: Context) : LinearLayout(context) {
    private lateinit var mTvName: TextView
    private lateinit var mTvContent: TextView
    private var currentMsgSeq: Long = 0

    init {
        // Explicit MATCH_PARENT layout params so the reply quote fills the bubble width the MESSAGE
        // TEXT decides (LinearLayout re-measures match-width children to the column's wrap width),
        // instead of hugging the short quote text. Set here (not via the .width()/.marginHorizontal()
        // helpers) because at construction time the view has no layoutParams yet — .width() would NPE
        // and .margin() would silently no-op.
        this.layoutParams = LinearLayout.LayoutParams(FILL, WRAP).apply {
            leftMargin = 2.dp; rightMargin = 2.dp
        }
        this.vertical()
            .background(roundCornerDrawable(Colors.replyBackground, Settings.bubbleCornerRadius.value.dpf))
            // Inset the text by ~half the corner radius so it clears the rounded corners.
            .paddingHorizontal(maxOf(2.dp, (Settings.bubbleCornerRadius.value * 0.6f).dpf.toInt()))
            .content {
                mTvName = add<TextView>()
                    .textSize(10f * Settings.chatScale.value)
                    .textColor(Colors.replyText)
                mTvContent = add<TextView>()
                    .textSize(12f * Settings.chatScale.value)
                    .textColor(Colors.replyText)
                    .apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }
            }
    }

    fun loadData(contact: Contact, replyElement: ReplyElement) {
        val reply = replyElement as ReplyElementEx
        currentMsgSeq = reply.replayMsgSeq
        if (reply.senderName == null) {
            val sendTime = Utils.formatTime(reply.replyMsgTime * 1000)
            reply.content = reply.sourceMsgTextElems.join { it.textElemContent ?: " " }
            reply.senderName = "${reply.senderUid} $sendTime"
            MsgUtil.msgService.getSingleMsg(contact, reply.replayMsgSeq) { _, _, msgRecords ->
                msgRecords?.getOrNull(0)?.let {
                    reply.senderName = buildString {
                        append(it.sendMemberName.ifEmpty { it.sendNickName })
                        append(" ")
                        append(sendTime)
                    }
                    reply.content = MsgUtil.summary(it)
                    if (currentMsgSeq == reply.replayMsgSeq) {
                        post {
                            loadData(contact, reply)
                        }
                    }
                }
            }
        }
        mTvName.text = reply.senderName
        // The summary's face emoji are baked at QQ's default chat size; shrink them to match this
        // smaller quote text so they don't tower over the words. Absolute fit → no compounding even
        // though reply.content is cached and re-bound on recycle.
        fitEmojiSpans(reply.content, mTvContent.textSize)
        mTvContent.text = reply.content
    }
}