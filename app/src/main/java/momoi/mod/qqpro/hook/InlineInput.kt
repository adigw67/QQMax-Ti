package momoi.mod.qqpro.hook

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.edit
import com.huanli233.qplus.utils.TextUtilKt
import com.tencent.mobileqq.aio.msglist.holder.base.PicSize
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.mobileqq.text.QQText
import com.tencent.qqnt.emotion.utils.QQSysFaceUtil
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.qqnt.msg.api.impl.MsgUtilApiImpl
import com.tencent.watch.aio_impl.coreImpl.vb.InputBarController
import com.tencent.watch.aio_impl.ext.AIOPicDownloader
import com.tencent.watch.aio_impl.ext.MsgUtil as WatchMsgUtil
import com.tencent.watch.ime.util.ImeTextUtil
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.MessageEdit
import momoi.mod.qqpro.hook.action.isGroup
import momoi.mod.qqpro.lib.AtTag
import momoi.mod.qqpro.lib.ImageTag
import momoi.mod.qqpro.lib.ImeEditText
import momoi.mod.qqpro.lib.InlineTag
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import moye.wearqq.AtElementArg
import moye.wearqq.IMEOperation
import moye.wearqq.ReplyElementArg
import java.lang.ref.WeakReference
import android.widget.ImageView
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols

/**
 * Controller for "完全行内输入" (Settings.fullInlineInput): everything that would normally open the
 * InputMethodFragment is represented inline in the chat EditText instead.
 *
 *  - @member  → an atomic "@nick " [AtTag] span (blue, deletes in one backspace)
 *  - image    → an atomic "[图片]" [ImageTag] span carrying the ready PicElement
 *  - reply    → a banner floating just above the input bar ("回复 xxx（点击取消）"); tap to cancel
 *  - edit     → same banner ("编辑中（点击取消）"); the next send recalls + resends
 *  - 复读/STT → the text is just dropped into the box as plain text
 *
 * The active EditText is registered by the input-bar hook (聊天底部按钮调整). The StartImeUtil
 * interception (InlineImeRoute) calls [consumePending] / [insertText] instead of navigating to the
 * keyboard page. At send time [send] walks the spans back into MsgElements.
 */
object InlineInput {
    private val tokenColor get() = M3.primary
    private const val BANNER_TAG = "qqpro_inline_banner"

    private val mainHandler = Handler(Looper.getMainLooper())

    private var editTextRef: WeakReference<ImeEditText>? = null
    private var controllerRef: WeakReference<InputBarController>? = null
    private var bannerRef: WeakReference<TextView>? = null

    // Inline reply/edit row built INSIDE the input pill (materialized mode). When registered, the
    // reply/edit indicator is rendered into this row (which moves WITH the bar on scroll) instead of
    // the floating [showBanner] overlay — the overlay decoupled visually while scrolling. The input
    // bar provides [onInlineReplyChanged] to re-run its grow routine when the row shows/hides.
    private var inlineReplyRowRef: WeakReference<View>? = null
    private var inlineReplyTextRef: WeakReference<TextView>? = null
    private var inlineReplyIconRef: WeakReference<ImageView>? = null
    private var onInlineReplyChanged: (() -> Unit)? = null

    /** Called by the input-bar hook (materialized) to register the inline reply/edit row + its views. */
    fun registerInlineReply(row: View, icon: ImageView, text: TextView, onChanged: () -> Unit) {
        inlineReplyRowRef = WeakReference(row)
        inlineReplyIconRef = WeakReference(icon)
        inlineReplyTextRef = WeakReference(text)
        onInlineReplyChanged = onChanged
    }

    /** Called by the input-bar hook (non-materialized) so the floating banner is used instead. */
    fun clearInlineReply() {
        inlineReplyRowRef = null
        inlineReplyIconRef = null
        inlineReplyTextRef = null
        onInlineReplyChanged = null
    }

    // Gallery-staged image elements awaiting insertion. They are drained into whichever inline
    // EditText is actually registered/visible at the time — the bar has two hosts (list-footer pill
    // and floating overlay pill), and focusAndShow() may switch hosts after consumePending() runs, so
    // inserting eagerly into the current host can land the image in a now-hidden box. Draining from
    // BOTH the consume path and register() (whichever fires with a live EditText first wins and
    // clears the list) makes the image follow the visible host. See [[gallery-send-flow]].
    private var pendingImageElements: List<MsgElement> = emptyList()
    private var bannerLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private data class ReplyState(val msgId: Long, val senderUid: String, val nick: String)
    private var reply: ReplyState? = null

    // ---- per-chat draft persistence (Settings.rememberDraft) ----
    // In-memory map keeps full fidelity within a session (incl. [图片] image tokens). It is also
    // mirrored to a "qqpro_drafts" SharedPreferences (as JSON) so a draft survives the app being
    // closed/killed: text + @ mentions + reply target + image *local file paths* (the pic MsgElement
    // itself isn't serializable, so we persist its source path and rebuild a fresh element on restore
    // — only works while the original file still exists). Cleared (both) when the msg is sent.
    private val drafts = HashMap<String, CharSequence>()
    private val replyDrafts = HashMap<String, ReplyState?>()
    private var currentChatKey: String? = null
    private val draftPrefs by lazy { Utils.application.getSharedPreferences("qqpro_drafts", 0) }

