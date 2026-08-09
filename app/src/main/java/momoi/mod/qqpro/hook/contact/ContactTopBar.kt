package momoi.mod.qqpro.hook.contact

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.tencent.qqnt.watch.contact.ui.item.ContactBaseItem
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.AddPersonIcon
import momoi.mod.qqpro.lib.material.BackArrowIcon
import momoi.mod.qqpro.lib.material.FriendNotifyIcon
import momoi.mod.qqpro.lib.material.GroupNotifyIcon
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialIconButton
import momoi.mod.qqpro.lib.material.SearchIcon
import momoi.mod.qqpro.util.Utils

/**
 * Material-style top action bar for the contacts page (2nd main page) — see [ContactListFragmentHook].
 *
 * The stock list begins with three tappable rows (加好友/群聊, 好友通知, 群通知). This moves those into a
 * compact bar of circular tonal icon buttons ([MaterialIconButton] from [momoi.mod.qqpro.lib.material])
 * pinned above the list, and adds a 搜索 button that reveals an inline filter field. The list below
 * then holds only the 好友 / 群聊 sections.
 *
 * Listeners (onClick / TextWatcher) are created HERE, in a plain helper — not inside the @Mixin
 * fragment body, where anonymous classes crash with IllegalAccessError. The fragment exposes plain
 * public methods ([ContactListFragmentHook.topAddFriend] etc.) that this bar invokes.
 *
 * Single submit path: the fragment feeds us the rebuilt (action-row-free) list via [updateList]; we
 * own the filtered [submit] so a live state change and a search keystroke never fight over the
 * adapter. State is held in this object (one contacts page exists at a time) because a @Mixin class
 * may not declare fields with initializers.
 */
object ContactTopBar {
    private var adapter: Any? = null
    private var fullList: List<ContactBaseItem> = emptyList()
    private var query = ""

    private var friendBtn: MaterialIconButton? = null
    private var groupBtn: MaterialIconButton? = null
    private var topContainer: LinearLayout? = null   // overlay holding the action + search rows
    private var actionRow: View? = null
    private var searchRow: View? = null
    private var searchField: EditText? = null
    private var barVisible = true                     // drives hide-on-scroll (flag, not view state)

    /** Feed the latest rebuilt list (sections + friends + groups, no action rows) and notify counts. */
    fun updateList(adapter: Any, list: List<ContactBaseItem>, friendCount: Int, groupCount: Int) {
        this.adapter = adapter
        fullList = list
        friendBtn?.setBadgeCount(friendCount)
        groupBtn?.setBadgeCount(groupCount)
        submit()
    }

    /** Apply the current query to [fullList] and push it to the adapter (the only submit path). */
    private fun submit() {
        val a = adapter ?: return
        val result: List<ContactBaseItem> = if (query.isEmpty()) fullList else filter(query)
        runCatching {
            a.javaClass.getMethod("submitList", List::class.java).invoke(a, result)
        }.onFailure { Utils.log("ContactTopBar submit: $it") }
    }

    /**
     * Keep the 好友/群聊 section headers, with only their matching contacts under each. A header is
     * dropped only if its whole section has no match, so the structure stays readable while filtering.
     */
    private fun filter(q: String): List<ContactBaseItem> {
        val out = ArrayList<ContactBaseItem>()
        var pendingHeader: SectionHeaderItem? = null
        var matchedInSection = false
        for (item in fullList) {
            if (item is SectionHeaderItem) {
                pendingHeader = item
                matchedInSection = false
            } else if (item.getTitle().contains(q, ignoreCase = true)) {
                if (pendingHeader != null && !matchedInSection) {
                    out.add(pendingHeader)      // first match in this section → emit its header
                    matchedInSection = true
                }
                out.add(item)
            }
        }
        return out
    }

    /**
     * Build a wrapper view [bar-overlay, rv] to be RETURNED from the fragment's onCreateView as its
     * root view — do NOT reparent [rv] after the fact. ContactListFragment.Y() returns a bare
     * RecyclerView, and ViewPager2's FragmentStateAdapter re-adds `fragment.getView()` to its page
     * container on every rebind; a post-hoc wrap gets orphaned (the bar vanishes on page switch).
     * Returning the wrapper AS the fragment view makes the adapter manage it wholesale.
     *
     * [rv] must be freshly created and parentless (straight from super.Y). The bar is an OVERLAY over
     * the list (FrameLayout): hiding it moves only its translationY, so the list never re-lays-out
     * (no scroll-feedback flicker). The list gets a fixed top inset so its first item sits below the
     * bar — pure layout, NO scroll-position logic (the single-observer path keeps it anchored).
     */
    fun wrap(host: ContactListFragmentHook, rv: RecyclerView): View {
        val ctx = rv.context
        val bar = buildActionBar(ctx, host)
        actionRow = bar
        val (row, field) = buildSearchRow(ctx)
        searchRow = row
        searchField = field
        barVisible = true

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        topContainer = top

        val wrap = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // M3 page surface behind the list (replaces the native light-blue page background) so the
            // rows / section headers / top bar all read as one Material surface.
            setBackgroundColor(M3.surface)
            addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }

