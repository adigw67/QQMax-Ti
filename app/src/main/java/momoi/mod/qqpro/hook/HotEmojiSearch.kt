package momoi.mod.qqpro.hook

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.nativeinterface.HotPicInfo
import com.tencent.qqnt.kernel.nativeinterface.IGProGetHotPicInfoListCallback
import com.tencent.qqnt.watch.emotion.EmotionDialogFragment
import com.tencent.qqnt.watch.emotion.hot.HotEmojiFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.util.Utils
import mqq.app.MobileQQ
import momoi.mod.qqpro.lib.material.M3

/**
 * Injects a search bar at the top of the emoji picker's "热图" (hot GIF) tab.
 * Uses QQ's built-in getHotPicInfoListSearchString API — no external key needed.
 * OnLoadMoreListener is a top-level non-inline helper to avoid IllegalAccessError
 * when the mixin body is copied into HotEmojiFragment's package.
 */
@Mixin
class HotEmojiSearch(dismissCall: EmotionDialogFragment.DialogDismissCall) : HotEmojiFragment(dismissCall) {

    // NOTE: Mixin classes have no constructor hook, so field initializers never run
    // (CLAUDE.md: "never add fields with initial values in a @Mixin class").
    // All fields must default to JVM null and be lazily initialized in code.
    // null = trending; non-null = searching
    private var currentQuery: String? = null
    private var lastSearchPicId: String? = null
    private var searchResults: ArrayList<HotPicInfo>? = null

