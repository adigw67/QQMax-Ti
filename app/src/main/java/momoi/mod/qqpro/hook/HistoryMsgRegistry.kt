package momoi.mod.qqpro.hook

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import java.util.concurrent.ConcurrentHashMap

/**
 * Records shown inside a 合并转发聊天记录 (chat-history) viewer aren't part of [
 * momoi.mod.qqpro.hook.action.CurrentMsgList], so the long-press menu hook can't resolve them by
 * `key_msg_id`. The history viewer registers its loaded inner records here; the menu hook falls back
 * to this map so 系统分享 / 转发 work for items inside a chat history too.
 */
object HistoryMsgRegistry {
    private val map = ConcurrentHashMap<Long, MsgRecord>()

    fun register(records: List<MsgRecord>) {
        records.forEach { it?.let { r -> map[r.msgId] = r } }
    }

    fun find(msgId: Long): MsgRecord? = map[msgId]
}
