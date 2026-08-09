package momoi.mod.qqpro.hook

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.qqnt.global.settings.api.IGlobalSettingsApi
import com.tencent.qqnt.kernel.api.IAvatarService
import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.nativeinterface.AvatarSize
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.DeleteRecentContactInfo
import com.tencent.qqnt.kernel.nativeinterface.IKernelRecentContactListener
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.NotificationCommonInfo
import com.tencent.qqnt.kernel.nativeinterface.RecentContactExtra
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import com.tencent.qqnt.kernel.nativeinterface.RecentContactListChangedInfo
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.qqnt.watch.notification.NotificationFacade
import com.tencent.qqnt.watch.notification.logic.INotifySessionService
import download
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.child
import momoi.mod.qqpro.enums.ElementType
import momoi.mod.qqpro.msg.getImageUrl
import momoi.mod.qqpro.util.Utils
import mqq.app.AppRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Action for the RemoteInput reply broadcast; package-scoped so only we receive it. */
private const val ACTION_REPLY = "momoi.mod.qqpro.NOTIF_REPLY"
private const val KEY_REPLY_TEXT = "qqpro_reply_text"
private const val EXTRA_PEER_UID = "qqpro_peer_uid"
private const val EXTRA_PEER_UIN = "qqpro_peer_uin"
private const val EXTRA_CHAT_TYPE = "qqpro_chat_type"
private const val EXTRA_NOTIFY_ID = "qqpro_notify_id"

/**
 * Takes over message-notification posting so that (a) each chat gets its own notification that the
 * next message updates instead of replacing every other chat's, (b) tapping it actually opens that
 * chat, and (c) an inline reply (RemoteInput) sends straight back to the chat.
 *
 * The native [NotificationFacade.r] funnels into a giant obfuscated builder whose notify id falls
 * back to a single shared id (-113) unless preview is on. We rebuild the notification ourselves with
 * a per-uin id (512–521, the same range [INotifySessionService.uniqueNotifyIdByUin] hands out — which
 * is also used as the PendingIntent request code, giving each chat a distinct tap target). The tap
 * intent stores `key_peerUin` as a long (what the cold-start SplashFragment path expects); the warm
 * `MainActivity.onNewIntent` path is bridged by [NotificationClickRedirect]. See its docs for why.
 *
 * Only the real Android-notification path is taken over (when `allow_notification` is on). When it's
 * off the base app broadcasts to the watch ROM instead — we leave that, and revoke/refresh passes,
 * to the original implementation.
 */
@Mixin
class NotificationReply : NotificationFacade() {
    override fun r(
        app: AppRuntime,
        msgRecord: RecentContactInfo,
        commonInfo: NotificationCommonInfo?,
        forRevoke: Boolean,
    ) {
        if (forRevoke || !Settings.allowNotification.value) {
            super.r(app, msgRecord, commonInfo, forRevoke)
            return
        }
        // Reuse QQ's own check (foreground / open chat, notification-enabled). When it says
        // suppress, drop the notification entirely instead of posting our own.
        try {
            val checker = this.e?.e
            if (checker != null &&
                (!checker.d(app, msgRecord, null) || !checker.a(app, msgRecord, null))
            ) {
                Utils.log("NotificationReply: suppressed (foreground/disabled) uin=${msgRecord.peerUin}")
                return
            }
        } catch (e: Throwable) {
            Utils.log("NotificationReply: check failed (${e.message}), posting anyway")
        }
        try {
            postChatNotification(this, app, msgRecord)
        } catch (e: Throwable) {
            Utils.log("NotificationReply: post failed, falling back: ${e.message}")
            super.r(app, msgRecord, commonInfo, forRevoke)
        }
    }
}

