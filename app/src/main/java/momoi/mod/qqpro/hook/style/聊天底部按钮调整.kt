package momoi.mod.qqpro.hook.style

import android.annotation.SuppressLint
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.forEach
import androidx.core.widget.doAfterTextChanged
import com.tencent.mobileqq.app.ThreadManagerV2
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MemberRole
import com.tencent.watch.aio_impl.coreImpl.helper.GroupAIOHelper
import com.tencent.watch.aio_impl.coreImpl.vb.`InputBarController$inputContent$2`
import com.tencent.watch.aio_impl.coreImpl.vb.InputBarControllerKt
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.asGroup
import momoi.mod.qqpro.drawable.plusIconDrawable
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.drawable.sendIconDrawable
import momoi.mod.qqpro.hook.AttachmentOverlay
import momoi.mod.qqpro.hook.InlineEmojiPanel
import momoi.mod.qqpro.hook.InlineInput
import momoi.mod.qqpro.hook.VoiceRecord
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentGroupMembers
import momoi.mod.qqpro.hook.action.SelfContact
import momoi.mod.qqpro.hook.action.isGroup
import momoi.mod.qqpro.hook.translate.MessageTranslate
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.ImeEditText
import momoi.mod.qqpro.lib.GroupScope
import momoi.mod.qqpro.lib.adjustViewBounds
import momoi.mod.qqpro.lib.background
import momoi.mod.qqpro.lib.bitmapDecodeAssets
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.create
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.height
import momoi.mod.qqpro.lib.imageResource
import momoi.mod.qqpro.lib.longClickable
import momoi.mod.qqpro.lib.margin
import momoi.mod.qqpro.lib.marginHorizontal
import momoi.mod.qqpro.lib.onAttach
import momoi.mod.qqpro.lib.onDetach
import momoi.mod.qqpro.lib.onEachLayout
import momoi.mod.qqpro.lib.onFocusChange
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.paddingHorizontal
import momoi.mod.qqpro.lib.scaleType
import momoi.mod.qqpro.lib.size
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.util.Utils
import java.io.File
import java.io.FileOutputStream
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols

