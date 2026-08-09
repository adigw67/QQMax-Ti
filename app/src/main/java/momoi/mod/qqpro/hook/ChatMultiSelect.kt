package momoi.mod.qqpro.hook
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.RecyclerView
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.enums.NTMsgType
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.menu.Entry
import momoi.mod.qqpro.hook.menu.buildMessageActions
import momoi.mod.qqpro.hook.menu.menuScope
import momoi.mod.qqpro.lib.SwipeBackLayout
import kotlinx.coroutines.launch
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 消息多选 — multi-select mode for chat messages (the "多选" long-press entry; visibility via 菜单自定义).
 *
 * Entered from the long-press menu's "多选" entry, which seeds the long-pressed message. While
 * active a single TAP on any message bubble toggles its selection (scrolling still works), and a
 * long-press RANGE-selects from the last selection to the pressed message — the native menu fragment
 * self-dismisses + range-selects instead of opening
 * (see 长按菜单调整). A bottom Material bar shows the count and exits.
 *
 * This is phase 1 — selection + UI only. Batch actions (转发/删除/合并转发/复制) hang off
 * [selected] and will be wired into the bar later.
 *
 * Cell→msgId is resolved through a [WeakHashMap] keyed by the cell's root view (the RecyclerView's
 * direct child, populated from [momoi.mod.qqpro.hook.aio_cell.AIOCell.HookCell.i] on every bind),
 * NOT by adapter position — so it stays correct regardless of any header/footer offset and survives
 * recycling (a recycled view rebinds to its new msgId in place).
 */
object ChatMultiSelect {

    var active = false
        private set

    /** Selected msgIds in tap order (LinkedHashSet → insertion-ordered for future batch ops). */
    val selected = linkedSetOf<Long>()

    /** The most recently selected message — the anchor for long-press range selection. */
    private var anchorMsgId = 0L

    private var rvRef: WeakReference<RecyclerView>? = null
    /** The live chat RecyclerView (captured on every cell bind), for features outside the list — e.g.
     *  the screenshot renderer needs the adapter even when invoked from the long-press menu overlay. */
    val recyclerView: RecyclerView? get() = rvRef?.get()
    /** The RV the touch-listener / decoration are currently installed on (install once per RV). */
    private var installedOn: WeakReference<RecyclerView>? = null
    /** Cell-root view → msgId, refreshed on every bind. Weak keys so recycled cells don't leak. */
    private val cellMsgId = WeakHashMap<View, Long>()

    private var bar: View? = null
    private var countLabel: TextView? = null
    private var backCb: OnBackPressedCallback? = null
    /** Open batch-action sheet (scrim) + its own back handler, so it can be dismissed independently. */
    private var menu: View? = null
    private var menuBackCb: OnBackPressedCallback? = null

    // Set true by the gesture handler when an ACTION_UP should be consumed (a toggle tap); cleared
    // on every ACTION_DOWN. Same one-shot pattern as GalleryMultiSelectHelper.
    private var interceptNextUp = false

    private val overlayPaint = Paint().apply { color = (M3.primary and 0x00FFFFFF) or 0x55_000000 }

    // ── called from the cell bind (AIOCell.HookCell.i) ──────────────────────────

    /**
     * Record this cell's [msgId] and make sure the RecyclerView it lives in has our touch listener
     * + selection decoration installed. Grey-tip system messages are mapped to 0 so they can't be
     * selected. Re-installs when the chat is reopened (a fresh RecyclerView), resetting stale state.
     */
    fun bindCell(view: View, msgId: Long, msgType: Int) {
        cellMsgId[view] = if (msgType == NTMsgType.GRAYTIPS) 0L else msgId
        // During RecyclerView bind the cell isn't attached yet (view.parent == null) — the RV only
        // becomes the parent in the layout pass AFTER bind. Defer the lookup/install to view.post,
        // which the RunQueue runs once the view is attached & laid out. installFor self-guards
        // (re-installs only on a new RV), so posting on every bind is cheap.
        view.post { installFor(view) }
    }

