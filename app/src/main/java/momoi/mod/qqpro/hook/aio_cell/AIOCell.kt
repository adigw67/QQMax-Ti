package momoi.mod.qqpro.hook.aio_cell

import android.annotation.SuppressLint
import android.content.Context
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.View
import com.tencent.mobileqq.text.style.EmoticonSpan
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tencent.qqnt.kernel.nativeinterface.MemberInfo
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ui.cell.base.BaseWatchItemCell
import com.tencent.watch.aio_impl.ui.cell.unsupport.WatchToQQViewMsgItem
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.enums.NTMsgType
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.fitEmojiSpans
import momoi.mod.qqpro.renderQQFaces
import momoi.mod.qqpro.hook.ChatMultiSelect
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentGroupMembers
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.action.isGroup
import momoi.mod.qqpro.hook.translate.MessageTranslate
import momoi.mod.qqpro.lib.create
import momoi.mod.qqpro.hook.parseAtMembers
import momoi.mod.qqpro.util.linkify
import com.tencent.watch.aio_impl.ui.cell.av.WatchQavItem
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.parseHexColor
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.warpOnce
import android.widget.LinearLayout
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private fun lpName(v: Int?) = when (v) {
    null -> "?"
    ViewGroup.LayoutParams.MATCH_PARENT -> "FILL"
    ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP"
    else -> v.toString()
}

object AIOCell {
    val AIOCellGroupWidget.contentWidget get() = this.getContentWidget<View>()!!
    val hooks = mutableListOf<Hook<*>>()


