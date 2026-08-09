package momoi.mod.qqpro

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import momoi.mod.qqpro.hook.applyRotaryScroll
import momoi.mod.qqpro.hook.findTarget
import momoi.mod.qqpro.hook.menu.AttachmentMenuConfig
import momoi.mod.qqpro.hook.menu.LongPressMenuConfig
import momoi.mod.qqpro.hook.menu.MenuConfig
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils
import java.util.Collections

/**
 * 菜单自定义 editor — a single drag-to-reorder list for one [MenuConfig] (the chat long-press menu or
 * the "+" attachment menu, chosen by the [EXTRA_MENU] intent extra). Each item has a drag handle on
 * the left; a fixed separator row splits the list into "shown" (above) and "hidden" (below) — drag an
 * item below the separator and it stops appearing in that menu. The order is persisted on every drop.
 *
 * Standalone [Activity] (the settings screen is a plain Activity with no FragmentManager). Registered
 * in app/mixin/AndroidManifest.xml.
 */
class MenuEditorActivity : Activity() {

    companion object {
        const val EXTRA_MENU = "qqpro_menu"
        const val MENU_LONGPRESS = "longpress"
        const val MENU_ATTACHMENT = "attachment"

        private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
        private val ID_HANDLE = View.generateViewId()
        private val ID_ICON = View.generateViewId()
        private val ID_LABEL = View.generateViewId()
    }

    private lateinit var config: MenuConfig
    private lateinit var adapter: Adapter
    private var recycler: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Translucent window + no system open/close transition: the SwipeBackLayout's own slide/fade is
        // the transition, and a transparent window lets a swipe-back reveal the settings screen behind
        // (instead of black). The content layers stay opaque (M3.surface) so the editor itself is solid.
        // convertToTranslucent is @hide → reach it reflectively. Mirrors 设置页.
        runCatching {
            val m = Activity::class.java.declaredMethods
                .filter { it.name == "convertToTranslucent" }
                .minByOrNull { it.parameterTypes.size } ?: return@runCatching
            m.isAccessible = true
            m.invoke(this, *arrayOfNulls<Any?>(m.parameterTypes.size))
        }.onFailure { Utils.log("MenuEditor: convertToTranslucent failed: $it") }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        overridePendingTransition(0, 0)

