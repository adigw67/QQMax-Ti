package momoi.mod.qqpro.hook

import android.view.View
import androidx.fragment.app.Fragment
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.watch.contact.api.IContactRuntimeService
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.ime.util.ImeTextUtil
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.child
import momoi.mod.qqpro.safeCacheDir
import download
import momoi.mod.qqpro.msg.getImageUrl
import momoi.mod.qqpro.util.Utils
import mqq.app.MobileQQ
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Open QQ's friend selector, then send the built elements to each chosen target — a real "forward"
 * to another chat (unlike RepeatMsg/复读 which only resends in the current chat). Mirrors the native
 * DefaultMenuHandler.doSharePic flow but works for any element list.
 *
 * jar-obfuscated FriendSelectData fields: b = uid, e = isGroup.
 * 0x7e0805cd = R.drawable.icon_share.
 */
fun View.forwardToFriends(title: String = "转发", buildElements: () -> ArrayList<MsgElement>) =
    forwardElementsVia(WatchPicElementExtKt.W(this)?.let { WatchPicElementExtKt.Y(it) }, title, buildElements)

/**
 * Like [forwardToFriends] but takes the nav [fragment] directly instead of resolving it from a View.
 * Needed when the forward happens AFTER an async detour (e.g. the image editor): by the time the
 * result comes back the original host View may be detached, so the caller captures the nav fragment
 * up-front (while attached) and passes it here.
 */
fun forwardElementsVia(fragment: Fragment?, title: String = "转发", buildElements: () -> ArrayList<MsgElement>) {
    if (fragment == null) {
        Utils.log("forwardElementsVia: no nav fragment")
        return
    }
    val app = MobileQQ.getMobileQQ().peekAppRuntime() ?: return
    val contactService = app.getRuntimeService(IContactRuntimeService::class.java, "")
    contactService.startFriendSelect(
        fragment,
        emptyList(),
        arrayListOf(app.currentUid),
        title,
        0x7e0805cd,
        1, 10, null, false, true
    ) { _, friends ->
        if (friends.isNotEmpty()) {
            val elements = buildElements()
            friends.forEach { friend ->
                val contact = Contact(if (friend.e) 2 else 1, friend.b, "")
                MsgUtil.msgService.sendMsg(
                    contact, 0L, elements,
                    IOperateCallback { code, msg -> Utils.log("forward send result=$code msg=$msg") }
                )
            }
        }
        kotlin.Unit
    }
}

/** Forward plain text to selected friends/groups. */
fun View.forwardText(text: CharSequence) = forwardToFriends {
    ImeTextUtil.a.b(text.toString())
}

/**
 * Open the friend selector and forward a message to each chosen target by RE-SENDING its original
 * elements via [MsgServiceImpl.sendMsg] (the same permitted path text/camera forwarding uses).
 *
 * NOTE: the kernel's "proper" [forwardMsg] API is gated on this watch product — it returns code=0
 * with an empty result map and delivers nothing (same restriction as getMemberExtInfo's -10122).
 * Re-sending elements works only for self-contained media that carry server-side references
 * (video / voice / sticker …). File / 合并转发聊天记录 / 群邀请(ark) can't be re-sent this way, so the
 * caller must not offer this for those types.
 */
fun View.forwardMsgRecord(msg: MsgRecord, msgItem: WatchAIOMsgItem? = null, title: String = "转发") {
    Utils.log("forwardMsgRecord: begin msgId=${msg.msgId} type=${msg.msgType} elems=${msg.elements?.map { it.elementType }}")
    val navFragment = WatchPicElementExtKt.W(this)?.let { WatchPicElementExtKt.Y(it) }
    if (navFragment == null) {
        Utils.log("forwardMsgRecord: no nav fragment")
        return
    }
    val app = MobileQQ.getMobileQQ().peekAppRuntime() ?: run {
        Utils.log("forwardMsgRecord: no app runtime")
        return
    }
    val contactService = app.getRuntimeService(IContactRuntimeService::class.java, "")
    contactService.startFriendSelect(
        navFragment,
        emptyList(),
        arrayListOf(app.currentUid),
        title,
        0x7e0805cd,
        1, 10, null, false, true
    ) { _, friends ->
        Utils.log("forwardMsgRecord: selected ${friends.size} target(s)")
        if (friends.isNotEmpty()) {
            // Rebuild on a background thread: a received PicElement points at the sender's local
            // path (gone here) and plain sendMsg won't do a uuid/md5 second-transfer, so the pic
            // upload fails (exclamation mark). Instead download each pic to a local file and build
            // a FRESH pic element from it (same as camera/gallery send). Non-pic elements re-send
            // as-is. Other media (video/voice/sticker) already carry re-usable refs.
            val original = ArrayList(msg.elements ?: emptyList())
            Thread {
                val elements = rebuildForForward(original, msgItem)
                Utils.log("forwardMsgRecord: re-sending ${elements.size} element(s) via sendMsg")
                friends.forEach { friend ->
                    val dst = Contact(if (friend.e) 2 else 1, friend.b, "")
                    Utils.log("forwardMsgRecord: sending msgId=${msg.msgId} -> chatType=${dst.chatType} peer=${dst.peerUid}")
                    MsgUtil.msgService.sendMsg(
                        dst, 0L, elements,
                        IOperateCallback { code, errMsg -> Utils.log("forwardMsgRecord: result code=$code msg=$errMsg peer=${dst.peerUid}") }
                    )
                }
            }.start()
        }
        kotlin.Unit
    }
}