    private fun installFor(view: View) {
        val rv = findRecyclerView(view) ?: return
        rvRef = WeakReference(rv)
        if (installedOn?.get() === rv) return
        // New RecyclerView (chat (re)opened) — any prior session is stale.
        if (active) reset()
        installedOn = WeakReference(rv)
        val gesture = GestureDetector(rv.context, gestureListener(rv)).apply { setOnDoubleTapListener(null) }
        rv.addOnItemTouchListener(touchListener(gesture))
        rv.addItemDecoration(decoration())
        Utils.log("ChatMultiSelect: installed listeners on RV ${rv.hashCode()}")
    }

    // ── public API (menu entry + bar) ───────────────────────────────────────────

    /** Enter multi-select, seeding the long-pressed [seedMsgId] (0 = none). */
    fun enter(seedMsgId: Long) {
        val rv = rvRef?.get() ?: run { Utils.log("ChatMultiSelect.enter: no RV captured yet"); return }
        active = true
        selected.clear()
        if (seedMsgId != 0L) selected.add(seedMsgId)
        anchorMsgId = seedMsgId
        showBar(rv)
        updateBar()
        applyGutter(rv, true)
        Utils.log("ChatMultiSelect: entered, seed=$seedMsgId")
        rv.post { dumpGeometry("ENTER", rv) }
    }

    fun exit() {
        if (!active) return
        val rv = rvRef?.get()
        reset()
        Utils.log("ChatMultiSelect: exited")
        rv?.post { dumpGeometry("EXIT", rv) }
    }

    private fun reset() {
        active = false
        selected.clear()
        anchorMsgId = 0L
        dismissMenu()
        removeBar()
        backCb?.remove(); backCb = null
        rvRef?.get()?.let { applyGutter(it, false) }
    }

    /**
     * Precise geometry dump to qqpro_debug.log (exact integer px) — the reliable substitute for a
     * uiautomator XML dump, which (a) can't be captured on the chat screen (the input cursor's blink
     * keeps the UI non-idle so `uiautomator dump` errors with "could not get idle state") and (b)
     * wouldn't show the Canvas-drawn selection overlay/gutter anyway (it's an ItemDecoration, not
     * view nodes). Logs RV padding/size, each visible cell's bounds + width + msgId, and the bar.
     */
    private fun dumpGeometry(tag: String, rv: RecyclerView) {
        runCatching {
            val loc = IntArray(2); rv.getLocationOnScreen(loc)
            Utils.log("MSDUMP[$tag] active=$active gutter(px)=$gutter sel=${selected.size} " +
                "rv pad L=${rv.paddingLeft} T=${rv.paddingTop} R=${rv.paddingRight} B=${rv.paddingBottom} " +
                "size=${rv.width}x${rv.height} screen=(${loc[0]},${loc[1]})")
            for (i in 0 until rv.childCount) {
                val c = rv.getChildAt(i)
                val mid = cellMsgId[c] ?: 0L
                Utils.log("MSDUMP[$tag]  child[$i] ${c.javaClass.simpleName} " +
                    "L=${c.left} T=${c.top} R=${c.right} B=${c.bottom} w=${c.width} msgId=$mid sel=${mid in selected}")
            }
            bar?.let { b ->
                val bl = IntArray(2); b.getLocationOnScreen(bl)
                Utils.log("MSDUMP[$tag]  bar ${b.javaClass.simpleName} screen=(${bl[0]},${bl[1]}) size=${b.width}x${b.height}")
            } ?: Utils.log("MSDUMP[$tag]  bar=null")
        }.onFailure { Utils.log("MSDUMP[$tag] failed: $it") }
    }