        val which = intent?.getStringExtra(EXTRA_MENU) ?: MENU_LONGPRESS
        config = if (which == MENU_ATTACHMENT) AttachmentMenuConfig else LongPressMenuConfig
        val title = if (which == MENU_ATTACHMENT) "附件菜单" else "长按消息菜单"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            addView(header(title), LinearLayout.LayoutParams(MP, WC))
        }

        adapter = Adapter(buildRows())
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MenuEditorActivity)
            this.adapter = this@MenuEditorActivity.adapter
            setPadding(2 * 8.dp, 4.dp, 2 * 8.dp, 64.dp)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(M3.surface)
        }
        recycler = rv
        touchHelper.attachToRecyclerView(rv)
        root.addView(rv, LinearLayout.LayoutParams(MP, 0, 1f))

        setContentView(SwipeBackLayout(this).apply {
            ignoreDisableSetting = true
            setBackgroundColor(M3.surface)
            addView(root, FrameLayout.LayoutParams(MP, MP))
            onSwipeBack = { finish() }
        })
    }

    // ── header ───────────────────────────────────────────────────────────────────

    // Compact fixed top bar — back chevron + title only. The "drag to reorder / below the line is
    // hidden" hint lives in the 菜单自定义 settings card and as a list header row, NOT here: a tall
    // fixed bar eats the small watch screen and hides the rows being dragged.
    private fun header(title: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(14.dp, 8.dp, 14.dp, 6.dp)
        isClickable = true
        setOnClickListener { finish() }
        addView(ImageView(context).apply {
            setImageDrawable(MaterialSymbol(MaterialSymbols.chevron_left, M3.primary))
        }, LinearLayout.LayoutParams(22.dp, 22.dp))
        addView(TextView(context).apply {
            text = title; textSize = 16f; setTextColor(M3.onSurface)
        }, LinearLayout.LayoutParams(WC, WC).apply { marginStart = 4.dp })
    }

    // ── row model ──────────────────────────────────────────────────────────────────

    /** A row is either a configurable item (by catalog key) or the single fixed separator. */
    private sealed class Row {
        class Item(val key: String) : Row()
        object Separator : Row()
    }

    /** Initial rows: visible items, then the separator, then hidden items. */
    private fun buildRows(): MutableList<Row> {
        val (visible, hidden) = config.resolved()
        val rows = ArrayList<Row>(visible.size + hidden.size + 1)
        visible.forEach { rows.add(Row.Item(it)) }
        rows.add(Row.Separator)
        hidden.forEach { rows.add(Row.Item(it)) }
        return rows
    }

    /** Recompute visible/hidden from the separator's position and persist. */
    private fun persist() {
        val rows = adapter.rows
        val sep = rows.indexOfFirst { it is Row.Separator }.let { if (it < 0) rows.size else it }
        val visible = rows.take(sep).filterIsInstance<Row.Item>().map { it.key }
        val hidden = rows.drop(sep + 1).filterIsInstance<Row.Item>().map { it.key }
        config.save(visible, hidden)
    }

    // ── adapter ──────────────────────────────────────────────────────────────────

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = false // drag starts from the handle only
        override fun isItemViewSwipeEnabled() = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            // The separator is fixed in the list; everything else moves up/down (across the separator).
            if (adapter.rows.getOrNull(vh.adapterPosition) is Row.Separator) return 0
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        }

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.adapterPosition
            val to = target.adapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            Collections.swap(adapter.rows, from, to)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            // Drop finished: persist the new order and refresh the shown/hidden dim state.
            persist()
            adapter.notifyDataSetChanged()
        }
    })

    private inner class Adapter(val rows: MutableList<Row>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_ITEM = 0
        private val TYPE_SEP = 1

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) =
            if (rows[position] is Row.Separator) TYPE_SEP else TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_SEP) SepVH(separatorView()) else ItemVH(itemView())

        @SuppressLint("NotifyDataSetChanged")
        override fun onBindViewHolder(vh: RecyclerView.ViewHolder, position: Int) {
            if (vh !is ItemVH) return
            val row = rows[position] as? Row.Item ?: return
            val spec = config.specFor(row.key) ?: return
            val sepPos = rows.indexOfFirst { it is Row.Separator }.let { if (it < 0) rows.size else it }
            val hidden = position > sepPos
            vh.icon.setImageDrawable(MaterialSymbol(spec.symbol, if (hidden) M3.hint else M3.primary))
            vh.label.text = spec.label
            vh.label.setTextColor(if (hidden) M3.hint else M3.onSurface)
            vh.handle.setImageDrawable(
                MaterialSymbol(MaterialSymbols.drag_handle, if (hidden) M3.hint else M3.onSurfaceVariant))
        }

        private inner class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
            val handle: ImageView = v.findViewById(ID_HANDLE)
            val icon: ImageView = v.findViewById(ID_ICON)
            val label: TextView = v.findViewById(ID_LABEL)

            init {
                @Suppress("ClickableViewAccessibility")
                handle.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(this)
                    false
                }
            }
        }

        private inner class SepVH(v: View) : RecyclerView.ViewHolder(v)
    }

    // ── row views (built in code; ids assigned so the VH can find them) ───────────

    private fun itemView(): View = LinearLayout(this).apply {
        layoutParams = RecyclerView.LayoutParams(MP, WC).apply { topMargin = 3.dp; bottomMargin = 3.dp }
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = M3.rounded(M3.surfaceContainer, M3.radiusMd)
        setPadding(14.dp, 12.dp, 16.dp, 12.dp)
        addView(ImageView(context).apply { id = ID_HANDLE }, LinearLayout.LayoutParams(24.dp, 24.dp))
        addView(ImageView(context).apply { id = ID_ICON },
            LinearLayout.LayoutParams(22.dp, 22.dp).apply { marginStart = 14.dp })
        addView(TextView(context).apply { id = ID_LABEL; textSize = 15f },
            LinearLayout.LayoutParams(0, WC, 1f).apply { marginStart = 16.dp })
    }

    @SuppressLint("SetTextI18n")
    private fun separatorView(): View = LinearLayout(this).apply {
        layoutParams = RecyclerView.LayoutParams(MP, WC).apply { topMargin = 12.dp; bottomMargin = 6.dp }
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(6.dp, 4.dp, 6.dp, 4.dp)
        fun line() = View(context).apply { setBackgroundColor(M3.outlineVariant) }
        addView(line(), LinearLayout.LayoutParams(0, 1.dp, 1f))
        addView(TextView(context).apply {
            text = "以下不显示"; textSize = 11f; setTextColor(M3.hint)
            setPadding(10.dp, 0, 10.dp, 0)
        }, LinearLayout.LayoutParams(WC, WC))
        addView(line(), LinearLayout.LayoutParams(0, 1.dp, 1f))
    }

    // Rotary-encoder (crown) scrolling — this standalone Activity needs its own dispatch.
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        recycler?.let { findTarget(it) }?.let { applyRotaryScroll(ev, it) }
        return super.dispatchGenericMotionEvent(ev)
    }

    // No system close transition — the swipe-back slide/fade is our exit animation.
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
