package momoi.mod.qqpro.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ScrollView
import com.tencent.mobileqq.text.QQText
import com.tencent.qqnt.emotion.utils.QQSysFaceUtil
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Progress
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * In-chat system-face (sysface) picker for the inline input pill ([Settings.inlineEmojiButton]).
 * Shows a scrollable grid of QQ sysfaces floating just above the input bar (where the keyboard
 * was). Tapping a face inserts its emo-code rendered as an EmoticonSpan (via [QQText], same
 * encoding the inline send path parses) into the EditText. Toggling the panel collapses the soft
 * keyboard; tapping the input field again closes the panel and reopens the keyboard.
 *
 * This is a plain object (not a @Mixin), so the anonymous listener/adapter classes it creates are
 * fine — the mixin-copy package issue only affects classes declared inside @Mixin method bodies.
 */
object InlineEmojiPanel {
    private const val TAG = "qqpro_inline_emoji_panel"
    private var panel: View? = null
    private var boundEdit: EditText? = null
    // Input pill we translated up to sit above the panel (reset to 0 on dismiss).
    private var liftedPill: View? = null
    // Decoded sysface drawables, cached after the first (slow) build so later opens are instant.
    private var faceCache: List<Pair<Int, Drawable>>? = null

    private val detachDismiss = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) { dismiss() }
    }

    val isShowing get() = panel != null

    /**
     * Show/hide the sysface grid for [editText]. [host] is the full-bounds container the panel is
     * attached to (and bottom-anchored, like a soft keyboard). Pass it when the field lives in its
     * own window — e.g. a dialog — where the editText context is a ContextThemeWrapper, not the
     * Activity, and the field isn't pinned to the screen bottom. The chat bar passes null: the panel
     * resolves the Activity content view and floats just above the input pill (its old behaviour).
     */
    fun toggle(editText: EditText, host: ViewGroup? = null) {
        if (isShowing) { dismiss(); showKeyboard(editText) } else show(editText, host)
    }

    /** Walk the ContextWrapper chain to the backing Activity (handles ContextThemeWrapper dialogs). */
    private fun unwrapActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    fun dismiss() {
        liftedPill?.let { it.translationY = 0f }
        liftedPill = null
        val p = panel
        panel = null
        if (p != null) {
            val parent = p.parent as? ViewGroup
            // Defer the actual removeView: dismiss() can be reached from a host's
            // onDetachedFromWindow (e.g. a dialog dismissing on back), and removing a child while
            // its parent is mid-dispatchDetachedFromWindow corrupts the parent's child array
            // (NPE in ViewGroup.dispatchDetachedFromWindow). Posting it runs after the dispatch.
            if (parent != null) parent.post { runCatching { parent.removeView(p) } }
        }
    }

    private fun imm(v: View) =
        v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun showKeyboard(editText: EditText) {
        editText.requestFocus()
        editText.post { imm(editText)?.showSoftInput(editText, 0) }
    }

    private fun hideKeyboard(editText: EditText) {
        imm(editText)?.hideSoftInputFromWindow(editText.windowToken, 0)
    }

    private fun show(editText: EditText, host: ViewGroup?) {
        runCatching {
            val content = host
                ?: unwrapActivity(editText.context)?.findViewById<ViewGroup>(android.R.id.content)
                ?: run { Utils.log("InlineEmojiPanel.show: no host/content"); return }

            if (boundEdit !== editText) {
                boundEdit = editText
                // Tapping the input field closes the panel and brings the keyboard back.
                editText.setOnClickListener { if (isShowing) { dismiss(); showKeyboard(editText) } }
                editText.removeOnAttachStateChangeListener(detachDismiss)
                editText.addOnAttachStateChangeListener(detachDismiss)
            }

            hideKeyboard(editText)
            // Build after the keyboard collapses so the panel floats just above the input bar
            // (which stays at the bottom, tappable), not over it.
            editText.postDelayed({
                runCatching {
                    if (boundEdit !== editText) return@runCatching
                    dismiss()
                    val ctx = editText.context
                    val container = FrameLayout(ctx).apply {
                        tag = TAG
                        isClickable = true // swallow taps so they don't fall through to the chat
                        // Material sheet: surface-container with rounded top corners, sitting above the bar.
                        background = GradientDrawable().apply {
                            setColor(M3.surfaceContainer)
                            cornerRadii = floatArrayOf(
                                M3.radiusLg, M3.radiusLg, M3.radiusLg, M3.radiusLg, 0f, 0f, 0f, 0f,
                            )
                        }
                        elevation = 24.dp.toFloat()
                    }
                    val bottomMargin: Int
                    val panelH: Int
                    if (host != null) {
                        // Generic field (own window): float a keyboard-height sheet at the window
                        // bottom — the field may sit anywhere (often near the top), so don't anchor
                        // to it; it stays visible above the sheet. Size from the real screen height
                        // (the decor/rootView can over-report), capped so the close header stays on
                        // screen.
                        val screenH = ctx.resources.displayMetrics.heightPixels
                        bottomMargin = 0
                        panelH = (screenH * 0.5f).toInt()
                            .coerceAtMost((screenH - 8.dp).coerceAtLeast(160.dp))
                    } else {
                        // Chat bar: sit just above the input pill (its parent), leaving it tappable below.
                        val pill = editText.parent as? View
                        bottomMargin = if (pill != null) {
                            val loc = IntArray(2); pill.getLocationInWindow(loc)
                            val cloc = IntArray(2); content.getLocationInWindow(cloc)
                            ((cloc[1] + content.height) - loc[1]).coerceAtLeast(0)
                        } else 96.dp
                        // Cap the height to what's actually available above the input bar so the panel
                        // can never overflow off the top of the screen (leave a small top inset).
                        val available = (content.height - bottomMargin - 8.dp).coerceAtLeast(120.dp)
                        panelH = (content.height * 0.62f).toInt().coerceAtLeast(220.dp).coerceAtMost(available)
                    }
                    content.addView(container, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, panelH, Gravity.BOTTOM
                    ).apply { this.bottomMargin = bottomMargin })
                    panel = container
                    // In host mode the sheet covers the whole window (the field included), so there's
                    // no input pill to tap for closing — add a header bar with a close (✕) button that
                    // dismisses the picker and brings the keyboard back. The chat bar has no header
                    // (you tap the field/emoji key to close), so this is host-only.
                    val headerH = if (host != null) 40.dp else 0
                    if (host != null) {
                        // Collapsed = the half-screen sheet; expanded = full screen (covers the field).
                        val maxFull = ctx.resources.displayMetrics.heightPixels
                        addCloseHeader(container, editText, headerH, minH = panelH, maxH = maxFull)
                    }
                    populate(container, editText, headerH)
                    Utils.log("InlineEmojiPanel shown (h=$panelH bottomMargin=$bottomMargin host=${host != null})")
                }.onFailure { Utils.log("InlineEmojiPanel build failed: $it") }
            }, 180)
        }.onFailure { Utils.log("InlineEmojiPanel.show failed: $it") }
    }

    /**
     * Top bar (host mode) with a drag handle + centered "表情" label and a trailing ✕. Dragging the
     * bar resizes the sheet between [minH] (the half-screen default) and [maxH] (full screen, which
     * covers the field); dragging well below [minH] closes it. The ✕ closes the picker and restores
     * the keyboard.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addCloseHeader(
        container: FrameLayout, editText: EditText, headerH: Int, minH: Int, maxH: Int,
    ) {
        val ctx = container.context
        val header = FrameLayout(ctx).apply { background = M3.ripple(null) }

        // Visual drag affordance: a short rounded pill near the top-center.
        val handle = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(M3.onSurfaceVariant); cornerRadius = 2f.dpf
            }
        }
        header.addView(handle, FrameLayout.LayoutParams(32.dp, 4.dp, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = 6.dp })

        val title = android.widget.TextView(ctx).apply {
            text = "表情"
            setTextColor(M3.onSurfaceVariant)
            textSize = 13f
        }
        header.addView(title, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val close = ImageView(ctx).apply {
            setImageDrawable(MaterialSymbol(MaterialSymbols.close, M3.onSurfaceVariant))
            val p = 11.dp
            setPadding(p, p, p, p)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { dismiss(); showKeyboard(editText) }
        }
        header.addView(close, FrameLayout.LayoutParams(headerH, headerH, Gravity.END or Gravity.CENTER_VERTICAL))

        // Drag-to-resize. The sheet is bottom-anchored, so a larger height grows it upward.
        var startY = 0f
        var startH = 0
        var dragged = false
        header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    startH = container.layoutParams?.height ?: minH
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (startY - e.rawY).toInt() // dragging up is positive
                    if (kotlin.math.abs(dy) > 4.dp) dragged = true
                    val lp = container.layoutParams ?: return@setOnTouchListener true
                    // Allow a little past minH so an over-drag-down can trigger close on release.
                    lp.height = (startH + dy).coerceIn((minH * 0.5f).toInt(), maxH)
                    container.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val lp = container.layoutParams
                    val h = lp?.height ?: minH
                    when {
                        // Dragged well below the collapsed size → close.
                        h < minH * 0.7f -> { dismiss(); showKeyboard(editText) }
                        // Past the midpoint → snap open to full screen.
                        h > (minH + maxH) / 2 -> { if (lp != null) { lp.height = maxH; container.layoutParams = lp } }
                        // Otherwise snap back to the collapsed half-screen.
                        else -> { if (lp != null) { lp.height = minH; container.layoutParams = lp } }
                    }
                    // A tap (no real drag) on the bar also collapses-toggle nothing; ✕ handles close.
                    if (!dragged) { /* treat as no-op tap */ }
                    true
                }
                else -> false
            }
        }

        container.addView(header, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, headerH, Gravity.TOP))
    }

    /**
     * Fills [container] with the sysface grid. Shows a spinner immediately; if the drawables are
     * already cached the grid is built instantly, otherwise they are decoded in small batches
     * (posting between batches) so the spinner keeps animating instead of freezing the UI.
     */
    private fun populate(container: FrameLayout, editText: EditText, topInset: Int = 0) {
        val ctx = container.context
        val screenW = ctx.resources.displayMetrics.widthPixels
        // Big faces for a watch: fewer columns => larger cells.
        val columns = 6
        // Flexible cell width derived from the width actually available INSIDE the grid padding, so the
        // last column never spills off the (round) screen edge. columns*cell + 2*pad == screenW.
        val gridPad = 6.dp
        val cell = ((screenW - gridPad * 2) / columns).coerceAtLeast(24.dp)
        val pad = (cell * 0.16f).toInt()

        val progress = M3Progress.spinner(ctx)
        container.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            this.topMargin = topInset
        })

        val grid = GridLayout(ctx).apply {
            columnCount = columns
            setPadding(gridPad, gridPad, gridPad, gridPad)
        }
        val scroll = ScrollView(ctx).apply { addView(grid) }
        container.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            this.topMargin = topInset
        })

        fun addCell(id: Int, d: Drawable) {
            val iv = ImageView(ctx).apply {
                setImageDrawable(d)
                setPadding(pad, pad, pad, pad)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = M3.ripple(null) // Material press feedback per face
                setOnClickListener { insert(editText, id) }
            }
            grid.addView(iv, GridLayout.LayoutParams().apply { width = cell; height = cell })
        }

        val cache = faceCache
        if (cache != null) {
            for ((id, d) in cache) addCell(id, d)
            container.removeView(progress)
            Utils.log("InlineEmojiPanel: grid from cache (${cache.size})")
            return
        }

        // First open: decode in batches so the spinner animates; hide the grid until it's ready.
        scroll.visibility = View.INVISIBLE
        val ids = ArrayList<Int>()
        val all = QQSysFaceUtil.a.h()
        for (k in 0 until all.size) {
            val id = all[k] ?: continue
            if (QQSysFaceUtil.a.j(id)) ids.add(id)
        }
        val result = ArrayList<Pair<Int, Drawable>>()
        fun step(start: Int) {
            if (panel !== container) return // dismissed mid-build
            var i = start
            var n = 0
            while (i < ids.size && n < 24) {
                val id = ids[i]; i++
                val d = runCatching { QQSysFaceUtil.a.d(id) }.getOrNull() ?: continue
                result.add(id to d)
                addCell(id, d)
                n++
            }
            if (i < ids.size) {
                grid.post { step(i) }
            } else {
                faceCache = result
                scroll.visibility = View.VISIBLE
                container.removeView(progress)
                Utils.log("InlineEmojiPanel: grid built+cached (${result.size})")
            }
        }
        step(0)
    }

    private fun insert(editText: EditText, localId: Int) {
        runCatching {
            val emo = QQSysFaceUtil.a.g(localId)
            val rendered = QQText(emo, 3, 18, null)
            val s = editText.selectionStart.coerceAtLeast(0)
            val e = editText.selectionEnd.coerceAtLeast(0)
            editText.text?.replace(minOf(s, e), maxOf(s, e), rendered)
        }.onFailure { Utils.log("InlineEmojiPanel insert failed: $it") }
    }
}