@Mixin
class 聊天底部按钮调整() : `InputBarController$inputContent$2`() {
    @SuppressLint("ResourceType", "ClickableViewAccessibility")
    override fun invoke(): Any = (super.invoke() as ConstraintLayout).apply {
        forEach {
            it.visibility = View.INVISIBLE
        }
        // Inline mode (with the "单行输入" setting off): let the bar grow with a multiline EditText
        // instead of the fixed 36dp height. When 单行输入 is on, keep the original single-line bar.
        // The container ConstraintLayout doesn't wrap-content reliably around our unconstrained
        // child, so its height is driven explicitly from the EditText line count (see onEachLayout).
        val inlineGrow = Settings.inlineChatInput.value && !Settings.singleLineInput.value
        // Materialize the input chrome (filled pill, M3 symbol icons, primary send circle). Only the
        // inline pill path below honours it (the non-inline keyboard-key branch has no EditText).
        val mat = Settings.materializeChat.value
        val rootContainer = this
        val emoji = getChildAt(0)
        val keyboard = getChildAt(2)
        GroupScope(this).apply {
            // A FRESH drawable per button — one shared GradientDrawable instance can't back several
            // views at once (its bounds/callback follow the last view, so earlier ones, e.g. the "+",
            // render as a thin sliver). Material accent circle for the side icon buttons.
            val roundBg = { roundCornerDrawable(M3.primary, 9999f) }
            // Material primary pill for the keyboard button — same accent color as the +/voice
            // buttons (instead of QQ's native blue rounded-rect drawable).
            val inputBg = { roundCornerDrawable(M3.primary, 9999f) }
            // 圆屏模式：左右边距至少取圆屏安全区，避免输入框两侧按钮被圆边裁切。
            val sideSpaceDp = if (momoi.mod.qqpro.lib.RoundWatch.enabled) {
                val density = rootContainer.resources.displayMetrics.density
                maxOf(
                    Settings.screenCornerDiameter.value.toInt(),
                    (momoi.mod.qqpro.lib.RoundWatch.horizontalInsetPx(rootContainer.context) / density).toInt(),
                )
            } else {
                Settings.screenCornerDiameter.value.toInt()
            }
            // Baseline single-line height of the bar; buttons stay this tall while the EditText grows.
            val lineH = 36.dp
            // Height of the inline reply/edit row (materialized) added on top of the input row inside
            // the pill; the bar grows by this much while a reply/edit is active (see applyInlineGrow).
            val replyRowH = 20.dp
            add<LinearLayout>().size(FILL, FILL).apply {
                    if (momoi.mod.qqpro.lib.RoundWatch.enabled) {
                        paddingHorizontal((14.dp / Settings.scale.value).toInt())
                    }
                }.content {
                    val voice =
                        create<ImageView>()
                            .height(if (Settings.inlineChatInput.value) lineH else FILL)
                            .adjustViewBounds()
                            .apply {
                                if (mat) setImageDrawable(MaterialSymbol(MaterialSymbols.mic, M3.onSurfaceVariant))
                                else bitmapDecodeAssets("pro/ic_voice.png")
                            }.padding(6.dp)
                            .scaleType(ImageView.ScaleType.FIT_CENTER)
                    if (Settings.fullInlineInput.value) {
                        // 完全行内输入: hold-to-record inline (timer + slide-to-cancel / slide-to-STT
                        // overlay) instead of opening the native VoiceInputFragment page.
                        VoiceRecord.attach(voice)
                    } else {
                        ThreadManagerV2.getUIHandlerV2().post {
                            b.e.invoke(voice)
                        }
                    }

                    if (Settings.inlineChatInput.value) {
                        // One large pill holds the emoji button (left), the EditText
                        // (middle) and the mic/send button (right). The emoji button
                        // hides while the keyboard is open; the mic turns into a send
                        // button whenever there is text to send.
                        // Fixed corner radius (half the single-line height) instead of a 9999 capsule:
                        // at one line it still looks like a pill, but as the bar grows the radius stays
                        // put so it becomes a rounded rectangle that expands UPWARD. The side buttons are
                        // bottom-anchored (Gravity.BOTTOM) so they stay pinned to the bottom while the
                        // EditText grows above them, instead of riding up with a vertically-centred pill.
                        // Material: a rounded RECTANGLE (12dp corners — NOT a capsule) with a
                        // surface fill AND a 1dp outline, holding a reply/edit row on top of the input
                        // row (vertical). Non-material keeps the original translucent capsule (single row).
                        val pillRadius = if (mat) M3.radiusMd else if (inlineGrow) (lineH / 2f) else 9999f
                        val pill = create<LinearLayout>().height(FILL).weight(1f)
                            .background(
                                if (mat) M3.filledOutlined(M3.surfaceContainer, M3.outline, pillRadius)
                                else roundCornerDrawable(0x22_FFFFFF, pillRadius))
                            .gravity(if (inlineGrow) Gravity.BOTTOM else Gravity.CENTER_VERTICAL)
                        if (mat) pill.vertical()
                        val send = create<ImageView>().scaleType(ImageView.ScaleType.FIT_CENTER).apply {
                            if (mat) {
                                // Compact filled-primary send circle (26dp). Glyph kept at 16dp via the
                                // 5dp padding (26 − 2·5). A bottom margin of (lineH − 26)/2 lifts it off
                                // the row floor so its centre lines up with the other full-height buttons.
                                size(26.dp, 26.dp)
                                padding(5.dp)
                                (layoutParams as? LinearLayout.LayoutParams)?.apply {
                                    // Equal bottom + right inset so the circle is evenly spaced from the
                                    // pill's bottom-right corner (centres it against the other buttons).
                                    val m = (lineH - 26.dp) / 2
                                    bottomMargin = m
                                    marginEnd = m
                                }
                                background = roundCornerDrawable(M3.primary, 9999f)
                                setImageDrawable(MaterialSymbol(MaterialSymbols.send, M3.onPrimary))
                            } else {
                                height(lineH); adjustViewBounds(); padding(8.dp)
                                setImageDrawable(sendIconDrawable())
                            }
                            visibility = View.GONE
                        }
                        lateinit var emojiBtn: ImageView
                        lateinit var emojiToggle: ImageView
                        lateinit var editText: ImeEditText
                        lateinit var hintView: TextView
                        // Inline reply/edit row (materialized only): built below and driven by
                        // InlineInput (show/hide + text/icon). Null in non-materialized mode.
                        var replyRow: LinearLayout? = null
                        var replyIcon: ImageView? = null
                        var replyText: TextView? = null
                        // Bar auto-grow state + routine. The input bar has two homes (see
                        // WatchAIOListVB / InputBarController):
                        //  • at the chat bottom it lives INSIDE a list footer (the 44dp "sliver"
                        //    container b.d()), so growing that footer naturally pushes messages up;
                        //  • scrolled up it floats in an overlay (b.a(), WRAP_CONTENT) that covers the
                        //    list — we just let it overlay (like the native float bar). We must NOT
                        //    change the RecyclerView padding here: that fires onScrolled, and the native
                        //    scroll listener then collapses the floating bar to the up-arrow (state 0),
                        //    making the EditText vanish on the next newline.
                        // Driven from text changes and re-attach as well as layout passes, because in
                        // the floating overlay global-layout passes are sparse (the list is static), so
                        // onEachLayout alone makes the bar grow only after a collapse/reopen.
                        var sliverBaseH = -1
                        // Per-instance dedup for the grow log. MUST be per bar (captured here), not a
                        // shared top-level global: two concurrently-attached bars (visible float + a
                        // stale/neighbor footer instance) with different states would each invalidate
                        // the other's dedup on a shared global, ping-ponging the log every frame.
                        var lastInlineGrowLog = ""
                        val applyInlineGrow = fun() {
                            val parent = rootContainer.parent as? ViewGroup ?: return
                            var p: ViewGroup? = parent
                            var depth = 0
                            while (p != null && depth < 4) {
                                p.clipChildren = false
                                p.clipToPadding = false
                                p = p.parent as? ViewGroup
                                depth++
                            }
                            val lines = editText.lineCount.coerceIn(1, 4)
                            // The inline reply/edit row (materialized) adds its height on top of the
                            // input row, so the bar must grow by it while a reply/edit is active.
                            val replyExtra = if (replyRow?.visibility == View.VISIBLE) replyRowH else 0
                            val target = lineH + (lines - 1) * editText.lineHeight + replyExtra
                            val lp = rootContainer.layoutParams ?: return
                            (lp as? FrameLayout.LayoutParams)?.gravity = Gravity.BOTTOM
                            if (lp.height != target) {
                                lp.height = target
                                rootContainer.layoutParams = lp
                            }
                            val sliver = runCatching { b.d() }.getOrNull()
                            if (sliver != null && sliverBaseH < 0) {
                                sliverBaseH = sliver.layoutParams?.height?.takeIf { it > 0 } ?: (44.dp)
                            }
                            val inFooter = sliver != null && parent === sliver
                            // Footer mode (at bottom): grow the footer item so it pushes messages up and
                            // the taller bar is fully laid out & touchable. Float mode: keep the now empty
                            // footer at its base height so it leaves no blank gap at the list end.
                            sliver?.layoutParams?.let { slp ->
                                val want = if (inFooter) target + sliver.paddingTop + sliver.paddingBottom else sliverBaseH
                                if (slp.height != want) {
                                    slp.height = want
                                    sliver.layoutParams = slp
                                }
                            }
                            // applyInlineGrow runs on EVERY global-layout pass (onEachLayout is an
                            // OnGlobalLayoutListener, so it fires many times/second while scrolling or
                            // typing). Dedup the log on the MEANINGFUL grow state ONLY. The old key
                            // folded in the volatile geometry (rootTop/rootH), which shifts almost every
                            // frame — so the dedup never matched and the log spammed qqpro_debug.log
                            // (drowning the very inlineGrow transitions this line exists to show). The
                            // geometry is still emitted for debugging, just not used to gate the log.
                            // Include the InputBarController identity (ctrl) so multiple concurrent bar
                            // instances (visible + a stale/leaked one) are distinguishable, and any
                            // return of the footer/float reparent thrash (see InputBarAlwaysFloat) is
                            // visible as this instance's state flipping rather than hidden by dedup.
                            val inlineGrowKey = "state=${runCatching { b.g }.getOrNull()} inFooter=$inFooter lines=$lines target=$target sliverH=${sliver?.layoutParams?.height} ctrl=${System.identityHashCode(b)}"
                            if (inlineGrowKey != lastInlineGrowLog) {
                                lastInlineGrowLog = inlineGrowKey
                                Utils.log("inlineGrow $inlineGrowKey parent=${parent.javaClass.simpleName} rootTop=${rootContainer.top} rootH=${rootContainer.height}")
                            }
                        }
                        // Materialized: split the pill into a reply/edit row on top of the input row.
                        // The reply row lives INSIDE the pill view tree, so it scrolls with the bar
                        // (the old floating banner decoupled visually while scrolling). Hidden until
                        // InlineInput shows it. The input row (emoji/editText/voice/send) is added by
                        // the content block below, into `contentGroup`.
                        val contentGroup: LinearLayout = if (mat) {
                            val ctx = rootContainer.context
                            val row = LinearLayout(ctx).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                visibility = View.GONE
                                setPadding(10.dp, 3.dp, 6.dp, 0)
                            }
                            val ic = ImageView(ctx).apply {
                                setImageDrawable(MaterialSymbol(MaterialSymbols.reply, M3.primary))
                            }
                            val tv = TextView(ctx).apply {
                                setTextColor(M3.primary)
                                textSize = 10f
                                isSingleLine = true
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                gravity = Gravity.CENTER_VERTICAL
                            }
                            val closeBtn = ImageView(ctx).apply {
                                setImageDrawable(MaterialSymbol(MaterialSymbols.close, M3.onSurfaceVariant))
                                setPadding(3.dp, 3.dp, 3.dp, 3.dp)
                                background = M3.ripple(null)
                            }.clickable { InlineInput.cancelReplyOrEdit() }
                            row.addView(ic, LinearLayout.LayoutParams(14.dp, 14.dp).apply { marginEnd = 4.dp })
                            row.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
                            row.addView(closeBtn, LinearLayout.LayoutParams(20.dp, 20.dp))
                            replyRow = row; replyIcon = ic; replyText = tv
                            val inputRow = LinearLayout(ctx).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = if (inlineGrow) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
                            }
                            pill.addView(row, LinearLayout.LayoutParams(FILL, WRAP))
                            // Base height = lineH (NOT 0): a height-0 weighted child only expands when
                            // the pill has a fixed height, but the pill's height comes from the bar
                            // container, which is only sized by applyInlineGrow (wired only when
                            // inlineGrow is on, and only after the EditText lays out). In the float host
                            // (WRAP_CONTENT) or with 单行输入 on, the pill would otherwise measure this row
                            // at 0 → the whole input section collapses to 0px and stops receiving touch
                            // until an @-insert focuses the field and forces a grow. A lineH base makes
                            // the pill always wrap to at least one row; weight still lets it grow upward.
                            pill.addView(inputRow, LinearLayout.LayoutParams(FILL, lineH).apply { weight = 1f })
                            inputRow
                        } else pill
                        contentGroup.content {
                            emojiBtn = add<ImageView>().height(lineH).adjustViewBounds()
                                .scaleType(ImageView.ScaleType.FIT_CENTER).padding(8.dp)
                            if (Settings.attachmentOverlay.value) {
                                // Emoji button becomes a "+" that opens the attachment overlay.
                                emojiBtn.setImageDrawable(
                                    if (mat) MaterialSymbol(MaterialSymbols.add, M3.onSurfaceVariant)
                                    else plusIconDrawable())
                                emojiBtn.clickable { hideIme(emojiBtn); AttachmentOverlay.show(emojiBtn, emoji) }
                            } else {
                                if (mat) emojiBtn.setImageDrawable(MaterialSymbol(MaterialSymbols.mood, M3.onSurfaceVariant))
                                else emojiBtn.bitmapDecodeAssets("pro/ic_emoji.png")
                                emojiBtn.clickable { hideIme(emojiBtn); emoji.callOnClick() }
                            }
                            // Emoji-picker toggle, shown only while typing (inlineEmojiButton). It sits
                            // in the same left slot as emojiBtn (which hides once there is text), so it
                            // appears as an emoji key regardless of the attachment-overlay "+" setting.
                            emojiToggle = add<ImageView>().height(lineH).adjustViewBounds()
                                .scaleType(ImageView.ScaleType.FIT_CENTER).padding(8.dp)
                                .apply {
                                    if (mat) setImageDrawable(MaterialSymbol(MaterialSymbols.mood, M3.onSurfaceVariant))
                                    else bitmapDecodeAssets("pro/ic_emoji.png")
                                }
                            emojiToggle.visibility = View.GONE
                            emojiToggle.clickable { InlineEmojiPanel.toggle(editText) }
                            // Long-press the typing emoji key to open the attachment overlay (+ menu)
                            // and collapse the keyboard, mirroring the "+" button's behaviour.
                            emojiToggle.longClickable {
                                InlineEmojiPanel.dismiss()
                                hideIme(emojiToggle)
                                AttachmentOverlay.show(emojiToggle, emoji)
                            }
                            // The built-in single-line hint and autosize both fight a growing
                            // multiline field, so drop them and overlay a custom hint TextView inside
                            // a FrameLayout that we toggle with the text. When 单行输入 is on (inlineGrow
                            // false) the field stays single-line; otherwise it grows up to 4 lines.
                            val inputWrap = add<FrameLayout>().height(FILL).weight(1f)
                            editText = create<ImeEditText>()
                                .background(null)
                                .paddingHorizontal(4.dp)
                                .textColor(M3.onSurface).textSize(14f)
                                .gravity(Gravity.CENTER_VERTICAL)
                                .apply {
                                    if (inlineGrow) {
                                        inputType = InputType.TYPE_CLASS_TEXT or
                                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                                        // Grow up to 4 lines, then the field scrolls internally.
                                        maxLines = 4
                                    } else {
                                        inputType = InputType.TYPE_CLASS_TEXT or
                                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                                        isSingleLine = true
                                        maxLines = 1
                                    }
                                }
                            // Both keyboard-committed images (Gboard GIF/sticker/clipboard paste via
                            // the IME) and clipboard paste-menu images splice into the box as an inline
                            // "[图片]" token (composed with the rest of the message) rather than sending
                            // immediately; each falls back to a direct send when no inline box is active.
                            editText.onImageUri = { uri -> pasteImeImageAsToken(uri) }
                            editText.onImagePaste = { uri -> pasteImeImageAsToken(uri) }
                            hintView = create<TextView>()
                                .text("说点什么...")
                                .textColor(if (mat) M3.hint else 0x80_FFFFFF.toInt())
                                .textSize(14f)
                                .gravity(Gravity.CENTER_VERTICAL)
                                .paddingHorizontal(4.dp)
                                .apply { isSingleLine = true; maxLines = 1 }
                            inputWrap.addView(editText, FrameLayout.LayoutParams(
                                FILL, FILL, Gravity.CENTER_VERTICAL))
                            inputWrap.addView(hintView, FrameLayout.LayoutParams(
                                WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START))
                            // Drive the bottom-bar container height from the EditText's line count so
                            // the bar (whose ConstraintLayout won't wrap-content reliably) grows with
                            // the text, up to 4 lines, then the field scrolls inside the fixed height.
                            // Bottom-anchor the bar and disable clipping on its parents so it grows
                            // UPWARD off the fixed-height float/sliver containers instead of off-screen.
                            if (inlineGrow) {
                                (rootContainer.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.BOTTOM
                                editText.onEachLayout { applyInlineGrow() }
                                // Re-show after a scroll-hide doesn't change the text, so onEachLayout
                                // alone (sparse in the float overlay) may leave the bar collapsed though
                                // it still holds multiline text — re-apply on attach.
                                editText.onAttach { editText.post { applyInlineGrow() } }
                                // When the bar collapses (state 0 / scroll-hide) the EditText detaches
                                // and onEachLayout stops firing, so a grown footer height would otherwise
                                // linger as a blank gap with no bar present. Reset it to base on detach;
                                // it re-applies when the bar shows again.
                                editText.onDetach {
                                    runCatching {
                                        val sliver = b.d()
                                        sliver.layoutParams?.let {
                                            if (sliverBaseH > 0 && it.height != sliverBaseH) {
                                                it.height = sliverBaseH
                                                sliver.layoutParams = it
                                            }
                                        }
                                    }
                                }
                            }
                            if (!Settings.hideVoiceButton.value) add(voice.background(null))
                            add(send)
                        }
                        send.clickable {
                            if (Settings.fullInlineInput.value) InlineInput.send()
                            else sendInline(editText)
                        }
                        // Long-press the send button to translate the input (into 发送语言) without
                        // sending; the field shows a 翻译中… placeholder while in flight.
                        if (Settings.translateSendButton.value) {
                            send.longClickable { MessageTranslate.translateInput(editText, send) }
                        }
                        // Mic/send/emoji button visibility for the current text. Extracted so it can
                        // also be re-run when voice-to-text conversion finishes (see below).
                        val applyButtonState = fun() {
                            // Use isNullOrEmpty (not isNullOrBlank): a space is real content the
                            // user typed (the hint already disappeared), so it must flip to send.
                            val hasText = !editText.text.isNullOrEmpty()
                            // While STT is converting, the mic shows a spinner — keep it visible (and
                            // the send button hidden) even though recognized text is filling in, so
                            // the progress indicator doesn't vanish on the first character.
                            val converting = Settings.fullInlineInput.value && VoiceRecord.isConverting
                            hintView.visibility = if (hasText) View.GONE else View.VISIBLE
                            val emojiMode = hasText && Settings.inlineEmojiButton.value
                            // Hide the emoji / "+" button when there is text, to make room for
                            // the send button; show the emoji-picker toggle in its place if enabled.
                            emojiBtn.visibility = if (hasText) View.GONE else View.VISIBLE
                            emojiToggle.visibility = if (emojiMode) View.VISIBLE else View.GONE
                            voice.visibility = when {
                                converting -> View.VISIBLE
                                hasText || Settings.hideVoiceButton.value -> View.GONE
                                else -> View.VISIBLE
                            }
                            send.visibility = if (hasText && !converting) View.VISIBLE else View.GONE
                            if (!emojiMode) InlineEmojiPanel.dismiss()
                        }
                        editText.doAfterTextChanged {
                            applyButtonState()
                            // Grow/shrink the bar on every edit. Posted so editText.lineCount reflects
                            // the new text (it updates after the next measure). In the floating overlay
                            // layout passes are too sparse to catch this on their own.
                            if (inlineGrow) editText.post { applyInlineGrow() }
                        }
                        if (Settings.fullInlineInput.value) {
                            // Re-evaluate the buttons when STT starts/finishes so the spinner survives
                            // the live text insertion and the mic flips to send only once converted.
                            VoiceRecord.onConvertStateChanged = { applyButtonState() }
                        }
                        if (Settings.fullInlineInput.value) {
                            // Materialized: drive the reply/edit indicator into the inline row built
                            // above (it moves with the bar — no decoupling). Otherwise use the floating
                            // banner. Register the inline row BEFORE register() so a restored draft's
                            // reply target renders straight into it.
                            val rr = replyRow; val ri = replyIcon; val rt = replyText
                            if (mat && rr != null && ri != null && rt != null) {
                                InlineInput.registerInlineReply(rr, ri, rt) { applyInlineGrow() }
                            } else {
                                InlineInput.clearInlineReply()
                            }
                            InlineInput.register(editText, b)
                        }
                        add(pill.marginHorizontal(sideSpaceDp.dp))
                    } else {
                        val left = add<ImageView>().height(FILL).adjustViewBounds()
                            .scaleType(ImageView.ScaleType.FIT_CENTER).background(roundBg()).padding(8.dp)
                        if (Settings.attachmentOverlay.value) {
                            left.setImageDrawable(plusIconDrawable())
                            left.clickable { hideIme(left); AttachmentOverlay.show(left, emoji) }
                        } else {
                            left.bitmapDecodeAssets("pro/ic_emoji.png")
                            left.clickable { hideIme(left); emoji.callOnClick() }
                        }
                        voice.background(roundBg())
                        val input = if (Settings.text.isEmpty()) {
                            create<ImageView>().bitmapDecodeAssets("pro/ic_keyboard.png")
                                .scaleType(ImageView.ScaleType.FIT_CENTER).padding(8.dp)
                        } else {
                            create<TextView>().gravity(Gravity.CENTER).textSize(14f)
                                .textColor(M3.onSurface).text(Settings.text)
                        }.height(FILL).weight(1f)
                            .background(inputBg()).clickable {
                                keyboard.callOnClick()
                            }

                        if (Settings.hideVoiceButton.value) {
                            add(input)
                        } else if (Settings.swapCenterKeyboard.value) {
                            add(input.marginHorizontal(2.dp))
                            add(voice)
                        } else {
                            add(voice.marginHorizontal(2.dp))
                            add(input)
                        }
                    }
                }
        }
        // 全员禁言 (whole-group mute) + non-admin: hide this input bar and show a hint instead.
        if (Settings.muteHideInputBar.value) applyGroupMuteHint(this)
        // 圆屏模式：输入框整体上移，底部留圆形内切矩形的安全边距（对称缩进基准 insetPx），
        // 发送键/语音键底部不被圆边切掉。post 一次等原生把 layoutParams 装好再设 bottomMargin。
        if (momoi.mod.qqpro.lib.RoundWatch.enabled) {
            val container = rootContainer
            container.post {
                val lp = container.layoutParams as? ViewGroup.MarginLayoutParams ?: return@post
                lp.bottomMargin = momoi.mod.qqpro.lib.RoundWatch.insetPx(container.context)
                container.layoutParams = lp
            }
        }
    }

    private fun sendInline(editText: EditText) {
        val text = editText.text?.toString().orEmpty()
        // Don't drop whitespace — a space is valid content the user chose to send.
        if (text.isEmpty()) return
        runCatching {
            val elements = InlineInput.parseTextElements(text)
            val contact = Contact(
                CurrentContact.chatType,
                CurrentContact.peerUid,
                CurrentContact.guildId
            )
            MsgUtil.msgService.sendMsg(contact, 0L, elements, IOperateCallback { code, msg ->
                Utils.log("inline chat send result=$code msg=$msg")
            })
            editText.setText("")
        }.onFailure { Utils.log("inline chat send failed: $it") }
    }
}