    private fun results(): ArrayList<HotPicInfo> {
        var r = searchResults
        if (r == null) { r = ArrayList(); searchResults = r }
        return r
    }

    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val original = super.Y(inflater, container, savedInstanceState)!!
        return runCatching { wrapWithSearchBar(original) }.getOrElse {
            Utils.log("HotEmojiSearch: wrap failed: $it"); original
        }
    }

    private fun wrapWithSearchBar(original: View): View {
        val ctx = original.context
        val wrapper = LinearLayout(ctx)
        wrapper.orientation = LinearLayout.VERTICAL
        wrapper.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(8.dp, 4.dp, 8.dp, 4.dp)
        bar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val input = EditText(ctx)
        input.hint = "搜索 GIF"
        input.setHintTextColor(M3.hint)
        input.setTextColor(M3.onSurface)
        input.textSize = 13f
        input.setSingleLine()
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.background = GradientDrawable().apply {
            setColor(M3.surfaceContainer)
            cornerRadius = 14.dp.toFloat()
        }
        input.setPadding(10.dp, 6.dp, 10.dp, 6.dp)
        input.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(input)

        val btn = TextView(ctx)
        btn.text = "搜索"
        btn.setTextColor(M3.primary)
        btn.textSize = 13f
        btn.gravity = Gravity.CENTER
        btn.setPadding(8.dp, 0, 4.dp, 0)
        btn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        bar.addView(btn)

        wrapper.addView(bar)
        original.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        wrapper.addView(original)

        // Override SmartRefreshLayout load-more. Field 'j' = refreshView in HotEmojiFragment.
        // Use javaClass (= HotEmojiFragment at runtime) not superclass (= WatchFragment).
        runCatching {
            val rf = getRefreshView()
            // makeHotEmojiLoadMoreListener is a top-level non-inline function so the
            // OnLoadMoreListener anonymous class lives in this package, not the target package.
            rf?.H(makeHotEmojiLoadMoreListener { loadMore ->
                val q = currentQuery
                if (q != null) {
                    loadMoreSearch(q, original, loadMore)
                } else {
                    loadMoreTrending(loadMore)
                }
            })
            Utils.log("HotEmojiSearch: load-more listener overridden")
        }.onFailure { Utils.log("HotEmojiSearch: override load-more failed: $it") }

        val doSearch = {
            val q = input.text?.toString()?.trim().orEmpty()
            hideKeyboard(input)
            if (q.isBlank()) {
                currentQuery = null
                lastSearchPicId = ""
                results().clear()
                restoreTrending()
            } else {
                currentQuery = q
                lastSearchPicId = ""
                results().clear()
                searchGifs(q, "", original, append = false)
            }
        }
        btn.setOnClickListener { doSearch() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }

        Utils.log("HotEmojiSearch: search bar injected")
        return wrapper
    }

    private fun searchGifs(query: String, afterPicId: String, root: View, append: Boolean) {
        val rv = root.findRecyclerView() ?: run {
            Utils.log("HotEmojiSearch: RecyclerView not found"); return
        }
        val msgService = getMsgService() ?: run {
            Utils.log("HotEmojiSearch: msgService null"); return
        }
        Utils.log("HotEmojiSearch: searching '$query' after='$afterPicId' append=$append")
        msgService.getHotPicInfoListSearchString(
            query, afterPicId, 20, 1, false,
            IGProGetHotPicInfoListCallback { result, errMsg, results ->
                Utils.log("HotEmojiSearch: result=$result errMsg=$errMsg count=${results?.size}")
                if (result == 0 && results != null) {
                    if (results.isNotEmpty()) {
                        lastSearchPicId = results.last().picId ?: lastSearchPicId
                    }
                    if (append) results().addAll(results) else searchResults = ArrayList(results)
                    val snap = ArrayList(results())
                    rv.post {
                        submitList(rv, snap)
                        if (!append) rv.scrollToPosition(0)
                        finishLoadMore()
                    }
                } else {
                    rv.post { finishLoadMore() }
                }
            }
        )
    }

    private fun loadMoreSearch(query: String, root: View, notifyDone: () -> Unit) {
        searchGifs(query, lastSearchPicId ?: "", root, append = true)
    }

    private fun loadMoreTrending(notifyDone: () -> Unit) {
        runCatching {
            val vm = getVmField() ?: return
            val repoField = vm.javaClass.getDeclaredField("e")
            repoField.isAccessible = true
            val repo = repoField.get(vm) ?: return
            repo.javaClass.getMethod("a").invoke(repo)
        }.onFailure { Utils.log("HotEmojiSearch: loadMoreTrending failed: $it") }
    }

    private fun restoreTrending() {
        runCatching {
            val vm = getVmField() ?: run { Utils.log("HotEmojiSearch: viewModel null"); return }
            val repoField = vm.javaClass.getDeclaredField("e")
            repoField.isAccessible = true
            val repo = repoField.get(vm) ?: return
            val dataField = repo.javaClass.getDeclaredField("d")
            dataField.isAccessible = true
            (dataField.get(repo) as? MutableList<*>)?.clear()
            repo.javaClass.getMethod("a").invoke(repo)
            Utils.log("HotEmojiSearch: restored trending")
        }.onFailure { Utils.log("HotEmojiSearch: restoreTrending failed: $it") }
    }

    private fun submitList(rv: RecyclerView, list: ArrayList<HotPicInfo>) {
        val adapter = rv.adapter ?: return
        runCatching {
            adapter.javaClass.getMethod("submitList", List::class.java).invoke(adapter, list)
        }.onFailure { Utils.log("HotEmojiSearch: submitList failed: $it") }
    }

    private fun finishLoadMore() {
        runCatching {
            getRefreshView()?.javaClass?.getMethod("q", Boolean::class.java)
                ?.invoke(getRefreshView(), true)
        }.onFailure { /* SmartRefreshLayout may auto-finish */ }
    }

    private fun getRefreshView(): SmartRefreshLayout? = runCatching {
        val f = findFieldInHierarchy(this, "j")
        f?.get(this) as? SmartRefreshLayout
    }.onFailure { Utils.log("HotEmojiSearch: getRefreshView failed: $it") }.getOrNull()

    private fun getVmField(): Any? = runCatching {
        val f = findFieldInHierarchy(this, "i")
        f?.get(this)
    }.getOrNull()

    private fun hideKeyboard(view: View) {
        runCatching {
            (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}

// Top-level non-inline: the OnLoadMoreListener anonymous class lives in this package,
// not in HotEmojiFragment's package, so no IllegalAccessError when the mixin is copied.
fun makeHotEmojiLoadMoreListener(onLoadMore: (notifyDone: () -> Unit) -> Unit): com.scwang.smart.refresh.layout.listener.OnLoadMoreListener {
    return object : com.scwang.smart.refresh.layout.listener.OnLoadMoreListener {
        override fun q(layout: com.scwang.smart.refresh.layout.api.RefreshLayout) {
            onLoadMore { layout.javaClass.getMethod("q", Boolean::class.java).invoke(layout, true) }
        }
    }
}

// Walk up the class hierarchy until the field is found.
// Safe when javaClass resolves to a superclass (e.g. WatchFragment instead of HotEmojiFragment)
// because getDeclaredField only checks the exact class, not its parents.
// We start from the target class name if javaClass misses, then fall back to walking from javaClass.
fun findFieldInHierarchy(obj: Any, name: String): java.lang.reflect.Field? {
    // Try the known target class first
    runCatching {
        val cls = Class.forName("com.tencent.qqnt.watch.emotion.hot.HotEmojiFragment")
        val f = cls.getDeclaredField(name)
        f.isAccessible = true
        return f
    }
    // Walk up from obj's actual class
    var cls: Class<*>? = obj.javaClass
    while (cls != null) {
        runCatching {
            val f = cls!!.getDeclaredField(name)
            f.isAccessible = true
            return f
        }
        cls = cls.superclass
    }
    return null
}

private fun getMsgService() = runCatching {
    val runtime = MobileQQ.sMobileQQ?.peekAppRuntime() ?: return@runCatching null
    (runtime.getRuntimeService(IKernelService::class.java, "") as? IKernelService)?.msgService
}.getOrNull()

private fun View.findRecyclerView(): RecyclerView? {
    if (this is RecyclerView) return this
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val found = getChildAt(i).findRecyclerView()
            if (found != null) return found
        }
    }
    return null
}
