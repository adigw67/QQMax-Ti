package momoi.mod.qqpro.hook.translate

import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Message translation inside a 合并转发聊天记录 (chat-history / batch-forward) viewer.
 *
 * Those records aren't backed by a live [com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget] —
 * the viewer ([momoi.mod.qqpro.hook.aio_cell.ForwardMsg]) renders each text bubble into its OWN
 * TextView — so [MessageTranslate]'s cell-bound path can't reach them. The viewer registers each text
 * TextView here at build time; the long-press 翻译 entry (added in [buildMessageActions] for the
 * history path) toggles translation and we render straight into the registered view via
 * [MessageTranslate.translateInto].
 *
 * The viewer's RecyclerView recycles cells, so a registered view may later be rebound to a different
 * record. We re-apply on [register] when the msg is still toggled on, and store the original [base]
 * text per msgId so a toggle-off restores it.
 */
object HistoryTranslate {
    private val views = HashMap<Long, WeakReference<TextView>>()
    private val bases = HashMap<Long, CharSequence>()
    /** msgIds the user toggled translation on for. */
    private val on = HashSet<Long>()

    /** Called by the forward viewer for each text bubble it builds. */
    fun register(msgId: Long, tv: TextView, base: CharSequence) {
        if (msgId == 0L) return
        if (views.size > 1024) { views.clear(); bases.clear() }
        views[msgId] = WeakReference(tv)
        bases[msgId] = base
        // Cell recycled/rebound while still toggled on → re-render the translation into the fresh view.
        if (on.contains(msgId)) MessageTranslate.translateInto(tv, base)
    }

    /** Whether a (still-attached) text bubble is registered for [msgId] — gates the menu entry. */
    fun has(msgId: Long): Boolean = views[msgId]?.get() != null

    fun isOn(msgId: Long): Boolean = on.contains(msgId)

    /** Toggle translation for [msgId]'s on-screen text bubble. */
    fun toggle(msgId: Long) {
        val tv = views[msgId]?.get() ?: return
        val base = bases[msgId] ?: tv.text
        if (on.remove(msgId)) {
            tv.text = base
        } else {
            on.add(msgId)
            MessageTranslate.translateInto(tv, base)
        }
    }
}