/**
 * Hide the soft keyboard if it's open. Called when the "+"/emoji button is tapped so the IME
 * collapses automatically before the attachment overlay or emoji panel opens.
 * Top-level (not inside the @Mixin body) to stay clear of the mixin-copy package issues.
 */
fun hideIme(view: View) {
    runCatching {
        val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }.onFailure { Utils.log("hideIme failed: $it") }
}

/**
 * Copy an image/GIF URI from the IME keyboard into a temp file and send it as a chat message.
 * Must be a top-level function (not inside a @Mixin body) so the Thread lambda has a public
 * constructor when the mixin is copied into the target package.
 */
fun sendImeImage(uri: Uri) {
    Thread {
        runCatching {
            val ctx = Utils.application
            val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull() ?: ""
            val ext = when {
                mime == "image/gif" -> "gif"
                mime == "image/png" -> "png"
                mime == "image/webp" -> "webp"
                else -> "jpg"
            }
            val dir = ctx.getExternalFilesDir("photos") ?: ctx.filesDir
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "qqpro_ime_${System.currentTimeMillis()}.$ext")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { input.copyTo(it) }
            }
            if (!file.exists() || file.length() == 0L) {
                Utils.log("IME image: empty file for $uri"); return@runCatching
            }
            val element = com.tencent.watch.aio_impl.ext.MsgUtil().a(file.path, 0)
            MsgUtil.msgService.sendMsg(
                CurrentContact, 0L, arrayListOf(element),
                IOperateCallback { code, msg -> Utils.log("IME image send result=$code msg=$msg") }
            )
            Utils.log("IME image sent: $uri -> ${file.path}")
        }.onFailure { Utils.log("IME image send failed: $it") }
    }.start()
}

