package momoi.mod.qqpro.hook

import com.huanli233.qplus.utils.TextUtilKt
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.msg.api.impl.MsgServiceImpl
import com.tencent.qqnt.msg.api.impl.MsgUtilApiImpl
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.isGroup
import momoi.mod.qqpro.util.Utils
import moye.wearqq.AtElementArg
import moye.wearqq.IMEOperation
import moye.wearqq.ReplyElementArg

@Mixin
class ReplyWithAt : MsgServiceImpl() {
    override fun sendMsg(
        peer: Contact,
        msgId: Long,
        msgElements: ArrayList<MsgElement>,
        listener: IOperateCallback?
    ) {
        for (obj in IMEOperation.INSTANCE.extra) {
            if (obj is ReplyElementArg) {
                val replyEl = MsgUtilApiImpl.instance.createReplyElement(obj.replayMsgId)
                replyEl.replyElement?.let { it.senderUid = obj.senderUidStr.toLongOrNull() ?: 0L }
                msgElements.add(0, replyEl)

                if (Settings.replyWithAt.value && CurrentContact.isGroup) {
                    val nick = TextUtilKt.b64Decode(obj.senderNickname)
                    val atEl = MsgUtilApiImpl.instance.createAtTextElement(
                        "@$nick", obj.senderUidStr, 2
                    )
                    msgElements.add(1, atEl)
                    // Ensure a space before the user's typed text that follows the AT element.
                    val firstText = msgElements.getOrNull(2)?.textElement
                    if (firstText != null && !firstText.content.startsWith(" ")) {
                        firstText.content = " " + firstText.content
                    }
                    Utils.log("ReplyWithAt: added @$nick for group reply")
                }
            } else if (obj is AtElementArg) {
                val nick = TextUtilKt.b64Decode(obj.atNickname)
                msgElements.add(
                    msgElements.size,
                    MsgUtilApiImpl.instance.createAtTextElement("@$nick ", obj.atUid, 2)
                )
            }
        }
        for (extra in IMEOperation.extraMsg) {
            msgElements.add(msgElements.size, extra)
        }
        IMEOperation.INSTANCE.clearExtra()
        IMEOperation.extraMsg.clear()
        sendMsg_old(peer, msgId, msgElements, listener)
    }
}
