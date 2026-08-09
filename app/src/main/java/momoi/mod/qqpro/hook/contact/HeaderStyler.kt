package momoi.mod.qqpro.hook.contact

import android.graphics.Typeface
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.tencent.widget.SingleLineTextView
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

/**
 * Makes the "好友"/"群聊" [SectionHeaderItem] rows look like section titles instead of cards:
 * strips the card background, hides the avatar, and shows large bold text.
 *
 * The contacts adapter renders every row from one layout (item_contact) and can't be subclassed
 * safely, so we restyle each row view as it attaches to the RecyclerView. This is a named listener
 * (not an inline anonymous class) on purpose — anonymous listener classes created inside a @Mixin
 * method body crash with IllegalAccessError. Recycled views are always reset on the non-header
 * branch so a header view reused for a normal row looks correct again.
 *
 * The title is a com.tencent.widget.SingleLineTextView (a custom View, NOT a TextView), whose
 * setTextSize(float) takes raw px — so sp sizes are converted via the display metrics.
 */
class HeaderStyler(
    private val rv: RecyclerView,
    private val adapter: Any,
) : RecyclerView.OnChildAttachStateChangeListener {

    private val res = rv.context.resources
    private val pkg = rv.context.packageName
    private val titleId = res.getIdentifier("title", "id", pkg)
    private val avatarId = res.getIdentifier("avatar", "id", pkg)
    private val bgItem = res.getIdentifier("bg_contact_item", "drawable", pkg)
    private val normalColor = res.getIdentifier("qui_common_text_primary", "color", pkg)
        .let { if (it != 0) runCatching { res.getColor(it, null) }.getOrDefault(-1) else -1 }

    // SingleLineTextView.setTextSize(float) interprets its arg as SP (applyDimension COMPLEX_UNIT_SP)
    // while getTextSize() returns PX — so we capture the row's size in SP (px / scaledDensity) and
    // feed SP back. Using a multiple of the row's own size keeps it correct under any UI scale/dpi.
    private val scaledDensity = res.displayMetrics.scaledDensity
    private var baseSp = 0f

    override fun onChildViewAttachedToWindow(view: View) {
        runCatching {
            val pos = rv.getChildAdapterPosition(view)
            if (pos < 0) return
            val list = adapter.javaClass.getMethod("getCurrentList").invoke(adapter) as List<*>
            style(view, list.getOrNull(pos) is SectionHeaderItem)
        }.onFailure { Utils.log("HeaderStyler: $it") }
    }

    override fun onChildViewDetachedFromWindow(view: View) {}

    private fun style(itemView: View, header: Boolean) {
        val title = itemView.findViewById<View>(titleId) as? SingleLineTextView
        val avatar = itemView.findViewById<View>(avatarId)
        if (header) {
            itemView.background = null                 // no card
            avatar?.visibility = View.GONE             // collapse the avatar slot → text moves left
            title?.apply {
                if (baseSp > 0f) setTextSize(baseSp * 1.35f)
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(M3.onSurfaceVariant)       // light gray, distinct from row text
            }
        } else {
            avatar?.visibility = View.VISIBLE
            // With the Material contacts list on, give each row an M3 surface card + onSurface text to
            // match the Material top bar; otherwise restore the native card bg + text color (this view
            // may have been a header before recycling).
            val material = Settings.materialContactsList.value
            if (material) itemView.background = M3.ripple(M3.rounded(M3.surfaceContainer, M3.radiusLg))
            else if (bgItem != 0) itemView.setBackgroundResource(bgItem)
            title?.apply {
                // First normal row gives us the base size (in SP); reset others to it in case this
                // view was previously a (1.35x bold) header. Don't resize the captured row itself.
                if (baseSp <= 0f) baseSp = if (scaledDensity > 0f) textSize / scaledDensity else 0f
                else setTextSize(baseSp)
                setTypeface(Typeface.DEFAULT)
                setTextColor(if (material) M3.onSurface else normalColor)
            }
        }
    }
}
