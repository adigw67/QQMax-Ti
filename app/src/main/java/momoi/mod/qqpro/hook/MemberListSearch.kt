package momoi.mod.qqpro.hook

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.tencent.qqnt.kernel.nativeinterface.MemberInfo
import com.tencent.qqnt.watch.troop.ui.member.mvi.GroupMemberListIntent
import com.tencent.qqnt.watch.troop.ui.member.ui.GroupMemberFragment
import com.tencent.qqnt.watch.troop.ui.member.ui.item.GroupMemberBaseItem
import com.tencent.qqnt.watch.troop.ui.member.ui.item.GroupMemberItem
import com.tencent.qqnt.watch.troop.ui.member.ui.rv.GroupMemberAdapter
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.onChildAttached
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Progress
import momoi.mod.qqpro.lib.material.MaterialIconButton
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.leadingSymbol
import momoi.mod.qqpro.util.Utils
import java.lang.ref.WeakReference

/**
 * 群成员列表改造 (watch-friendly): replaces the native group member list's in-list "邀请成员" row with a
 * compact top bar holding two small white-outline icons (drawn programmatically, no app resources) —
 * 加 (add/invite) and 搜索 (search). The member list scrolls below. Tapping 搜索 collapses both icons
 * into a single inline EditText on the left with an ✕ on the right; the ✕ exits search.
 *
 * We do NOT subclass the adapter (overriding its abstract VH methods via Mixin crashes with
 * AbstractMethodError). Instead an [RecyclerView.AdapterDataObserver] snapshots the native list, we
 * strip the invite item, and re-submit a members-only (optionally filtered) copy. The add icon fires
 * the same [GroupMemberListIntent.InvitedToGroup] intent the native invite row used.
 */
object MemberListSearch {
    private const val SEARCH_TAG = "qqpro_member_search"

    // Members only (invite row stripped); this is the source we filter from.
    private var fullList: List<GroupMemberItem> = emptyList()
    private var lastSubmittedByUs: List<GroupMemberBaseItem>? = null
    private var query = ""
    private var adapterRef: WeakReference<GroupMemberAdapter>? = null
    private var contentRef: WeakReference<ViewGroup>? = null

    /** Wraps [root] with the top bar and returns the new root (or null to keep [root]). */
    fun install(fragment: GroupMemberFragment, root: View): View? {
        if (root.tag == SEARCH_TAG) return null
        val rv = findRecyclerView(root) ?: return null
        val adapter = rv.adapter as? GroupMemberAdapter ?: return null
        val ctx = root.context
        // The list has a fixed 18dp top margin (round-edge inset); our bar now provides that
        // spacing, so drop it to avoid a doubled gap. topMargin lives on MarginLayoutParams, so
        // it's safe despite the obfuscated ConstraintLayout fields.
        (rv.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 0

        // Materialize each member row: M3 surface-container card + onSurface name text (the native
        // rows are grey with white text, which clashes with the themed light page in light mode).
        if (Settings.useM3Settings.value) {
            val titleId = ctx.resources.getIdentifier("title", "id", ctx.packageName)
            rv.onChildAttached { row ->
                row.background = M3.ripple(M3.rounded(M3.surfaceContainer, M3.radiusLg))
                (if (titleId != 0) row.findViewById<View>(titleId) else null)?.let { tv ->
                    runCatching {
                        tv.javaClass.getMethod("setTextColor", Int::class.javaPrimitiveType).invoke(tv, M3.onSurface)
                    }
                }
            }
        }

        // Fresh state for this screen.
        fullList = emptyList()
        lastSubmittedByUs = null
        query = ""
        adapterRef = WeakReference(adapter)
        val fragmentRef = WeakReference(fragment)

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            tag = SEARCH_TAG
            // Stacked above the list; paddingTop provides the round-edge clearance.
            setPadding(10.dp, 10.dp, 10.dp, 6.dp)
        }

        val addIcon = icon(ctx, MaterialSymbols.person_add)
        val searchIcon = icon(ctx, MaterialSymbols.search)
        val et = EditText(ctx).apply {
            visibility = View.GONE
            hint = "搜索成员"
            setHintTextColor(M3.hint)
            setTextColor(M3.onSurface)
            textSize = 13f
            setSingleLine()
            setPadding(10.dp, 5.dp, 10.dp, 5.dp)
            background = GradientDrawable().apply {
                setColor(M3.surfaceContainer)
                cornerRadius = 10.dp.toFloat()
            }
        }
        val exit = TextView(ctx).apply {
            visibility = View.GONE
            leadingSymbol(MaterialSymbols.close, M3.onSurfaceVariant, sizeDp = 15, gap = 0)
            setPadding(12.dp, 4.dp, 6.dp, 4.dp)
        }

        // icon() already gives each a fixed 30dp LayoutParams; just add a gap before the search icon.
        (searchIcon.layoutParams as LinearLayout.LayoutParams).marginStart = 12.dp
        bar.addView(addIcon)
        bar.addView(searchIcon)
        bar.addView(et, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(exit)

        fun enterSearch() {
            addIcon.visibility = View.GONE
            searchIcon.visibility = View.GONE
            et.visibility = View.VISIBLE
            exit.visibility = View.VISIBLE
            et.requestFocus()
            et.post {
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        fun exitSearch() {
            et.setText("")
            query = ""
            apply()
            et.visibility = View.GONE
            exit.visibility = View.GONE
            addIcon.visibility = View.VISIBLE
            searchIcon.visibility = View.VISIBLE
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(et.windowToken, 0)
        }

        addIcon.setOnClickListener {
            runCatching {
                val frag = fragmentRef.get() ?: return@runCatching
                val uids = fullList.mapNotNull { infoOf(it)?.uid }
                frag.b0(GroupMemberListIntent.InvitedToGroup(uids))
            }.onFailure { Utils.log("MemberSearch: invite failed: $it") }
        }
        searchIcon.setOnClickListener { enterSearch() }
        exit.setOnClickListener { exitSearch() }
        et.doAfterTextChanged {
            query = et.text?.toString()?.trim().orEmpty()
            apply()
        }

        // Stack the bar above the list (no overlap; the cleared RV margin keeps total height tight).
        // Overwrite the native (black) page background with M3.surface on every layer — the wrap, the
        // native root and the list — so the real dark background never shows through, including while
        // the member list is still loading (otherwise it's a black screen behind the spinner).
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = SEARCH_TAG
            setBackgroundColor(M3.surface)
        }
        wrap.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP))
        // Host the list in a FrameLayout so the M3 loading spinner can overlay it (the spinner
        // can't be a child of the RecyclerView itself, nor centered in the vertical wrap).
        val listHost = android.widget.FrameLayout(ctx).apply { setBackgroundColor(M3.surface) }
        root.setBackgroundColor(M3.surface)
        rv.setBackgroundColor(M3.surface)
        listHost.addView(root, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wrap.addView(listHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // M3 loading spinner over the list area until the first members arrive (onAdapterChanged).
        contentRef = WeakReference(listHost)
        if (fullList.isEmpty()) M3Progress.show(listHost, sizeDp = 36)

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = onAdapterChanged()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onAdapterChanged()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = onAdapterChanged()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onAdapterChanged()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = onAdapterChanged()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = onAdapterChanged()
        })
        onAdapterChanged()
        Utils.log("MemberSearch: top bar installed")
        return wrap
    }