        installHideOnScroll(rv)
        submit()
        // Reserve space for the overlay bar by padding the list's top to the bar's measured height.
        // A one-shot post() is unreliable: on re-entry the bar may not be measured yet (height 0), so
        // the padding stays 0 and the first 好友/群聊 header hides under the bar (the recurring bug).
        // Track the bar's real height on every layout pass and keep the padding in sync — this also
        // self-corrects if the bar height changes (e.g. a badge appears).
        rv.clipToPadding = false
        top.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->
            val h = b - t
            if (h > 0 && rv.paddingTop != h) {
                rv.setPadding(rv.paddingLeft, h, rv.paddingRight, rv.paddingBottom)
                Utils.log("ContactTopBar: set list top padding=$h")
            }
        }
        Utils.log("ContactTopBar: wrapped")
        return wrap
    }

    /** Four evenly-weighted circular tonal icon buttons; keeps refs to the badged notify buttons. */
    private fun buildActionBar(ctx: Context, host: ContactListFragmentHook): LinearLayout {
        val c = M3.ACCENT
        val add = iconButton(ctx, AddPersonIcon(c)) { host.topAddFriend() }
        val friend = iconButton(ctx, FriendNotifyIcon(c)) { host.topFriendNotify() }
        val group = iconButton(ctx, GroupNotifyIcon(c)) { host.topGroupNotify() }
        val search = iconButton(ctx, SearchIcon(c)) { toggleSearch() }
        friendBtn = friend
        groupBtn = group
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(8.dp, 6.dp, 8.dp, 4.dp)
            for (b in listOf(add, friend, group, search)) {
                // Fixed circular button centered in a weighted cell so the row spreads evenly.
                val cell = LinearLayout(ctx).apply {
                    gravity = Gravity.CENTER
                    clipChildren = false
                    clipToPadding = false
                    addView(b, LinearLayout.LayoutParams(30.dp, 30.dp))
                }
                addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    /**
     * Hide the overlay bar on scroll-down, show on scroll-up. Drives a [barVisible] flag (never reads
     * view state) so repeated scroll callbacks during a fling don't restart the animation, and moves
     * only translationY so the list never re-lays-out — no flicker.
     */
    private fun installHideOnScroll(rv: RecyclerView) {
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val top = topContainer ?: return
                if (searchRow?.visibility == View.VISIBLE) return    // never hide while searching
                if (dy > 6 && barVisible) {
                    barVisible = false
                    top.animate().translationY(-top.height.toFloat()).setDuration(160).start()
                } else if (dy < -6 && !barVisible) {
                    barVisible = true
                    top.animate().translationY(0f).setDuration(160).start()
                }
            }
        })
    }

    private fun iconButton(ctx: Context, icon: android.graphics.drawable.Drawable, onClick: () -> Unit): MaterialIconButton =
        MaterialIconButton(ctx).apply {
            setIcon(icon)
            setTonalContainer()                 // monochrome icons → M3 tonal circle behind them
            setOnClickListener { runCatching { onClick() }.onFailure { Utils.log("ContactTopBar click: $it") } }
        }

    /**
     * [searchRow, editText] — a back chevron + the filter field. Hidden until 搜索 is tapped; when
     * shown it REPLACES the action row in the same top slot (the back button restores the row).
     */
    private fun buildSearchRow(ctx: Context): Pair<View, EditText> {
        val back = MaterialIconButton(ctx).apply {
            setIcon(BackArrowIcon(M3.ACCENT))
            setTonalContainer()
            setOnClickListener { closeSearch() }
        }
        val et = M3.searchField(ctx, "搜索好友/群聊")
        et.doAfterTextChanged {
            query = et.text?.toString()?.trim().orEmpty()
            submit()
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 6.dp, 12.dp, 4.dp)
            visibility = View.GONE
            addView(back, LinearLayout.LayoutParams(30.dp, 30.dp).apply { marginEnd = 6.dp })
            addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row to et
    }

    private fun toggleSearch() {
        if (searchRow?.visibility == View.VISIBLE) closeSearch() else openSearch()
    }

    private fun openSearch() {
        val field = searchField ?: return
        actionRow?.visibility = View.GONE
        searchRow?.visibility = View.VISIBLE
        ensureBarShown()
        field.requestFocus()
        showKeyboard(field)
    }

    private fun closeSearch() {
        val field = searchField ?: return
        searchRow?.visibility = View.GONE
        actionRow?.visibility = View.VISIBLE
        ensureBarShown()
        field.setText("")          // clears query via the text watcher → restores full list
        hideKeyboard(field)
    }

    /** Bring the overlay back to its resting position (in case it was hidden by scroll). */
    private fun ensureBarShown() {
        barVisible = true
        topContainer?.animate()?.translationY(0f)?.setDuration(140)?.start()
    }

    private fun showKeyboard(v: View) {
        runCatching {
            (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(v: View) {
        runCatching {
            (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
        }
    }
}
