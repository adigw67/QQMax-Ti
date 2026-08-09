package momoi.mod.qqpro.hook

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.AIOLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.aio.api.list.IListUIOperationApi
import com.tencent.mvi.api.help.CreateViewParams
import com.tencent.watch.aio_impl.coreImpl.vb.WatchAIOListVB
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.asGroup
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3CircularProgress
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.action.RecentContacts
import momoi.mod.qqpro.hook.view.BubbleTextView
import momoi.mod.qqpro.hook.view.smoothScrollToStartAndFlash
import momoi.mod.qqpro.lib.FrameScope
import momoi.mod.qqpro.lib.background
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize

/**
 * One stop the jump chip can take you to. [seq] is the msgSeq of an important unread message
 * (@我/回复/新文件/新公告); a [seq] of null is the terminal "first unread" stop, which is jumped to
 * the count-based way (it has no single msgSeq).
 */
private data class JumpPoint(val seq: Long?, val label: String)

/**
 * The top-right "↑ X条新消息" jump chip. When [Settings.chatImportantJump] is on it steps through the
 * important unread messages one at a time, bottom→top (newest unread first), before the final stop at
 * the first unread; with it off it behaves as the plain first-unread jump.
 *
 * The important stops come from the same kernel signal the conversation-list tags read
 * ([RecentContacts.Data.raw].listOfSpecificEventTypeInfosInMsgBox → each msg's msgSeq + label). They
 * are visited newest→oldest because that is the order you encounter them scrolling up from the bottom.
 * A stop clears (and the chip advances to the next, older one) as soon as its message scrolls into
 * view — whether you got there by tapping the chip or by scrolling manually. When the queue empties
 * (the first unread is reached) the chip hides.
 */
