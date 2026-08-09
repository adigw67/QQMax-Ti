package momoi.mod.qqpro.hook.view

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Selection
import android.text.Spannable
import android.text.method.ArrowKeyMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

private val ACCENT get() = M3.primary
private val BG = M3.surface
private val GREEN = M3.primary

/**
 * "部分复制" — full-screen viewer that shows a single message's text in a selectable [TextView]
 * so the user can free-select an arbitrary range and copy it via the system selection toolbar
 * (the native 复制文本 menu item copies the whole message at once; this lets you grab a part).
 *
 * Follows the rest of the app's dialog pattern: a [MyDialogFragment] built in Kotlin (no XML),
 * dark full-screen background, wrapped in [SwipeBackLayout] so a left-to-right swipe dismisses it
 * on watches without a back button. A no-arg secondary constructor exists so the framework can
 * re-instantiate it after process death without crashing (content is simply empty then).
 */
class PartialCopyFragment(private val content: String) : MyDialogFragment() {

    constructor() : this("")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        // Round watch screens clip the corners — keep content off the edges (same idea as the
        // long-press menu's vh insets). Tighten everything so the text body gets the space.
        val edge = if (Utils.isRoundScreen) 16.dp else 8.dp
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setPadding(edge, 6.dp, edge, 6.dp)

        val title = TextView(ctx).apply {
            text = "长按选择要复制"
            textSize = 10f
            setTextColor(M3.hint)
            gravity = Gravity.CENTER
            setPadding(0, 2.dp, 0, 4.dp)
        }
        root.addView(title, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Selectable text body. Fills the remaining height and scrolls for long messages — this is
        // the part the user actually works with, so it gets almost all the screen.
        val scroll = ScrollView(ctx).apply { isFillViewport = false }
        val body = TextView(ctx).apply {
            text = content
            textSize = 13f
            setTextColor(M3.onSurface)
            setPadding(8.dp, 6.dp, 8.dp, 6.dp)
            setTextIsSelectable(true)
            // setTextIsSelectable already wires this, but set it explicitly so selection handles
            // work reliably under the watch ROM's stripped-down theme.
            movementMethod = ArrowKeyMovementMethod.getInstance()
            background = GradientDrawable().apply {
                setColor(M3.surfaceContainer)
                cornerRadius = 8.dp.toFloat()
            }
        }
        scroll.addView(body, ViewGroup.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(FILL, 0, 1f))

        // Two slim buttons share one row so they cost minimal vertical space.
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        root.addView(bar, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dp
        })
        button(bar, "全选", GREEN, M3.onPrimary) {
            body.requestFocus()
            runCatching {
                (body.text as? Spannable)?.let { Selection.setSelection(it, 0, it.length) }
            }.onFailure { Utils.log("PartialCopy: selectAll failed: $it") }
        }
        button(bar, "关闭", M3.surfaceContainerHigh, M3.onSurface) { dismiss() }

        return SwipeBackLayout(ctx).apply {
            addView(root, FILL, FILL)
            onSwipeBack = { dismiss() }
        }
    }

    private fun button(
        bar: LinearLayout,
        label: String,
        bg: Int,
        fg: Int,
        onClick: () -> Unit
    ) {
        val ctx = bar.context
        val tv = TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(0, 6.dp, 0, 6.dp)
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = 16.dp.toFloat()
            }
            setOnClickListener { onClick() }
        }
        // Equal-weight columns with a small gap between them.
        bar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = if (bar.childCount > 0) 6.dp else 0
        })
    }
}
