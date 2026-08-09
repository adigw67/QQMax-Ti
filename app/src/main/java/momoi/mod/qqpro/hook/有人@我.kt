package momoi.mod.qqpro.hook

import androidx.core.text.buildSpannedString
import androidx.core.text.color
import com.tencent.qqnt.chats.core.adapter.holder.BaseChatViewHolder
import com.tencent.qqnt.chats.core.adapter.itemdata.BaseChatItem
import com.tencent.qqnt.chats.core.adapter.itemdata.RecentContactChatItem
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import com.tencent.qqnt.watch.chat.list.WatchRecentContactHolder
import com.tencent.qqnt.watch.chat.list.WatchRecentItemBuilder
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Colors
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

const val TOKEN = "\u200B\u200B\u200B\u200B\u200B"

/** peerUid -> the notify tag currently shown for it (e.g. "[有人@我]", "[有新文件]"). */
val notifyTagMap = mutableMapOf<String, String>()

/**
 * Fallback labels for the msgbox specific-event codes when the kernel doesn't ship a localized
 * `highlightDigest`. Codes come from QQ's own `AtTypeHelper.typeToStrMap`
 * (com.tencent.qqnt.chats.biz.summary.highlight.AtTypeHelper): 1000/1001=@我, 1002=回复,
 * 2000=@全体, 2001=新文件, 2003=作业, 2004=公告.
 */
private val eventTypeLabels = mapOf(
    1000 to "[有人@我]",
    1001 to "[有人@我]",
    1002 to "[有人回复我]",
    2000 to "[有人@全体成员]",
    2001 to "[有新文件]",
    2003 to "[有新作业]",
    2004 to "[有新公告]",
)

/**
 * Short label for a single msgbox specific-event message, or null when the event isn't an
 * actionable highlight (no digest AND an unrecognised [eventType] — e.g. DM type 1008, a generic
 * "new message" event whose msgSeq is a bogus 1). Returning null keeps such events OUT of the
 * in-chat jump-point stepper (SkipAction in 跳转第一条未读消息.kt), which otherwise labelled them
 * "[新消息]" and jumped to the very first message. The kernel's ready `highlightDigest` wins.
 */
fun specificEventLabel(eventType: Int, digest: String?): String? =
    digest?.takeIf { it.isNotEmpty() } ?: eventTypeLabels[eventType]

/**
 * Resolve the notify tag to show for a recent contact, or null for none.
 *
 * Primary signal: `originData.listOfSpecificEventTypeInfosInMsgBox` — the same authoritative,
 * Java-visible event list QQ's phone build reads in `AtHighLightSupplier`. Each entry carries an
 * `eventTypeInMsgBox` code plus (usually) a ready-to-display `highlightDigest`. We pick the most
 * recent event (highest msgSeq) we can label.
 *
 * Fallback: the legacy `notifiedType != 0 && atType == 6` heuristic, which is empirically proven to
 * mean "@我" on this watch build — kept in case the kernel doesn't populate the event list.
 */
private fun resolveNotifyLabel(info: RecentContactInfo): String? {
    val events = info.listOfSpecificEventTypeInfosInMsgBox
    if (events != null) {
        var bestSeq = Long.MIN_VALUE
        var bestLabel: String? = null
        for (e in events) {
            val last = e.msgInfos?.lastOrNull()
            val label = last?.highlightDigest?.takeIf { it.isNotEmpty() }
                ?: eventTypeLabels[e.eventTypeInMsgBox]
            val seq = last?.msgSeq ?: 0L
            if (label != null && seq >= bestSeq) {
                bestSeq = seq
                bestLabel = label
            }
        }
        if (bestLabel != null) return bestLabel
    }
    if (info.notifiedType != 0 && info.atType == 6) return "[有人@我]"
    return null
}

@Mixin
abstract class 有人at我 : WatchRecentItemBuilder() {
    override fun m(
        p0: BaseChatViewHolder<BaseChatItem?>,
        p1: RecentContactChatItem,
        p2: List<Any?>
    ) {
        super.m(p0, p1, p2)
        val tv = (p0 as WatchRecentContactHolder).b.c
        p0.itemView.clickable {
            m(p0, p1, p2)
            tv.text = tv.text.toString().removeBefore(TOKEN)
        }

        // Live unread count (UnreadInfo.count == item.i.b) — the SAME field that drives the red
        // badge. Must NOT use item.a.unreadCnt (originData): on a read, QQ pushes an UnreadPayload
        // that zeroes item.i.b (badge clears) but leaves originData.unreadCnt stale, which left the
        // tag stuck until a full rebind (list refresh / re-enter chat).
        val unread = p1.i.b
        val uid = p1.a.peerUid
        val label = if (unread > 0L) resolveNotifyLabel(p1.a) else null

        // Diagnostic: dump every signal so the real on-device codes can be confirmed from the log.
        if (unread > 0L) {
            val events = p1.a.listOfSpecificEventTypeInfosInMsgBox?.joinToString(",") { e ->
                "${e.eventTypeInMsgBox}:${e.msgInfos?.lastOrNull()?.highlightDigest}"
            }
            Utils.log(
                "notify-tag ${p1.a.peerName} unread=$unread atType=${p1.a.atType} " +
                    "notifiedType=${p1.a.notifiedType} events=[$events] -> $label"
            )
        }

        if (label != null) {
            notifyTagMap[uid] = label
        } else {
            notifyTagMap.remove(uid)
            tv.text = tv.text.toString().removeBefore(TOKEN)
        }

        notifyTagMap[uid]?.let { tag ->
            val tagColor = if (Settings.materialChatList.value) M3.error else Colors.atMe
            tv.text = buildSpannedString {
                color(tagColor) {
                    append(tag)
                }
                append(TOKEN)
                append(tv.text.toString().removeBefore(TOKEN))
            }
        }
    }
}

private fun String.removeBefore(token: String): String {
    val index = indexOf(token)
    return if (index != -1) substring(index + token.length) else this
}
