package momoi.mod.qqpro.hook.aio_cell

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import momoi.mod.qqpro.lib.material.M3CircularProgress
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.qqnt.kernel.api.impl.MsgService
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.PicElement
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.biz.richframework.util.RFWSaveUtil
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.qqnt.kernel.nativeinterface.AddFavEmojiReq
import com.tencent.qqnt.kernel.nativeinterface.IAddFavEmojiCallback
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.watch.api.IMsgApi
import com.tencent.richframework.widget.matrix.RFWMatrixImageView
import com.tencent.watch.aio_impl.ui.menu.AIOLongClickMenuFragment
import com.tencent.watch.aio_impl.ui.menu.MenuItemFactory
import com.tencent.watch.ime.util.ImeTextUtil
import loadPicElement
import loadErrorImage
import momoi.mod.qqpro.lib.bitmapDecodeFile
import download
import java.io.File
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.child
import momoi.mod.qqpro.safeCacheDir
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.util.parseHexColor
import android.text.Spanned
import com.tencent.mobileqq.text.style.EmoticonSpan
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.SelfContact
import momoi.mod.qqpro.hook.forwardText
import momoi.mod.qqpro.hook.forwardToFriends
import momoi.mod.qqpro.hook.style.MyImageView
import momoi.mod.qqpro.hook.translate.HistoryTranslate
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.hook.view.smoothScrollToStart
import momoi.mod.qqpro.lib.applyLongScreenshotFit
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.background
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.create
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.lib.find
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.id
import momoi.mod.qqpro.lib.imageFitsHorizontally
import momoi.mod.qqpro.lib.isTapOutsideImage
import momoi.mod.qqpro.lib.layoutParams
import momoi.mod.qqpro.lib.linearLayout
import momoi.mod.qqpro.lib.longClickable
import momoi.mod.qqpro.lib.margin
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.paddingHorizontal
import momoi.mod.qqpro.lib.size
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.TapObserverLayout
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.lib.width
import momoi.mod.qqpro.msg.getImageUrl
import momoi.mod.qqpro.removeAfter
import momoi.mod.qqpro.showDialog
import momoi.mod.qqpro.showFragment
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.linkify
import momoi.mod.qqpro.util.runOnUi

/**
 * The single full-screen, non-flippable image viewer for the whole app (native [RFWMatrixImageView]
 * with pinch/double-tap zoom, tap-outside-to-dismiss, right-swipe-back and an M3 loading indicator).
 * Everything that shows ONE image routes here — chat photos, forward-history images, market-face /
 * store stickers, group-bulletin and card images — so there is exactly one viewer to maintain.
 *
 * Three load paths (use the matching constructor):
 *  - [bmp]: an already-rendered bitmap (market face, bulletin, card) — shown immediately, no spinner.
 *  - [kernelLoad] provided (live chat images, see [momoi.mod.qqpro.hook.ImagePlay]): the kernel owns
 *    the file, so we never download manually — the lambda resolves the local path (triggering the
 *    kernel download with progress if needed) and reports back the file to decode.
 *  - [pic] only (forward-history images that carry a real URL): download via our HTTP loader.
 */
class BigImageFragment(
    private val pic: PicElement? = null,
    private val bmp: android.graphics.Bitmap? = null,
    private val kernelLoad: ((onProgress: (Float) -> Unit, onDone: (String?) -> Unit) -> Unit)? = null,
) : MyDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        // M3 loading indicator: spins until download starts, then shows determinate progress.
        val spinner = M3CircularProgress(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(40.dp, 40.dp, Gravity.CENTER)
        }
        val image = RFWMatrixImageView(ctx, null)
            .layoutParams(ViewGroup.LayoutParams(FILL, FILL))
        val onProgress = { p: Float -> spinner.indeterminate = false; spinner.progress = p }
        when {
            // Pre-rendered bitmap (market face / bulletin / card): show at once, no loading.
            bmp != null -> { image.setImageBitmap(bmp); spinner.visibility = View.GONE; image.applyLongScreenshotFit() }
            kernelLoad != null -> {
                kernelLoad(onProgress) { path ->
                    spinner.visibility = View.GONE
                    val f = path?.let { java.io.File(it) }
                    if (f != null && f.exists() && f.length() > 0) { image.bitmapDecodeFile(f); image.applyLongScreenshotFit() }
                    else image.loadErrorImage()
                }
            }
            pic != null -> {
                image.loadPicElement(
                    pic,
                    onDone = { spinner.visibility = View.GONE; image.applyLongScreenshotFit() },
                    onProgress = onProgress,
                )
            }
            else -> spinner.visibility = View.GONE
        }
        // Observe taps without consuming them so the native matrix view keeps pinch/double-tap/pan;
        // a tap on the empty area outside the image dismisses.
        val content = TapObserverLayout(ctx).content {
            add(image)
            add(spinner)
        }
        content.onSingleTap = { x, y ->
            if (image.isTapOutsideImage(x, y)) this@BigImageFragment.dismiss()
        }

        // Right-swipe back to dismiss, but only while the image has no horizontal pan room (fully
        // zoomed out); once zoomed, a horizontal drag pans the image instead.
        val swipe = SwipeBackLayout(ctx)
        swipe.onSwipeBack = { this@BigImageFragment.dismiss() }
        swipe.canSwipe = { image.imageFitsHorizontally() }
        swipe.addView(content, FrameLayout.LayoutParams(FILL, FILL))
        return swipe
    }
}

