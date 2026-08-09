package momoi.mod.qqpro.lib

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.findAll
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.leadingSymbol
import momoi.mod.qqpro.util.Utils

/**
 * Reusable, watch-friendly search bar for any [RecyclerView] backed by a [ListAdapter]. Installs a
 * compact, always-visible top bar: a white-outline 搜索 icon at the start, then an inline EditText
 * that fills the rest and live-filters the list by [nameOf]. Items for which [isPinned] returns true
 * (e.g. "从群聊中选择" header rows) are always kept at the top regardless of the query.
 *
 * Three distinct fragments — all using the `fragment_friend_select` layout (RecyclerView id
 * `friend_list`) — each get their own bar via a thin @Mixin onViewCreated hook:
 *   • [com.tencent.qqnt.watch.fs.FriendSelectFragment]      → 转发 / 分享到 / 创建群聊 / 邀请 (friend pick)
 *   • [com.tencent.qqnt.watch.fs.SelectFromGroupFragment]   → 选择群聊 (pick a group)
 *   • [com.tencent.qqnt.watch.fs.GroupMemberSelectFragment] → 从群聊中选择成员 (pick members from a group)
 * They differ only in adapter item type, so each hook passes its own [nameOf]. The group member list
 * (群成员管理) uses its own bar in [momoi.mod.qqpro.hook.MemberListSearch] — it needs an 加 invite icon
 * too, so it isn't shared here.
 *
 * We never subclass the adapter (overriding its abstract VH methods via Mixin crashes). Instead we
 * observe it, snapshot the host's submitted list, and re-submit a filtered copy — the host's own
 * selection state lives on the items / in its own set, so filtering the displayed list leaves it
 * intact. Returns the new wrapping root, or null to keep [root] unchanged.
 */
/**
 * Materialize the selector's bottom confirm WatchButton (a MaterialButton) — the native one is a
 * blue pill with a colourful non-material icon (e.g. the green 转发 glyph). Recolor to the M3 accent
 * pill with a Material Symbol icon + onPrimary text. The icon is picked from the button's own label.
 */
fun styleConfirmButton(root: View) {
    runCatching {
        val id = root.resources.getIdentifier("confirm", "id", root.context.packageName)
        val btn = (if (id != 0) root.findViewById<View>(id) else null) ?: return
        (btn as? TextView)?.setTextColor(M3.onPrimary)
        btn.background = GradientDrawable().apply { setColor(M3.primary); cornerRadius = M3.radiusPill }
        val label = (btn as? TextView)?.text?.toString().orEmpty()
        val sym = when {
            label.contains("转发") -> MaterialSymbols.forward
            label.contains("发送") || label.contains("分享") -> MaterialSymbols.send
            label.contains("创建") -> MaterialSymbols.group
            label.contains("邀请") -> MaterialSymbols.person_add
            else -> MaterialSymbols.send
        }
        // setIcon / setIconTint live on MaterialButton (not on our compile classpath) — via reflection.
        runCatching {
            btn.javaClass.getMethod("setIcon", android.graphics.drawable.Drawable::class.java)
                .invoke(btn, MaterialSymbol(sym, M3.onPrimary))
            btn.javaClass.getMethod("setIconTint", android.content.res.ColorStateList::class.java)
                .invoke(btn, android.content.res.ColorStateList.valueOf(M3.onPrimary))
        }
    }
}

/**
 * Materialize the friend selector's pinned entry rows (从群聊中选择 / 选择群聊), whose avatar shows a
 * colourful icon_group_member. Replace it with a Material group symbol (accent) on an M3 tonal circle.
 */
fun styleSelectorHeaderIcons(root: View) {
    runCatching {
        val res = root.resources
        val pkg = root.context.packageName
        val titleId = res.getIdentifier("title", "id", pkg)
        val avatarId = res.getIdentifier("avatar", "id", pkg)
        val rv = (root as? ViewGroup)?.findAll { it is RecyclerView } as? RecyclerView ?: return
        rv.onChildAttached { row ->
            // The title is a com.tencent.widget.SingleLineTextView (NOT a TextView) — read via getText().
            val titleView = if (titleId != 0) row.findViewById<View>(titleId) else null
            val title = runCatching { titleView?.javaClass?.getMethod("getText")?.invoke(titleView) as? CharSequence }
                .getOrNull()?.toString().orEmpty()
            if (title != "从群聊中选择" && title != "选择群聊") return@onChildAttached
            // The avatar is a com.tencent.qqnt.avatar.WatchAvatarView (extends View, NOT ImageView/ViewGroup),
            // so there is no ImageView to retarget. It exposes a public setImageDrawable(Drawable) that
            // rasterises the drawable into a circular RGB_565 bitmap shader (no alpha, self-masks a circle).
            // Hand it a square, solid-filled tonal drawable with a centred group glyph so the rasterised
            // circle reads as an M3 tonal avatar — a bare glyph would land on a black circle.
            val avatar = (if (avatarId != 0) row.findViewById<View>(avatarId) else null) ?: return@onChildAttached
            val m = avatar.javaClass.methods.firstOrNull {
                it.name == "setImageDrawable" && it.parameterTypes.size == 1
            } ?: return@onChildAttached
            runCatching { m.invoke(avatar, tonalGroupAvatar()) }
                .onFailure { Utils.log("styleSelectorHeaderIcons: setImageDrawable failed: $it") }
        }
    }.onFailure { Utils.log("styleSelectorHeaderIcons: $it") }
}

