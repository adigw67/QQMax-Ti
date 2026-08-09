package momoi.mod.qqpro.hook

import android.view.View
import androidx.fragment.app.Fragment
import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IClearMsgRecordsCallback
import com.tencent.qqnt.watch.troop.ui.setting.ClearMsgItem
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.hook.view.ConfirmFragment
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import mqq.app.MobileQQ

/**
 * Fixes a native crash in group settings' 清空聊天记录 ([ClearMsgItem]).
 *
 * The native `onClick` confirms via a full-screen TipsFragment, then asynchronously calls the kernel
 * `clearMsgRecords`; on success it posts a UI Runnable that does `getFragment().requireContext()` to
 * show a `WatchToast`. By the time that Runnable runs the `TroopSettingFragment` can already be
 * detached (the confirm navigation pauses it and the kernel callback is async), so `requireContext()`
 * throws `IllegalStateException: not attached to a context` and the app crashes.
 *
 * We replace `onClick` with the same confirm-then-clear flow but show the result toast on the
 * lifecycle-safe application context (never detached) — exactly like the conversation-list 清空消息
 * (`ClearMsgListItem`) already does. The body delegates to a top-level helper so the lambdas /
 * anonymous classes it needs are NOT compiled into the @Mixin body (which would crash with
 * IllegalAccessError).
 */
@Mixin
class ClearMsgItemSafe(fragment: Fragment, uid: String) : ClearMsgItem(fragment, uid) {
    override fun onClick(v: View?) {
        confirmClearTroopMsg(this)
    }
}

private fun confirmClearTroopMsg(item: ClearMsgItem) {
    // `uid` is a private field on ClearMsgItem (no getter) → read it reflectively.
    val uid = runCatching {
        ClearMsgItem::class.java.getDeclaredField("uid").apply { isAccessible = true }.get(item) as? String
    }.getOrNull()
    if (uid.isNullOrEmpty()) { Utils.log("ClearMsgItemSafe: no uid, skipping"); return }
    runCatching {
        ConfirmFragment(
            title = "确定要清空聊天记录吗？",
            confirmLabel = "清空",
            destructive = true,
        ) { clearTroopMsg(uid) }
            .show(item.fragment.parentFragmentManager, "qqpro_clear_troop_msg")
    }.onFailure { Utils.log("ClearMsgItemSafe: confirm show failed: $it") }
}

private fun clearTroopMsg(uid: String) {
    runCatching {
        val app = MobileQQ.sMobileQQ.peekAppRuntime() ?: run { Utils.log("ClearMsgItemSafe: no app"); return }
        val ks = app.getRuntimeService(IKernelService::class.java, "") as? IKernelService
        val msgService = ks?.msgService ?: run { Utils.log("ClearMsgItemSafe: no msgService"); return }
        // chatType 2 = group, mirroring the native `new Contact(2, uid, "")`.
        msgService.clearMsgRecords(Contact(2, uid, ""), IClearMsgRecordsCallback { code, msg, count ->
            Utils.log("ClearMsgItemSafe clearMsgRecords: code=$code msg=$msg count=$count")
            // Application context — safe even if the settings fragment has since detached.
            runOnUi { Utils.toast(Utils.application, if (code == 0) "已清空" else "清空失败") }
        })
    }.onFailure { Utils.log("ClearMsgItemSafe.clear failed: $it") }
}
