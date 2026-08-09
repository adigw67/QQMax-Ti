package momoi.mod.qqpro.hook

import android.content.Context
import android.view.View
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.watch.contact.api.IContactRuntimeService
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.biz.richframework.util.RFWSaveUtil
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import WatchPicElementExtKt
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.aio_cell.doAddFavEmoji
import momoi.mod.qqpro.hook.menu.MsgCapabilities
import momoi.mod.qqpro.hook.screenshot.ChatScreenshot
import momoi.mod.qqpro.hook.summarize.SummaryMessages
import momoi.mod.qqpro.hook.summarize.SummaryStore
import momoi.mod.qqpro.hook.summarize.SummaryViewer
import momoi.mod.qqpro.hook.translate.MessageTranslate
import momoi.mod.qqpro.hook.view.PartialCopyFragment
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import mqq.app.MobileQQ
import java.io.File

/**
 * Batch executors for 消息多选 (see [ChatMultiSelect]). WHICH options appear is decided by reusing the
 * shared per-message option set (`menu.buildMessageActions`) and intersecting it
 * over the selection — this map only says HOW a given option runs on the whole selection. A label
 * with no entry here is never shown in batch (single-only actions excluded automatically).
 *
 * Executors receive the live [WatchAIOMsgItem]s (not bare records) so pic-bearing ones can do the
 * kernel original-file download. They reuse existing helpers (copy / partial-copy / repeat / share /
 * forward / save / fav); forward/delete/recall touch the kernel only because there's no other path.
 */
val batchExecutors: Map<String, (View, List<WatchAIOMsgItem>) -> Unit> = mapOf(
    "复制" to { v, items -> Utils.copyToClipboard(v.context, joinText(items)) },
    "部分复制" to { v, items ->
        val text = joinText(items)
        val fm = AttachmentOverlay.aioFragment(v)?.let { runCatching { it.parentFragmentManager }.getOrNull() }
        if (fm != null) runCatching { PartialCopyFragment(text).show(fm, "qqpro_ms_partial_copy") }
            .onFailure { Utils.copyToClipboard(v.context, text) }
        else Utils.copyToClipboard(v.context, text)
    },
    "转发" to { v, items -> v.batchForward(items) },
    "复读" to { _, items -> items.forEach { repeatMsgRecord(it.d, it) } },
    "系统分享" to { v, items -> v.shareMessages(items.map { it.d }) },
    "保存" to { v, items ->
        batchPicFiles(v.context, items) { ctx, files ->
            var ok = 0
            files.forEach { f -> runCatching { RFWSaveUtil.a(ctx, f.path, null); ok++ } }
            Utils.toast(ctx, if (ok > 0) "已保存 $ok 张到相册" else "保存失败")
        }
    },
    "收藏" to { v, items ->
        batchPicFiles(v.context, items) { ctx, files ->
            files.forEach { f -> runCatching { doAddFavEmoji(ctx, f) } }
            Utils.toast(ctx, "已收藏 ${files.size} 张")
        }
    },
    "截图" to { v, items -> ChatScreenshot.capture(v, items.map { it.d.msgId }) },
    "总结" to { v, items -> v.summarizeSelected(items) },
    "翻译" to { _, items -> items.forEach { MessageTranslate.setManual(it.d, true) } },
    "隐藏翻译" to { _, items -> items.forEach { MessageTranslate.setManual(it.d, false) } },
    "撤回" to { v, items ->
        items.forEach { item ->
            runCatching { KernelServiceUtil.c()?.recallMsg(CurrentContact, item.d.msgId, null) }
                .onFailure { Utils.log("batch recall failed id=${item.d.msgId}: $it") }
        }
        Utils.toast(v.context, "已撤回 ${items.size} 条")
    },
    "删除" to { v, items -> batchDelete(v.context, items.map { it.d }) },
)

/**
 * Join the selected messages' copy-text with a BLANK line (two newlines) between each. Uses the shared
 * [MsgCapabilities.copyText] (text → ark rich text → file name) so batch copy matches single-message copy.
 * When [Settings.multiSelectCopySender] is on, each message is prefixed with its sender's name
 * ("名字：内容"), matching QQ's merged-forward copy format.
 */
private fun joinText(items: List<WatchAIOMsgItem>): String = items.joinToString("\n\n") { item ->
    val body = MsgCapabilities(item.d, item).copyText.orEmpty()
    if (Settings.multiSelectCopySender.value) "${SummaryMessages.senderName(item)}：$body" else body
}.trim()