/** A square M3 tonal-filled drawable with a centred group glyph, sized for [WatchAvatarView] rasterisation. */
private fun tonalGroupAvatar(): android.graphics.drawable.Drawable = object : android.graphics.drawable.Drawable() {
    private val size = 48.dp
    private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        // OPAQUE tonal: this is rasterized into a WatchAvatarView with no live background, so a
        // translucent M3.TONAL would composite over black and look dark navy in light mode.
        color = M3.tonalSolid; style = android.graphics.Paint.Style.FILL
    }
    private val glyph = MaterialSymbol(MaterialSymbols.group, M3.primary, insetFraction = 0.26f)
    override fun onBoundsChange(b: android.graphics.Rect) { glyph.bounds = b }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun draw(canvas: android.graphics.Canvas) {
        canvas.drawRect(bounds, bg)
        glyph.draw(canvas)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}

object ListSearchBar {
    private const val TAG = "qqpro_list_search"

    /**
     * Install the bar into [view] — the fragment's REAL attached view (call from onViewCreated, NOT
     * onCreateView/Y: WatchFragment caches its view and skips Y on reuse, so a Y-return wrapper is
     * unreliable).
     *
     * We reparent the RecyclerView in place: wrap = [bar, rv] takes the rv's old slot and inherits the
     * rv's original LayoutParams object, so ConstraintLayout constraints carry over untouched (no
     * constraint-field writes — those throw NoSuchFieldError under R8).
     *
     * The list is resolved by its layout id [rvIdName] (not "first RecyclerView found"): the selector
     * hosts its list inside a NESTED nav_host_fragment and can momentarily expose a placeholder
     * RecyclerView, and wrapping that (then short-circuiting) is what made 转发 / group-select flaky.
     * The list also often isn't attached/bound at onViewCreated time, so we retry on the view's handler
     * until the id-matched RecyclerView has a [ListAdapter], then wrap exactly once (idempotent via the
     * wrap's [TAG]).
     */
    fun install(
        view: View,
        hint: String,
        nameOf: (Any) -> String,
        isPinned: (Any) -> Boolean = { false },
        rvIdName: String = "friend_list",
        attempt: Int = 0,
    ) {
        // Overwrite the native (black) page background immediately, before the list adapter has bound
        // (tryInstall retries until it does). Otherwise the real dark background shows through during
        // loading. Cheap + idempotent, so safe to set on every retry.
        if (Settings.useM3Settings.value) view.setBackgroundColor(M3.surface)
        if (tryInstall(view, hint, nameOf, isPinned, rvIdName)) return
        if (attempt >= 20) { Utils.log("ListSearchBar($hint): gave up after $attempt attempts (no list-adapter RV)"); return }
        view.postDelayed({ install(view, hint, nameOf, isPinned, rvIdName, attempt + 1) }, 50)
    }