    /** Native list changed — snapshot members (drop the invite row) unless it's our own echo. */
    private fun onAdapterChanged() {
        val adapter = adapterRef?.get() ?: return
        val cur = runCatching { adapter.currentList }.getOrNull() ?: return
        if (cur == lastSubmittedByUs) return
        fullList = cur.filterIsInstance<GroupMemberItem>()
        if (fullList.isNotEmpty()) contentRef?.get()?.let { M3Progress.hide(it) }
        apply()
    }

    private fun apply() {
        val adapter = adapterRef?.get() ?: return
        val q = query
        val result: List<GroupMemberBaseItem> =
            if (q.isEmpty()) fullList
            else fullList.filter { nameOf(it).contains(q, ignoreCase = true) }
        lastSubmittedByUs = result
        runCatching { adapter.submitList(result) }.onFailure { Utils.log("MemberSearch apply: $it") }
    }

    // Accent-tinted icon in an M3 tonal circle, matching the other top bars (ContactTopBar/QZoneTopBar
    // use MaterialIconButton(30dp) + setTonalContainer()).
    private fun icon(ctx: Context, path: String): MaterialIconButton = MaterialIconButton(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(30.dp, 30.dp)
        setIcon(MaterialSymbol(path, M3.ACCENT))
        setTonalContainer()
    }

    private fun infoOf(item: GroupMemberItem): MemberInfo? = runCatching {
        // The MemberInfo field is obfuscated to `a` (and collides with method a()), so reflect it.
        item.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(item) as? MemberInfo
    }.getOrNull()

    /** Best display name for a member; falls back to the item's own sort key [GroupMemberItem.b]. */
    private fun nameOf(item: GroupMemberItem): String {
        val info = infoOf(item)
        val name = info?.let {
            sequenceOf(it.remark, it.cardName, it.nick).firstOrNull { s -> !s.isNullOrBlank() }
        }
        return name ?: runCatching { item.b() }.getOrNull().orEmpty()
    }

    private fun findRecyclerView(v: View): RecyclerView? {
        if (v is RecyclerView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findRecyclerView(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}

@Mixin
class GroupMemberListSearch : GroupMemberFragment() {
    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = super.Y(inflater, container, savedInstanceState)
        return runCatching { MemberListSearch.install(this, root) }
            .onFailure { Utils.log("MemberSearch: install failed: $it") }
            .getOrNull() ?: root
    }
}