    /**
     * Apply the user's chat text color / size overrides to every TextView under [view]
     * (recursively). Used for all message bodies — plain text, text+image and the special-cell
     * views (reply/forward/card/struct/file) — so the style is consistent everywhere.
     */
    fun applyMsgTextStyle(view: View?, loc: Int) {
        view ?: return
        val color = resolveMsgTextColor(loc)
        fun walk(v: View) {
            if (v is TextView) {
                v.setTextColor(color)
                // Keep any Material-symbol icon (e.g. the call-record phone/video icon) matched to the
                // message text color, including custom overrides.
                (v.compoundDrawables.asSequence() + v.compoundDrawablesRelative.asSequence())
                    .forEach { (it as? MaterialSymbol)?.recolor(color) }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
    }

    /** Recolor every TextView under [view] to [color] (recursive). Used to theme grey-tip cells. */
    private fun recolorTextViews(view: View?, color: Int) {
        view ?: return
        if (view is TextView) view.setTextColor(color)
        if (view is ViewGroup) for (i in 0 until view.childCount) recolorTextViews(view.getChildAt(i), color)
    }

    /**
     * The message text color for a bubble side [loc] (0 = other/对方, else self/我的): the per-side
     * override (other = [Settings.textColor], self = [Settings.textColorSelf]), else auto-contrast
     * against that side's bubble color so light bubbles get dark text and dark bubbles light text.
     */
    private fun resolveMsgTextColor(loc: Int): Int {
        val side = if (loc == 0) Settings.textColor else Settings.textColorSelf
        parseHexColor(side.value)?.let { return it }
        return M3.onColor(BubbleCorner.resolvedBubbleColor(loc))
    }

    init {
        addHook<ReplyView>(
            type = NTMsgType.REPLY,
            onBind = { msg, widget ->
                val reply = msg.elements.firstNotNullOf { it.replyElement }
                loadData(CurrentContact, reply)
                setOnClickListener(ReplyClick(widget, reply))
                // The native bubble already renders its own "回复 xxx" line inside the content;
                // with our ReplyView quote shown too that makes two cards. Hide the native line
                // (any TextView starting with 回复/引用/>>) and keep only our quote + content.
                fun hideNativeReply(v: View) {
                    // Never touch OUR reply card — its quoted text can legitimately start with
                    // 回复/引用 (e.g. replying to a reply), which would otherwise blank it.
                    if (v.tag == "qqpro_reply_view") return
                    if (v is TextView) {
                        val t = v.text?.toString()?.trimStart()
                        if (t != null && (t.startsWith("回复") || t.startsWith("引用") || t.startsWith(">>"))) {
                            // Hide the whole native quote row (the reply line + its time sibling),
                            // not just the text — otherwise the native card's timestamp stays.
                            val parent = v.parent
                            if (parent is ViewGroup && parent.childCount <= 3) {
                                parent.visibility = View.GONE
                            } else {
                                v.visibility = View.GONE
                            }
                        }
                    }
                    if (v is ViewGroup) for (i in 0 until v.childCount) hideNativeReply(v.getChildAt(i))
                }
                hideNativeReply(widget)
                // The native quote can inflate after bind — re-hide on the next frame.
                widget.post { hideNativeReply(widget) }
            },
            appendMode = true
        )
        addHook<ForwardMsgView>(
            type = NTMsgType.MULTIMSGFORWARD,
            onBind = { msg, widget ->
                if (msg.forwardData == null) {
                    msg.forwardData = ForwardMsgData(CurrentContact, msg, msg)
                }
                loadData(CurrentContact, msg.forwardData!!)
            },
        )
        addHook<CardMsgView>(
            type = NTMsgType.ARKSTRUCT,
            onBind = { msg, widget ->
                loadData(msg.elements.firstNotNullOf { it.arkElement })
            }
        )
        addHook<StructMsgView>(
            type = NTMsgType.STRUCT,
            onBind = { msg, widget ->
                loadData(msg.elements.firstNotNullOf { it.structMsgElement })
            }
        )
        // File transfers (local FILE and group ONLINEFILE) otherwise fall through
        // to the orange "view on phone" placeholder; render a name + size card.
        val fileBind: FileMsgView.(MsgRecordEx, AIOCellGroupWidget) -> Unit = { msg, widget ->
            loadData(msg.elements.firstNotNullOf { it.fileElement })
        }
        addHook<FileMsgView>(type = NTMsgType.FILE, onBind = fileBind)
        addHook<FileMsgView>(type = NTMsgType.ONLINEFILE, onBind = fileBind)
    }

    inline fun <reified T : View> addHook(
        type: Int,
        noinline onBind: T.(MsgRecordEx, AIOCellGroupWidget) -> Unit,
        appendMode: Boolean = false
    ) {
        hooks.add(
            Hook(
                type = type,
                onBind = onBind,
                createView = { create<T>(it) },
                appendMode = appendMode
            )
        )
    }

    class Hook<T : View>(
        val type: Int,
        private val onBind: T.(MsgRecordEx, AIOCellGroupWidget) -> Unit,
        val createView: (Context) -> T,
        val appendMode: Boolean
    ) {
        private val views = WeakHashMap<AIOCellGroupWidget, WeakReference<T>>()
        @Suppress("UNCHECKED_CAST")
        fun bind(widget: AIOCellGroupWidget, view: View, msg: MsgRecordEx) {
            view.visibility = View.VISIBLE
            if (!appendMode) {
                widget.contentWidget.visibility = View.GONE
            }
            onBind(view as T, msg, widget)
        }

        fun getOrCreate(widget: AIOCellGroupWidget): T {
            return views.getOrPut(widget) {
                val view = createView(widget.context)
                val warp = widget.contentWidget.warpOnce()
                // The WeakHashMap entry may have been GC'd between binds on this low-RAM watch,
                // leaving the previous instance still attached → duplicate cards (e.g. double
                // reply quotes). Drop any same-tag sibling before adding the fresh one.
                val tag = view.tag
                if (tag != null) {
                    val children = (warp as? ViewGroup)?.let { g ->
                        (0 until g.childCount).map { g.getChildAt(it) }
                    } ?: emptyList()
                    children.filter { it !== view && it.tag == tag }.forEach {
                        (it.parent as? ViewGroup)?.removeView(it)
                    }
                }
                warp.addView(view, 0)
                WeakReference(view)
            }.get()!!
        }

        fun recover(widget: AIOCellGroupWidget) {
            views[widget]?.get()?.let {
                it.visibility = View.GONE
                if (!appendMode) {
                    widget.contentWidget.visibility = View.VISIBLE
                }
            }
        }
    }

    @Mixin
    abstract class HookCell : BaseWatchItemCell<WatchAIOMsgItem, View>() {
        @SuppressLint("SetTextI18n")
        override fun i(
            view: View,
            item: WatchAIOMsgItem,
            p3: Int,
            p4: List<Any>,
            p5: Lifecycle,
            p6: LifecycleOwner?
        ) {
            super.i(view, item, p3, p4, p5, p6)
            // 消息多选: record this cell's view→msgId so a tap/long-press in multi-select mode can
            // resolve which message was hit, and install the selection touch listener + decoration on
            // the RecyclerView (once per list). No-op (transparent) unless multi-select is active.
            runCatching {
                ChatMultiSelect.bindCell(view, item.d.msgId, item.d.msgType)
            }.onFailure { Utils.log("ChatMultiSelect.bindCell failed: $it") }
            // Universal per-bind diagnostic: log EVERY message as it binds, not just the
            // WatchToQQView placeholder / text bubbles. Previously most cell types (pic, mix,
            // ark, file, forward, markdown, …) emitted no log line at all, so messages appeared
            // to "go missing" and couldn't be pinned. Element type + presence here identifies the
            // exact kind of any message (incl. the ones that fall through to other cells).
            // Diagnostic only — building the element dump reflects over every field, which is far
            // too expensive to run per message when logging is off (the dominant scroll cost).
            if (Utils.loggingEnabled) runCatching {
                val r = item.d
                val els = runCatching { r.elements }.getOrNull().orEmpty()
                val parts = els.joinToString(" | ") { e ->
                    "${runCatching { e.elementType }.getOrNull()}:${elementContent(e)}"
                }
                Utils.log(
                    "MsgBind item=${item.javaClass.simpleName} cell=${view.javaClass.simpleName} " +
                        "msgType=${runCatching { r.msgType }.getOrNull()} sub=${runCatching { r.subMsgType }.getOrNull()} " +
                        "sender=${runCatching { r.senderUid }.getOrNull()} seq=${runCatching { r.msgSeq }.getOrNull()} " +
                        "els[${els.size}]=[$parts]"
                )
            }.onFailure { Utils.log("MsgBind dump failed: $it") }
            // Diagnostic for the orange "请在手机QQ查看" placeholder (WatchToQQViewMsgItem): dump what the
            // message actually carries, so we can tell whether the content is present client-side (some
            // typed element non-null → potentially renderable with a new cell hook) or the watch only
            // received a server-side stub (no usable element → not recoverable on the watch).
            if (item is WatchToQQViewMsgItem && Utils.loggingEnabled) runCatching {
                val r = item.d
                val els = runCatching { r.elements }.getOrNull().orEmpty()
                Utils.log("UnsupportedMsg msgType=${r.msgType} subType=${r.subMsgType} elementCount=${els.size} content='${item.o}'")
                els.forEachIndexed { i, e ->
                    Utils.log("  UnsupportedMsg el[$i] elementType=${runCatching { e.elementType }.getOrNull()} present=[${elementPresence(e)}]")
                }
            }.onFailure { Utils.log("UnsupportedMsg dump failed: $it") }
            // Grey-tip cells (WatchGrayTipsCell) have a bare TextView as their root view —
            // not an AIOCellGroupWidget — and the native cell sets no movement method, so
            // the member-name spans built into tipsContent (see GrayTipMention.kt) are
            // inert. Enable them here; the spans themselves are created at decode time.
            if (view is TextView) {
                // Grey-tip cells keep raw QQ sysface codes on this watch build (they render as □
                // boxes); parse them into face spans, preserving the clickable member-name spans
                // (QQText copies existing spans, and sysface parsing is length-preserving so their
                // offsets stay valid). Skip when already rendered (recycled view).
                val t = view.text
                val hasFace = (t as? Spanned)
                    ?.getSpans(0, t.length, EmoticonSpan::class.java)?.isNotEmpty() == true
                if (!t.isNullOrEmpty() && !hasFace) {
                    val rendered = renderQQFaces(t, 14)
                    fitEmojiSpans(rendered, view.textSize)
                    view.text = rendered
                }
                if (Settings.parseAtMember.value && view.movementMethod !is LinkMovementMethod) {
                    view.movementMethod = LinkMovementMethod.getInstance()
                    view.highlightColor = 0x33888888
                }
            }
            val widget = view as? AIOCellGroupWidget ?: return
            run {
                val senderUid = item.d.senderUid
                val nickView = widget.getNickWidget<TextView>()
                // Theme the nick text for BOTH group and DM (group re-sets it in bindNick too): the
                // native nick is white → invisible on a light bubble in light mode.
                nickView?.setTextColor(M3.onSurface)
                // hide the avatar/nick header when the previous (older) message in
                // the list is from the same sender, so consecutive messages only
                // show the header once. Applies in both group AND DM chats.
                val hideHeader = Settings.hideRepeatedSender.value
                    && item.d.msgType != NTMsgType.GRAYTIPS
                    && run {
                        // Use the live adapter list (not the racy mirror) to find the previous msg.
                        val prev = CurrentMsgList.prevMsg(item)
                        prev != null && prev.d.msgType != NTMsgType.GRAYTIPS
                            && prev.d.senderUid == senderUid
                    }
                if (hideHeader) {
                    // The cell (AIOCellGroupWidget.onMeasure) sizes itself as
                    //   nick.measuredHeight + content + time + nickContentDistance
                    // reading the nick's RAW measuredHeight WITHOUT checking its visibility.
                    // A plain GONE therefore leaves the nick's STALE measured height on a
                    // recycled cell (super.onMeasure / FrameLayout skips GONE children, so
                    // it's never re-measured to 0) → a phantom header-sized gap after scroll.
                    // Fix: GONE it (so it isn't drawn) AND force its measured height to 0
                    // ourselves. Because the parent never re-measures a GONE child, that 0
                    // sticks. NEVER mutate layoutParams — lp persists across recycling and
                    // corrupts the list; only visibility + a forced child measure are safe.
                    nickView?.let {
                        it.tag = null
                        it.text = ""
                        it.setCompoundDrawables(null, null, null, null)
                        it.visibility = View.GONE
                        it.measure(
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY)
                        )
                    }
                } else {
                    nickView?.let {
                        // Restore visibility only — never touch layoutParams. The parent's
                        // next onMeasure re-measures this now-VISIBLE nick to its real height.
                        it.visibility = View.VISIBLE
                        it.tag = senderUid
                    }
                    // The custom avatar/nick rebind is group-only: it relies on the
                    // group-member lookup and group-card naming. In DM, leave the
                    // header as the native super.i() rendered it (only the collapse
                    // above is our doing) so consecutive-message combining still works.
                    if (CurrentContact.isGroup) {
                        // Avatar depends only on the message record, so apply it now and
                        // unconditionally — never gate it on the async member lookup, which
                        // silently drops self / missing members and would otherwise leave a
                        // recycled cell showing the previous sender's avatar.
                        GroupAvatarHook.bindAvatar(widget, item.d)
                        // Nick text needs member info, so it follows the member callback.
                        CurrentGroupMembers.get(senderUid) { member ->
                            val apply = {
                                if (widget.getNickWidget<TextView>()?.tag == senderUid) {
                                    GroupAvatarHook.bindNick(widget, item.d, member)
                                }
                            }
                            // Cache hits call back synchronously on the main thread during this bind
                            // pass — apply immediately so the leveled nick replaces the native text in
                            // the SAME frame (no one-frame flicker while scrolling). Only the async
                            // (kernel network) callback, off the main thread, needs to post.
                            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) apply()
                            else widget.post(apply)
                        }
                    }
                }
            }
            // Resolve the single matching hook first, then recover every other
            // hook BEFORE binding the match. recover() re-shows contentWidget when
            // that hook previously owned a view for this (recycled) widget; running
            // it after bind() would re-reveal the orange "view on phone" text on top
            // of e.g. the ark card. Binding last guarantees contentWidget ends hidden.
            // Group replies sometimes arrive as MIX (2) with a replyElement instead of msgType REPLY
            // (9) — match the reply hook on either so the quote block + tap-to-jump work in groups too.
            val matched = hooks.firstOrNull { h ->
                h.type == item.d.msgType ||
                    (h.type == NTMsgType.REPLY && item.d.elements.any { it.replyElement != null })
            }
            hooks.forEach { if (it !== matched) it.recover(widget) }
            var matchedView: View? = null
            matched?.let {
                val view = it.getOrCreate(widget)
                matchedView = view
                it.bind(widget, view, item.d as MsgRecordEx)
                (item as? WatchToQQViewMsgItem)?.o = ""
            }
            // Only linkify the native text bubble. Special messages (file/struct/
            // ark/forward) hide contentWidget and render their own view, so running
            // linkify on it would e.g. match a file extension in the hidden text.
            // Append-mode hooks (reply) keep contentWidget, so still linkify those.
            if (matched == null || matched.appendMode) {
                (widget.contentWidget as? TextView)?.let {
                    // Match @mention usernames FIRST so they win over URL/number matching: linkify()
                    // skips any range already covered by a mention span (see Linkify.overlapsReserved).
                    it.parseAtMembers()
                    it.linkify()
                    // Reset the content AND every wrapper LinearLayout it sits inside back to
                    // "hug content". When this cell is recycled from one that had a
                    // +1/link-preview/special view, the content stays wrapped in one (or, from
                    // older builds, several NESTED) vertical LinearLayouts whose lp still carries
                    // weight=1 (FILL/0/1f). A weighted child balloons to fill all remaining vertical
                    // space — a one-line message rendered a full-screen-tall bubble. WRAP alone
                    // doesn't undo weight; only weight=0 does, and the weight may live on an
                    // intermediate wrapper, not the TextView. Walk the whole chain up to the bubble
                    // FrameLayout. LinkPreview re-asserts FILL/weight on the content afterwards when
                    // a preview is actually present, so this only neutralises plain text.
                    it.layoutParams?.let { lp ->
                        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        (lp as? LinearLayout.LayoutParams)?.weight = 0f
                    }
                    var p = it.parent
                    while (p is LinearLayout) {
                        (p.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            lp.weight = 0f
                        }
                        p = p.parent
                    }
                    it.requestLayout()
                    // Diagnostic: dump the full ancestor lp chain for wrapped plain-text cells so we
                    // can see exactly what is forcing the bubble height on the real device.
                    if (it.parent is LinearLayout) {
                        val sb = StringBuilder("bubble-chain text='${it.text?.take(8)}' ")
                        var v: View? = it
                        var depth = 0
                        while (v != null && depth < 8) {
                            val lp = v.layoutParams
                            val w = lp?.width; val h = lp?.height
                            val wt = (lp as? LinearLayout.LayoutParams)?.weight
                            sb.append("\n  [${depth}] ${v.javaClass.simpleName} lp=${lpName(w)}x${lpName(h)} wt=$wt mH=${v.measuredHeight} vis=${v.visibility}")
                            v = v.parent as? View
                            depth++
                        }
                        Utils.log(sb.toString())
                    }
                }
            }
            // 通话记录图标: on a call-record bubble (通话时长 …) swap the native phone/video BITMAP
            // (aio_telephone_filled_icon_white / aio_video_on_filled_icon_white, fixed 32px) for a Material
            // vector, scaled to the text size. Done here (after native bind) rather than by @Mixin'ing the
            // cell's generic d(K,T,…) — that broke super-routing (AbstractMethodError). applyMsgTextStyle
            // below then recolors this MaterialSymbol to match the text.
            if (item is WatchQavItem) runCatching {
                (widget.contentWidget as? TextView)?.let { tv ->
                    val size = (tv.textSize * 1.2f).toInt()
                    if (size > 0) {
                        val icon = MaterialSymbol(
                            if (item.r) MaterialSymbols.videocam else MaterialSymbols.call,
                            tv.currentTextColor,
                        )
                        icon.setBounds(0, 0, size, size)
                        tv.setCompoundDrawablesRelative(icon, null, null, null)
                        tv.compoundDrawablePadding = (size * 0.28f).toInt()
                    }
                    // Gate the native redial-on-tap behind a confirmation (avoid accidental calls).
                    CallRecordConfirm.gate(tv, item.r)
                }
            }
            // Apply the user's message text color / size override to ALL message text, not just
            // plain-text bubbles: recurse the content body (covers text+image and other mixed
            // cells) and the matched special-cell view (reply/forward/card/struct/file). The
            // nick/time header lives outside contentWidget, so it's left untouched.
            val loc = runCatching { widget.locationType }.getOrDefault(0)
            applyMsgTextStyle(runCatching { widget.contentWidget }.getOrNull(), loc)
            applyMsgTextStyle(matchedView, loc)
            // The per-message timestamp is Canvas-drawn with a SHARED paint (default #99ffffff) — set
            // its color so it adapts to light/dark instead of being near-invisible on a light surface.
            runCatching { widget.aioRuntime.a().color = M3.onSurfaceVariant }
            // Grey-tip system messages render through contentWidget, so applyMsgTextStyle just colored
            // them as bubble text — override to the themed tip color (mention spans keep their color).
            if (item.d.msgType == NTMsgType.GRAYTIPS) {
                recolorTextViews(runCatching { widget.contentWidget }.getOrNull(), M3.onSurfaceTip)
            }
            BubbleCorner.apply(widget)
            // Same guard as linkify: don't run link preview off a special message's
            // hidden contentWidget text (e.g. a file extension matched as a URL).
            if (matched == null || matched.appendMode) {
                LinkPreview.bind(widget)
            } else {
                LinkPreview.hide(widget)
            }
            PlusOneButton.bind(widget, item)
            // Translate this text bubble (manual 翻译 entry or per-chat 翻译全部消息). No-op for
            // non-text cells / when translation isn't requested. Runs last so the original text
            // (set/linkified above) is the translation source and the result renders below/over it.
            MessageTranslate.bind(widget, item)
        }
    }

}