    /** One attempt. Returns true when done (wrapped now, or already wrapped); false to retry later. */
    private fun tryInstall(
        view: View,
        hint: String,
        nameOf: (Any) -> String,
        isPinned: (Any) -> Boolean,
        rvIdName: String,
    ): Boolean {
        // Resolve the REAL list by its layout id — a nested nav_host_fragment can momentarily hold a
        // placeholder RecyclerView, and wrapping that (then short-circuiting) is exactly why 转发 broke.
        val rv = findListById(view, rvIdName) ?: return false
        val curParent = rv.parent as? ViewGroup ?: return false
        if (curParent.tag == TAG) return true  // this specific list already wrapped
        @Suppress("UNCHECKED_CAST")
        val adapter = rv.adapter as? ListAdapter<Any, RecyclerView.ViewHolder> ?: return false  // not bound yet → retry
        val ctx = view.context

        // Material-ize the selector: M3.surface page background, M3 surface-container row cards, M3
        // title text and the M3 checkbox graphic. Installed once here (before this list is tagged).
        if (Settings.useM3Settings.value) {
            // Paint the list's whole ancestor chain (up to [view]) with M3.surface — the black strip
            // above the search bar comes from a native dark container between the root and the list,
            // which a single view.setBackgroundColor doesn't cover.
            var p: View? = curParent
            while (p != null) { p.setBackgroundColor(M3.surface); if (p === view) break; p = p.parent as? View }
            view.setBackgroundColor(M3.surface)
            rv.setBackgroundColor(M3.surface)
            val titleId = view.resources.getIdentifier("title", "id", view.context.packageName)
            rv.onChildAttached { row ->
                row.background = M3.ripple(M3.rounded(M3.surfaceContainer, M3.radiusLg))
                (if (titleId != 0) row.findViewById<View>(titleId) else null)?.let { tv ->
                    runCatching {
                        tv.javaClass.getMethod("setTextColor", Int::class.javaPrimitiveType).invoke(tv, M3.onSurface)
                    }
                }
                // The checkboxes themselves are materialized app-wide by the QUICheckBox @Mixin.
            }
        }

        // Per-install state held in closures (no shared object fields → multiple instances are safe).
        var fullList: List<Any> = runCatching { adapter.currentList.toList() }.getOrDefault(emptyList())
        var lastSubmittedByUs: List<Any>? = null
        var query = ""

        fun apply() {
            val q = query
            val result: List<Any> = if (q.isEmpty()) {
                fullList
            } else {
                val pinned = fullList.filter(isPinned)
                val matched = fullList.filter { !isPinned(it) && nameOf(it).contains(q, ignoreCase = true) }
                pinned + matched
            }
            lastSubmittedByUs = result
            runCatching { adapter.submitList(result) }.onFailure { Utils.log("ListSearchBar apply: $it") }
        }

        // Always-visible bar: [search pill (icon + EditText)] [✕ clear]. The icon is a left compound
        // drawable inside the pill so the whole field reads as one search box.
        val et = EditText(ctx).apply {
            this.hint = hint
            setHintTextColor(M3.hint)
            setTextColor(M3.onSurface)
            textSize = 13f
            setSingleLine()
            setPadding(10.dp, 5.dp, 10.dp, 5.dp)
            compoundDrawablePadding = 7.dp
            val ic = MaterialSymbol(MaterialSymbols.search, M3.onSurface).apply { setBounds(0, 0, 16.dp, 16.dp) }
            setCompoundDrawables(ic, null, null, null)
            background = GradientDrawable().apply {
                setColor(M3.surfaceContainer)
                cornerRadius = 10.dp.toFloat()
            }
        }
        val clear = TextView(ctx).apply {
            leadingSymbol(MaterialSymbols.close, M3.onSurfaceVariant, sizeDp = 14, gap = 0)
            setPadding(10.dp, 4.dp, 4.dp, 4.dp)
            visibility = View.GONE
            setOnClickListener { et.setText("") }
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 10.dp, 12.dp, 6.dp)
            addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(clear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        et.doAfterTextChanged {
            query = et.text?.toString()?.trim().orEmpty()
            clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            apply()
        }

        // Reparent rv in place: wrap inherits rv's slot + LayoutParams; rv goes below the bar.
        val index = curParent.indexOfChild(rv)
        val rvLp = rv.layoutParams
        curParent.removeViewAt(index)
        (rv.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 0
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG
            if (Settings.useM3Settings.value) setBackgroundColor(M3.surface)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        curParent.addView(wrap, index, rvLp)
        Utils.log("ListSearchBar($hint): reparented rv under ${curParent.javaClass.simpleName}, rvLp=${rvLp.javaClass.simpleName}, wrap.children=${wrap.childCount}")

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            fun changed() {
                val cur = runCatching { adapter.currentList.toList() }.getOrNull() ?: return
                if (cur == lastSubmittedByUs) return  // our own echo — ignore
                fullList = cur
                apply()
            }
            override fun onChanged() = changed()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = changed()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = changed()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = changed()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = changed()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = changed()
        })
        Utils.log("ListSearchBar: installed (hint=$hint)")
        return true
    }

    /** The RecyclerView with layout id [idName] (e.g. friend_list) under [root], or null. */
    private fun findListById(root: View, idName: String): RecyclerView? {
        val id = root.resources.getIdentifier(idName, "id", root.context.packageName)
        if (id == 0) return null
        return root.findViewById(id)
    }
}
