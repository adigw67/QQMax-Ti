package momoi.mod.qqpro.hook.summarize

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import momoi.mod.qqpro.enums.ElementType
import momoi.mod.qqpro.hook.action.SelfContact

/**
 * Turn live chat messages into the [Summarizer.Msg] list the worker expects (sender + flattened
 * text, media rendered as short placeholders so context isn't lost). Grey tips (recall/poke) and
 * empty messages are dropped.
 */
object SummaryMessages {

    fun from(items: List<WatchAIOMsgItem>): List<Summarizer.Msg> =
        items.mapNotNull { item ->
            val rec = item.d
            if (rec.elements.isEmpty()) return@mapNotNull null
            if (rec.elements[0].elementType == ElementType.GREY_TIP) return@mapNotNull null
            val text = textOf(rec).trim()
            if (text.isEmpty()) return@mapNotNull null
            Summarizer.Msg(senderName(item), text)
        }

    fun senderName(item: WatchAIOMsgItem): String {
        val rec = item.d
        if (rec.senderUid == SelfContact.peerUid) return "我"
        return sequenceOf(rec.sendMemberName, rec.sendRemarkName, rec.sendNickName, item.l?.toString())
            .firstOrNull { !it.isNullOrBlank() }
            ?: (rec.senderUid?.takeIf { it.isNotEmpty() } ?: rec.senderUin.toString())
    }

    /** Flattened text of a message, with media rendered as short placeholders. */
    private fun textOf(rec: MsgRecord): String {
        val sb = StringBuilder()
        for (e in rec.elements) {
            when (e.elementType) {
                ElementType.TEXT -> sb.append(e.textElement?.content ?: "")
                ElementType.PIC -> sb.append("[图片]")
                ElementType.VIDEO -> sb.append("[视频]")
                ElementType.FILE -> sb.append("[文件] ").append(e.fileElement?.fileName ?: "")
                ElementType.PTT -> sb.append("[语音]")
                ElementType.ARK -> sb.append("[卡片]")
                ElementType.MULTI_FORWARD -> sb.append("[聊天记录]")
                ElementType.MFACE, ElementType.FACE -> sb.append(e.marketFaceElement?.faceName ?: "[表情]")
                ElementType.SHARE_LOCATION -> sb.append("[位置]")
                ElementType.WALLET -> sb.append("[红包]")
                else -> {}
            }
        }
        return sb.toString().replace('\n', ' ')
    }
}