/** Build and post a per-chat notification with an inline reply action. */
private fun postChatNotification(facade: NotificationFacade, app: AppRuntime, msg: RecentContactInfo) {
    val ctx = Utils.application
    ensureReceiverRegistered(ctx)
    ensureReadListenerRegistered(app)
    // The channel id encodes the current sound/vibrate modes (system alerts are produced by the
    // channel itself); post to whichever channel matches the active modes.
    val channelId = NotificationAlert.ensureChannel(ctx)

    val peerUid = msg.peerUid ?: ""
    val peerUin = msg.peerUin
    val chatType = msg.chatType
    val name = msg.remark?.takeIf { it.isNotEmpty() } ?: (msg.peerName ?: peerUid)

    val sess = app.getRuntimeService(INotifySessionService::class.java, "") as INotifySessionService
    val notifyId = sess.uniqueNotifyIdByUin(peerUin)

    // Respect the global "show preview" toggle for the visible body text.
    val showPreview = runCatching {
        QRoute.api(IGlobalSettingsApi::class.java).isGlobalShowPreview()
    }.getOrDefault(true)
    val content = if (showPreview) {
        runCatching { facade.l(app, msg) }.getOrDefault("[新消息]")
    } else {
        "你收到一条新消息"
    }

    // Large icon = this chat's avatar. For groups the avatar isn't keyed by uid (so the native
    // facade.n() — which calls getAvatarPath(peerUid) — finds nothing); groups must be fetched via
    // getGroupAvatarPath(peerUin). Fall back to the QQ app icon only when neither resolves.
    val avatar: Bitmap? = resolveChatAvatar(app, msg, facade)
        ?: BitmapFactory.decodeResource(ctx.resources, ctx.applicationInfo.icon)

    // Tap → open this exact chat. key_peerUin is stored as a LONG: that's what the COLD-start path
    // (SplashFragment.onResume) reads via getLongExtra — it bails on 0, so a String there silently
    // breaks cold-start redirects. The WARM path (MainActivity.onNewIntent) reads getStringExtra, so
    // [NotificationClickRedirect] synthesizes the String form from this long before super. See its docs.
    val clickIntent = Intent().apply {
        setClassName(ctx, "com.tencent.qqnt.watch.mainframe.MainActivity")
        action = "com.tencent.qqnt.watch.action.MAINACTIVITY"
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra("open_chatfragment", true)
        putExtra("tab_index", 0)
        putExtra("entrance", 6)
        putExtra("key_notification_click_action", true)
        putExtra("key_peerId", peerUid)
        putExtra("key_peerUin", peerUin)
        putExtra("key_chat_type", chatType)
        putExtra("key_chat_name", name)
    }
    val contentPi = PendingIntent.getActivity(
        ctx, notifyId, clickIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
    )

    val smallIcon = ctx.resources.getIdentifier("notify_newmessage", "mipmap", ctx.packageName)
        .takeIf { it != 0 } ?: ctx.applicationInfo.icon

    // Inline reply.
    val replyIntent = Intent(ACTION_REPLY).apply {
        setPackage(ctx.packageName)
        putExtra(EXTRA_PEER_UID, peerUid)
        putExtra(EXTRA_PEER_UIN, peerUin)
        putExtra(EXTRA_CHAT_TYPE, chatType)
        putExtra(EXTRA_NOTIFY_ID, notifyId)
    }
    val replyPi = PendingIntent.getBroadcast(
        ctx, notifyId, replyIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
    )
    val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("回复").build()
    val replyAction = NotificationCompat.Action.Builder(smallIcon, "回复", replyPi)
        .addRemoteInput(remoteInput)
        .setAllowGeneratedReplies(true)
        .build()

    // Builds a fresh notification; with no [bigPicture] it uses BigTextStyle so longer messages
    // expand to the full text instead of being clipped to one line.
    fun build(bigPicture: Bitmap?, alertOnce: Boolean): android.app.Notification {
        val b = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(name)
            .setContentText(content)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // The image re-post updates the SAME id for the same message, so it must not re-trigger
            // the system-channel sound/vibration (alertOnce=true). A genuinely new message is a fresh
            // post with alertOnce=false, so it still alerts even while the chat's notification shows.
            .setOnlyAlertOnce(alertOnce)
            .addAction(replyAction)
        if (avatar != null) b.setLargeIcon(avatar)
        if (bigPicture != null) {
            b.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bigPicture)
                    .setBigContentTitle(name)
                    .setSummaryText(content)
            )
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(content))
        }
        return b.build()
    }

    val nm = NotificationManagerCompat.from(ctx)
    nm.notify(notifyId, build(null, alertOnce = false))
    // Fire the APP-driven alert once per message (应用内 mode only — 系统 is produced by the channel,
    // 关闭 does nothing), not on the later image re-post below.
    NotificationAlert.fire(ctx)
    Utils.log("NotificationReply: posted id=$notifyId uin=$peerUin type=$chatType preview=$showPreview")

    // For an image message, load the picture in the background and re-post with BigPictureStyle.
    if (showPreview && msg.abstractContent?.any { it.elementType == ElementType.PIC } == true) {
        fetchImageBitmap(ctx, app, msg) { bmp ->
            runCatching { nm.notify(notifyId, build(bmp, alertOnce = true)) }
                .onFailure { Utils.log("NotificationReply: re-notify with image failed: ${it.message}") }
            Utils.log("NotificationReply: attached image to id=$notifyId")
        }
    }
}

