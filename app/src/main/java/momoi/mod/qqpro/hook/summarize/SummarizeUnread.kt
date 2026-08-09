package momoi.mod.qqpro.hook.summarize

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.hook.AttachmentOverlay
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3CircularProgress
import momoi.mod.qqpro.util.Utils

/**
 * The "AI总结" pill shown below the "↑ X条新消息" jump chip when the unread count exceeds 20.
 * Tapping it summarizes from the first unread message to the end of the chat. A circular progress
 * sits to the left of the label while the unread history pages in (same logic/visuals as the jump
 * chip). Built in this non-@Mixin file so its click listener (an anonymous class) isn't copied into
 * the patched class (which would IllegalAccessError — see the project's mixin-anon-class rule).
 */
fun addSummarizeUnreadButton(parent: FrameLayout, jumpChip: View, topMargin: Int, unreadTotal: Int) {
    val ctx = parent.context
    val spinner = M3CircularProgress(ctx).apply {
        indicatorColor = M3.onPrimary
        // No track ring: on the primary-colored pill a full 360° track reads as a complete static
        // circle and masks the growing progress arc (the fill looks "frozen" even though it advances).
        // Without it, determinate mode shows only the arc growing from the top — an unambiguous fill.
        trackColor = 0
        visibility = View.GONE
    }
    val tv = TextView(ctx).apply {
        text = "AI总结"
        textSize = 12f
        setTextColor(M3.onPrimary)
    }
    val pill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundCornerDrawable(M3.primary, 9999f, 0f, 9999f, 0f)
        setPadding(6.dp, 6.dp, 6.dp, 6.dp)
        addView(spinner, LinearLayout.LayoutParams(14.dp, 14.dp).apply { rightMargin = 4.dp })
        addView(tv)
    }
    parent.addView(pill, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        gravity = Gravity.RIGHT or Gravity.TOP
        this.topMargin = topMargin
    })
    pill.setOnClickListener { summarizeUnread(pill, jumpChip, spinner, unreadTotal) }
}

private var busy = false

private fun summarizeUnread(anchor: View, jumpChip: View, spinner: M3CircularProgress, unreadTotal: Int) {
    if (busy) return
    val target = unreadTotal.coerceAtLeast(1)
    Utils.log("summarizeUnread: target=$target loaded=${CurrentMsgList.msgList.value.size}")
    // The jump chip hides immediately on press; the AI总结 pill stays (with its spinner) until the
    // summary dialog actually appears.
    jumpChip.visibility = View.GONE

    val open = {
        busy = false
        spinner.visibility = View.GONE
        spinner.indeterminate = true
        spinner.progress = 0f
        val list = CurrentMsgList.msgList.value
        val slice = list.takeLast(target.coerceAtMost(list.size))
        val apiMsgs = SummaryMessages.from(slice)
        Utils.log("summarizeUnread: slice=${slice.size} apiMsgs=${apiMsgs.size}")
        if (apiMsgs.isEmpty()) { Utils.toast(anchor.context, "没有可总结的消息") }
        else {
            val fm = AttachmentOverlay.aioFragment(anchor)
                ?.let { runCatching { it.parentFragmentManager }.getOrNull() }
            if (fm == null) Utils.log("summarizeUnread: no fragment manager")
            else runCatching {
                val key = SummaryStore.keyOf(CurrentContact.chatType, CurrentContact.peerUid)
                SummaryViewer.live(apiMsgs, "未读 ${apiMsgs.size} 条", key).show(fm, "qqpro_summary")
                // Dialog shown — now hide the pill (the unread stretch is consumed).
                anchor.visibility = View.GONE
            }.onFailure { Utils.log("summarizeUnread: open failed: $it") }
        }
    }

    if (CurrentMsgList.msgList.value.size >= target) { open(); return }

    // Page up the unread history first, with a determinate spinner — same path the jump chip uses.
    busy = true
    spinner.indeterminate = true
    spinner.progress = 0f
    spinner.visibility = View.VISIBLE
    val onProgress: (Int) -> Unit = { pct ->
        // Keep SPINNING (indeterminate) until there's real measurable progress. upwardMsg fires
        // onProgress(0) synchronously on the first page (before == startSize); flipping to determinate
        // there cancels the spin animator and leaves a static 0% ring for the whole (large) unread
        // paging run. Only switch to the determinate fill once pct actually advances.
        if (pct > 0) {
            spinner.indeterminate = false
            spinner.progress = pct.coerceIn(1, 100) / 100f
        }
    }
    val onFail: () -> Unit = {
        // Reached top before target (history shorter than unread) — just summarize what we have.
        Utils.log("summarizeUnread: paging stopped before target, using loaded")
        open()
    }
    val list = CurrentMsgList.msgList.value
    if (list.isEmpty()) { open(); return }
    CurrentMsgList.upwardMsg(list.size - 1, target - 1, onProgress, onFail) { open() }
}
