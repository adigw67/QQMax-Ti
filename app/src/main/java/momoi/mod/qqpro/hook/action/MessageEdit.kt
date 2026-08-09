package momoi.mod.qqpro.hook.action

import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import moye.wearqq.AtElementArg
import moye.wearqq.IMEOperation
import moye.wearqq.ReplyElementArg
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.rebuildElementsForResend
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi

/**
 * Tracks the "edit my own message" flow.
 *
 * Tapping 编辑 in the long-press menu of one of your own messages opens the input method (like 复读)
 * pre-filled with the original content and shows a cancel banner (like 回复). The next send recalls
 * (撤回) the original message and sends the edited content in its place.
 *
 * The original content is staged into the shared [IMEOperation] channels — reply (extra), @-mentions
 * (extra), image(s) (extraMsg) and plain text (extraText) — exactly like the gallery / member-picker
 * flows do, so both the inline input ([momoi.mod.qqpro.hook.InlineInput.consumePending]) and the
 * native InputMethodFragment rebuild the full editable message, not just its text.
 */
object MessageEdit {
    /** id of the message currently being edited, or 0 when not editing. */
    var editingMsgId: Long = 0L
        private set

    /** Begin editing [msgId] with only plain [text] prefilled (legacy text-only entry point). */
    fun begin(msgId: Long, text: String) {
        editingMsgId = msgId
        IMEOperation.INSTANCE.clearExtra()
        IMEOperation.extraMsg.clear()
        IMEOperation.extraText = text
        runCatching { IMEOperation.INSTANCE.openIME() }
            .onFailure {
                editingMsgId = 0L
                Utils.log("message edit: openIME failed: $it")
            }
    }

    /**
     * Begin editing [msg], staging its full content (text + @ + reply + image) into the input.
     * Pictures must be downloaded + rebuilt into fresh sendable elements (received pics point at the
     * sender's local path and won't re-upload), which blocks, so that part runs off the UI thread.
     */
    fun beginFull(msg: MsgRecord) {
        editingMsgId = msg.msgId
        val elements = ArrayList(msg.elements ?: emptyList())
        if (elements.none { it.picElement != null }) {
            stageAndOpen(msg.msgId, elements)
        } else {
            Thread {
                val rebuilt = runCatching { rebuildElementsForResend(elements) }
                    .getOrElse { Utils.log("message edit: rebuild failed: $it"); elements }
                runOnUi { stageAndOpen(msg.msgId, rebuilt) }
            }.start()
        }
    }

    /** Stage [elements] into IMEOperation and open the input prefilled for editing [msgId]. */
    private fun stageAndOpen(msgId: Long, elements: List<MsgElement>) {
        editingMsgId = msgId
        IMEOperation.INSTANCE.clearExtra()
        IMEOperation.extraMsg.clear()

        // Reply first, so consumePending (which reverses the staging order) applies it before the @s.
        val reply = elements.firstNotNullOfOrNull { it.replyElement }
        reply?.let { r ->
            IMEOperation.INSTANCE.setExtra(
                ReplyElementArg(r.replayMsgId, r.senderUidStr ?: "", r.sourceMsgText ?: "", "", "")
            )
        }
        // When 回复带@ is on for a group reply, consumePending's setReply re-adds the @sender token
        // itself; skip the original @sender element here so it isn't duplicated.
        val skipAtUid = if (reply != null && Settings.replyWithAt.value && CurrentContact.isGroup)
            reply.senderUidStr else null

        val textSb = StringBuilder()
        for (ele in elements) {
            val te = ele.textElement
            when {
                // An @-mention is a TextElement with atType != 0 (content == "@nick", atNtUid == uid).
                te != null && te.atType != 0 -> {
                    val uid = te.atNtUid ?: ""
                    if (uid.isNotEmpty() && uid == skipAtUid) continue
                    val nick = (te.content ?: "").removePrefix("@")
                    IMEOperation.INSTANCE.setExtra(AtElementArg(uid, nick, ""))
                }
                te != null -> textSb.append(te.content ?: "")
                ele.picElement != null -> IMEOperation.extraMsg.add(ele)
            }
        }
        IMEOperation.extraText = textSb.toString()
        Utils.log("message edit: staged msgId=$msgId text='${textSb.take(20)}' pics=${IMEOperation.extraMsg.size} extra=${IMEOperation.INSTANCE.extra.size}")
        runCatching { IMEOperation.INSTANCE.openIME() }
            .onFailure {
                editingMsgId = 0L
                Utils.log("message edit: openIME failed: $it")
            }
    }

    /** Consume and clear the current edit state, returning the message id (0 if none). */
    fun consume(): Long {
        val id = editingMsgId
        editingMsgId = 0L
        return id
    }
}
