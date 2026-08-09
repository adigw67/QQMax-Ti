package momoi.mod.qqpro.hook.sticker

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import momoi.mod.qqpro.hook.qzone.HChipScroll
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.lib.bitmapDecodeFile
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3CircularProgress
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.leadingSymbol
import momoi.mod.qqpro.util.Utils

/**
 * Material 3 store-sticker (商城表情) picker. Top: a horizontal scroll of pack chips (the user's owned
 * packs, synced from the phone — see [StickerStore]). Below: a 2-wide grid of that pack's stickers.
 * Tapping a sticker sends it to the current chat and dismisses. All data via the watch-proven kernel
 * emoticon APIs; no store/mall browse (no API for that on this build).
 */
class StickerPickerFragment : MyDialogFragment() {

    private var chipRow: LinearLayout? = null
    private var searchChip: TextView? = null
    private var searchInput: EditText? = null
    private var searchMode = false
    private lateinit var grid: RecyclerView
    private var emptyLabel: TextView? = null

    private var packs: List<StickerStore.Pack> = emptyList()
    private var selectedEpId: Int = -1
    /** Stickers of the currently selected pack (what's shown when no search query is active). */
    private val packStickers = ArrayList<StickerStore.Sticker>()
    /** The grid's live display list — either [packStickers] or the cross-pack search matches. */
    private val stickers = ArrayList<StickerStore.Sticker>()
    /** Lazily aggregated stickers across every pack, cached for the "搜索全部" search. */
    private var allStickers: List<StickerStore.Sticker>? = null
    private var loadingAll = false
    private var loadingPack = false
    private var searchQuery = ""
    private var cell = 100.dp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val edge = if (Utils.isRoundScreen) 16.dp else 8.dp
        cell = ((ctx.resources.displayMetrics.widthPixels - 2 * edge - 8.dp) / 2).coerceAtLeast(72.dp)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(edge, 8.dp, edge, 6.dp)
        }

        root.addView(TextView(ctx).apply {
            text = "表情"
            setTextColor(M3.onSurface)
            textSize = 15f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Search field for the dedicated "搜索" category chip. Hidden until that chip is selected; then
        // it filters across ALL packs (aggregated lazily via [StickerStore.loadAllStickers]).
        val search = EditText(ctx).apply {
            hint = "搜索全部表情"
            setHintTextColor(M3.hint)
            setTextColor(M3.onSurface)
            textSize = 13f
            setSingleLine()
            setPadding(10.dp, 6.dp, 10.dp, 6.dp)
            compoundDrawablePadding = 7.dp
            val ic = MaterialSymbol(MaterialSymbols.search, M3.onSurfaceVariant).apply { setBounds(0, 0, 16.dp, 16.dp) }
            setCompoundDrawables(ic, null, null, null)
            background = GradientDrawable().apply {
                setColor(M3.surfaceContainer)
                cornerRadius = 10.dp.toFloat()
            }
            visibility = View.GONE
            doAfterTextChanged { onSearchChanged(text?.toString()?.trim().orEmpty()) }
        }
        searchInput = search

        // Pack chips (horizontal scroll). HChipScroll implements HorizontalDragWidget so the
        // SwipeBackLayout does NOT grab horizontal drags here — the chips scroll, and swipe-back
        // still works on the rest of the fragment (title / grid).
        val scroll = HChipScroll(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp, 0, 8.dp)
        }
        chipRow = row
        scroll.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The search field lives directly under the chip row — revealed only in the 搜索 category.
        root.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dp
        })

        // Sticker grid (2-wide).
        grid = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, 2)
            clipToPadding = false
            setPadding(0, 4.dp, 0, 0)
            adapter = StickerAdapter()
        }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyLabel = TextView(ctx).apply {
            text = "加载中…"
            setTextColor(M3.onSurfaceVariant)
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(emptyLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        loadPacks()
        return swipeBackWrap(root)
    }

    private fun loadPacks() {
        StickerStore.loadPacks { list ->
            if (!isAdded) return@loadPacks
            packs = list
            buildChips()
            if (list.isNotEmpty()) selectPack(list.first().epId)
            else updateEmpty()
        }
    }

    private fun buildChips() {
        val ctx = context ?: return
        val row = chipRow ?: return
        row.removeAllViews()
        // Dedicated "搜索" category chip (always first) — enters cross-pack search mode when tapped.
        val sChip = TextView(ctx).apply {
            text = "搜索"
            textSize = 11f
            isSingleLine = true
            gravity = Gravity.CENTER
            compoundDrawablePadding = 5.dp
            setPadding(12.dp, 6.dp, 12.dp, 6.dp)
            setOnClickListener { enterSearch() }
        }
        searchChip = sChip
        row.addView(sChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = 8.dp
        })
        packs.forEach { pack ->
            val chip = TextView(ctx).apply {
                text = pack.name
                textSize = 11f
                isSingleLine = true
                gravity = Gravity.CENTER
                compoundDrawablePadding = 5.dp
                setPadding(12.dp, 6.dp, 12.dp, 6.dp)
                setOnClickListener { selectPack(pack.epId) }
            }
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 8.dp
            })
            // A representative sticker as the chip's leading icon (the pack has no cover image).
            StickerStore.packIcon(pack.epId) { file ->
                if (!isAdded || file == null || !file.exists()) return@packIcon
                runCatching {
                    val bmp = android.graphics.BitmapFactory.decodeFile(file.path) ?: return@packIcon
                    val d = android.graphics.drawable.BitmapDrawable(resources, bmp).apply { setBounds(0, 0, 18.dp, 18.dp) }
                    chip.setCompoundDrawables(d, null, null, null)
                }.onFailure { Utils.log("chip icon decode err: $it") }
            }
        }
        styleChips()
    }

    /** Selected chip = filled accent; others = tonal. M3 colour tokens, recomputed on every selection. */
    private fun styleChips() {
        val row = chipRow ?: return
        // The search chip (index 0) is selected in search mode; its glyph recolours with selection.
        searchChip?.let { chip ->
            val fg = if (searchMode) M3.onPrimary else M3.onSurfaceVariant
            chip.background = GradientDrawable().apply {
                cornerRadius = 9999f
                setColor(if (searchMode) M3.primary else M3.surfaceContainer)
            }
            chip.setTextColor(fg)
            chip.leadingSymbol(MaterialSymbols.search, fg, sizeDp = 14, gap = 5)
        }
        // Pack chips follow the search chip, so they live at index i+1.
        packs.forEachIndexed { i, pack ->
            val chip = row.getChildAt(i + 1) as? TextView ?: return@forEachIndexed
            val selected = !searchMode && pack.epId == selectedEpId
            chip.background = GradientDrawable().apply {
                cornerRadius = 9999f
                setColor(if (selected) M3.primary else M3.surfaceContainer)
            }
            chip.setTextColor(if (selected) M3.onPrimary else M3.onSurfaceVariant)
        }
    }

    private fun selectPack(epId: Int) {
        searchInput?.visibility = View.GONE
        searchMode = false
        if (epId == selectedEpId && packStickers.isNotEmpty()) {
            // Same pack (e.g. returning from the 搜索 category) — just re-render, no reload.
            styleChips(); render(); return
        }
        selectedEpId = epId
        styleChips()
        packStickers.clear()
        loadingPack = true
        render()
        StickerStore.loadStickers(epId) { list ->
            if (!isAdded || searchMode || epId != selectedEpId) return@loadStickers
            loadingPack = false
            packStickers.clear()
            packStickers.addAll(list)
            render()
        }
    }

    /** Enter the dedicated 搜索 category: reveal the field, filter across all packs. */
    private fun enterSearch() {
        if (searchMode) return
        searchMode = true
        styleChips()
        searchInput?.let { it.visibility = View.VISIBLE; it.requestFocus() }
        // If there's already a query, make sure the aggregate is loaded; otherwise just prompt.
        onSearchChanged(searchQuery)
        render()
    }

    /** React to the search field: filter across all packs (aggregate loaded lazily). */
    private fun onSearchChanged(q: String) {
        searchQuery = q
        if (!searchMode) return
        if (q.isNotEmpty() && allStickers == null && !loadingAll) {
            loadingAll = true
            render() // paints "搜索中…" while aggregating
            StickerStore.loadAllStickers(packs) { all ->
                if (!isAdded) return@loadAllStickers
                loadingAll = false
                allStickers = all
                render()
            }
            return
        }
        render()
    }

    /** Recompute the grid's display list from the current mode (pack view vs. cross-pack search). */
    private fun render() {
        val q = searchQuery
        stickers.clear()
        if (!searchMode) {
            stickers.addAll(packStickers)
        } else if (q.isNotEmpty()) {
            allStickers?.let { all -> stickers.addAll(all.filter { it.name.contains(q, ignoreCase = true) }) }
        }
        grid.adapter?.notifyDataSetChanged()
        updateEmpty()
    }

    private fun updateEmpty() {
        val empty = stickers.isEmpty()
        emptyLabel?.visibility = if (empty) View.VISIBLE else View.GONE
        if (!empty) return
        emptyLabel?.text = when {
            searchMode && loadingAll -> "搜索中…"
            searchMode && searchQuery.isEmpty() -> "输入关键字搜索全部表情"
            searchMode -> "没有匹配的表情"
            loadingPack -> "加载中…"
            packs.isEmpty() -> "没有可用的表情包（在手机上添加后会同步到手表）"
            else -> "这个表情包暂时没有内容"
        }
    }

    private fun onStickerTap(s: StickerStore.Sticker) {
        val ctx = context ?: return
        Utils.toast(ctx, "发送中…")
        StickerStore.send(s) { ok ->
            if (ok) dismiss() else context?.let { Utils.toast(it, "发送失败") }
        }
    }

    private inner class StickerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            // A column cell: square image card on top, sticker name label below.
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            // Square rounded surface container holding the centred sticker image.
            val card = FrameLayout(ctx).apply {
                background = GradientDrawable().apply { cornerRadius = M3.radiusLg.toFloat(); setColor(M3.surfaceContainer) }
            }
            val pad = 8.dp
            val img = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(cell - pad * 2, cell - pad * 2, Gravity.CENTER)
            }
            val spin = M3CircularProgress(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(24.dp, 24.dp, Gravity.CENTER)
            }
            card.addView(img); card.addView(spin)
            col.addView(card, LinearLayout.LayoutParams(cell, cell))
            val name = TextView(ctx).apply {
                textSize = 11f
                setTextColor(M3.onSurfaceVariant)
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(4.dp, 3.dp, 4.dp, 0)
            }
            col.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return object : RecyclerView.ViewHolder(col) {}
        }

        override fun getItemCount() = stickers.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val s = stickers[position]
            val col = holder.itemView as LinearLayout
            val card = col.getChildAt(0) as FrameLayout
            val name = col.getChildAt(1) as TextView
            val img = card.getChildAt(0) as ImageView
            val spin = card.getChildAt(1)
            // Even outer spacing via item margins.
            (col.layoutParams as? RecyclerView.LayoutParams)?.let {
                val m = 4.dp
                it.setMargins(m, m, m, m)
                it.width = cell
            }
            // Keep the label a single reserved line (empty string still measures one line) so grid rows stay aligned.
            name.text = s.name
            img.setImageDrawable(null)
            spin.visibility = View.VISIBLE
            col.setOnClickListener { onStickerTap(s) }
            // Tag the view with the sticker so a recycled async result for an old sticker is ignored.
            col.tag = s.eId
            StickerStore.thumbFile(s) { file ->
                if (col.tag != s.eId) return@thumbFile // recycled
                spin.visibility = View.GONE
                if (file != null && file.exists()) runCatching { img.bitmapDecodeFile(file) }
                    .onFailure { Utils.log("sticker thumb decode err: $it") }
            }
        }
    }
}
