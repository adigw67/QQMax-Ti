package momoi.mod.qqpro.hook.view

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.recyclerview.widget.AIOLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.leadingSymbol

/**
 * The native chat unread bubble ([com.tencent.watch.aio_impl.reserve1.unreadbubble.UnreadBubbleVB])
 * drives this view through [setText] / [setBackgroundResource] / [setVisibility]. We restyle it into a
 * large pill at the bottom-right (left side rounded, right flush to the screen edge) and take over its
 * visibility and click.
 *
 * ## Many instances, one active
 * QQ preloads several chat fragments, so ~10+ BubbleTextView instances exist at once. The return
 * [anchors] and [current] are SHARED statics (a jump must survive the jumping chat's bubble detach/
 * attach). Therefore only the ACTIVE bubble — the one that resolved its own message RecyclerView
 * ([resolvedRv] != null) — may show the button or mutate anchors; the rest (preloaded, no RV) stay
 * hidden and never touch shared state. Without this gate every preloaded instance flips to DARK on any
 * anchor and a phantom (no RV) can be the one on screen, so tapping it does nothing.
 *
 * ## List geometry (verified, see CurrentMsgList.Hook.n)
 * Oldest-first: index 0 = oldest, the newest message at the HIGHEST message index, a single footer one
 * past it (so `itemCount == messages + 1`). Hook.n heals the rendered list from the full mirror on every
 * push, so the live adapter and the mirror stay index-aligned. [distanceToLast] = rows between the last
 * visible row and the newest message (≤0 == newest/footer on screen).
 *
 * ## Visibility ([refresh]) — active instance only
 *  1. native unread count → COLORED (blue "↓ N").
 *  2. a return [anchors] entry is pending → DARK (shown regardless of scroll direction).
 *  3. no anchor: we're above the bottom AND the last scroll was DOWNWARD → DARK. The direction gate keeps
 *     the button from blocking the screen while scrolling UP to read history; it reappears heading down.
 *  4. otherwise hidden.
 *
 * ## Return anchors — index-based, "clear all passed at once"
 * An upward jump ([beginJumpUp]) pushes the message we left from — only when not already at the bottom
 * (jumping from the bottom has the bottom as its return point, so no anchor). An anchor is removed once
 * the viewport's bottom ([lastVisible]) has descended back to/past its index ([Anchor.left] gates this so
 * a still-unscrolled DEFERRED jump doesn't prune instantly). Because that's a single `lastVisible >= idx`
 * test, scrolling down past several anchors clears them ALL in one pass — you never stop at, or get sent
 * back to, a point you already scrolled past. Backstop: at the true bottom all anchors are dropped.
 *
 * ## Scrolling — delegated, never hand-rolled
 * Up-jumps use [smoothScrollToStart] (framework LinearSmoothScroller). Go-to-bottom delegates to the
 * NATIVE bubble click ([delegateClick]) — QQ's own JumpBottom loads the latest page and lands on the true
 * bottom past the footer. (A custom frame-by-frame glide oscillated forever at the bottom because
 * canScrollVertically never reads false there; delegating to native removes that failure mode.)
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class BubbleTextView(context: Context) : TextView(context) {
    private val blueBgColor = M3.primary
    private val greyBgColor = (M3.surfaceContainerHigh and 0x00FFFFFF) or 0xCC_000000.toInt()
    private val blueBg = roundCornerDrawable(blueBgColor, 9999f, 0f, 9999f, 0f)
    private val greyBg = roundCornerDrawable(greyBgColor, 9999f, 0f, 9999f, 0f)
    private val coloredTint = M3.onColor(blueBgColor)
    private val darkTint = M3.onColor(greyBgColor)

    // Native intent, captured from its setText / setVisibility calls. hasUnread() = both together.
    private var nativeWantsShow = false
    private var isCountMode = false
    private var countText = ""

    // +1 last scroll was downward (toward newest), -1 upward, 0 none. Drives the no-anchor direction gate.
    private var lastScrollDir = 0

    private var listenersAttached = false
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var scrollListener: RecyclerView.OnScrollListener? = null
    // Set only when THIS bubble found its own chat message list. Doubles as the "I am the active bubble"
    // flag — a preloaded instance never resolves one and so never shows the button / touches anchors.
    private var resolvedRv: RecyclerView? = null
    private var lastMode = Mode.HIDDEN

    private enum class Mode { HIDDEN, COLORED, DARK }

    // Native installs its own go-to-bottom click; captured here and wrapped so we can run staged return
    // and delegate to it for the actual go-to-bottom.
    private var delegateClick: OnClickListener? = null

    init {
        gravity = Gravity.CENTER
        setTextColor(M3.onSurface)
        textSize = 14f
        setPadding(18.dp, 9.dp, 14.dp, 9.dp)
    }

    // ---- native driving us ----

    override fun setBackgroundResource(resid: Int) { refresh() }

    override fun setText(text: CharSequence?, type: BufferType?) {
        countText = text?.toString().orEmpty()
        isCountMode = countText.isNotEmpty()
        refresh()
    }

    // Native uses VISIBLE(0) for the count, INVISIBLE(4) for back-to-bottom, GONE(8) to hide.
    override fun setVisibility(visibility: Int) {
        nativeWantsShow = visibility != View.GONE
        refresh()
    }

    override fun setOnClickListener(l: OnClickListener?) {
        if (l == null) {
            delegateClick = null
            super.setOnClickListener(null)
            return
        }
        delegateClick = l
        super.setOnClickListener { v -> onBubbleClick(v) }
    }

    // ---- list helpers ----

    private fun rv(): RecyclerView? =
        resolvedRv ?: findChatRecyclerView() ?: runCatching { CurrentMsgList.vb.H }.getOrNull()

    private fun lm(): AIOLayoutManager? = rv()?.layoutManager as? AIOLayoutManager

    private fun findChatRecyclerView(): RecyclerView? {
        var root: View = this
        while (root.parent is View) root = root.parent as View
        return findAioRecyclerView(root)
    }

    private fun findAioRecyclerView(v: View): RecyclerView? {
        if (v is RecyclerView && v.layoutManager is AIOLayoutManager) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) findAioRecyclerView(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun liveList(): List<*> =
        runCatching { CurrentMsgList.uiOp?.m() }.getOrNull() ?: CurrentMsgList.msgList.value

    private fun msgCount(): Int = runCatching { liveList().size }.getOrDefault(0)

    private fun msgIdAt(pos: Int): Long? =
        (liveList().getOrNull(pos) as? WatchAIOMsgItem)?.d?.msgId

    /** Adapter position of the message with [id] in the LIVE list (what cells are bound from), or -1. */
    private fun liveIndexOfMsgId(id: Long): Int =
        runCatching { liveList().indexOfFirst { (it as? WatchAIOMsgItem)?.d?.msgId == id } }.getOrDefault(-1)

    /** Position of the message with [id] in the full mirror (all loaded history), or -1 if truly gone. */
    private fun mirrorIndexOfMsgId(id: Long): Int =
        runCatching { CurrentMsgList.msgList.value.indexOfFirst { it.d.msgId == id } }.getOrDefault(-1)

    /** True when the message with [id] is within the currently visible row range. */
    private fun isMsgIdVisible(id: Long): Boolean {
        val lm = lm() ?: return false
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < 0) return false
        for (p in first..last) if (msgIdAt(p) == id) return true
        return false
    }

    /**
     * Rows between the last visible row and the newest message (≤0 == newest/footer on screen), or
     * [DIST_UNKNOWN] when the list state is transiently invalid (findLastVisibleItemPosition == -1 during
     * a jump's re-layout). The sentinel is distinct from any real distance (incl. -1 at the footer) so
     * callers never mistake "can't tell" for "at the bottom" — that ambiguity once destroyed anchors.
     */
    private fun distanceToLast(): Int {
        val lm = lm() ?: return DIST_UNKNOWN
        val lastVisible = lm.findLastVisibleItemPosition()
        if (lastVisible < 0) return DIST_UNKNOWN
        val mirror = CurrentMsgList.msgList.value
        if (mirror.isEmpty()) return DIST_UNKNOWN
        if (liveList().size == mirror.size) return (mirror.size - 1) - lastVisible
        val visibleLastId = msgIdAt(lastVisible) ?: return DIST_UNKNOWN
        val idx = mirror.indexOfLast { it.d.msgId == visibleLastId }
        return if (idx < 0) DIST_UNKNOWN else (mirror.size - 1) - idx
    }

    // ---- visibility state machine ----

    private fun hasUnread(): Boolean = nativeWantsShow && isCountMode

    /**
     * Prune return anchors by POSITION (robust to missed scroll frames). Only the active bubble runs this.
     * An anchor flips [Anchor.left] = true once the jump carries the viewport above it (lastVisible < its
     * index); after that, once the viewport's bottom descends back to/past it (lastVisible >= its index)
     * it's removed — and since that test uses the single current lastVisible, every passed anchor is
     * removed in the same pass. At the true bottom all anchors are dropped (backstop).
     */
    private fun pruneAnchors() {
        if (anchors.isEmpty() || resolvedRv == null) return
        val lm = lm() ?: return
        val firstVisible = lm.findFirstVisibleItemPosition()
        val lastVisible = lm.findLastVisibleItemPosition()
        if (firstVisible < 0 || lastVisible < 0) return // transient invalid layout — leave anchors alone
        val mirror = CurrentMsgList.msgList.value
        val before = anchors.size
        if (mirror.isNotEmpty() && (mirror.size - 1) - lastVisible <= SCROLL_DIST_THRESHOLD) {
            anchors.clear() // at the true bottom — every return point is moot
        } else {
            val it = anchors.iterator()
            while (it.hasNext()) {
                val a = it.next()
                val idx = liveIndexOfMsgId(a.msgId)
                if (idx < 0) {
                    if (mirrorIndexOfMsgId(a.msgId) < 0) it.remove() // deleted from history
                    continue
                }
                when {
                    // Entirely below the viewport: a real (large) jump carried us above it → keep for return.
                    idx > lastVisible -> a.left = true
                    // It had left and the viewport has descended back to/past it → returned. Because this is
                    // a single lastVisible-based test, scrolling down past several clears them all at once.
                    a.left -> it.remove()
                    // Still visible but now BELOW the top of the viewport: we jumped ABOVE it without it ever
                    // leaving the screen (reply source right next to it) → reached, no return needed → clear.
                    // An anchor still AT/above the top (idx <= firstVisible) is a pending/just-created jump
                    // that hasn't scrolled yet → keep (this is what avoids clearing during a deferred jump).
                    idx > firstVisible -> it.remove()
                }
            }
        }
        if (anchors.size != before) Utils.log("BubbleTextView.pruneAnchors: $before -> ${anchors.size} first=$firstVisible last=$lastVisible")
    }

    /**
     * Recompute the mode and apply it. Called on every scroll AND layout pass, so it MUST be cheap and
     * idempotent: appearance is only touched on an actual mode change, else re-styling every layout would
     * requestLayout in a loop via the global-layout listener. Only an unread-count text change restyles
     * within COLORED.
     */
    private fun refresh() {
        pruneAnchors()
        val dist = distanceToLast()
        val active = resolvedRv != null
        val unread = hasUnread()
        val farFromBottom = dist != DIST_UNKNOWN && dist > SCROLL_DIST_THRESHOLD
        // Reaching the newest drops the post-jump force-show (don't clear on UNKNOWN — that's transient).
        if (dist != DIST_UNKNOWN && dist <= SCROLL_DIST_THRESHOLD) forceShow = false
        // Only the active bubble shows from anchors/distance/force. Unread is native-authoritative (it only
        // ever sets the count on the visible chat's bubble), so it's allowed through ungated. With no anchor
        // and no force-show, the direction gate (show on downward scroll, hide on upward) keeps the button
        // from blocking the screen while reading history.
        val show = unread ||
            (active && farFromBottom && (anchors.isNotEmpty() || forceShow || lastScrollDir > 0))
        val mode = when {
            !show -> Mode.HIDDEN
            unread -> Mode.COLORED
            else -> Mode.DARK
        }
        if (mode != lastMode) {
            when (mode) {
                Mode.COLORED -> {
                    background = blueBg
                    setTextColor(coloredTint)
                    super.setText(countText, BufferType.NORMAL)
                    leadingSymbol(MaterialSymbols.arrow_downward, coloredTint, sizeDp = 14, gap = 3)
                }
                Mode.DARK -> {
                    background = greyBg
                    super.setText("", BufferType.NORMAL)
                    leadingSymbol(MaterialSymbols.arrow_downward, darkTint, sizeDp = 14, gap = 0)
                }
                Mode.HIDDEN -> {}
            }
            super.setVisibility(if (mode == Mode.HIDDEN) View.GONE else View.VISIBLE)
            Utils.log("BubbleTextView.refresh: $lastMode -> $mode id=${System.identityHashCode(this)} active=$active shown=$isShown unread=$unread anchors=${anchors.size} force=$forceShow dir=$lastScrollDir dist=$dist rv=${System.identityHashCode(resolvedRv)}")
            lastMode = mode
        } else if (mode == Mode.COLORED && text?.toString() != countText) {
            setTextColor(coloredTint)
            super.setText(countText, BufferType.NORMAL)
            leadingSymbol(MaterialSymbols.arrow_downward, coloredTint, sizeDp = 14, gap = 3)
        }
    }

    // ---- click ----

    private fun onBubbleClick(v: View?) {
        // Per spec, in priority order:
        //  1. unread (blue) → always go to the newest/unread (native), clearing any anchors.
        //  2. any anchor pending → go to the most-recent (nearest) one and clear it. Anchors already on
        //     screen count as reached, so drop them first — that avoids a no-op "return" to a spot you can
        //     already see, and means the tap always does something.
        //  3. no anchor → go to the bottom.
        if (hasUnread()) { anchors.clear(); goToBottom(v); return }
        anchors.removeAll { isMsgIdVisible(it.msgId) }
        val target = anchors.lastOrNull()
        if (target != null) {
            val idx = liveIndexOfMsgId(target.msgId)
            if (idx >= 0) {
                anchors.remove(target)
                forceShow = true   // keep the button up after the return so a follow-up tap reaches bottom
                Utils.log("BubbleTextView: return to anchor id=${target.msgId} idx=$idx remaining=${anchors.size}")
                rv()?.smoothScrollToStart(idx)
                refresh()
                return
            }
        }
        anchors.clear()
        goToBottom(v)
    }

    /**
     * Animated go-to-bottom: teleport most of the way then smooth-scroll the last short stretch to the
     * true bottom past the footer ([smoothScrollToEnd], framework LinearSmoothScroller — self-terminating,
     * so no oscillation). Falls back to the native JumpBottom click only if our RV isn't resolved.
     */
    private fun goToBottom(v: View?) {
        val rv = rv()
        if (rv != null && (rv.adapter?.itemCount ?: 0) > 0) {
            Utils.log("BubbleTextView: go to bottom animated (count=${msgCount()})")
            rv.smoothScrollToEnd()
            return
        }
        Utils.log("BubbleTextView: go to bottom fallback native JumpBottom")
        delegateClick?.onClick(v)
    }

    // ---- lifecycle / listeners ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachListeners(0)
        Utils.log("BubbleTextView.onAttached: id=${System.identityHashCode(this)} shown=$isShown anchors=${anchors.size}")
        refresh()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (current === this) current = null
        // Do NOT clear [anchors] here — shared static; with many preloaded instances any one detaching
        // would wipe the visible chat's jump anchor. Stale anchors self-prune via the mirror/index checks.
        lastScrollDir = 0
        lastMode = Mode.HIDDEN
        resolvedRv?.let { r ->
            layoutListener?.let { l -> runCatching { r.viewTreeObserver?.removeOnGlobalLayoutListener(l) } }
            scrollListener?.let { s -> runCatching { r.removeOnScrollListener(s) } }
        }
        layoutListener = null
        scrollListener = null
        resolvedRv = null
        listenersAttached = false
    }

    private fun attachListeners(tries: Int) {
        if (listenersAttached || tries > 20) return
        val rv = findChatRecyclerView() ?: runCatching { CurrentMsgList.vb.H }.getOrNull()
        if (rv == null) {
            postDelayed({ attachListeners(tries + 1) }, 200)
            return
        }
        listenersAttached = true
        resolvedRv = rv
        // We resolved a real chat list → this is the active bubble. (Set here, not in onAttached, so a
        // preloaded instance that never resolves an RV never becomes `current`.)
        current = this
        val sl = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val nd = if (dy > 4) 1 else if (dy < -4) -1 else lastScrollDir
                if (nd != lastScrollDir) {
                    Utils.log("BubbleTextView.onScrolled: dy=$dy dir=$lastScrollDir->$nd dist=${distanceToLast()} last=${lm()?.findLastVisibleItemPosition()} count=${msgCount()}")
                    lastScrollDir = nd
                }
                refresh()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                // The user grabbing the list (DRAGGING — programmatic smooth scrolls report SETTLING) means
                // they've taken manual control, so drop the post-jump force-show and let the direction gate
                // govern (hide while reading upward). A programmatic jump never trips this.
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && forceShow) {
                    forceShow = false
                    Utils.log("BubbleTextView: forceShow cleared (user drag)")
                }
                refresh()
            }
        }
        scrollListener = sl
        rv.addOnScrollListener(sl)
        // Re-evaluate on layout passes too: a jump / older-page load can change position without an
        // onScrolled (e.g. an instant scrollToPosition), and this is what prunes a passed anchor.
        val ll = ViewTreeObserver.OnGlobalLayoutListener { refresh() }
        layoutListener = ll
        runCatching { rv.viewTreeObserver.addOnGlobalLayoutListener(ll) }
        Utils.log("BubbleTextView listeners attached id=${System.identityHashCode(this)} shown=$isShown rv=${System.identityHashCode(rv)}")
        refresh()
    }

    companion object {
        private const val SCROLL_DIST_THRESHOLD = 3
        private const val MAX_ANCHORS = 8

        // distanceToLast() sentinel for "list state transiently invalid" — distinct from any real distance
        // (incl. -1 at the footer) so it's never mistaken for "at the bottom".
        private const val DIST_UNKNOWN = Int.MIN_VALUE

        // The active bubble (resolved its own RV). Used by beginJumpUp only to nudge a refresh.
        private var current: BubbleTextView? = null

        /**
         * A return point: a message we jumped UP from. [left] flips true once the jump carries the
         * viewport above the anchor's index; only after that does the viewport descending back to/past it
         * prune it — which keeps a DEFERRED jump (still showing the anchor before it scrolls) from pruning
         * instantly.
         */
        private class Anchor(val msgId: Long) {
            var left = false
        }

        // Outstanding upward-jump return points, oldest-first; tapping returns to the most recent (last).
        // Shared static so they survive the jumping chat's bubble detach/attach.
        private val anchors = ArrayList<Anchor>()

        // Set on any programmatic jump/return so the button stays visible afterward (even if that scroll
        // ended going upward) — so you're never stranded mid-chat with no way back to the bottom. Cleared
        // when the user manually drags (direction gate takes over) or once the newest is reached.
        private var forceShow = false

        /**
         * Call right before a programmatic upward jump (reply source / jump-to-first-unread / search).
         * Pushes the top-most currently visible message as a return anchor — but ONLY if we aren't already
         * at the bottom (jumping from the bottom has the bottom as its natural return point, so no anchor,
         * and the down button just goes to the newest). Reads the authoritative visible list (vb.H)
         * directly rather than whichever instance is `current`.
         */
        fun beginJumpUp() {
            val lm = runCatching { CurrentMsgList.vb.H.layoutManager as? AIOLayoutManager }.getOrNull()
            val first = lm?.findFirstVisibleItemPosition() ?: -1
            val last = lm?.findLastVisibleItemPosition() ?: -1
            val mirror = CurrentMsgList.msgList.value
            val atBottom = last >= 0 && mirror.isNotEmpty() && (mirror.size - 1) - last <= SCROLL_DIST_THRESHOLD
            val live = runCatching { CurrentMsgList.uiOp?.m() }.getOrNull()
            val item = (live?.getOrNull(first) ?: mirror.getOrNull(first)) as? WatchAIOMsgItem
            val id = item?.d?.msgId
            if (!atBottom && id != null) {
                anchors.removeAll { it.msgId == id }    // de-dup: move to most-recent
                anchors.add(Anchor(id))
                while (anchors.size > MAX_ANCHORS) anchors.removeAt(0)
            }
            // Always force the button visible after a jump (even when jumping from the bottom, so a single
            // tap brings you back to the newest). Cleared by the next manual drag or on reaching bottom.
            forceShow = true
            Utils.log("BubbleTextView beginJumpUp first=$first last=$last id=$id atBottom=$atBottom anchors=${anchors.size}")
            current?.refresh()
        }
    }
}