// ── executors ───────────────────────────────────────────────────────────────────

/**
 * Forward every selected message to chosen targets. Opens the friend selector ONCE, then re-sends
 * each message's (rebuilt) elements to each target — mirrors [forwardMsgRecord] but loops the
 * selection. Threads each item's [WatchAIOMsgItem] so [rebuildElementsForResend] can kernel-download
 * pics that were never opened full-screen (fixes "rich media transfer failed").
 */
private fun View.batchForward(items: List<WatchAIOMsgItem>) {
    val navFragment = WatchPicElementExtKt.W(this)?.let { WatchPicElementExtKt.Y(it) } ?: run {
        Utils.log("batchForward: no nav fragment"); return
    }
    val app = MobileQQ.getMobileQQ().peekAppRuntime() ?: run { Utils.log("batchForward: no app"); return }
    val contactService = app.getRuntimeService(IContactRuntimeService::class.java, "")
    contactService.startFriendSelect(
        navFragment, emptyList(), arrayListOf(app.currentUid),
        "转发 ${items.size} 条", 0x7e0805cd, 1, 10, null, false, true
    ) { _, friends ->
        if (friends.isNotEmpty()) {
            Thread {
                val built = items.map { rebuildElementsForResend(ArrayList(it.d.elements ?: emptyList()), it) }
                friends.forEach { friend ->
                    val dst = Contact(if (friend.e) 2 else 1, friend.b, "")
                    built.forEach { elements ->
                        MsgUtil.msgService.sendMsg(dst, 0L, elements,
                            IOperateCallback { code, m -> Utils.log("batchForward result=$code msg=$m peer=${dst.peerUid}") })
                    }
                }
            }.start()
        }
        kotlin.Unit
    }
}

/** Summarize the selected messages (oldest→newest) via a streaming [SummaryViewer]. */
private fun View.summarizeSelected(items: List<WatchAIOMsgItem>) {
    val ordered = items.sortedBy { it.d.msgSeq }
    val apiMsgs = SummaryMessages.from(ordered)
    if (apiMsgs.isEmpty()) { Utils.toast(context, "没有可总结的消息"); return }
    val fm = AttachmentOverlay.aioFragment(this)?.let { runCatching { it.parentFragmentManager }.getOrNull() }
    if (fm == null) { Utils.log("summarizeSelected: no fragment manager"); return }
    val key = SummaryStore.keyOf(CurrentContact.chatType, CurrentContact.peerUid)
    runCatching {
        SummaryViewer.live(apiMsgs, "选中 ${apiMsgs.size} 条", key).show(fm, "qqpro_summary")
    }.onFailure { Utils.log("summarizeSelected: open failed: $it") }
}

/** Local-delete every selected message (kernel delete by id list + live-remove from the open list). */
private fun batchDelete(ctx: Context, msgs: List<MsgRecord>) {
    val first = msgs.firstOrNull() ?: return
    val ids = ArrayList(msgs.map { it.msgId })
    val contact = Contact(first.chatType, first.peerUid, "")
    runCatching {
        MsgUtil.msgService.deleteMsg(contact, ids, IOperateCallback { code, reason ->
            Utils.log("batchDelete: ${ids.size} ids code=$code reason=$reason")
            runOnUi {
                if (code == 0) { CurrentMsgList.removeLive(ids.toSet()); Utils.toast(ctx, "已删除 ${ids.size} 条") }
                else Utils.toast(ctx, "删除失败")
            }
        })
    }.onFailure { Utils.log("batchDelete failed: $it"); Utils.toast(ctx, "删除失败") }
}

/**
 * Resolve the (first) picture file of each selected item off the UI thread via the robust kernel
 * resolver ([resolveOriginalPicFile]: on-disk → cache → kernel download → HTTP), then deliver the
 * files to [use] on the UI thread. Threading the item enables the kernel download for un-opened pics.
 */
private fun batchPicFiles(ctx: Context, items: List<WatchAIOMsgItem>, use: (Context, List<File>) -> Unit) {
    Thread {
        val files = items.mapNotNull { item ->
            val el = item.d.elements?.firstOrNull { it.picElement != null } ?: return@mapNotNull null
            resolveOriginalPicFile(ctx, el, item)
        }
        runOnUi { use(ctx, files) }
    }.start()
}