    private fun chatKey(): String? {
        val uid = CurrentContact.peerUid
        if (uid.isNullOrEmpty()) return null
        return "${CurrentContact.chatType}:$uid"
    }

    /** Snapshot the current box (text + spans) and reply target into the per-chat draft store. */
    private fun saveDraft() {
        if (!Settings.rememberDraft.value) return
        val key = currentChatKey ?: return
        val et = editText() ?: return
        // Diagnostic: catch the case where a draft that HELD image/@ tokens is about to be overwritten
        // by an empty box (e.g. the native fragment cleared the EditText underneath us on a gallery
        // round-trip). That silently drops a staged image before it can be sent.
        val prevSpans = runCatching { (drafts[key] as? Spanned)?.let { d -> d.getSpans(0, d.length, InlineTag::class.java).size } ?: 0 }.getOrNull() ?: 0
        val nowText = et.text
        val nowSpans = runCatching { nowText?.let { it.getSpans(0, it.length, InlineTag::class.java).size } ?: 0 }.getOrNull() ?: 0
        if (prevSpans > 0 && nowSpans == 0 && (nowText?.length ?: 0) == 0) {
            Utils.log("InlineInput.saveDraft: WARNING overwriting draft that had $prevSpans token(s) with an EMPTY box (key=$key et=${System.identityHashCode(et)} attached=${et.isAttachedToWindow}) — staged image/@ being dropped")
        }
        drafts[key] = SpannableStringBuilder(et.text ?: "")
        replyDrafts[key] = reply
        // Mirror to disk so the draft survives an app restart.
        runCatching {
            val json = serializeDraft(et)
            draftPrefs.edit { if (json == null) remove(key) else putString(key, json) }
        }.onFailure { Utils.log("InlineInput persist draft failed: $it") }
    }

    /**
     * Serialize the box to JSON: an ordered list of text / @-mention / image segments, plus the
     * reply target. Images are stored as their local source file path (resolved via AIOPicDownloader,
     * == WatchPicElementExtKt.C0); an image whose path can't be resolved is dropped. Returns null when
     * there is nothing worth keeping (so the caller removes the key).
     */
    private fun serializeDraft(et: ImeEditText): String? {
        val text = et.text ?: return null
        val segs = JSONArray()
        val spans = text.getSpans(0, text.length, InlineTag::class.java)
            .sortedBy { text.getSpanStart(it) }
        var i = 0
        for (tag in spans) {
            val s = text.getSpanStart(tag); val e = text.getSpanEnd(tag)
            if (s < i || s < 0 || e <= s) continue
            if (s > i) segs.put(JSONObject().put("t", text.subSequence(i, s).toString()))
            when (tag) {
                is AtTag -> segs.put(JSONObject().put("at", JSONObject()
                    .put("uid", tag.uid).put("nick", tag.nick).put("type", tag.atType)))
                is ImageTag -> imagePath(tag.element)?.let { segs.put(JSONObject().put("img", it)) }
            }
            i = e
        }
        if (i < text.length) segs.put(JSONObject().put("t", text.subSequence(i, text.length).toString()))
        val o = JSONObject().put("segs", segs)
        reply?.let { o.put("reply", JSONObject().put("id", it.msgId).put("uid", it.senderUid).put("nick", it.nick)) }
        return if (segs.length() == 0 && !o.has("reply")) null else o.toString()
    }