/**
 * Copy a pasted clipboard image URI into a temp file, build its pic element off the UI thread, then
 * splice it into the inline input as an atomic "[图片]" token (so it's composed with the rest of the
 * message and sent on the next send). If there is no live inline box to receive it — e.g. 完全行内输入
 * is off — fall back to sending the image directly, matching [sendImeImage]. Top-level (not in a
 * @Mixin body) so its Thread/Runnable lambdas have public constructors when copied to the target.
 */
fun pasteImeImageAsToken(uri: Uri) {
    Thread {
        runCatching {
            val ctx = Utils.application
            val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull() ?: ""
            val ext = when {
                mime == "image/gif" -> "gif"
                mime == "image/png" -> "png"
                mime == "image/webp" -> "webp"
                else -> "jpg"
            }
            val dir = ctx.getExternalFilesDir("photos") ?: ctx.filesDir
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "qqpro_paste_${System.currentTimeMillis()}.$ext")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { input.copyTo(it) }
            }
            if (!file.exists() || file.length() == 0L) {
                Utils.log("paste image: empty file for $uri"); return@runCatching
            }
            val element = com.tencent.watch.aio_impl.ext.MsgUtil().a(file.path, 0)
            momoi.mod.qqpro.util.runOnUi {
                // insertElements returns false when no inline EditText is registered → send directly.
                if (!InlineInput.insertElements(listOf(element))) {
                    MsgUtil.msgService.sendMsg(
                        CurrentContact, 0L, arrayListOf(element),
                        IOperateCallback { code, msg -> Utils.log("paste image send result=$code msg=$msg") }
                    )
                    Utils.log("paste image: no inline box, sent directly $uri -> ${file.path}")
                } else {
                    Utils.log("paste image: inserted as token $uri -> ${file.path}")
                }
            }
        }.onFailure { Utils.log("paste image failed: $it") }
    }.start()
}