/**
 * Show the native long-press menu (the same [AIOLongClickMenuFragment] normal chat uses) for a
 * message inside the forward-history view, with the given menu items. Because these messages are
 * not backed by a real on-screen cell, the actions are performed by our [onItem] callback rather
 * than the cell's native handler. The 长按菜单调整 Mixin still styles/reorders the menu, and degrades
 * gracefully (no 撤回 button) since it can't resolve a cell from our callback.
 */
// The enum field names are obfuscated in the target APK, but each constant's `name` (the string
// passed to its constructor) is preserved, so resolve them by name to stay obfuscation-proof.
private val MENU_COPY = MenuItemFactory.ItemEnum.valueOf("CopyMsg")
private val MENU_REPEAT = MenuItemFactory.ItemEnum.valueOf("RepeatMsg")
private val MENU_SAVE_PIC = MenuItemFactory.ItemEnum.valueOf("SavePic")
private val MENU_SAVE_FAV_EMOJI = MenuItemFactory.ItemEnum.valueOf("SaveFavEmoji")
private val MENU_SHARE = MenuItemFactory.ItemEnum.valueOf("Share")

private fun View.showHistoryMenu(
    msgId: Long,
    items: List<MenuItemFactory.ItemEnum>,
    onItem: (MenuItemFactory.ItemEnum) -> Unit,
) {
    val fragment = AIOLongClickMenuFragment({ onItem(it) }, "pg_watch_long_press_menu")
    fragment.arguments = Bundle().apply {
        putLong("key_msg_id", msgId)
        putStringArrayList("key_item_list", ArrayList(items.map { it.name }))
    }
    showFragment(fragment)
}

/**
 * QQ builds inline face emoji as fixed-size [EmoticonSpan]s (sized to QQ's default chat text
 * size via EmoConstants), so they don't follow our 字体大小 setting when the surrounding text is
 * scaled. Re-size each emoji span by the same [scale] so they grow/shrink with the text.
 * Runs on a freshly-built summary every bind, so the size never compounds.
 */
private fun scaleEmojiSpans(cs: CharSequence, scale: Float): CharSequence {
    if (scale == 1.0f || cs !is Spanned) return cs
    cs.getSpans(0, cs.length, EmoticonSpan::class.java).forEach { span ->
        val newSize = (span.c * scale).toInt()
        if (newSize > 0) span.h(newSize)
    }
    return cs
}

private fun copyText(context: Context, text: CharSequence) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("text", text))
}

fun savePic(context: Context, pic: PicElement) {
    val cacheDir = context.safeCacheDir
    if (cacheDir == null) {
        Utils.log("savePic: no cache dir available, skip")
        return
    }
    val cacheFile = cacheDir.child("${pic.md5HexStr}.jpg")
    if (cacheFile.exists()) {
        RFWSaveUtil.a(context, cacheFile.path, null)
        Utils.toast(context, "已保存到相册")
    } else {
        download(pic.getImageUrl(), cacheFile) { ok ->
            runOnUi {
                if (ok) { RFWSaveUtil.a(context, cacheFile.path, null); Utils.toast(context, "已保存到相册") }
                else Utils.toast(context, "保存失败")
            }
        }
    }
}

/**
 * Resolve a local file path for [pic]. Prefer QQ's own resolved path; fall back to the file we
 * already cached when rendering the image in the history view.
 */
private fun picLocalPath(context: Context, pic: PicElement): String? {
    runCatching { WatchPicElementExtKt.C0(pic) }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() && File(it).exists() }
        ?.let { return it }
    val cacheDir = context.safeCacheDir ?: return null
    val cacheFile = cacheDir.child("${pic.md5HexStr}.jpg")
    return cacheFile.takeIf { it.exists() }?.path
}

