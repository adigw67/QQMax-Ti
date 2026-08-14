package momoi.mod.qqpro.hook.aio_cell

import android.annotation.SuppressLint
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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
import momoi.mod.qqpro.enums.ElementType
import momoi.mod.qqpro.enums.NTMsgType
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.fitEmojiSpans
import momoi.mod.qqpro.renderQQFaces
import momoi.mod.qqpro.hook.ChatMultiSelect
import momoi.mod.qqpro.hook.FontPack
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
import org.json.JSONObject
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
                // 字体包启用时逐字兜底（MiSans 未覆盖的生僻字用 Unifont），幂等包装。
                v.text = FontPack.fallback(v.text)
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
     * 防撤回标记：把“ 已撤回”小字附到被保留显示的撤回消息气泡文字后。
     * 定位正文 TextView 时不再只依赖 textElement：markdown / struct(小程序) / ark(卡片)
     * 等复合元素也能产出候选文本（含去掉标记/标签的纯文本版本）；候选全部匹配不上时
     * 退回标记气泡内最长的 TextView（昵称/时间都远短于正文）。幂等：已带“已撤回”跳过。
     */
    fun appendRecallMark(item: WatchAIOMsgItem, root: View?) {
        if (root == null || !Settings.antiRecall.value || !CurrentMsgList.isRecalled(item)) return
        val candidates = recallTextCandidates(item)
        if (candidates.isEmpty()) {
            Utils.log("antiRecall: 无文本候选可定位红字 msgId=${item.d.msgId}")
            return
        }
        runCatching {
            var marked = false
            fun visit(v: View) {
                if (marked) return
                if (v is TextView) {
                    val cur = normalizeRecallText(v.text?.toString().orEmpty())
                    if (cur.isEmpty() || cur.contains("已撤回")) return
                    if (candidates.any { cur.contains(it) }) {
                        markRecalled(v)
                        marked = true
                    }
                } else if (v is ViewGroup) {
                    for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
            }
            visit(root)
            if (!marked) {
                // 兜底：渲染结果与候选差异大（如 markdown 渲染后插入按钮标签）时，
                // 标记气泡内文本最长的 TextView。
                var best: TextView? = null
                var bestLen = -1
                fun longest(v: View) {
                    if (v is TextView) {
                        val cur = v.text?.toString().orEmpty()
                        if (cur.isBlank() || cur.contains("已撤回")) return
                        if (cur.length > bestLen) {
                            best = v
                            bestLen = cur.length
                        }
                    } else if (v is ViewGroup) {
                        for (i in 0 until v.childCount) longest(v.getChildAt(i))
                    }
                }
                longest(root)
                if (best != null) {
                    Utils.log("antiRecall: 候选未匹配按最长文本兜底 msgId=${item.d.msgId} candidates=${candidates.take(3)}")
                    markRecalled(best!!)
                } else {
                    Utils.log("antiRecall: 未找到可标记的正文 TextView msgId=${item.d.msgId}")
                }
            }
        }.onFailure { Utils.log("anti-recall marker failed: $it") }
    }

    private fun markRecalled(tv: TextView) {
        val cur = tv.text?.toString().orEmpty()
        val sp = SpannableStringBuilder(cur)
        val start = sp.length
        sp.append(" 已撤回")
        sp.setSpan(
            RelativeSizeSpan(0.72f), start, sp.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sp.setSpan(
            ForegroundColorSpan(M3.error), start, sp.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tv.text = sp
    }

    /**
     * 收集撤回消息元素里可用于定位正文 TextView 的候选文本（归一化后去重）。
     * 覆盖 text / markdown / struct XML / ark JSON / linkInfo 各元素。
     */
    private fun recallTextCandidates(item: WatchAIOMsgItem): List<String> {
        val out = ArrayList<String>()
        runCatching { item.d.elements }.getOrNull()?.forEach { el ->
            runCatching { el.textElement?.content }.getOrNull()?.let { out.add(it) }
            runCatching { el.markdownElement?.content }.getOrNull()?.let {
                out.add(it)
                // markdown 渲染后 **加粗** → 加粗，候选去掉语法标记后即可匹配。
                out.add(Regex("""[*_~`#>]""").replace(it, ""))
            }
            runCatching { el.structMsgElement?.xmlContent }.getOrNull()?.let {
                out.add(it)
                val stripped = Regex("""<[^>]+>""").replace(it, " ")
                out.add(stripped)
                // 标签替换成空格会拆开相邻文本，再去空格生成紧贴版本便于匹配。
                out.add(Regex("""\s+""").replace(stripped, ""))
            }
            runCatching { el.arkElement?.bytesData }.getOrNull()?.let {
                // ark JSON：取所有可读字符串片段（title/desc/prompt/文本等）。
                out.addAll(collectJsonStrings(it))
            }
            runCatching { el.arkElement?.linkInfo?.title }.getOrNull()?.let { out.add(it) }
            runCatching { el.arkElement?.linkInfo?.desc }.getOrNull()?.let { out.add(it) }
        }
        val norm = out.mapNotNull { s ->
            normalizeRecallText(s).takeIf { it.length >= 2 && it.length <= 300 }
        }
        return norm.distinct()
    }

    /** 递归收集 JSON 里所有 2..200 字符的字符串值，用于定位卡片/小程序正文。 */
    private fun collectJsonStrings(raw: String): List<String> {
        val out = ArrayList<String>()
        try {
            fun walk(v: Any) {
                when (v) {
                    is JSONObject -> {
                        val it = v.keys()
                        while (it.hasNext()) walk(v.get(it.next() as String))
                    }
                    is org.json.JSONArray -> {
                        for (i in 0 until v.length()) walk(v.get(i))
                    }
                    is String -> {
                        if (v.length in 2..200) out.add(v)
                    }
                }
            }
            walk(JSONObject(raw))
        } catch (_: Exception) {
            // 非 JSON 或解析失败：忽略，调用方已有其他候选
        }
        return out
    }

    /** 归一化：去零宽字符、统一空白、解 XML 实体、去首尾空白。 */
    private fun normalizeRecallText(s: String): String =
        s.replace("\u200b", "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * The message text color for a bubble side [loc] (0 = other/对方, else self/我的): the per-side
     * override (other = [Settings.textColor], self = [Settings.textColorSelf]), else auto-contrast
     * against that side's bubble color so light bubbles get dark text and dark bubbles light text.
     */
    private fun resolveMsgTextColor(loc: Int): Int {
        val side = if (loc == 0) Settings.textColor else Settings.textColorSelf
        parseHexColor(side.value)?.let { return it }
        // 自己的消息：深色模式白色、浅色模式黑色（用户显式设置过文字颜色则优先）。
        // 自动对比色对半透明 primaryContainer 的亮度判断不可靠（忽略 alpha），这里按模式直接定。
        if (loc != 0) {
            return if (Settings.lightMode.value) 0xFF_000000.toInt() else 0xFF_FFFFFF.toInt()
        }
        return M3.onColor(BubbleCorner.resolvedBubbleColor(loc))
    }

    init {
        addHook<ReplyView>(
            type = NTMsgType.REPLY,
            onBind = { msg, widget ->
                // firstNotNullOf 在元素存在但对应子元素为 null（如部分小程序/复合消息的
                // 结构）时抛 NoSuchElementException，主线程未捕获会直接崩/卡死——全部改安全写法。
                val reply = msg.elements.firstNotNullOfOrNull { it.replyElement } ?: return@addHook
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
                val ark = msg.elements.firstNotNullOfOrNull { it.arkElement } ?: return@addHook
                loadData(ark)
            }
        )
        addHook<StructMsgView>(
            type = NTMsgType.STRUCT,
            onBind = { msg, widget ->
                val struct = msg.elements.firstNotNullOfOrNull { it.structMsgElement } ?: return@addHook
                loadData(struct)
            }
        )
        // File transfers (local FILE and group ONLINEFILE) otherwise fall through
        // to the orange "view on phone" placeholder; render a name + size card.
        val fileBind: FileMsgView.(MsgRecordEx, AIOCellGroupWidget) -> Unit = { msg, widget ->
            val file = msg.elements.firstNotNullOfOrNull { it.fileElement }
            if (file != null) loadData(file)
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
            // 表情回应：气泡下展示已有表态（空则跳过；点击小圆片切换本人表态）。
            runCatching {
                EmojiReaction.attach(view, item.d)
                if (Utils.loggingEnabled) {
                    val likes = runCatching { item.d.emojiLikesList }.getOrNull().orEmpty()
                    if (likes.isNotEmpty()) {
                        Utils.log(
                            "EmojiReaction: msg=${item.d.msgId} likes=" +
                                likes.joinToString("|") { "${it.emojiId}:${it.emojiType}:${it.likesCnt}:${it.isClicked}" }
                        )
                    }
                }
            }.onFailure { Utils.log("EmojiReaction.attach failed: $it") }
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
            // 机器人消息占位兜底：手表内核把部分机器人消息直接以文本下发，内容就是
            // “[暂不支持该消息类型，请用手机QQ查看]”。检测到占位文本就按 msgId 补拉真实内容，
            // 拉到后（RobotMsgFetcher 缓存 + patchVisible）替换显示；拉不到保持占位。
            runCatching {
                val txt = item.d.elements?.firstOrNull { it.elementType == ElementType.TEXT }
                    ?.textElement?.content
                if (txt != null && txt.contains("暂不支持") && txt.contains("手机QQ")) {
                    RobotMsgFetcher.request(item.d)
                    val (fetchedText, fetchedMd) = RobotMsgFetcher.renderableFor(item.d.msgId)
                    if (fetchedMd != null || fetchedText != null) {
                        val tv = widget.contentWidget as? TextView ?: return@runCatching
                        tv.text = if (fetchedMd != null) {
                            momoi.mod.qqpro.lib.Markdown.toSpannable(fetchedMd)
                        } else fetchedText
                    }
                }
            }.onFailure { Utils.log("robot placeholder fetch failed: $it") }
            // 机器人 markdown 消息：`renderBotText`（修复回复带图显示）已把 markdown 正文降级为
            // 纯文本 cell，这里在 native bind 之后把正文替换为轻量 Markdown 渲染结果
            // （**加粗**、*斜体*、`代码`、[链接] 等，与桌面版 QQ 的 markdown 消息一致）。
            // 只在确实只携带 markdown 正文的消息上生效；纯文本/图片消息不受影响。
            runCatching {
                val tv = widget.contentWidget as? TextView ?: return@runCatching
                // 元素里的 markdown 优先；元素为空时用按 msgId 补拉到的内容（RobotMsgFetcher）。
                val (fetchedText, fetchedMd) = RobotMsgFetcher.renderableFor(item.d.msgId)
                val mdEl = item.d.elements?.firstOrNull {
                    it.elementType == ElementType.MARKDOWN &&
                        it.markdownElement?.content?.isNotBlank() == true
                }
                val mdContent = mdEl?.markdownElement?.content ?: fetchedMd
                if (mdContent != null) {
                    val rendered = momoi.mod.qqpro.lib.Markdown.toSpannable(mdContent)
                    if (rendered.isNotEmpty()) {
                        // 机器人 markdown 消息常带 inline keyboard 按钮：把按钮标签附在正文下方。
                        val kbRows = item.d.elements?.firstOrNull { it.inlineKeyboardElement != null }
                            ?.inlineKeyboardElement?.rows
                        val kbText = kbRows?.mapNotNull { row ->
                            row.buttons?.mapNotNull { it.label?.takeIf { l -> l.isNotBlank() } }
                                ?.joinToString(" · ")?.takeIf { it.isNotBlank() }
                        }?.filterNotNull()?.joinToString("\n")?.takeIf { it.isNotBlank() }
                        tv.text = if (kbText != null) {
                            SpannableStringBuilder(rendered).append("\n🔘 ").append(kbText)
                        } else rendered
                    }
                } else if (fetchedText != null) {
                    // 补拉到的纯文本（ark 卡片文本等）替换占位符。
                    tv.text = fetchedText
                }
            }.onFailure { Utils.log("markdown render failed: $it") }
            // The per-message timestamp is Canvas-drawn with a SHARED paint (default #99ffffff) — set
            // its color so it adapts to light/dark instead of being near-invisible on a light surface.
            runCatching { widget.aioRuntime.a().color = M3.onSurfaceVariant }
            // Grey-tip system messages render through contentWidget, so applyMsgTextStyle just colored
            // them as bubble text — override to the themed tip color (mention spans keep their color).
            if (item.d.msgType == NTMsgType.GRAYTIPS) {
                recolorTextViews(runCatching { widget.contentWidget }.getOrNull(), M3.onSurfaceTip)
            }
            BubbleCorner.apply(widget)
            // 防撤回标记：被撤回但被我们保留显示的消息，在气泡文字后附上小字“已撤回”。
            // 聊天与截图走同一绑定路径，因此聊天截图里同样显示该标记。
            // 注意：撤回那一帧恢复发生在渲染前，适配器可能不会重新 bind（原消息“没变”），
            // 所以渲染后还会由 CurrentMsgList.markRecalledVisible 补标一次。
            appendRecallMark(item, runCatching { widget.contentWidget }.getOrNull())
            // B站视频卡片（链接 + 小程序分享）：识别到 bilibili 链接时，隐藏原小程序/链接预览，
            // 只显示视频卡片；否则按原有逻辑走链接预览。
            // 检测在主线程同步做：扫描已硬限界（ark 只取头部 16K），且这个手表内核的消息元素
            // 在后台线程读取 arkElement 会返回 null，异步扫描会导致解析失效。
            val biliMsg = item.d as? MsgRecordEx
            if (Utils.loggingEnabled && biliMsg != null) {
                Utils.log(
                    "BiliCard: bind check msgType=${runCatching { biliMsg.msgType }.getOrNull()} " +
                        "sub=${runCatching { biliMsg.subMsgType }.getOrNull()} " +
                        "els=${runCatching { biliMsg.elements?.size }.getOrNull()} " +
                        "hasArk=${runCatching { biliMsg.elements?.any { it.arkElement != null } }.getOrNull()} " +
                        "hasStruct=${runCatching { biliMsg.elements?.any { it.structMsgElement != null } }.getOrNull()}"
                )
            }
            val biliRef = BiliCard.refOf(
                widget.getContentWidget<View>() as? TextView,
                biliMsg,
            ) ?: BiliCard.extractFromViews(matchedView)
            if (Utils.loggingEnabled) {
                Utils.log("BiliCard: bind result=${biliRef?.let { it::class.simpleName } ?: "null"}")
            }
            if (biliRef != null) {
                matchedView?.visibility = View.GONE
                BiliCard.bind(widget, biliRef)
                LinkPreview.hide(widget)
            } else {
                BiliCard.hide(widget)
                if (matched == null || matched.appendMode) LinkPreview.bind(widget)
                else LinkPreview.hide(widget)
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
            // 诊断用内容截断必须在分配前做：小程序/ark 的 bytesData 可能是数百 KB 到数 MB 的
            // ByteArray，主线程整段 String() 解码会瞬间大分配触发 GC 卡死（老手表实测 ANR）。
            is CharSequence -> if (v.length > 256) v.subSequence(0, 256).toString() + "…" else v.toString()
            is ByteArray -> if (v.size > 512) "<${v.size}b>" else runCatching { String(v) }.getOrDefault("<${v.size}b>")
            is Number, is Boolean, is Char -> v.toString()
            else -> return@mapNotNull null
        }.trim()
        if (s.isEmpty() || s == "0" || s == "false") null else "${f.name}=${s.take(160)}"
    }
    "$name{${fields.joinToString(" ").take(500)}}"
}.getOrDefault("?")