    /**
     * Reserve (or release) the left gutter via the RecyclerView's LEFT PADDING — NOT getItemOffsets.
     * A decor-inset change doesn't reliably re-measure already-cached viewholders (on EXIT they kept
     * the narrowed width and left an empty strip on the right), whereas a padding change always
     * re-measures children against the new content width. clipToPadding=false lets the selection
     * number still draw inside the gutter (the padding region) without being clipped.
     */
    private var savedPad: IntArray? = null
    private var savedClip = true
    private fun applyGutter(rv: RecyclerView, on: Boolean) {
        if (on) {
            if (savedPad == null) {
                savedPad = intArrayOf(rv.paddingLeft, rv.paddingTop, rv.paddingRight, rv.paddingBottom)
                savedClip = rv.clipToPadding
            }
            val p = savedPad!!
            rv.clipToPadding = false
            rv.setPadding(gutter, p[1], p[2], p[3])
        } else {
            savedPad?.let { rv.setPadding(it[0], it[1], it[2], it[3]) }
            rv.clipToPadding = savedClip
            savedPad = null
        }
        // A padding change requests a layout but does NOT re-measure already-attached cells —
        // RecyclerView caches each child's measured width and only re-measures on a scroll fill pass.
        // So toggling the gutter would leave the cells at their old width (a gap on the right after
        // EXIT) until you scrolled. forceLayout() on each attached child sets its FORCE_LAYOUT flag,
        // making RecyclerView's shouldMeasureChild re-measure it to the new content width this pass.
        for (i in 0 until rv.childCount) rv.getChildAt(i).forceLayout()
        rv.requestLayout()
        rv.invalidateItemDecorations()
    }

    /** Toggle [msgId]'s selection. Stays in multi-select even at 0 selected — exit is explicit. */
    fun toggle(msgId: Long) {
        if (!active || msgId == 0L) return
        if (!selected.add(msgId)) selected.remove(msgId) else anchorMsgId = msgId
        updateBar()
        rvRef?.get()?.invalidateItemDecorations()
    }

