package momoi.mod.qqpro.hook.style

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ext.MsgListUtilKt
import com.tencent.watch.aio_impl.ui.cell.UnSupportWatchAIOMsgItem
import momoi.anno.mixin.StaticHook
import momoi.mod.qqpro.enums.ElementType
import momoi.mod.qqpro.util.Utils

@StaticHook(MsgListUtilKt::class)
fun c(msg: MsgRecord): WatchAIOMsgItem {
    val reply = msg.elements.firstOrNull { it.replyElement != null }
    if (reply == null) {
        val result = MsgListUtilKt.c(msg)
        dumpUnsupported(msg, result)
        // Official-bot / markdown / ark messages fall into the watch's "unsupported" cell
        // ("[暂不支持该消息类型，请用手机QQ查看]"). When the record carries renderable text (a plain
        // text element or a markdown body), rebuild it as a normal text message so bot replies are
        // actually viewable on the watch.
        return renderBotText(msg, result)
    }
    msg.elements.remove(reply)
    val rawType = msg.msgType
    msg.msgType = 2
    val result = MsgListUtilKt.c(msg)
    msg.elements.add(0, reply)
    msg.msgType = rawType
    return result
}

/**
 * Re-render a message that the watch routed to the unsupported cell as plain text.
 * Keeps the FIRST renderable body (text element preferred over markdown), drops the rest
 * (ark / inline keyboard / …) so the native text cell can draw it. No-op when the record
 * carries nothing renderable, or when [result] is already a supported item.
 */
private fun renderBotText(msg: MsgRecord, result: WatchAIOMsgItem): WatchAIOMsgItem {
    if (result !is UnSupportWatchAIOMsgItem) return result
    val textEl = msg.elements.firstOrNull {
        it.elementType == ElementType.TEXT && it.textElement?.content?.isNotBlank() == true
    }
    val mdEl = msg.elements.firstOrNull {
        it.elementType == ElementType.MARKDOWN && it.markdownElement?.content?.isNotBlank() == true
    }
    val keep = textEl ?: mdEl ?: return result

    val savedType = msg.msgType
    val savedElements = msg.elements
    return try {
        msg.elements.clear()
        msg.elements.add(keep)
        msg.msgType = 2 // text message → native text cell
        MsgListUtilKt.c(msg)
    } catch (t: Throwable) {
        Utils.log("renderBotText failed: $t")
        result
    } finally {
        msg.elements = savedElements
        msg.msgType = savedType
    }
}

/**
 * Diagnostic: dump the full structure of any message that the watch routes to
 * the "unsupported message" cell, so we can identify what a group-invite (and
 * other unsupported) message actually carries (ark/struct/longmsg/file) and
 * build a precise renderer. Read via qqpro_debug.log.
 */
private fun dumpUnsupported(msg: MsgRecord, result: WatchAIOMsgItem) {
    try {
        // Skip the common, already-rendered cases (plain text / face only) to keep
        // the log light; dump everything else along with the resulting item class.
        val onlyTextFace = msg.msgType == 2 && msg.elements?.all {
            it.elementType == 1 || it.elementType == 2 || it.elementType == 6
        } == true
        if (onlyTextFace) return
        val sb = StringBuilder("MSGDUMP result=${result.javaClass.simpleName} msgType=${msg.msgType} subType=${msg.subMsgType} elems=${msg.elements?.size}")
        msg.elements?.forEachIndexed { i, e ->
            sb.append("\n  [$i] elementType=${e.elementType}")
            e.arkElement?.let { sb.append(" ark.sub=${it.subElementType} ark.bytesData=${it.bytesData}") }
            e.structMsgElement?.let { sb.append(" struct.xml=${it.xmlContent}") }
            e.structLongMsgElement?.let { sb.append(" longmsg.resId=${it.resId} longmsg.xml=${it.xmlContent}") }
            e.grayTipElement?.let { sb.append(" grayTip.subType=${it.subElementType}") }
            e.fileElement?.let { sb.append(" file.name=${it.fileName} file.size=${it.fileSize}") }
            e.walletElement?.let { sb.append(" wallet=present") }
        }
        Utils.log(sb.toString())
    } catch (e: Exception) {
        Utils.log("dumpUnsupported error: ${e.message}")
    }
}