/** Names of the non-null typed sub-elements on a [com.tencent.qqnt.kernel.nativeinterface.MsgElement]
 *  (e.g. "textElement,arkElement"), via reflection — used by the unsupported-message diagnostic to
 *  reveal which content a "view on phone QQ" message still carries. Empty → no usable element. */
private fun elementPresence(e: Any): String = runCatching {
    e.javaClass.fields
        .filter { it.name.endsWith("Element") }
        .mapNotNull { f -> runCatching { if (f.get(e) != null) f.name else null }.getOrNull() }
        .joinToString(",")
}.getOrDefault("?")

/**
 * Full content dump of a message element: finds the non-null typed sub-element (textElement,
 * picElement, arkElement, fileElement, multiForwardMsgElement, …) and dumps all of its scalar
 * field values (String / ByteArray-decoded / number). Even when field names are R8-obfuscated,
 * the VALUES expose the real text / JSON / file name / ark payload, so any message — incl. the
 * ones that fall through to the "view on phone" placeholder — is fully identifiable from the log.
 */
private fun elementContent(e: Any): String = runCatching {
    val typed = e.javaClass.fields
        .filter { it.name.endsWith("Element") }
        .firstNotNullOfOrNull { f -> runCatching { f.get(e) }.getOrNull()?.let { f.name to it } }
        ?: return "?"
    val (name, obj) = typed
    val fields = obj.javaClass.fields.mapNotNull { f ->
        val v = runCatching { f.get(obj) }.getOrNull() ?: return@mapNotNull null
        val s = when (v) {
            is CharSequence -> v.toString()
            is ByteArray -> runCatching { String(v) }.getOrDefault("<${v.size}b>")
            is Number, is Boolean, is Char -> v.toString()
            else -> return@mapNotNull null
        }.trim()
        if (s.isEmpty() || s == "0" || s == "false") null else "${f.name}=${s.take(160)}"
    }
    "$name{${fields.joinToString(" ").take(500)}}"
}.getOrDefault("?")
