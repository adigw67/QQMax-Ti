package momoi.mod.qqpro.hook.qzone

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.watch.qzone_impl.common.QZoneBusinessLooper
import com.tencent.watch.qzone_impl.common.task.QZoneTask
import com.tencent.watch.qzone_impl.feed.model.BusinessFeedData
import com.tencent.watch.qzone_impl.feed.model.CellCommentInfo
import com.tencent.watch.qzone_impl.feed.model.Comment
import com.tencent.watch.qzone_impl.feed.model.User
import com.tencent.watch.qzone_impl.frame.IAdapterHost
import com.tencent.watch.qzone_impl.protocol.request.QZoneAddCommentRequest
import com.tencent.watch.qzone_impl.protocol.request.QZoneAddReplyRequest
import com.tencent.watch.qzone_impl.protocol.request.QzoneDeleteCommentRequest
import com.tencent.watch.qzone_impl.protocol.request.QzoneDeleteReplyRequest
import com.tencent.watch.qzone_impl.service.QZoneWriteOperationService
import com.tencent.watch.qzone_impl.utils.UinUtils
import NS_MOBILE_FEEDS.Reply
import momoi.mod.qqpro.hook.ProfileDetailCard
import momoi.mod.qqpro.hook.openUserQzone
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3QQEditText
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils
import android.widget.ImageView
import java.util.UUID

/**
 * Custom Material 3 comment-thread screen for a QZone post ([Settings.materializeQzone]). Replaces the
 * cramped native inline blob: each comment/reply is a structured row (avatar + author + full-width
 * body), replies carry a compact "回复 〈target〉" overline (never inline) so who-replies-to-whom is
 * clear and the body wraps cleanly on the watch.
 *
 * The reply bar at the bottom is an [M3QQEditText] (emoji + hold-to-mic STT built in) — typing and
 * voice edit the same field, with no input-mode dialog. Tapping a comment targets it for a reply;
 * otherwise the send posts a top-level comment. Posting goes straight through the kernel:
 *  - comment → [QZoneAddCommentRequest] + [QZoneTask] + [QZoneBusinessLooper]
 *  - reply   → [QZoneAddReplyRequest] (content encoded as @{uin:..,nick:..,who:1,auto:1}<text>)
 *
 * Args are passed via the constructor (host is not Parcelable); a no-arg secondary constructor lets
 * the framework recreate it harmlessly (it just closes).
 */