private fun fileMd5(file: File): String {
    val md = java.security.MessageDigest.getInstance("MD5")
    file.inputStream().use { ins ->
        val buf = ByteArray(8192)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Add the image as a favourite emoji (收藏表情). The forwarded [pic]'s own md5/size describe QQ's
 * original which we never downloaded, so — like Share's createPicElement — we upload the file we
 * actually have and describe it with its own md5/size to keep the request self-consistent.
 */
fun saveFavEmoji(context: Context, pic: PicElement) {
    val cacheDir = context.safeCacheDir
    if (cacheDir == null) {
        Utils.log("saveFavEmoji: no cache dir available, skip")
        return
    }
    val cacheFile = cacheDir.child("${pic.md5HexStr}.jpg")
    if (cacheFile.exists()) {
        doAddFavEmoji(context, cacheFile)
    } else {
        download(pic.getImageUrl(), cacheFile) { ok ->
            if (ok) runOnUi { doAddFavEmoji(context, cacheFile) }
            else Utils.log("saveFavEmoji: download failed for ${pic.md5HexStr}")
        }
    }
}

fun doAddFavEmoji(context: Context, file: File) {
    val md5 = fileMd5(file)
    val req = AddFavEmojiReq("", 0, file.path, file.length(), file.name, md5, false, true)
    val msgService = WatchPicElementExtKt.r0().wrapperSession?.msgService
    if (msgService == null) {
        Utils.log("saveFavEmoji: msgService null")
        return
    }
    Utils.log("saveFavEmoji: req path=${file.path} size=${file.length()} md5=$md5")
    msgService.addFavEmoji(req, IAddFavEmojiCallback { code, s, type ->
        Utils.log("saveFavEmoji result=$code msg=$s type=$type")
        runOnUi {
            Utils.toast(
                context,
                if (type == 1) "表情已存在" else if (code == 0) "收藏表情成功" else "收藏表情失败"
            )
        }
    })
}

/** Forward the image to selected friends/groups (转发). */
private fun View.sharePic(context: Context, pic: PicElement) {
    val path = picLocalPath(context, pic) ?: run {
        Utils.log("sharePic: no local path for ${pic.md5HexStr}")
        return
    }
    forwardToFriends {
        arrayListOf(QRoute.api(IMsgApi::class.java).createPicElement(path, 0))
    }
}

/** Re-send plain text to the currently open chat (the 复读文本 action). */
private fun repeatText(text: CharSequence) {
    runCatching {
        val elements = ImeTextUtil.a.b(text.toString())
        val contact = Contact(
            CurrentContact.chatType,
            CurrentContact.peerUid,
            CurrentContact.guildId
        )
        MsgUtil.msgService.sendMsg(contact, 0L, elements, IOperateCallback { code, msg ->
            Utils.log("history repeat send result=$code msg=$msg")
        })
    }.onFailure { Utils.log("history repeat failed: $it") }
}

class DetailFragment(private val contact: Contact, private val data: ForwardMsgData) :
    MyDialogFragment() {
    private val mMsgList = mutableListOf<MsgRecord>()
    private lateinit var mRv: RecyclerView

    /** Scroll the history list to the original message the reply points at (by msgSeq). */
    private fun jumpToReply(seq: Long) {
        val index = mMsgList.indexOfFirst { it.msgSeq == seq }
        if (index >= 0) {
            mRv.smoothScrollToStart(index)
        } else {
            Utils.toast(mRv.context, "无法定位消息")
        }
    }

    // Honor the user's chat appearance settings inside the chat-history viewer, the same way the
    // live chat does: bubble fill follows 气泡颜色(对方), text color follows 文字颜色, and the body
    // size follows both chatScale and 字体大小. Blank/invalid overrides fall back to the defaults.
    private val bubbleColor: Int
        get() = parseHexColor(Settings.bubbleColorOther.value) ?: M3.surfaceContainer
    private val msgTextColor: Int
        // Forward card is styled like an "other" bubble: 对方 override (textColor), else auto-contrast.
        get() = parseHexColor(Settings.textColor.value) ?: M3.onColor(bubbleColor)

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        data.getDetail {
            mMsgList.clear()
            mMsgList.addAll(it)
            // Make these inner records resolvable by the long-press menu hook so 系统分享 / 转发
            // work for items inside a chat history.
            momoi.mod.qqpro.hook.HistoryMsgRegistry.register(it)
            runOnUi {
                mRv.adapter?.notifyDataSetChanged()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val content = create<LinearLayout>(inflater.context)
            .vertical()
            .size(FILL, FILL)
            // Themed viewer surface (was a fixed translucent black, which left the onSurface title/text
            // unreadable on a light theme).
            .background(M3.surface)
            .paddingHorizontal(4.dp)
            .content {
                add<TextView>()
                    .text(data.title)
                    .textSize(13f)
                    .width(FILL)
                    .gravity(Gravity.CENTER)
                    .textColor(M3.onSurface)
                    .apply {
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    .clickable { dismiss() }
                mRv = add<RecyclerView>()
                    .linearLayout()
                    .layoutParams(LinearLayout.LayoutParams(FILL, 0, 1f))
                    .content(
                        data = mMsgList,
                        factory = {
                            create<LinearLayout>(this)
                                .vertical()
                                .width(FILL)
                                .content {
                                    add<TextView>()
                                        // Scale the nick with chatScale (like the group cell's nick)
                                        // so the avatar — sized off this view's textSize in
                                        // applyAvatar — also tracks 头像大小/聊天缩放 settings.
                                        .textSize(12f * Settings.chatScale.value)
                                        .textColor(M3.onSurfaceVariant)
                                        .id(0)
                                    add<LinearLayout>()
                                        .vertical()
                                        .padding(3.dp)
                                        // Inset horizontally by half the corner radius on top of
                                        // the base padding, matching BubbleCorner's chat-bubble
                                        // formula (native pad + radius*0.5) so the shape lines up.
                                        .paddingHorizontal(
                                            3.dp + (Settings.bubbleCornerRadius.value * 0.5f).dpf.toInt()
                                        )
                                        .margin(bottom = 2.dp)
                                        .id(1)
                                }
                        },
                        update = { msg ->
                            Utils.log("forward item nick=${msg.sendNickName} senderUin=${msg.senderUin} senderUid=${msg.senderUid} fromUid=${msg.fromUid} memberName=${msg.sendMemberName} remark=${msg.sendRemarkName} nameType=${msg.nameType} peerName=${msg.peerName} fromAppid=${msg.fromAppid}")
                            val nickView = find<TextView>(0)
                            val isSelf = msg.senderUid == SelfContact.peerUid
                            // "只显示一次发送者" — collapse the nick header (and its avatar) when the
                            // previous forwarded message is from the same sender, mirroring the live
                            // chat's hideRepeatedSender behaviour. Match on sendNickName, NOT senderUid:
                            // forwarded records of unresolvable senders all share one placeholder uid,
                            // so a uid match would wrongly merge distinct people. sendNickName is the
                            // reliable per-sender field (and is what's actually displayed).
                            val idx = mMsgList.indexOf(msg)
                            val prev = mMsgList.getOrNull(idx - 1)
                            val hideHeader = Settings.hideRepeatedSender.value &&
                                prev != null && prev.sendNickName == msg.sendNickName
                            if (hideHeader) {
                                nickView.visibility = View.GONE
                                GroupAvatarHook.clearNickAvatar(nickView)
                            } else {
                                nickView.visibility = View.VISIBLE
                                nickView.text = msg.sendNickName
                                // Same toggle as the group cell: 群成员头像 gated by showGroupAvatar,
                                // self gated additionally by showSelfAvatar. Avatar is keyed on the
                                // (correct, for resolvable senders) senderUin; unresolvable senders get
                                // the server's QQ-logo placeholder, exactly as on phone.
                                if (Settings.showGroupAvatar.value && (!isSelf || Settings.showSelfAvatar.value)) {
                                    GroupAvatarHook.bindAvatarToNick(nickView, msg.senderUin)
                                } else {
                                    GroupAvatarHook.clearNickAvatar(nickView)
                                }
                            }
                            find<LinearLayout>(1)
                                .apply {
                                    removeAllViews()
                                }
                                .content {
                                    val textElements = mutableListOf<MsgElement>()
                                    val applyTexts = {
                                        if (textElements.isNotEmpty()) {
                                            group.background(roundCornerDrawable(bubbleColor, Settings.bubbleCornerRadius.value.dpf))
                                            val summary = scaleEmojiSpans(
                                                MsgUtil.summary(textElements),
                                                Settings.chatScale.value
                                            )
                                            val textView = add<TextView>()
                                                .textSize(14f * Settings.chatScale.value)
                                                .textColor(msgTextColor)
                                                .text(summary)
                                                .longClickable {
                                                    showHistoryMenu(
                                                        msg.msgId,
                                                        listOf(MENU_COPY, MENU_REPEAT, MENU_SHARE)
                                                    ) { item ->
                                                        when (item) {
                                                            MENU_COPY ->
                                                                copyText(group.context, summary)
                                                            MENU_REPEAT ->
                                                                repeatText(summary)
                                                            MENU_SHARE ->
                                                                forwardText(summary)
                                                            else -> {}
                                                        }
                                                    }
                                                }
                                                .apply { linkify() }
                                            // Register for the 翻译 long-press entry (history path has no
                                            // live cell; translation renders into this TextView).
                                            HistoryTranslate.register(msg.msgId, textView, summary)
                                            LinkPreview.bindHistory(group, summary)
                                            textElements.clear()
                                        }
                                    }
                                    msg.elements.forEach { ele ->
                                        ele.replyElement?.let { reply ->
                                            group.background(roundCornerDrawable(bubbleColor, Settings.bubbleCornerRadius.value.dpf))
                                            add<ReplyView>()
                                                .clickable { jumpToReply(reply.replayMsgSeq) }
                                                .loadData(contact, reply)
                                            return@forEach
                                        }
                                        ele.multiForwardMsgElement?.let {
                                            group.background(roundCornerDrawable(bubbleColor, Settings.bubbleCornerRadius.value.dpf))
                                            add<ForwardMsgView>()
                                                .loadData(
                                                    contact,
                                                    ForwardMsgData(contact, data.rootMsg, msg)
                                                )
                                            return@forEach
                                        }
                                        ele.picElement?.let { pic ->
                                            applyTexts()
                                            add<FrameLayout>().content {
                                                val image = add<MyImageView>()
                                                    .size(pic.picWidth, pic.picHeight)
                                                    .clickable {
                                                        showDialog(BigImageFragment(pic))
                                                    }
                                                    .longClickable {
                                                        showHistoryMenu(
                                                            msg.msgId,
                                                            listOf(
                                                                MENU_SAVE_FAV_EMOJI,
                                                                MENU_SHARE,
                                                                MENU_SAVE_PIC,
                                                            )
                                                        ) { item ->
                                                            when (item) {
                                                                MENU_SAVE_FAV_EMOJI ->
                                                                    saveFavEmoji(group.context, pic)
                                                                MENU_SHARE ->
                                                                    sharePic(group.context, pic)
                                                                MENU_SAVE_PIC ->
                                                                    savePic(group.context, pic)
                                                                else -> {}
                                                            }
                                                        }
                                                    }
                                                // M3 loading indicator: spins until the download starts,
                                                // then shows real determinate progress (HTTP Content-Length).
                                                val progress = add<M3CircularProgress>()
                                                progress.layoutParams =
                                                    FrameLayout.LayoutParams(28.dp, 28.dp, Gravity.CENTER)
                                                image.loadPicElement(
                                                    pic,
                                                    onDone = { ok ->
                                                        progress.visibility = View.GONE
                                                        if (!ok) Utils.log(
                                                            "history image load failed md5=${pic.md5HexStr}"
                                                        )
                                                    },
                                                    onProgress = { p ->
                                                        progress.indeterminate = false
                                                        progress.progress = p
                                                    },
                                                )
                                            }
                                            return@forEach
                                        }
                                        textElements.add(ele)
                                    }
                                    applyTexts()
                                }
                        })
            }
        // Wrap so a left-to-right swipe dismisses the viewer, for watches without a back
        // button. Vertical drags pass through to the RecyclerView untouched.
        return momoi.mod.qqpro.lib.SwipeBackLayout(inflater.context).apply {
            addView(content, FILL, FILL)
            onSwipeBack = { dismiss() }
        }
    }
}

class ForwardMsgData(val contact: Contact, val rootMsg: MsgRecord, val rawMsg: MsgRecord) {
    val title: String
    val previewLines: List<String>
    val summary: String

    init {
        val content =
            rawMsg.elements?.firstNotNullOf { it.multiForwardMsgElement }
                ?.xmlContent
                ?.replace("&lt;", "<")
                ?.replace("&gt;", ">")
                ?.replace("&amp;", "&")
                ?.replace("&quot;", "\"")
                ?.replace("&apos;", "'")
        val split = content?.split("</title>")?.map {
            it.split(">").last()
        }
        Utils.log("forward xmlContent dump >>>${content}<<<")
        title = split?.getOrNull(0) ?: ""
        previewLines = split?.drop(1) ?: listOf()
        summary = content?.removeAfter("</summary>")?.split(">")?.last() ?: ""
    }

    fun getDetail(callback: (List<MsgRecord>) -> Unit) {
        (KernelServiceUtil.c() as? MsgService)?.service?.getMultiMsg(
            contact, rootMsg.msgId, rawMsg.msgId
        ) { i: Int, s: String, msgRecords: ArrayList<MsgRecord> ->
            callback(msgRecords)
        }
    }
}