private const val MUTE_HINT_TAG = "qqpro_mute_hint"

/**
 * When the current group is under 全员禁言 (whole-group mute) and the current user is NOT owner/admin,
 * hide [bar] (the built input row) and show a "全员禁言中" hint in its place — the user can't send
 * anyway, so the input bar is useless and misleading.
 *
 * Whole-group mute comes from [GroupAIOHelper]'s per-group [com.tencent.qqnt.kernel.nativeinterface.GroupDetailInfo]
 * cache (`shutUpAllTimestamp != 0`), populated async on chat entry — so retry a few times until it
 * arrives. Self role comes from [CurrentGroupMembers] (waits for the member list). Both lambdas live
 * in this top-level function (NOT the @Mixin body) so they don't crash when the hook is copied.
 */
// Must be public (not private): it's called from the @Mixin-copied invoke() and from lambdas, which
// are separate classes/packages — a private static method triggers IllegalAccessError at runtime.
fun applyGroupMuteHint(bar: ViewGroup, attempt: Int = 0) {
    if (!CurrentContact.isGroup) return
    val detail = runCatching { GroupAIOHelper.b[CurrentContact.peerUid] }.getOrNull()
    if (detail == null) {
        // Group detail not cached yet (async flow on chat entry) — retry briefly, then give up.
        if (attempt < 10) bar.postDelayed({ applyGroupMuteHint(bar, attempt + 1) }, 300)
        return
    }
    if (detail.shutUpAllTimestamp == 0) {
        Utils.log("muteHint: group ${CurrentContact.peerUid} not 全员禁言")
        return
    }
    CurrentGroupMembers.get(SelfContact.peerUid) { self ->
        val privileged = self.role == MemberRole.OWNER || self.role == MemberRole.ADMIN
        Utils.log("muteHint: 全员禁言 active, selfRole=${self.role}, privileged=$privileged")
        if (!privileged) showMutedHint(bar)
    }
}