class SkipAction(
    private val rv: RecyclerView,
    private val chip: View,
    private val tv: TextView,
    private val spinner: M3CircularProgress,
    private val recent: RecentContacts.Data
): View.OnClickListener {

    private fun format(count: Int) = "↑ ${count}条新消息"
    private val unreadTotal = recent.unreadCntCached
    private var count = unreadTotal                 // remaining unread toward the first-unread stop (label only)
    private var isClicked = false
    private var isFinished = false

    // Ordered stops: important messages newest→oldest, then a terminal first-unread stop (seq=null).
    // When the setting is off the important stops are skipped → only the terminal stop remains.
    private val points: ArrayDeque<JumpPoint> = ArrayDeque<JumpPoint>().apply {
        if (Settings.chatImportantJump.value) {
            val seen = HashSet<Long>()
            recent.raw.listOfSpecificEventTypeInfosInMsgBox.orEmpty()
                // Only recognised highlights (@我/回复/文件/公告 or a real digest). Unrecognised events
                // (e.g. DM type 1008) get a null label and a bogus msgSeq=1 → drop them, or the chip
                // shows "[新消息]" for every message and jumps to the top of the chat.
                .flatMap { e -> e.msgInfos.orEmpty().mapNotNull { mi -> specificEventLabel(e.eventTypeInMsgBox, mi.highlightDigest)?.let { mi.msgSeq to it } } }
                .filter { it.first > 0L }
                .sortedByDescending { it.first }     // newest (closest to bottom) first
                .forEach { (seq, label) -> if (seen.add(seq)) addLast(JumpPoint(seq, label)) }
        }
        addLast(JumpPoint(null, format(unreadTotal)))
        Utils.log("SkipAction: ${size} stop(s) -> ${joinToString { it.label }}")
    }

    private fun head(): JumpPoint? = points.firstOrNull()

    /** The chip text WITHOUT the leading ↑ (terminal stop shows the live remaining count). */
    private fun bodyLabel(): String {
        val h = head() ?: return ""
        // Clamp: the count heuristic can undershoot to 0/negative once history is paged in, and
        // "↑ -2条新消息" is nonsense to show.
        return if (h.seq == null) "${count.coerceAtLeast(0)}条新消息" else h.label
    }

    /**
     * Repaint the chip for the current head stop. While [loading] the ↑ is dropped — the circular
     * progress on the left takes the arrow's place — and the arrow returns once the jump finishes.
     */
    private fun refreshLabel(loading: Boolean = false) {
        if (head() == null) return hide()
        tv.text = if (loading) bodyLabel() else "↑ ${bodyLabel()}"
    }

    private fun hide() {
        isFinished = true
        chip.visibility = View.GONE
    }

    init {
        refreshLabel()
        chip.setOnClickListener(this)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isFinished) return
                val first = (rv.layoutManager as AIOLayoutManager).findFirstVisibleItemPosition()
                if (first == -1) return
                val list = CurrentMsgList.msgList.value
                var dirty = false

                // Pop important stops that have scrolled into view. The list is in ascending-seq order
                // (higher index = newer = bottom), so the top-of-viewport seq descends as you scroll
                // up; a stop is visible once that seq has reached (≤) the stop's own seq.
                val firstSeq = list.getOrNull(first)?.d?.msgSeq
                if (firstSeq != null) {
                    while (true) {
                        val h = head() ?: break
                        if (h.seq != null && firstSeq <= h.seq) { points.removeFirst(); dirty = true } else break
                    }
                }

                // Always track the remaining unread (for the terminal stop), the original way.
                val newCount = unreadTotal - list.size + first
                if (newCount < count) {
                    count = newCount
                    dirty = true
                }

                val h = head()
                if (h == null || (h.seq == null && count <= 0)) { hide(); return }
                if (dirty) refreshLabel()
            }
        })
    }

    override fun onClick(v: View?) {
        if (isClicked || isFinished) return
        val h = head() ?: return
        val list = CurrentMsgList.msgList.value
        Utils.log("SkipAction click: head=${h.label} seq=${h.seq} count=$count listSize=${list.size}")

        // Drive the circular spinner on the chip's left while the jump loads. It starts indeterminate
        // (we don't yet know how many pages we'll fetch) and flips to determinate as soon as upwardMsg/
        // findMsg report a 0..100 percentage — "show the progress whenever possible".
        val onProgress: (Int) -> Unit = { pct ->
            // Stay indeterminate (spinning) until there's real progress: upwardMsg/findMsg fire
            // onProgress(0) on the first page, and flipping to determinate there leaves the ring sitting
            // at a static 0% until the next page lands. Only switch to the fill once pct actually moves.
            if (pct > 0) {
                spinner.indeterminate = false
                spinner.progress = pct.coerceIn(1, 100) / 100f
            }
        }
        // Jump finished (success or fail): hide the spinner, reset it, and bring the ↑ back.
        val done = {
            spinner.visibility = View.GONE
            spinner.indeterminate = true
            spinner.progress = 0f
            isClicked = false
            refreshLabel()
        }
        val onFail: () -> Unit = {
            done()
            Utils.toast(tv.context, "加载失败，请重试")
        }
        // Remember where we are now and show the back-down button so the user can return here.
        BubbleTextView.beginJumpUp()
        isClicked = true
        // Drop the ↑ and show the spinner in its place immediately (indeterminate until the first
        // progress tick) so the press gives instant feedback that the jump is working.
        spinner.indeterminate = true
        spinner.progress = 0f
        spinner.visibility = View.VISIBLE
        refreshLabel(loading = true)
        if (h.seq != null) {
            // Important stop: locate the exact message (paging up if needed) and smooth-scroll to it.
            CurrentMsgList.findMsg(h.seq, onProgress, result = { msg ->
                if (msg == null) { onFail(); return@findMsg }
                // Consume this stop NOW instead of waiting for the scroll listener to pop it: when the
                // target is already on screen the smooth-scroll is a no-op, so onScrolled never fires and
                // the chip would stay stuck on this stop forever (never advancing to the terminal
                // "first unread" jump). Popping on tap guarantees the stepper always progresses.
                if (head()?.seq == h.seq) points.removeFirstOrNull()
                val idx = CurrentMsgList.getMsgIndex(msg)
                Utils.log("SkipAction: jumped seq=${h.seq} -> idx=$idx, next=${head()?.label}")
                done()
                rv.safeSmoothToStart(idx)
            })
        } else {
            // Terminal "first unread" stop. Jump deterministically to the ORIGINAL first unread
            // (unreadTotal messages up from the newest) rather than the running `count`, which the
            // earlier important-stop jumps corrupt: paging in history drives it to 0/negative, which
            // made this branch silently no-op and left the chip stuck on screen. Retire it once done.
            if (list.isEmpty() || unreadTotal <= 0) { done(); hide(); return }
            CurrentMsgList.upwardMsg(list.size - 1, (unreadTotal - 1).coerceAtLeast(0), onProgress, onFail) { idx ->
                Utils.log("SkipAction: terminal jump -> first-unread idx=$idx (unreadTotal=$unreadTotal)")
                done()
                rv.safeSmoothToStart(idx)
                hide()
            }
        }
    }

    /**
     * Smooth-scroll [pos] to the top, but tolerate the out-of-range positions the count-based
     * [CurrentMsgList.upwardMsg] math can yield (e.g. -1 when the target resolves to the newest
     * message). The old code used [RecyclerView.scrollToPosition], which silently no-ops on an
     * invalid position; [smoothScrollToStart] instead throws "Invalid target position", so clamp:
     * a negative target means "nothing above to scroll to" → no-op (matches the old behavior).
     */
    private fun RecyclerView.safeSmoothToStart(pos: Int) {
        val n = layoutManager?.itemCount ?: 0
        if (n <= 0 || pos < 0) return
        smoothScrollToStartAndFlash(if (pos >= n) n - 1 else pos)
    }
}
@Mixin
class 跳转第一条未读消息 : WatchAIOListVB() {
    @SuppressLint("RtlHardcoded")
    override fun h(
        createViewParams: CreateViewParams,
        childView: View,
        uiHelper: IListUIOperationApi
    ): View = FrameScope(super.h(createViewParams, childView, uiHelper) as FrameLayout).apply {
        val peerUid = CurrentContact.peerUid
        Utils.log("current peerUid: $peerUid")
        RecentContacts.get(peerUid)?.let { recent ->
            Utils.log("unreadCntCached: ${recent.unreadCntCached}")
            if (recent.unreadCntCached > 0) {
                val ctx = childView.context
                // A circular progress sits to the LEFT of the count text; hidden until a jump is in
                // flight (see SkipAction). 14dp reads clearly at watch DPI without crowding the label.
                val spinner = M3CircularProgress(ctx).apply {
                    indicatorColor = M3.primary
                    visibility = View.GONE
                }
                val tv = TextView(ctx)
                    .textSize(12f)
                    .textColor(M3.primary)
                // The chip is now the rounded pill that holds [spinner][text] (the background moved off
                // the TextView). Left corners rounded, right square so it sits flush to the screen edge.
                val chip = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundCornerDrawable(M3.surfaceContainerHigh, 9999f, 0f, 9999f, 0f)
                    setPadding(6.dp, 6.dp, 6.dp, 6.dp)
                    addView(spinner, LinearLayout.LayoutParams(14.dp, 14.dp).apply { rightMargin = 4.dp })
                    addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
                add(chip)
                // FrameLayout.addView defaults to MATCH_PARENT — without WRAP the rounded pill balloons
                // to fill the whole screen. Anchor it top-right at wrap size, clearing the rich titlebar
                // (which overlays the list from the very top in chat-only mode) / screen top.
                chip.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.RIGHT or Gravity.TOP
                    topMargin = (if (Settings.enableTitlebar.value) Settings.titlebarHeight.value.toInt() + 12 else 10).dp
                }
                SkipAction(this@跳转第一条未读消息.H, chip, tv, spinner, recent)
                // 未读超过 20 条时,在跳转气泡下方加入"总结未读"按钮(从第一条未读到末尾)。
                if (Settings.summarizeUnreadButton.value && recent.unreadCntCached > 20) {
                    val chipTop = (if (Settings.enableTitlebar.value) Settings.titlebarHeight.value.toInt() + 12 else 10).dp
                    momoi.mod.qqpro.hook.summarize.addSummarizeUnreadButton(this.group as FrameLayout, chip, chipTop + 36.dp, recent.unreadCntCached)
                }
            }
        }
    }.group
}