    /** Append a colored, tagged token (used when rebuilding a draft from disk). */
    private fun appendToken(sb: SpannableStringBuilder, label: String, tag: InlineTag) {
        val start = sb.length
        sb.append(label)
        sb.setSpan(tag, start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(tokenColor), start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Local source-file path of a staged pic [element], or null (video / unresolved / missing file). */
    private fun imagePath(element: MsgElement): String? {
        if (element.picElement == null) return null
        val path = runCatching { AIOPicDownloader.a.d(element, PicSize.e) }.getOrNull()
        return path?.takeIf { it.isNotEmpty() && File(it).exists() }
    }

    /** Rebuild the box (text + @ spans) and [reply] from the on-disk JSON. Returns true if applied. */
    private fun restoreFromPrefs(et: ImeEditText, key: String): Boolean {
        val raw = draftPrefs.getString(key, null) ?: return false
        return runCatching {
            val o = JSONObject(raw)
            val sb = SpannableStringBuilder()
            o.optJSONArray("segs")?.let { segs ->
                for (idx in 0 until segs.length()) {
                    val seg = segs.getJSONObject(idx)
                    when {
                        seg.has("t") -> sb.append(seg.getString("t"))
                        seg.has("at") -> {
                            val at = seg.getJSONObject("at")
                            appendToken(sb, "@${at.getString("nick")} ",
                                AtTag(at.getString("uid"), at.getString("nick"), at.optInt("type", 2)))
                        }
                        seg.has("img") -> {
                            // Rebuild a fresh sendable pic element from the persisted local path; skip
                            // if the file is gone (moved/deleted since the draft was saved).
                            val path = seg.getString("img")
                            if (File(path).exists()) {
                                runCatching { WatchMsgUtil.a.a(path, 0) }.getOrNull()
                                    ?.let { appendToken(sb, "[图片]", ImageTag(it)) }
                            }
                        }
                    }
                }
            }
            et.setText(sb)
            et.setSelection(et.text?.length ?: 0)
            val r = o.optJSONObject("reply")
            reply = if (r != null) ReplyState(r.getLong("id"), r.getString("uid"), r.getString("nick")) else null
            drafts[key] = SpannableStringBuilder(et.text ?: "")
            replyDrafts[key] = reply
            true
        }.getOrElse { Utils.log("InlineInput restore-from-prefs failed: $it"); false }
    }

    private val draftWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = saveDraft()
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    /** True once an inline EditText is live (a chat is open with the inline pill built). */
    val isReady: Boolean get() = editTextRef?.get() != null

    // Native (pre-scale) text size per inline EditText, captured once so the size multiplier
    // applied in register() never compounds across re-registers.
    private val editBaseTextSize = java.util.WeakHashMap<ImeEditText, Float>()

    private fun editText(): ImeEditText? = editTextRef?.get()

    /** Called from the input-bar hook each time the inline pill is (re)built for a chat. */
    fun register(editText: ImeEditText, controller: InputBarController) {
        val prev = editTextRef?.get()
        Utils.log("InlineInput.register: et=${System.identityHashCode(editText)} prev=${if (prev != null) System.identityHashCode(prev) else null} barState=${runCatching { controller.g }.getOrNull()} pendingExtraMsg=${IMEOperation.extraMsg.size}")
        editTextRef = WeakReference(editText)
        controllerRef = WeakReference(controller)
        // Backspace on an already-empty box cancels an active 编辑/回复 (returns true to consume it).
        editText.onBackspaceWhenEmpty = { cancelReplyOrEdit() }
        // Match the chat message text size multiplier so the inline input box scales with it.
        // Capture the native size in a tag the first time so re-registers never compound.
        val scale = Settings.chatScale.value
        if (scale != 1.0f) {
            val base = editBaseTextSize.getOrPut(editText) { editText.textSize }
            editText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base * scale)
        }
        reply = null
        hideBanner()
        // Restore this chat's unsent draft (text + @/image spans + reply target), if any. The draft is
        // kept current by draftWatcher on every keystroke and by saveDraft() whenever reply changes, so
        // the store already holds the latest state from the last time this chat was open.
        val key = if (Settings.rememberDraft.value) chatKey() else null
        currentChatKey = key
        if (key != null) {
            val mem = drafts[key]
            Utils.log("InlineInput.register: restoring draft key=$key memLen=${mem?.length} (textBefore=${editText.text?.length})")
            if (mem != null) {
                val memSpans = runCatching { (mem as? Spanned)?.let { d -> d.getSpans(0, d.length, InlineTag::class.java).size } }.getOrNull()
                Utils.log("InlineInput.register: in-memory draft has $memSpans InlineTag span(s)")
                // Same session: restore the full in-memory copy (keeps [图片] image tokens too).
                runCatching {
                    editText.setText(SpannableStringBuilder(mem))
                    editText.setSelection(editText.text?.length ?: 0)
                    reply = replyDrafts[key]
                }.onFailure { Utils.log("InlineInput restore draft failed: $it") }
            } else {
                // After an app restart the in-memory map is empty — rebuild from the on-disk JSON.
                restoreFromPrefs(editText, key)
            }
            editText.addTextChangedListener(draftWatcher)
            val restoredSpans = runCatching { editText.text?.let { it.getSpans(0, it.length, InlineTag::class.java).size } }.getOrNull()
            Utils.log("InlineInput.register: after draft restore textLen=${editText.text?.length} spans=$restoredSpans")
        }
        // The input bar auto-hides on scroll, detaching this EditText; drop the floating banner then.
        // When it reattaches (bar reshown) the reply/edit state is still live, so rebuild the banner —
        // otherwise the reply/edit indicator vanishes after a scroll-hide/reshow.
        editText.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { updateBanner() }
            override fun onViewDetachedFromWindow(v: View) { hideBanner() }
        })
        // Show the reply banner for a restored draft (the attach listener above only fires on a later
        // (re)attach, so an already-attached box wouldn't get it otherwise).
        if (reply != null) updateBanner()
        // 上拉打开键盘: pulling the chat list up past its bottom opens the inline keyboard.
        ChatPullUpKeyboard.attach(editText)
        // If a gallery pick is still awaiting insertion (e.g. focusAndShow switched to this host),
        // drain it into this freshly-registered box now that the draft restore above is done.
        if (pendingImageElements.isNotEmpty()) {
            Utils.log("InlineInput.register: ${pendingImageElements.size} image(s) pending, draining into new host")
            editText.post { drainPendingImages("register") }
        }
    }

    // ---- inputs from the StartImeUtil interception (InlineImeRoute) ----

    /**
     * Apply everything an "aio" keyboard-page open would have carried — reply / @ / staged image(s)
     * / edit-or-复读 prefill — then clear the IMEOperation staging and focus the box.
     */
    fun consumePending() {
        val et = editText() ?: run { Utils.log("InlineInput.consumePending: NO editText (isReady=$isReady)"); return }
        val spansBefore = runCatching { et.text?.let { t -> t.getSpans(0, t.length, InlineTag::class.java).size } }.getOrNull()
        val draftSpans = runCatching { currentChatKey?.let { k -> (drafts[k] as? Spanned)?.let { d -> d.getSpans(0, d.length, InlineTag::class.java).size } } }.getOrNull()
        Utils.log("InlineInput.consumePending: et=${System.identityHashCode(et)} attached=${et.isAttachedToWindow} extraMsg=${IMEOperation.extraMsg.size} extra=${IMEOperation.INSTANCE.extra.size} textLenBefore=${et.text?.length} spansBefore=$spansBefore draftSpans=$draftSpans")
        runCatching {
            // Reply / @ are staged in IMEOperation.extra (newest first via add(0,…)); apply in input order.
            for (obj in IMEOperation.INSTANCE.extra.reversed()) {
                when (obj) {
                    is ReplyElementArg ->
                        setReply(obj.replayMsgId, obj.senderUidStr, TextUtilKt.b64Decode(obj.senderNickname))
                    is AtElementArg ->
                        insertAt(obj.atUid, TextUtilKt.b64Decode(obj.atNickname))
                }
            }
            // Images staged by the gallery flow: stash them and drain into the visible host (below),
            // rather than inserting eagerly here (the host may change in focusAndShow).
            pendingImageElements = ArrayList(IMEOperation.extraMsg)
            // Prefill text: 编辑 (MessageEdit active → banner) or 复读 (plain). Insert WITHOUT
            // focusAndShow (which would raise the keyboard); the single focusAndShow below handles it.
            IMEOperation.extraText?.let {
                if (it.isNotEmpty()) {
                    val start = et.selectionStart.coerceAtLeast(0)
                    val end = et.selectionEnd.coerceAtLeast(start)
                    et.text?.replace(start, end, it)
                }
            }
        }.onFailure { Utils.log("InlineInput.consumePending failed: $it") }
        IMEOperation.extraText = null
        IMEOperation.INSTANCE.clearExtra()
        IMEOperation.extraMsg.clear()
        updateBanner()
        // Show + focus the inline box but DON'T auto-raise the soft keyboard — the user taps the field
        // to type (matches the long-press 回复 behaviour). Avoids the keyboard popping over a not-yet-
        // shown field when replying while the bar is scroll-hidden.
        focusAndShow(showKeyboard = false)
        // Drain after focusAndShow so the (possibly switched) visible host receives the images.
        val cur = editText()
        if (cur != null) cur.post { drainPendingImages("consume") } else drainPendingImages("consume")
        Utils.log("InlineInput.consumePending: pendingImages=${pendingImageElements.size} textLenAfter=${et.text?.length}")
    }

    /**
     * Insert any gallery-staged image tokens into the currently-registered (visible) inline EditText,
     * then clear the staging. Idempotent: the first caller with a live EditText consumes the list, so
     * a later register()/consume() drain becomes a no-op. See [pendingImageElements].
     */
    private fun drainPendingImages(srcTag: String) {
        val imgs = pendingImageElements
        if (imgs.isEmpty()) return
        val et = editText()
        if (et == null) {
            Utils.log("InlineInput.drainPendingImages($srcTag): no editText, keeping ${imgs.size} pending")
            return
        }
        pendingImageElements = emptyList()
        Utils.log("InlineInput.drainPendingImages($srcTag): inserting ${imgs.size} into et=${System.identityHashCode(et)} attached=${et.isAttachedToWindow}")
        runCatching { for (el in imgs) insertImage(el) }
            .onFailure { Utils.log("InlineInput.drainPendingImages($srcTag) failed: $it") }
        saveDraft()
        Utils.log("InlineInput.drainPendingImages($srcTag): textLenAfter=${et.text?.length}")
    }

    /** STT / plain prefill: drop [s] into the box at the caret. */
    fun insertText(s: String) {
        val et = editText() ?: return
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(start)
        et.text?.replace(start, end, s)
        focusAndShow()
    }

    // ---- streaming text insertion (live STT recognition progress) ----
    // The native VoiceInputFragment shows speech-to-text results filling in progressively. With the
    // inline voice recorder ([VoiceRecord]) the recognition callback fires repeatedly with the
    // cumulative text so far; these helpers keep replacing the same range in the inline EditText so
    // the user sees the same real-time progress as the native page. sttAnchor < 0 means no stream is
    // active (so a stray update just inserts at the cursor).
    private var sttAnchor = -1
    private var sttLen = 0

    /** Begin a streaming insertion at the current cursor; shows the keyboard so progress is visible. */
    fun beginStreamingText() {
        val et = editText() ?: return
        sttAnchor = et.selectionStart.coerceAtLeast(0)
        sttLen = 0
        focusAndShow()
    }

    /** Replace the streamed range with the latest cumulative [text]. */
    fun updateStreamingText(text: String) {
        val et = editText() ?: return
        if (sttAnchor < 0) { insertText(text); return }
        val len = et.text?.length ?: 0
        val start = sttAnchor.coerceIn(0, len)
        val end = (sttAnchor + sttLen).coerceIn(start, len)
        et.text?.replace(start, end, text)
        sttLen = text.length
        et.setSelection((start + text.length).coerceAtMost(et.text?.length ?: 0))
    }

    /** Finish the stream: apply the final [text] (if any) and reset the stream state. */
    fun endStreamingText(text: String?) {
        if (text != null) updateStreamingText(text)
        sttAnchor = -1
        sttLen = 0
    }

    private fun insertAt(uid: String, nick: String) = insertToken("@$nick ", AtTag(uid, nick, 2))

    private fun insertImage(element: MsgElement) {
        val et = editText()
        val path = runCatching { imagePath(element) }.getOrNull()
        Utils.log("InlineInput.insertImage: et=${if (et != null) System.identityHashCode(et) else null} hasPic=${runCatching { element.picElement != null }.getOrNull()} srcPath=$path fileExists=${runCatching { path != null && File(path).exists() }.getOrNull()}")
        insertToken("[图片]", ImageTag(element))
    }

    /**
     * Route ready-to-send elements from QQ's native emoji selector (sysface / fav / market face /
     * image-gif) into the box as atomic tokens instead of sending them immediately
     * ([Settings.emojiPickerToInput]). Each element is carried by an [ImageTag] (which buildElements
     * emits verbatim at send time), labelled by its kind so faces show as "[表情]" and pics as "[图片]".
     * Returns false when there is no live inline box to receive them (caller then sends normally).
     */
    fun insertElements(elements: List<MsgElement>): Boolean {
        val et = editText() ?: return false
        runCatching {
            for (el in elements) {
                when {
                    el.picElement != null -> insertToken("[图片]", ImageTag(el))
                    el.faceElement != null -> insertFace(el)
                    else -> insertToken("[表情]", ImageTag(el))
                }
            }
            focusAndShow()
        }.onFailure { Utils.log("InlineInput.insertElements failed: $it") }
        return true
    }

    /**
     * Insert a sysface element so the actual emoji glyph is shown in the box instead of a literal
     * "[表情]". [FaceElementParser] renders the face to a CharSequence carrying QQ's EmoticonSpan
     * (the same span the chat list draws); we lay an atomic [ImageTag] over that run so it deletes
     * in one backspace and [buildElements] emits the exact original element verbatim at send time.
     * Falls back to a plain "[表情]" token if rendering fails (e.g. an unusual face type).
     */
    private fun insertFace(el: MsgElement) {
        val fe = el.faceElement
        // The picker stores a serverId in faceIndex (and faceType 3 = animated sticker, 1/2 = static).
        // Render the static face glyph the same way the inline emoji panel does — convert serverId →
        // localId and build a QQText EmoticonSpan — which works for every type (FaceElementParser only
        // handled plain sysfaces and returned empty for the animated ones). The animated lottie can't
        // play in an EditText, but the static face shows; the original element still sends verbatim.
        val rendered = runCatching {
            val localId = QQSysFaceUtil.a.a(fe.faceIndex)
            QQText(QQSysFaceUtil.a.g(localId), 3, 18, null)
        }.onFailure { Utils.log("insertFace render failed type=${fe.faceType} index=${fe.faceIndex}: $it") }
            .getOrNull()
        Utils.log("insertFace type=${fe.faceType} index=${fe.faceIndex} rendered='${rendered}' len=${rendered?.length}")
        if (rendered.isNullOrEmpty()) { insertToken("[表情]", ImageTag(el)); return }
        insertSpan(rendered, ImageTag(el), colorize = false)
    }

    private fun insertToken(label: String, tag: InlineTag) = insertSpan(label, tag, colorize = true)

    /**
     * Lay [tag] (and optionally the token tint) over [label] and splice it in at the caret as one
     * atomic run. [colorize] is off for rendered faces so the emoji glyph keeps its own colours.
     */
    private fun insertSpan(label: CharSequence, tag: InlineTag, colorize: Boolean) {
        val et = editText() ?: run { Utils.log("InlineInput.insertSpan: ABORT no editText for label='$label' tag=${tag.javaClass.simpleName}"); return }
        val sp = SpannableString(label)
        sp.setSpan(tag, 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (colorize) sp.setSpan(ForegroundColorSpan(tokenColor), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(start)
        val textObj = et.text
        textObj?.replace(start, end, sp)
        // Verify the span actually landed: read it back from the live Editable. If totalSpans doesn't
        // include the one we just set (e.g. text replaced underneath us), the element won't survive to send.
        val live = et.text
        val placed = runCatching { live != null && live.getSpanStart(tag) >= 0 }.getOrNull()
        val totalSpans = runCatching { live?.getSpans(0, live.length, InlineTag::class.java)?.size }.getOrNull()
        Utils.log("InlineInput.insertSpan: label='$label' tag=${tag.javaClass.simpleName} et=${System.identityHashCode(et)} attached=${et.isAttachedToWindow} textNull=${textObj == null} start=$start end=$end newLen=${live?.length} placed=$placed totalSpans=$totalSpans")
    }

    fun setReply(msgId: Long, senderUid: String, nick: String) {
        reply = ReplyState(msgId, senderUid, nick)
        // When 回复带@ is on for a group reply, inject the @sender as a real inline token at the
        // caret so it's visible and the caret can be moved around it — instead of silently
        // prepending it at send time. buildElements emits it from the span (and no longer auto-adds).
        if (Settings.replyWithAt.value && CurrentContact.isGroup) {
            insertAt(senderUid, nick)
        }
        updateBanner()
    }

    private fun onBannerClick() {
        cancelReplyOrEdit()
    }

    /**
     * Cancel an active 编辑/回复. Returns true if there was something to cancel (so a caller — e.g.
     * backspace on an empty box — can consume the gesture). Editing also clears the prefilled
     * original text/@/image tokens; both drop the staged reply.
     */
    fun cancelReplyOrEdit(): Boolean {
        val editing = MessageEdit.editingMsgId != 0L
        if (!editing && reply == null) return false
        if (editing) {
            MessageEdit.consume()
            editText()?.text?.clear()
        }
        reply = null
        updateBanner()
        return true
    }

    // ---- floating reply/edit banner (overlay above the input bar, so it never shrinks the box) ----

    private fun updateBanner() {
        // updateBanner() is the single chokepoint after any reply/edit-state change, so persist the
        // per-chat draft (text is already tracked live by draftWatcher; this captures reply changes).
        saveDraft()
        val editing = MessageEdit.editingMsgId != 0L
        val short = when {
            editing -> "编辑中"
            reply != null -> "回复 ${reply?.nick}"
            else -> null
        }
        // Materialized: drive the inline row inside the pill (moves with the bar; no decoupling).
        val inlineRow = inlineReplyRowRef?.get()
        if (inlineRow != null) {
            if (short == null) {
                inlineRow.visibility = View.GONE
            } else {
                inlineReplyTextRef?.get()?.text = short
                inlineReplyIconRef?.get()?.setImageDrawable(MaterialSymbol(
                    if (editing) MaterialSymbols.edit else MaterialSymbols.reply, tokenColor))
                inlineRow.visibility = View.VISIBLE
            }
            onInlineReplyChanged?.invoke()
            return
        }
        // Non-materialized: floating banner above the bar (the close icon is replaced by the suffix).
        val label = short?.let { "$it（点击取消）" }
        if (label == null) hideBanner() else showBanner(label)
    }

    private fun showBanner(label: String) {
        val et = editText() ?: return
        et.post {
            runCatching {
                // Host the banner in the AIO fragment's container (the same FrameLayout the
                // attachment overlay's scrim is added to) rather than android.R.id.content. That
                // way the overlay — added to this container later — draws ABOVE the banner, so the
                // banner sits at the input-bar layer and is covered by the overlay instead of
                // floating on top of it. (No elevation, so plain child order decides z.)
                val content = AttachmentOverlay.aioContainer(et) ?: return@post
                var banner = bannerRef?.get()
                if (banner == null || banner.parent !== content) {
                    (content.findViewWithTag<View>(BANNER_TAG))?.let { (it.parent as? ViewGroup)?.removeView(it) }
                    banner = TextView(et.context).apply {
                        tag = BANNER_TAG
                        setTextColor(tokenColor)
                        textSize = 11f
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(12.dp, 4.dp, 12.dp, 4.dp)
                        setBackgroundColor(M3.surfaceContainer)
                        isClickable = true
                        setOnClickListener { onBannerClick() }
                    }
                    content.addView(banner, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    ))
                    bannerRef = WeakReference(banner)
                    // Keep the banner pinned just above the input bar as the keyboard pushes it around.
                    // Register on the banner's OWN observer (which is the shared window observer once
                    // attached, so it still sees every global layout) — never the input bar's, since
                    // that view detaches on scroll and would make the matching remove fail. Drop any
                    // previous listener first: re-entering this branch across scroll hide/reshow cycles
                    // otherwise leaks a listener every time, and the pile of stale listeners each
                    // mutate layout during the traversal until the container's child array corrupts
                    // (null-child NPE in FrameLayout.layoutChildren).
                    removeBannerListener()
                    val listener = ViewTreeObserver.OnGlobalLayoutListener { repositionBanner() }
                    bannerLayoutListener = listener
                    banner.viewTreeObserver.addOnGlobalLayoutListener(listener)
                }
                banner.text = label
                banner.visibility = View.VISIBLE
                repositionBanner()
            }.onFailure { Utils.log("InlineInput.showBanner failed: $it") }
        }
    }

    /** Drop the global-layout listener from the (shared, still-attached) banner observer, if any. */
    private fun removeBannerListener() {
        val l = bannerLayoutListener ?: return
        bannerRef?.get()?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(l)
        bannerLayoutListener = null
    }

    private fun repositionBanner() {
        val banner = bannerRef?.get() ?: return
        // A leaked/late listener must never touch a detached banner: mutating its layout would run
        // against a stale parent during a traversal. Bail unless it's still attached to the tree.
        if (!banner.isAttachedToWindow) return
        val et = editText() ?: return
        val content = banner.parent as? View ?: return
        val pill = et.parent as? View ?: et
        val loc = IntArray(2); pill.getLocationInWindow(loc)
        val cloc = IntArray(2); content.getLocationInWindow(cloc)
        val bottomMargin = ((cloc[1] + content.height) - loc[1]).coerceAtLeast(0)
        val lp = banner.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.bottomMargin != bottomMargin) {
            lp.bottomMargin = bottomMargin
            banner.layoutParams = lp
        }
    }

    private fun hideBanner() {
        val banner = bannerRef?.get()
        if (banner != null) {
            removeBannerListener()
            // Defer the removeView off the current frame. hideBanner() is also called from the
            // EditText's onViewDetachedFromWindow, which fires *inside* the AIO fragment view's
            // detach traversal during a chat-exit (fragment pop) transition. Since the banner is a
            // direct child of that fragment view, removing it mid-traversal leaves a null entry in
            // the ViewGroup's children array and crashes later in FragmentContainerView
            // .endViewTransition → dispatchDetachedFromWindow (NPE on the null child). Posting the
            // removal runs it as its own message — never nested inside the traversal — so the
            // children array stays consistent.
            mainHandler.post { (banner.parent as? ViewGroup)?.removeView(banner) }
        }
        bannerLayoutListener = null
        bannerRef = null
    }

    /** Send button enabled when the box has any content (text or @/image tokens make it non-empty). */
    fun hasContent(): Boolean = !editText()?.text.isNullOrEmpty()

    /** Turn the current box contents (text + @ + image spans) and the active reply into MsgElements and send. */
    fun send() {
        val et = editText()
        if (et == null) { Utils.log("inline full send: ABORT no editText (isReady=$isReady)"); return }
        val spanCount = runCatching { et.text?.getSpans(0, et.text!!.length, InlineTag::class.java)?.size }.getOrNull()
        Utils.log("inline full send: enter et=${System.identityHashCode(et)} attached=${et.isAttachedToWindow} textLen=${et.text?.length} spans=$spanCount text='${et.text}'")
        val elements = buildElements()
        if (elements.isEmpty()) {
            Utils.log("inline full send: ABORT buildElements empty (textLen=${et.text?.length} spans=$spanCount) — nothing sent")
            return
        }
        val summary = elements.joinToString(",") { runCatching {
            "type=${it.elementType}${if (it.picElement != null) "/pic" else ""}${if (it.textElement != null) "/text" else ""}"
        }.getOrElse { "?" } }
        Utils.log("inline full send: dispatching ${elements.size} element(s): [$summary]")
        runCatching {
            val editId = MessageEdit.editingMsgId
            if (editId != 0L) {
                MessageEdit.consume()
                Utils.log("inline edit: recall original msgId=$editId then send")
                runCatching { KernelServiceUtil.c()?.recallMsg(CurrentContact, editId, null) }
                    .onFailure { Utils.log("inline edit recall failed: $it") }
            }
            val contact = Contact(CurrentContact.chatType, CurrentContact.peerUid, CurrentContact.guildId)
            MsgUtil.msgService.sendMsg(contact, 0L, elements, IOperateCallback { code, msg ->
                Utils.log("inline full send result=$code msg=$msg")
            })
            et.text?.clear()
            reply = null
            updateBanner()
        }.onFailure { Utils.log("inline full send failed: $it") }
    }

    private fun buildElements(): ArrayList<MsgElement> {
        val et = editText() ?: run { Utils.log("buildElements: no editText"); return arrayListOf() }
        val text = et.text ?: run { Utils.log("buildElements: et.text null (et=${System.identityHashCode(et)})"); return arrayListOf() }
        val out = ArrayList<MsgElement>()
        val api = MsgUtilApiImpl.instance
        Utils.log("buildElements: textLen=${text.length} text='$text'")

        // Reply element only. The @sender (回复带@) is now an inline AtTag token in the box (injected
        // by setReply), so the span walk below emits it — don't also prepend it here (would double it).
        reply?.let { r ->
            val replyEl = api.createReplyElement(r.msgId)
            replyEl.replyElement?.let { it.senderUid = r.senderUid.toLongOrNull() ?: 0L }
            out.add(replyEl)
        }

        // Walk the text in order, splitting at InlineTag spans.
        val spans = text.getSpans(0, text.length, InlineTag::class.java)
            .sortedBy { text.getSpanStart(it) }
        Utils.log("buildElements: found ${spans.size} InlineTag span(s): ${spans.joinToString(",") {
            "${it.javaClass.simpleName}@[${text.getSpanStart(it)},${text.getSpanEnd(it)}]"
        }}")
        var i = 0
        for (tag in spans) {
            val s = text.getSpanStart(tag)
            val e = text.getSpanEnd(tag)
            if (s < i || s < 0 || e <= s) {
                Utils.log("buildElements: SKIP malformed span ${tag.javaClass.simpleName} s=$s e=$e i=$i — its element will be LOST")
                continue
            }
            if (s > i) appendText(out, text.subSequence(i, s))
            when (tag) {
                is AtTag -> {
                    // Ensure a real space text element before/after the @mention unless it sits at
                    // the very start/end of the message (skip if the neighbour is already whitespace).
                    // Spaces must be their own TextElements — baking them into the @ name doesn't render.
                    if (s > 0 && !text[s - 1].isWhitespace()) out.add(api.createTextElement(" "))
                    out.add(api.createAtTextElement("@${tag.nick}", tag.uid, tag.atType))
                    if (e < text.length && !text[e].isWhitespace()) out.add(api.createTextElement(" "))
                }
                is ImageTag -> {
                    val pic = runCatching { tag.element.picElement != null }.getOrNull()
                    Utils.log("buildElements: emit ImageTag element type=${runCatching { tag.element.elementType }.getOrNull()} hasPic=$pic")
                    out.add(tag.element)
                }
            }
            i = e
        }
        if (i < text.length) appendText(out, text.subSequence(i, text.length))
        Utils.log("buildElements: produced ${out.size} element(s)")
        return out
    }

    /** Parse a plain-text run (preserving typed emoji via QQText) into elements. */
    private fun appendText(out: ArrayList<MsgElement>, seq: CharSequence) {
        val str = seq.toString()
        if (str.isEmpty()) return
        val api = MsgUtilApiImpl.instance
        // ImeTextUtil.b builds a QQText for emoji parsing, which chokes on embedded newlines and
        // silently yields no elements — that blocks the whole send. Parse each line on its own and
        // rejoin with explicit "\n" TextElements so multi-line messages send intact.
        val lines = str.split("\n")
        for ((idx, line) in lines.withIndex()) {
            if (idx > 0) out.add(api.createTextElement("\n"))
            if (line.isEmpty()) continue
            runCatching { out.addAll(ImeTextUtil.a.b(line)) }
                .onFailure {
                    Utils.log("InlineInput.appendText parse failed: $it")
                    out.add(api.createTextElement(line))
                }
        }
    }

    /** Newline-safe plain-text → MsgElements, for callers without span handling (e.g. sendInline). */
    fun parseTextElements(str: String): ArrayList<MsgElement> {
        val out = ArrayList<MsgElement>()
        appendText(out, str)
        return out
    }

    /**
     * Public entry for "pull up over chat to open the keyboard" (ChatPullUpKeyboard). Pops the
     * floating input bar and focuses the inline EditText, same as a reply/@/STT route would.
     */
    fun openKeyboard() {
        if (editText() == null) return
        focusAndShow()
    }

    /**
     * Pop the inline input bar (if scrolled-up / hidden) and focus the EditText. [showKeyboard]
     * controls whether the soft keyboard is also raised: the reply / @ / edit consume path passes
     * false so staging a reply doesn't auto-open the keyboard (the user taps the field to type — the
     * same behaviour as the long-press menu's 回复). Explicit keyboard requests (the pull-up gesture,
     * STT) pass true.
     */
    private fun focusAndShow(showKeyboard: Boolean = true) {
        // The input bar auto-hides (state g=0, arrow only) when the chat list is scrolled up. f(true)
        // targets the sliver state (g=1) which only shows at the list bottom, so it does nothing here.
        // Instead simulate pressing the up-arrow (showArrowListener m) which pops the floating input
        // (g=2) overlaid on the chat regardless of scroll. Skip when already shown (g==1 sliver / g==2).
        val etBefore = editText()
        runCatching {
            controllerRef?.get()?.let { c ->
                Utils.log("InlineInput.focusAndShow: barState=${c.g} etBefore=${if (etBefore != null) System.identityHashCode(etBefore) else null}")
                if (c.g != 1 && c.g != 2) {
                    Utils.log("InlineInput: bar hidden (state=${c.g}), simulating up-arrow")
                    c.m.onClick(editText())
                }
            }
        }.onFailure { Utils.log("InlineInput.forceShowBar failed: $it") }
        val et = editText() ?: return
        if (et !== etBefore) Utils.log("InlineInput.focusAndShow: host SWITCHED to et=${System.identityHashCode(et)} (was ${if (etBefore != null) System.identityHashCode(etBefore) else null})")
        et.post {
            runCatching {
                et.isFocusableInTouchMode = true
                et.requestFocus()
                et.setSelection(et.text?.length ?: 0)
                et.requestRectangleOnScreen(Rect(0, 0, et.width, et.height), true)
                // requestFocus alone won't raise the soft keyboard; ask the IMM explicitly — but only
                // when asked to (e.g. the reply consume path suppresses it so it doesn't auto-pop).
                if (showKeyboard) {
                    (et.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager)
                        ?.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }.onFailure { Utils.log("InlineInput.focusAndShow failed: $it") }
        }
    }
}