    /**
     * Long-press range select: select every message from the last-selected anchor to [msgId] inclusive,
     * by their order in the live message list. With no usable anchor yet (nothing selected, or the
     * anchor scrolled out of the loaded list) it just selects [msgId]. The anchor then moves to [msgId]
     * so a follow-up long-press extends from there. Never deselects — it only adds to the range.
     */
    fun selectRangeTo(msgId: Long) {
        if (!active || msgId == 0L) return
        val live = CurrentMsgList.msgList.value
        val to = live.indexOfFirst { it.d.msgId == msgId }
        val from = if (anchorMsgId != 0L) live.indexOfFirst { it.d.msgId == anchorMsgId } else -1
        if (to < 0 || from < 0) {
            selected.add(msgId)
        } else {
            for (i in minOf(from, to)..maxOf(from, to)) selected.add(live[i].d.msgId)
        }
        anchorMsgId = msgId
        updateBar()
        rvRef?.get()?.invalidateItemDecorations()
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun findRecyclerView(view: View): RecyclerView? {
        var p = view.parent
        while (p != null) {
            if (p is RecyclerView) return p
            p = (p as? View)?.parent
        }
        return null
    }

    private fun msgIdUnder(rv: RecyclerView, x: Float, y: Float): Long {
        // findChildViewUnder misses the left gutter (it's RV padding, left of every cell) so a tap on
        // the selection circle returns null. Fall back to the row whose vertical span contains y, so
        // the WHOLE row width — number circle included — toggles selection.
        var child = rv.findChildViewUnder(x, y)
        if (child == null) {
            for (i in 0 until rv.childCount) {
                val c = rv.getChildAt(i)
                if (y >= c.top && y < c.bottom) { child = c; break }
            }
        }
        return child?.let { cellMsgId[it] } ?: 0L
    }

    // ── touch interception (transparent unless active) ──────────────────────────

    private fun gestureListener(rv: RecyclerView) = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val msgId = msgIdUnder(rv, e.x, e.y)
            if (msgId == 0L) return false
            interceptNextUp = true   // consume this UP so the cell's own click doesn't fire
            toggle(msgId)
            return true
        }
        // Long-press is intentionally NOT handled here: the native long-press still opens the menu
        // fragment, which (when active) range-selects + self-dismisses (长按菜单调整). One handler.
    }

    private fun touchListener(gesture: GestureDetector) = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (!active) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { interceptNextUp = false; gesture.onTouchEvent(e); return false }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gesture.onTouchEvent(e)
                    val intercept = interceptNextUp
                    interceptNextUp = false
                    return intercept
                }
                else -> { gesture.onTouchEvent(e); return false } // let MOVE through → scrolling works
            }
        }
        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) { if (active) gesture.onTouchEvent(e) }
        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    }

    // ── selection overlay ───────────────────────────────────────────────────────

    /** Width of the left gutter (reserved via the RV's left padding while active) holding the number. */
    private val gutter get() = 30.dp

    private fun decoration() = object : RecyclerView.ItemDecoration() {
        private val radius = 9.dp.toFloat()
        private val tintRadius = 8.dp.toFloat()
        private val circleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = M3.primary }
        private val circleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = M3.onSurfaceVariant; style = Paint.Style.STROKE; strokeWidth = 2f
        }
        private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = M3.onColor(M3.primary); textAlign = Paint.Align.CENTER
            textSize = radius * 1.05f; isFakeBoldText = true
        }

        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            if (!active) return
            // While active the gutter IS the RV's left padding, so the content runs [paddingLeft,
            // width-paddingRight] and the number sits centered in the [0, paddingLeft] gutter.
            val left = parent.paddingLeft.toFloat()
            val right = (parent.width - parent.paddingRight).toFloat()
            // Badge numbers reflect the order the selection will be used in (time or tap order).
            val rank = selectionRank()
            val cx = parent.paddingLeft / 2f
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val msgId = cellMsgId[child] ?: 0L
                if (msgId == 0L) continue
                val isSel = msgId in selected
                val top = child.top.toFloat()
                val bottom = child.bottom.toFloat()
                // Full-width tint across the whole row (gutter included) so the selection is clearly
                // visible regardless of which side the bubble is on.
                // Canvas.drawRoundRect(left, top, right, bottom, rx, ry, paint) is API 21+ — the
                // API 19 watch only has the RectF overload. Without this, selecting a message
                // crashed onDrawOver → the chat went black.
                if (isSel) c.drawRoundRect(RectF(left, top, right, bottom), tintRadius, tintRadius, overlayPaint)
                // Number / tap-target circle, always centered in the left gutter, vertically on the row.
                val cy = (child.top + child.bottom) / 2f
                if (isSel) {
                    c.drawCircle(cx, cy, radius, circleFill)
                    val order = (rank[msgId] ?: selected.indexOf(msgId)) + 1
                    val baseline = cy - (numberPaint.descent() + numberPaint.ascent()) / 2f
                    c.drawText(order.toString(), cx, baseline, numberPaint)
                } else {
                    c.drawCircle(cx, cy, radius, circleBorder)
                }
            }
        }
    }

    // ── bottom action bar ───────────────────────────────────────────────────────

    @SuppressLint("SetTextI18n")
    private fun showBar(rv: RecyclerView) {
        if (bar != null) return
        val container = AttachmentOverlay.aioContainer(rv) ?: run {
            Utils.log("ChatMultiSelect.showBar: AIO container not found"); return
        }
        val ctx = container.context
        val label = TextView(ctx).apply {
            setTextColor(M3.onSurface); textSize = 15f; gravity = Gravity.CENTER
        }
        val close = ImageView(ctx).apply {
            setImageDrawable(MaterialSymbol(MaterialSymbols.close, M3.onSurfaceVariant))
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            isClickable = true
            setOnClickListener { exit() }
        }
        // Right: confirm (check) — opens the batch-action menu for the current selection.
        val confirm = ImageView(ctx).apply {
            setImageDrawable(MaterialSymbol(MaterialSymbols.check, M3.primary))
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            isClickable = true
            setOnClickListener { onConfirm(rv) }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // Center the [close · count · check] group so the buttons sit right next to the text
            // instead of at the bar's edges — on a round screen the corners are clipped.
            gravity = Gravity.CENTER
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            background = GradientDrawable().apply {
                setColor(M3.surfaceContainerHigh)
                cornerRadii = floatArrayOf(M3.radiusLg, M3.radiusLg, M3.radiusLg, M3.radiusLg, 0f, 0f, 0f, 0f)
            }
            isClickable = true   // swallow taps so they don't fall through to the chat
            addView(close, LinearLayout.LayoutParams(34.dp, 34.dp))
            addView(label, LinearLayout.LayoutParams(WC, WC).apply { marginStart = 4.dp; marginEnd = 4.dp })
            addView(confirm, LinearLayout.LayoutParams(34.dp, 34.dp))
        }
        container.addView(row, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        bar = row
        countLabel = label

        // Back press exits multi-select instead of popping the chat.
        AttachmentOverlay.aioFragment(rv)?.let { aio ->
            val cb = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { exit() }
            }
            aio.requireActivity().onBackPressedDispatcher.addCallback(aio.viewLifecycleOwner, cb)
            backCb = cb
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateBar() {
        countLabel?.text = "已选 ${selected.size} 条"
    }

    private fun removeBar() {
        (bar?.parent as? ViewGroup)?.removeView(bar)
        bar = null
        countLabel = null
    }

    // ── batch-action menu (confirm / check button) ───────────────────────────────

    /**
     * The selected messages as live items, in the order they'll be used for batch actions: by tap
     * order, or — when [Settings.multiSelectTimeOrder] is on (the default) — chronological (the order
     * of the live message list, which is already time-sorted).
     */
    private fun selectedItems(): List<WatchAIOMsgItem> {
        val live = CurrentMsgList.msgList.value
        val items = selected.mapNotNull { id -> live.find { it.d.msgId == id } }
        return if (Settings.multiSelectTimeOrder.value) items.sortedBy { live.indexOf(it) } else items
    }

    /**
     * Selected msgIds ranked for the gutter badge — same ordering as [selectedItems] so the number on
     * each cell matches the order it will actually be used in.
     */
    private fun selectionRank(): Map<Long, Int> {
        val ids = if (Settings.multiSelectTimeOrder.value) {
            val live = CurrentMsgList.msgList.value
            val pos = HashMap<Long, Int>(live.size)
            live.forEachIndexed { i, it -> pos[it.d.msgId] = i }
            selected.sortedBy { pos[it] ?: Int.MAX_VALUE }
        } else selected.toList()
        return ids.withIndex().associate { (i, id) -> id to i }
    }

    /**
     * Confirm pressed: reuse the long-press menu's per-message option set
     * ([buildMessageActions]), intersect it across the selection, and show the common
     * options that have a batch executor ([batchExecutors]). Single-only actions have no executor →
     * never shown. The kept [Entry]s give the row label/icon/destructive flag.
     */
    private fun onConfirm(rv: RecyclerView) {
        val items = selectedItems()
        if (items.isEmpty()) { Utils.toast(rv.context, "未选择消息"); return }
        val fm = AttachmentOverlay.aioFragment(rv)?.let { runCatching { it.parentFragmentManager }.getOrNull() }
        // Await each message's option set from the shared data layer (recall resolves via role lookup),
        // then intersect and keep the common options that have a batch executor.
        menuScope.launch {
            val perMsg = items.map { buildMessageActions(rv, it.d, it, fm, isHistory = false, names = emptySet()) }
            val common = perMsg.map { es -> es.map { it.label }.toSet() }.reduce { a, b -> a intersect b }
            val shown = perMsg.first()
                .filter { it.label in common && it.label in batchExecutors }
                .distinctBy { it.label }
            if (!active) return@launch  // user exited while awaiting
            if (shown.isEmpty()) { Utils.toast(rv.context, "无可批量操作"); return@launch }
            showActionMenu(rv, shown, items)
        }
    }

    /**
     * A Material action sheet (scrim + centered card of rows) for the common [entries]. Tapping a row
     * runs its batch executor on [msgs] then exits multi-select (destructive actions confirm first).
     * Dismissable via the scrim, the back button, or running an action — and torn down on [exit].
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showActionMenu(rv: RecyclerView, entries: List<Entry>, items: List<WatchAIOMsgItem>) {
        val container = AttachmentOverlay.aioContainer(rv) ?: run {
            Utils.log("ChatMultiSelect.showActionMenu: no container"); return
        }
        val ctx = container.context
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(M3.surfaceContainer); cornerRadius = M3.radiusLg }
            setClipToOutlineCompat(true)
            isClickable = true   // taps on the card don't fall through to the scrim
            setPadding(0, 6.dp, 0, 6.dp)
        }
        fun run(e: Entry) {
            dismissMenu()
            val view = rvRef?.get() ?: rv
            val exec = batchExecutors[e.label] ?: return
            if (e.destructive) {
                val fm = AttachmentOverlay.aioFragment(rv)?.let { runCatching { it.parentFragmentManager }.getOrNull() }
                val act = { exec(view, items); exit() }
                if (fm != null) runCatching {
                    momoi.mod.qqpro.hook.view.ConfirmFragment(
                        "确定要对选中的 ${items.size} 条消息执行「${e.label}」吗？", e.label, destructive = true
                    ) { act() }.show(fm, "qqpro_ms_confirm")
                }.onFailure { act() } else act()
            } else {
                exec(view, items)
                exit()
            }
        }
        entries.forEach { e -> card.addView(actionRow(ctx, e) { run(e) }) }
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(0x99_000000.toInt())
            isClickable = true
            setOnClickListener { dismissMenu() }
            addView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(card, FrameLayout.LayoutParams(MP, WC))
            }, FrameLayout.LayoutParams(MP, WC, Gravity.CENTER).apply {
                val m = 16.dp; leftMargin = m; rightMargin = m
            })
        }
        // Wrap in SwipeBackLayout so the sheet can be swiped away too (matches the long-press menu).
        val root = SwipeBackLayout(ctx).apply {
            onSwipeBack = { dismissMenu() }
            addView(scrim, FrameLayout.LayoutParams(MP, MP))
        }
        container.addView(root, FrameLayout.LayoutParams(MP, MP))
        menu = root
        // Back dismisses the menu first (registered after the bar's callback → higher priority).
        AttachmentOverlay.aioFragment(rv)?.let { aio ->
            val cb = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { dismissMenu() }
            }
            aio.requireActivity().onBackPressedDispatcher.addCallback(aio.viewLifecycleOwner, cb)
            menuBackCb = cb
        }
    }

    private fun dismissMenu() {
        (menu?.parent as? ViewGroup)?.removeView(menu)
        menu = null
        menuBackCb?.remove(); menuBackCb = null
    }

    private fun actionRow(ctx: android.content.Context, e: Entry, onClick: () -> Unit): View {
        val tint = if (e.destructive) M3.error else M3.primary
        val textColor = if (e.destructive) M3.error else M3.onSurface
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp, 12.dp, 18.dp, 12.dp)
            isClickable = true
            background = M3.ripple(null, 0x33_888888)
            setOnClickListener { onClick() }
            addView(ImageView(ctx).apply { setImageDrawable(MaterialSymbol(e.symbol, tint)) },
                LinearLayout.LayoutParams(22.dp, 22.dp))
            addView(TextView(ctx).apply { text = e.label; setTextColor(textColor); textSize = 15f },
                LinearLayout.LayoutParams(0, WC, 1f).apply { marginStart = 16.dp })
        }
    }

    private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
}