class QzoneCommentThread(
    private val data: BusinessFeedData? = null,
    private val host: IAdapterHost? = null,
) : MyDialogFragment() {

    constructor() : this(null, null)

    private companion object {
        /** Max visual indent levels for nested replies (each ≈20dp). Caps deep threads on the watch. */
        const val MAX_INDENT = 4
    }

    private var replyTo: ReplyTarget? = null
    private var input: M3QQEditText? = null
    private var listColumn: LinearLayout? = null

    /** Where the next reply is posted. The QZone protocol is 2-level: replies always attach to a
     *  top-level [comment] (via its commentid), and reply-to-reply is expressed only by who the reply
     *  is *addressed* to — [targetUin]/[targetNick], which may be the comment author OR a nested
     *  reply's author. So this carries both: the comment to attach under, and the author to address. */
    private data class ReplyTarget(val comment: Comment, val targetUin: Long, val targetNick: String)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val d = data
        if (d == null) { runCatching { dismiss() }; return View(ctx) }

        val edge = if (Utils.isRoundScreen) 16.dp else 8.dp
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(edge, 6.dp, edge, 6.dp)
        }

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        listColumn = column
        rebuildList(d)
        val scroll = ScrollView(ctx).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(column) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // --- reply bar ---
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val edit = M3QQEditText(ctx).apply {
            setHint("评论…")
            setMultiline(true)
            setFieldTextSize(12)
            permissionFragmentProvider = { this@QzoneCommentThread }
        }
        input = edit
        bar.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Send button scaled to match the reduced field (≈80%): 40→32dp, padding proportional.
        val send = ImageView(ctx).apply {
            setImageDrawable(MaterialSymbol(MaterialSymbols.send, M3.primary))
            setPadding(6.dp, 6.dp, 3.dp, 6.dp)
            isClickable = true
            setOnClickListener { onSend() }
        }
        bar.addView(send, LinearLayout.LayoutParams(32.dp, 32.dp).apply { marginStart = 4.dp })
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp })

        return swipeBackWrap(root)
    }

    private fun rebuildList(d: BusinessFeedData) {
        val column = listColumn ?: return
        val ctx = column.context
        column.removeAllViews()
        // Comments only — the post body is already on the feed card.
        val comments = runCatching { d.cellCommentInfo?.c }.getOrNull().orEmpty()
        if (comments.isEmpty()) column.addView(TextView(ctx).apply {
            text = "还没有评论，快来抢沙发"
            setTextColor(M3.onSurfaceTip); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, 20.dp, 0, 20.dp)
        })
        comments.forEach { c ->
            val rowView = QzoneFeedCard.commentRow(ctx, c.user, c.user?.nickName, null, c.comment, small = false)
            rowView.isClickable = true
            // Tap → open the author's QZone; long-press → 回复 / 删除 menu.
            rowView.setOnClickListener { openAuthor(c.user?.uin) }
            rowView.setOnLongClickListener { showCommentMenu(c, c); true }
            column.addView(rowView)
            // replies, nested by who-replies-to-whom
            runCatching { renderReplies(column, c) }
        }
    }

    /**
     * Render a comment's flat [Comment.replies] list as a nested tree. QZone stores replies flat
     * (each only records its [Reply.targetUser]), so the tree is reconstructed: a reply is a child of
     * the most recent earlier reply authored by its target uin; if the target is the comment author
     * (or absent), it sits at the first level under the comment. Visual indent is capped at
     * [MAX_INDENT] levels so deep threads don't run off the watch screen — targeting stays correct
     * regardless of the cap.
     */
    private fun renderReplies(column: LinearLayout, parent: Comment) {
        val ctx = column.context
        val replies = runCatching { parent.replies }.getOrNull().orEmpty()
        if (replies.isEmpty()) return
        val commentAuthor = parent.user?.uin ?: 0L
        val depthByReply = HashMap<Reply, Int>()   // 0 = directly under the comment
        val lastByAuthor = HashMap<Long, Reply>()   // uin -> most recent earlier reply by that author
        for (r in replies) {
            val tgt = r.targetUser?.uin ?: 0L
            val parentReply = if (tgt != 0L && tgt != commentAuthor) lastByAuthor[tgt] else null
            val depth = if (parentReply != null) (depthByReply[parentReply] ?: 0) + 1 else 0
            depthByReply[r] = depth
            (r.user?.uin ?: 0L).takeIf { it != 0L }?.let { lastByAuthor[it] = r }

            val rr = QzoneFeedCard.commentRow(ctx, r.user, r.user?.nickName, r.targetUser?.nickName, r.content, small = true)
            val indentLevel = (depth + 1).coerceAtMost(MAX_INDENT)   // +1: replies start one step under the comment
            rr.setPadding(indentLevel * 20.dp, rr.paddingTop, 0, rr.paddingBottom)
            rr.isClickable = true
            rr.setOnClickListener { openAuthor(r.user?.uin) }
            rr.setOnLongClickListener { showCommentMenu(parent, r); true }
            column.addView(rr)
        }
    }

    /** Open the QZone home of [uin]; the navigation closes this thread (one-shot, like the phone). */
    private fun openAuthor(uin: Long?) {
        val h = host ?: return
        if (uin == null || uin <= 0L) return
        val v = runCatching { h.b().requireView() }.getOrNull() ?: return
        runCatching { dismiss() }
        openUserQzone(v, uin)
    }

    /** Long-press action sheet for a comment or reply: 回复 always, 删除 when deletable. [parent] is
     *  the top-level comment a reply belongs to (reply targets reply to its parent comment). */
    private fun showCommentMenu(parent: Comment, target: Any) {
        val rows = ArrayList<Pair<String, () -> Unit>>()
        // Reply addresses the long-pressed message's author: a top-level comment → its author;
        // a nested reply → that reply's author (still posted under the parent comment).
        rows.add("回复" to {
            when (target) {
                is Reply -> setReplyTarget(ReplyTarget(parent, target.user?.uin ?: 0L, target.user?.nickName ?: ""))
                is Comment -> setReplyTarget(ReplyTarget(target, target.user?.uin ?: 0L, target.user?.nickName ?: ""))
                else -> setReplyTarget(ReplyTarget(parent, parent.user?.uin ?: 0L, parent.user?.nickName ?: ""))
            }
        })
        if (canDelete(target)) rows.add("删除" to {
            QzoneConfirmDialog("确定删除？", "删除", destructive = true) { deleteCommentOrReply(parent, target) }
                .show(childFragmentManager, "qzdelcr")
        })
        runCatching { QzoneOverflowFragment(rows, destructive = setOf("删除")).show(childFragmentManager, "qzcrmenu") }
            .onFailure { Utils.log("QzoneCommentThread menu: $it") }
    }

    /** Deletable when it isn't a pending fake and either the post is mine or I authored it. */
    private fun canDelete(target: Any): Boolean {
        val d = data ?: return false
        val self = UinUtils.b()
        val postMine = QzoneActions.isSelf(d)
        return when (target) {
            is Comment -> !target.isFake && (postMine || target.user?.uin == self)
            is Reply -> !target.isFake && (postMine || target.user?.uin == self)
            else -> false
        }
    }

    private fun deleteCommentOrReply(parent: Comment, target: Any) {
        val d = data ?: return
        runCatching {
            when (target) {
                is Comment -> deleteComment(d, target)
                is Reply -> deleteReply(d, target)
            }
            // Optimistic local removal so the thread updates immediately.
            runCatching {
                when (target) {
                    is Comment -> {
                        d.cellCommentInfo?.let { ci -> if (ci.c?.remove(target) == true && ci.b > 0) ci.b -= 1 }
                    }
                    is Reply -> parent.replies?.remove(target)
                }
            }
            rebuildList(d)
            // Re-bind the matching feed card so the post's comment list/count updates too (no full
            // refresh — that would jump the feed's scroll position).
            QzoneFeedM3.notifyFeeds()
            Utils.toast(requireContext(), "已删除")
        }.onFailure { Utils.log("QzoneCommentThread delete: $it"); Utils.toast(requireContext(), "删除失败") }
    }

    private fun deleteComment(d: BusinessFeedData, c: Comment) {
        if (c.isFake) return
        val fc = d.feedCommInfo
        val appid = fc.appid.toLong()
        val postOwner = d.user?.uin ?: d.owner_uin
        val cellId = runCatching { d.idInfo?.cellId }.getOrNull() ?: ""
        val commentAuthor = c.user?.uin ?: 0L
        val commentId = c.commentid ?: ""
        val busi = runCatching { d.operationInfo?.busiParam }.getOrNull()
        val req = QzoneDeleteCommentRequest(appid, postOwner, cellId, commentAuthor, commentId, 0, busi)
        val task = QZoneTask(req, null, QZoneWriteOperationService.h(), 10)
        task.addParameter("ugckey", fc.ugckey)
        task.addParameter("position", 0)
        QZoneBusinessLooper.a().c(task)
        Utils.log("QzoneCommentThread: delete comment $commentId on ${fc.feedskey}")
    }

    private fun deleteReply(d: BusinessFeedData, r: Reply) {
        if (r.isFake) return
        val fc = d.feedCommInfo
        val appid = fc.appid.toLong()
        val postOwner = d.user?.uin ?: d.owner_uin
        val cellId = runCatching { d.idInfo?.cellId }.getOrNull() ?: ""
        val commentUin = r.commentUin ?: 0L
        val commentId = r.commentId ?: ""
        val replyAuthor = r.user?.uin ?: 0L
        val replyId = r.replyId ?: ""
        val busi = runCatching { d.operationInfo?.busiParam }.getOrNull()
        val req = QzoneDeleteReplyRequest(appid, postOwner, cellId, commentUin, commentId, replyAuthor, replyId, 0, busi)
        val task = QZoneTask(req, null, QZoneWriteOperationService.h(), 11)
        task.addParameter("ugckey", fc.ugckey)
        task.addParameter("position", 0)
        QZoneBusinessLooper.a().c(task)
        Utils.log("QzoneCommentThread: delete reply $replyId on ${fc.feedskey}")
    }

    private fun setReplyTarget(t: ReplyTarget?) {
        replyTo = t
        input?.setHint(if (t == null) "评论…" else "回复 ${t.targetNick}…")
        input?.focusAndShowKeyboard()
    }

    private fun onSend() {
        val d = data ?: return
        val h = host ?: return
        val text = input?.trimmedText().orEmpty()
        if (text.isBlank()) { Utils.toast(requireContext(), "内容为空"); return }
        if (QzoneActions.isFake(d)) { Utils.toast(requireContext(), "正在发布, 请稍后"); return }
        val target = replyTo
        val ok = runCatching {
            if (target == null) postComment(d, text) else postReply(d, target, text)
        }.onFailure { Utils.log("QzoneCommentThread send: $it") }.isSuccess
        if (ok) {
            input?.setText("")
            setReplyTarget(null)
            // Show it immediately (the server write is async) by inserting an optimistic fake
            // comment/reply into the SAME feed-data object the thread and feed card share, then
            // rebuilding the thread — exactly like the delete path. We deliberately do NOT pull-refresh
            // the whole feed here: a comment must not re-sort/scroll the feed (that jumps the reading
            // position). Feed refresh is only correct after publishing a NEW post.
            runCatching { insertOptimistic(d, target, text) }
                .onFailure { Utils.log("QzoneCommentThread optimistic: $it") }
            Utils.toast(requireContext(), "已发送")
        } else {
            Utils.toast(requireContext(), "发送失败")
        }
    }

    /** Insert an optimistic (isFake) comment/reply into the local feed data + rebuild, for instant
     *  feedback before the async server write lands. A later refresh replaces it with real data. */
    private fun insertOptimistic(d: BusinessFeedData, target: ReplyTarget?, text: String) {
        val self = User(UinUtils.b(), ProfileDetailCard.selfNick() ?: "")
        if (target == null) {
            val c = Comment().apply {
                comment = text; user = self; commentid = ""; isFake = true; replies = ArrayList()
            }
            // A post with no comments yet may have a null cellCommentInfo. Its Kotlin property is
            // read-only (getter + field), so create + attach one via the public field by reflection.
            val ci = d.cellCommentInfo ?: CellCommentInfo().also { fresh ->
                runCatching { d.javaClass.getField("cellCommentInfo").set(d, fresh) }
                    .onFailure { Utils.log("QzoneCommentThread: set cellCommentInfo failed: $it") }
            }
            if (ci.c == null) ci.c = ArrayList()
            ci.c.add(c)
            ci.b += 1
            Utils.log("QzoneCommentThread: optimistic comment added (total ${ci.c.size})")
        } else {
            val reply = Reply().apply {
                content = text; user = self
                targetUser = User(target.targetUin, target.targetNick); isFake = true
            }
            val pc = target.comment
            val replies = (pc.replies as? MutableList<Reply>) ?: ArrayList<Reply>().also { pc.replies = it }
            replies.add(reply)
            Utils.log("QzoneCommentThread: optimistic reply added (total ${replies.size})")
        }
        rebuildList(d)
        // Re-bind the matching feed card so the post's comment list/count reflects the add too
        // (local only — no full refresh, so the feed's scroll position is untouched).
        QzoneFeedM3.notifyFeeds()
        // Scroll the thread so the freshly added row is visible.
        (listColumn?.parent as? ScrollView)?.post { (listColumn?.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun postComment(d: BusinessFeedData, text: String) {
        val fc = d.feedCommInfo
        val ownerUin = runCatching { d.user?.uin }.getOrNull() ?: d.owner_uin
        val cellId = runCatching { d.idInfo?.cellId }.getOrNull() ?: ""
        val busi = runCatching { d.operationInfo?.busiParam }.getOrNull()
        val req = QZoneAddCommentRequest(fc.appid, ownerUin, cellId, text, null, false, busi)
        val task = QZoneTask(req, null, QZoneWriteOperationService.h(), 0)
        task.addParameter("ugckey", fc.ugckey)
        task.addParameter("feedkey", fc.feedskey)
        task.addParameter("uniKey", UUID.randomUUID().toString())
        task.addParameter("clickScene", 0)
        QZoneBusinessLooper.a().c(task)
        Utils.log("QzoneCommentThread: posted comment on ${fc.feedskey}")
    }

    private fun postReply(d: BusinessFeedData, target: ReplyTarget, text: String) {
        val fc = d.feedCommInfo
        val cellId = runCatching { d.idInfo?.cellId }.getOrNull() ?: ""
        val busi = runCatching { d.operationInfo?.busiParam }.getOrNull()
        val targetUin = target.targetUin
        val targetNick = target.targetNick
        val commentId = target.comment.commentid ?: ""
        // who:1 (reply to a comment author), auto:1 — same encoding the native reply path uses.
        val encodedNick = targetNick.replace("%", "%25").replace(",", "%2C")
            .replace("{", "%7B").replace("}", "%7D").replace(":", "%3A")
        val content = "@{uin:$targetUin,nick:$encodedNick,who:1,auto:1}$text"
        val req = QZoneAddReplyRequest(fc.appid, UinUtils.b(), targetUin, cellId, commentId, content, null, 0, busi, "", HashMap())
        val task = QZoneTask(req, null, QZoneWriteOperationService.h(), 0)
        task.addParameter("ugckey", fc.ugckey)
        task.addParameter("feedkey", fc.feedskey)
        task.addParameter("uniKey", UUID.randomUUID().toString())
        task.addParameter("clickScene", 0)
        QZoneBusinessLooper.a().c(task)
        Utils.log("QzoneCommentThread: posted reply to $targetNick on ${fc.feedskey}")
    }
}
