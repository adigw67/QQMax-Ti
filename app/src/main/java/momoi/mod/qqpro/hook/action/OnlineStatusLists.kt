package momoi.mod.qqpro.hook.action

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.qqnt.chats.core.adapter.itemdata.RecentContactChatItem
import com.tencent.qqnt.watch.chat.list.WatchRecentContactHolder
import com.tencent.widget.SingleLineTextView
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.enums.ChatType
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.util.Utils

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Surface 1: conversation (main) list — a small presence dot on the TOP-LEFT of a DM row's avatar.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

private const val REC_BADGE_TAG = "qqpro_online_badge"

/**
 * Overlay a presence dot on the top-left of the avatar for a PRIVATE (DM) row; hidden for groups and
 * until presence for the peer is known. Called on every full bind ([RecentContacts.Hook.t]) so it
 * survives recycling. The row is a ConstraintLayout — we add the dot as an unconstrained child (lands
 * at 0,0) and translate it onto the avatar's top-left corner (avatar sits at marginStart/Top = 6dp).
 */
fun applyRecentOnlineBadge(holder: WatchRecentContactHolder, item: RecentContactChatItem) {
    if (!Settings.onlineStatusMainList.value) return
    runCatching {
        val root = holder.itemView as? ViewGroup ?: return
        val ctx = root.context
        var badge = root.findViewWithTag<ImageView>(REC_BADGE_TAG)
        if (item.a.chatType != ChatType.PRIVATE) { badge?.visibility = View.GONE; return }
        val uid = item.a.peerUid
        if (badge == null) {
            badge = ImageView(ctx).apply { tag = REC_BADGE_TAG }
            val size = 9.dp
            root.addView(badge, size, size)
            badge.translationX = 6.dp.toFloat()
            badge.translationY = 6.dp.toFloat()
        }
        if (!OnlineStatus.known(uid)) { badge.visibility = View.GONE; return }
        badge.visibility = View.VISIBLE
        badge.setImageDrawable(OnlineStatusUi.dot(ctx, OnlineStatus.isOnline(uid)))
    }.onFailure { Utils.log("OnlineStatus: recent badge failed: $it") }
}

/**
 * Start polling and keep the conversation list's presence dots fresh: a debounced observer forces the
 * list adapter to rebind (which re-runs [applyRecentOnlineBadge]) whenever presence pushes arrive.
 * Called from the conversation fragment's onCreateView; self-unregisters when the list detaches.
 */
fun wireRecentOnlineRefresh(root: View) {
    if (!Settings.onlineStatusMainList.value) return
    OnlineStatus.start()
    val rv = (root as? ViewGroup)?.findAll { it is RecyclerView } as? RecyclerView ?: return
    val handler = Handler(Looper.getMainLooper())
    val refresh = Runnable { runCatching { rv.adapter?.notifyDataSetChanged() } }
    val observer: () -> Unit = { handler.removeCallbacks(refresh); handler.postDelayed(refresh, 150); Unit }
    OnlineStatus.addObserver(observer)
    rv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            OnlineStatus.removeObserver(observer); handler.removeCallbacks(refresh)
        }
    })
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Surface 2: contacts (friend) list — append the full status description to a friend row's title.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Shows the presence description (e.g. "手机在线") on a SECOND line under a friend row's name. The row
 * (item_contact) is a fixed-40dp ConstraintLayout with a single-line title, so we don't restructure it:
 * we nudge the (vertically-centred) title up a few dp and drop a small status TextView just below it,
 * positioned by translation (no constraint surgery). Only friend rows get it — headers/groups/notify are
 * left alone; on recycling every row is reset first, so a recycled friend→header row never keeps a line.
 *
 * Wired as a child-attach listener + a presence observer (re-applies to visible rows on push), mirroring
 * the HeaderStyler pattern.
 */
class ContactOnlineStatusStyler(
    private val rv: RecyclerView,
    private val adapter: Any,
) : RecyclerView.OnChildAttachStateChangeListener {
    private val titleId = rv.context.resources.getIdentifier("title", "id", rv.context.packageName)

    private val observer: () -> Unit = { runCatching { reapplyVisible() } }

    fun install() {
        OnlineStatus.start()
        rv.addOnChildAttachStateChangeListener(this)
        OnlineStatus.addObserver(observer)
        rv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { OnlineStatus.removeObserver(observer) }
        })
    }

    override fun onChildViewAttachedToWindow(view: View) { runCatching { apply(view) } }
    override fun onChildViewDetachedFromWindow(view: View) {}

    private fun reapplyVisible() {
        for (i in 0 until rv.childCount) rv.getChildAt(i)?.let { runCatching { apply(it) } }
    }

    private fun apply(view: View) {
        val pos = rv.getChildAdapterPosition(view)
        if (pos < 0) return
        @Suppress("UNCHECKED_CAST")
        val list = runCatching { adapter.javaClass.getMethod("getCurrentList").invoke(adapter) as? List<Any?> }
            .getOrNull() ?: return
        val item = list.getOrNull(pos) ?: return
        val title = view.findViewById<View>(titleId) as? SingleLineTextView
        // Reset any second-line styling first — this view may be a recycled friend row now showing a
        // header/group/notify item.
        title?.translationY = 0f
        view.findViewWithTag<TextView>(STATUS_ROW_TAG)?.visibility = View.GONE

        // Only friend rows carry presence. ContactItem is R8-minified: fields a=uin, b=uid, c=nickName,
        // d=needExtIcon (getTitle() returns c). Read uid off `b`; skip headers/groups/notify rows.
        if (!item.javaClass.name.endsWith("ContactItem")) return
        val uid = runCatching { item.javaClass.getField("b").get(item) as? String }.getOrNull() ?: return
        if (!OnlineStatus.known(uid)) return
        val desc = OnlineStatus.describe(uid) ?: return
        title ?: return
        val root = view as? ViewGroup ?: return

        // Nudge the centred name up so the status line fits under it within the 40dp row.
        title.translationY = (-7).dp.toFloat()

        var line = root.findViewWithTag<TextView>(STATUS_ROW_TAG)
        if (line == null) {
            line = TextView(root.context).apply {
                tag = STATUS_ROW_TAG
                textSize = 10f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                // Under the name: x = avatar(10 start +28) + title marginLeft(10) = 48dp; y below centre.
                translationX = 48.dp.toFloat()
                translationY = 21.dp.toFloat()
            }
            root.addView(line, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        line.setTextColor(OnlineStatusUi.color(OnlineStatus.isOnline(uid)))
        line.text = desc
        line.visibility = View.VISIBLE
    }
}

private const val STATUS_ROW_TAG = "qqpro_contact_status_line"