/** Hide every child of [bar] and overlay a centered "全员禁言中" hint (idempotent). Public for the
 *  same cross-class-access reason as [applyGroupMuteHint] (it's called from a lambda class). */
fun showMutedHint(bar: ViewGroup) {
    if (bar.findViewWithTag<View>(MUTE_HINT_TAG) != null) return
    bar.forEach { it.visibility = View.GONE }
    val hint = TextView(bar.context).apply {
        tag = MUTE_HINT_TAG
        text = "全员禁言中"
        gravity = Gravity.CENTER
        // Themed secondary text — the old translucent white was invisible on a light input bar.
        setTextColor(M3.onSurfaceVariant)
        textSize = 13f
    }
    bar.addView(
        hint,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    )
    Utils.log("muteHint: input bar hidden, showing 全员禁言中")
}

/**
 * Synchronous "is the current chat under 全员禁言 and am I not allowed to speak?" — the gate the actual
 * input ENTRY POINTS use. [showMutedHint] only hides the visible input bar; without this a muted
 * non-admin can still surface the EditText via the pull-up keyboard, a reply tap, an @, an edit, or
 * STT — none of which look at that bar. Gate those opens with this instead.
 *
 * Mute is the sync per-group cache ([GroupAIOHelper] `shutUpAllTimestamp`); self role is the sync bulk
 * member cache ([CurrentGroupMembers.info]). If the role isn't cached yet the group is still confirmed
 * muted, so block (fail closed) — a brief block for an admin whose list hasn't loaded beats letting a
 * muted member type. Public for cross-package access from the @StaticHook / @Mixin call sites.
 */
fun isWholeMutedForSelf(): Boolean {
    if (!Settings.muteHideInputBar.value || !CurrentContact.isGroup) return false
    val detail = runCatching { GroupAIOHelper.b[CurrentContact.peerUid] }.getOrNull() ?: return false
    if (detail.shutUpAllTimestamp == 0) return false
    val role = CurrentGroupMembers.info?.get(SelfContact.peerUid)?.role
    return role != MemberRole.OWNER && role != MemberRole.ADMIN
}