/**
 * Resolve the large-icon avatar for [msg]'s chat. Single chats (chatType 1) are keyed by uid, so
 * the native [NotificationFacade.n] works; group chats (chatType 2) are keyed by group code and
 * must use [IAvatarService.getGroupAvatarPath], and when the file isn't cached yet we kick off a
 * background download so the next notification for that group has it. Returns null if no avatar
 * could be decoded (caller falls back to the app icon).
 */
private fun resolveChatAvatar(
    app: AppRuntime,
    msg: RecentContactInfo,
    facade: NotificationFacade,
): Bitmap? {
    if (msg.chatType == 2) {
        val avatarService = runCatching {
            (app.getRuntimeService(IKernelService::class.java, "") as IKernelService).avatarService
        }.getOrNull() ?: return null
        val path = runCatching { avatarService.getGroupAvatarPath(msg.peerUin, AvatarSize.SMALL) }.getOrNull()
        val bmp = path?.takeIf { it.isNotEmpty() && File(it).exists() }
            ?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        if (bmp != null) return bmp
        // Not cached yet — fetch it for next time, and let this notification use the app icon.
        runCatching {
            avatarService.forceDownloadGroupAvatar(msg.peerUin, AvatarSize.SMALL, null)
        }
        Utils.log("NotificationReply: group avatar not cached (group=${msg.peerUin}), downloading")
        return null
    }
    return runCatching { facade.n(msg, false) }.getOrNull()
}

/**
 * Resolve the photo for [msg] (an image message) and hand its scaled bitmap to [onReady]. Fetches
 * the full message by id to get the [PicElement], prefers a path QQ already has locally, otherwise
 * downloads it. Best-effort: stays silent if the image can't be obtained.
 */
private fun fetchImageBitmap(
    ctx: Context,
    app: AppRuntime,
    msg: RecentContactInfo,
    onReady: (Bitmap) -> Unit,
) {
    val contact = Contact(msg.chatType, msg.peerUid ?: "", "")
    runCatching {
        MsgUtil.msgService.getMsgsByMsgId(
            contact, arrayListOf(msg.msgId),
            com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback { code, _, records ->
                val pic = records?.firstOrNull()
                    ?.elements?.firstNotNullOfOrNull { it.picElement }
                if (pic == null) {
                    Utils.log("NotificationReply: no pic element (code=$code)")
                    return@IMsgOperateCallback
                }
                // Prefer a local copy QQ already resolved / we cached, else download.
                val local = runCatching { WatchPicElementExtKt.C0(pic) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() && File(it).exists() }
                    ?: (pic.sourcePath?.takeIf { it.isNotEmpty() && File(it).exists() })
                if (local != null) {
                    decodeScaled(local)?.let(onReady)
                    return@IMsgOperateCallback
                }
                val cacheFile = ctx.externalCacheDir?.child("${pic.md5HexStr}.jpg") ?: return@IMsgOperateCallback
                if (cacheFile.exists()) {
                    decodeScaled(cacheFile.path)?.let(onReady)
                } else {
                    download(pic.getImageUrl(), cacheFile) { ok ->
                        if (ok) decodeScaled(cacheFile.path)?.let(onReady)
                        else Utils.log("NotificationReply: image download failed ${pic.md5HexStr}")
                    }
                }
            }
        )
    }.onFailure { Utils.log("NotificationReply: fetchImageBitmap error: ${it.message}") }
}

/** Decode [path] downscaled so the bitmap is safe to hand a notification (caps the long edge). */
private fun decodeScaled(path: String): Bitmap? {
    val maxEdge = 1024
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > maxEdge) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
}

