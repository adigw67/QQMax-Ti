package momoi.mod.qqpro.hook

import androidx.fragment.app.Fragment
import com.tencent.qqnt.chats.core.adapter.itemdata.RecentContactChatItem
import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.api.IMsgService
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IClearMsgRecordsCallback
import com.tencent.qqnt.watch.chat.list.`WatchRecentItemBuilder$listener$1`
import com.tencent.qqnt.watch.chat.list.longclick.DeleteItem
import com.tencent.qqnt.watch.chat.list.longclick.SetDisturbItem
import com.tencent.qqnt.watch.chat.list.longclick.SetTopItem
import com.tencent.qqnt.watch.ui.componet.AbsItem
import com.tencent.qqnt.watch.ui.componet.select.SelectDialogFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.hook.view.ConfirmFragment
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import mqq.app.MobileQQ

/**
 * A 清空消息 (clear all messages) row for the conversation-list long-press menu. The native menu only
 * offers 删除 / 置顶 / 免打扰 — clearing a chat's history is otherwise buried in chat settings. Reuses the
 * native [AbsItem] row chrome (icon + label) so it looks identical to the other entries.
 *
 * On tap it confirms (destructive) and then calls [IMsgService.clearMsgRecords] with a [Contact] built
 * from the row's own chatType/peerUid (the native group-settings ClearMsgItem hardcodes chatType=2, so
 * we build our own to also cover 1-on-1 chats).
 */
class ClearMsgListItem(
    private val fragment: Fragment,
    private val item: RecentContactChatItem,
) : AbsItem(null as String?) {

    override fun getIconResId(): Int =
        Utils.application.resources.getIdentifier("icon_delete_friend", "drawable", Utils.application.packageName)

    override fun getText(): Int =
        Utils.application.resources.getIdentifier("clear_msg", "string", Utils.application.packageName)

    override fun onClick(v: android.view.View?) {
        val info = item.a // RecentContactInfo
        val chatType = info.chatType
        val peerUid = info.peerUid
        ConfirmFragment(
            title = "确定清空该聊天的全部消息？",
            confirmLabel = "清空",
            destructive = true,
        ) { clear(chatType, peerUid) }
            .show(fragment.parentFragmentManager, "qqpro_clear_msg_confirm")
    }

    private fun clear(chatType: Int, peerUid: String) {
        runCatching {
            val app = MobileQQ.sMobileQQ.peekAppRuntime() ?: run { Utils.log("ClearMsgListItem: no app"); return }
            val ks = app.getRuntimeService(IKernelService::class.java, "") as? IKernelService
            val msgService = ks?.msgService ?: run { Utils.log("ClearMsgListItem: no msgService"); return }
            val contact = Contact(chatType, peerUid, "")
            msgService.clearMsgRecords(contact, IClearMsgRecordsCallback { code, msg, count ->
                Utils.log("clearMsgRecords result: code=$code, msg=$msg, count=$count")
                if (code == 0) runOnUi { Utils.toast(Utils.application, "已清空") }
                else runOnUi { Utils.toast(Utils.application, "清空失败") }
            })
        }.onFailure { Utils.log("ClearMsgListItem.clear failed: $it") }
    }
}

/**
 * Append a 清空消息 row to the conversation-list long-press menu. The native handler (the anonymous
 * `listener` field's `a()`) shows a [SelectDialogFragment] containing 删除 / 置顶 / 免打扰. We rebuild the
 * same dialog with our [ClearMsgListItem] added. Field `a` is the captured outer builder; its `d` field
 * is the current chat [Fragment] (used as the host for our confirm dialog).
 */
@Mixin
class ChatListLongClickMenu : `WatchRecentItemBuilder$listener$1`() {
    override fun a(recentContactChatItem: RecentContactChatItem) {
        val fragment = this.a?.d ?: return
        val items = arrayListOf<AbsItem>(
            DeleteItem(recentContactChatItem),
            SetTopItem(recentContactChatItem),
            SetDisturbItem(recentContactChatItem),
            ClearMsgListItem(fragment, recentContactChatItem),
        )
        SelectDialogFragment(items, null, null, 6)
            .show(fragment.parentFragmentManager, "SelectFragment")
    }
}