/**
 * Re-send a copy of [msg] to the CURRENTLY OPEN chat (the 复读 action). Unlike [forwardMsgRecord]
 * (which opens the friend selector to send elsewhere), this resends in place. Supports any
 * self-contained message — text / @ / reply / image / video / voice / sticker — by re-sending its
 * original elements with each picture rebuilt from a freshly-downloaded local file (received pics
 * point at the sender's local path and won't second-transfer otherwise). Runs the rebuild off the
 * UI thread because it blocks on the image download.
 */
fun repeatMsgRecord(msg: MsgRecord, msgItem: WatchAIOMsgItem? = null) {
    Utils.log("repeatMsgRecord: begin msgId=${msg.msgId} elems=${msg.elements?.map { it.elementType }}")
    val original = ArrayList(msg.elements ?: emptyList())
    val contact = Contact(CurrentContact.chatType, CurrentContact.peerUid, CurrentContact.guildId)
    Thread {
        val elements = rebuildForForward(original, msgItem)
        Utils.log("repeatMsgRecord: re-sending ${elements.size} element(s) via sendMsg")
        MsgUtil.msgService.sendMsg(
            contact, 0L, elements,
            IOperateCallback { code, errMsg -> Utils.log("repeatMsgRecord: result code=$code msg=$errMsg") }
        )
    }.start()
}

/**
 * Rebuild [elements] for re-send the same way [forwardMsgRecord] does (download each picture to a
 * fresh local file and build a new pic element from it; pass other elements through). Exposed so the
 * 编辑 flow can stage re-sendable image elements. Must run off the UI thread (blocks on download).
 */
fun rebuildElementsForResend(elements: List<MsgElement>, msgItem: WatchAIOMsgItem? = null): ArrayList<MsgElement> =
    rebuildForForward(elements, msgItem)

/**
 * Replace each PicElement with a fresh pic MsgElement built from the kernel's local ORIGINAL file, so
 * it can actually be uploaded on forward. Must run off the UI thread (blocks on download).
 *
 * Resolution goes through [resolveOriginalPicFile] (the same robust path 保存/系统分享 use): on-disk
 * original → md5 cache → KERNEL download via AIOPicDownloader (needs [msgItem]) → HTTP. The earlier
 * bug was that we only tried C0/HTTP — if the image had never been opened full-screen there was no
 * on-disk file and the HTTP url gave "rich media transfer failed"; the kernel download fixes that,
 * but it only runs when the caller threads through the [msgItem]. Falls back to the original element.
 */
private fun rebuildForForward(elements: List<MsgElement>, msgItem: WatchAIOMsgItem?): ArrayList<MsgElement> {
    val out = ArrayList<MsgElement>(elements.size)
    elements.forEach { ele ->
        if (ele.picElement == null) {
            out.add(ele)
            return@forEach
        }
        val file = resolveOriginalPicFile(Utils.application, ele, msgItem)
        if (file != null) {
            runCatching { com.tencent.watch.aio_impl.ext.MsgUtil().a(file.path, 0) }
                .onSuccess { out.add(it); Utils.log("forward: rebuilt pic element from ${file.path}") }
                .onFailure { Utils.log("forward: pic build failed: $it, sending original"); out.add(ele) }
        } else {
            Utils.log("forward: could not resolve original pic file, sending original element")
            out.add(ele)
        }
    }
    return out
}