/** FLAG_IMMUTABLE on S+, harmless 0 below (the constant only exists on M+ where we always run). */
private fun immutableFlag(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

/** RemoteInput needs a mutable PendingIntent so the system can fill in the typed reply. */
private fun mutableFlag(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

private val receiverRegistered = AtomicBoolean(false)

private fun ensureReceiverRegistered(ctx: Context) {
    if (!receiverRegistered.compareAndSet(false, true)) return
    try {
        val appCtx = ctx.applicationContext
        val filter = android.content.IntentFilter(ACTION_REPLY)
        // The APK bundles an old androidx core whose ContextCompat lacks the 4-arg
        // registerReceiver, so call the framework method directly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(NotifReplyReceiver(), filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appCtx.registerReceiver(NotifReplyReceiver(), filter)
        }
        Utils.log("NotificationReply: receiver registered")
    } catch (e: Throwable) {
        receiverRegistered.set(false)
        Utils.log("NotificationReply: receiver register failed: ${e.message}")
    }
}

private val readListenerRegistered = AtomicBoolean(false)

/**
 * Register a kernel recent-contact listener (once) so that when a chat's unread count drops to zero
 * — including reads synced from another logged-in device — we dismiss this device's notification for
 * that chat. The notify id we posted under is [INotifySessionService.uniqueNotifyIdByUin], the same
 * id [INotifySessionService.cancelNotificationByUin] cancels, so the two line up.
 */
private fun ensureReadListenerRegistered(app: AppRuntime) {
    if (!readListenerRegistered.compareAndSet(false, true)) return
    try {
        val service = KernelServiceUtil.g()?.recentContactService
            ?: throw IllegalStateException("no recentContactService")
        service.addKernelRecentContactListener(NotifReadListener(app))
        Utils.log("NotificationReply: read listener registered")
    } catch (e: Throwable) {
        readListenerRegistered.set(false)
        Utils.log("NotificationReply: read listener register failed: ${e.message}")
    }
}

/**
 * Cancels the notifications of any chats in [contacts] whose unread count has reached zero. Safe to
 * call repeatedly; cancelling an already-absent notification is a no-op.
 */
private fun cancelReadChats(app: AppRuntime, contacts: Collection<RecentContactInfo>?) {
    if (contacts.isNullOrEmpty()) return
    val sess = runCatching {
        app.getRuntimeService(INotifySessionService::class.java, "") as INotifySessionService
    }.getOrNull() ?: return
    for (c in contacts) {
        if (c.unreadCnt <= 0L && c.peerUin != 0L) {
            runCatching { sess.cancelNotificationByUin(c.peerUin) }
                .onSuccess { Utils.log("NotificationReply: cleared notif (read elsewhere) uin=${c.peerUin}") }
        }
    }
}

/**
 * Watches recent-contact changes and clears notifications for chats that have been fully read
 * (unread → 0), which is what happens when the conversation is read on another device.
 */
private class NotifReadListener(private val app: AppRuntime) : IKernelRecentContactListener {
    override fun onRecentContactListChangedVer2(list: ArrayList<RecentContactListChangedInfo>?, seq: Int) {
        list?.forEach { cancelReadChats(app, it.changedList) }
    }

    override fun onRecentContactListChanged(
        sorted: ArrayList<Long>?,
        changed: ArrayList<RecentContactInfo>?,
        extra: RecentContactExtra?,
    ) {
        cancelReadChats(app, changed)
    }

    override fun onRecentContactNotification(
        list: ArrayList<RecentContactInfo>?,
        common: NotificationCommonInfo?,
        seq: Int,
    ) {
        cancelReadChats(app, list)
    }

    override fun onGuildDisplayRecentContactListChanged(list: ArrayList<RecentContactListChangedInfo>?) {}
    override fun onDeletedContactsNotify(list: ArrayList<DeleteRecentContactInfo>?) {}
    override fun onMsgUnreadCountUpdate(map: HashMap<String, Int>?) {}
}

/** Receives the inline reply text and sends it to the originating chat. */
class NotifReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY_TEXT)
        val peerUid = intent.getStringExtra(EXTRA_PEER_UID) ?: ""
        val chatType = intent.getIntExtra(EXTRA_CHAT_TYPE, 0)
        val notifyId = intent.getIntExtra(EXTRA_NOTIFY_ID, -1)
        try {
            if (text.isNullOrBlank() || peerUid.isEmpty() || chatType == 0) {
                Utils.log("NotificationReply: empty reply (text=$text uid=$peerUid type=$chatType)")
                return
            }
            val element = MsgElement().apply {
                elementType = ElementType.TEXT
                textElement = TextElement().apply { content = text.toString() }
            }
            MsgUtil.msgService.sendMsg(
                Contact(chatType, peerUid, ""), 0L, arrayListOf<MsgElement>(element),
                IOperateCallback { code, msg -> Utils.log("NotificationReply: send result=$code msg=$msg") }
            )
            Utils.log("NotificationReply: reply sent uid=$peerUid type=$chatType")
        } catch (e: Throwable) {
            Utils.log("NotificationReply: send failed: ${e.message}")
        } finally {
            // Dismiss the notification so the reply UI stops spinning, even if the send failed.
            if (notifyId >= 0) runCatching { NotificationManagerCompat.from(context).cancel(notifyId) }
        }
    }
}
