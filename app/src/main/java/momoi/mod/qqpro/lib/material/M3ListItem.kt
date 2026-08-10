package momoi.mod.qqpro.lib.material

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import momoi.mod.qqpro.lib.dp

/**
 * A Material 3 list row: optional leading view (avatar/icon) + a title / optional subtitle column +
 * optional trailing view (switch / chevron / badge). Reused by selectors, member pickers, and
 * settings rows so every list reads as one system.
 *
 *     M3ListItem(ctx).leading(avatar).title("Alice").subtitle("online").trailing(switch)
 *
 * Public on purpose (a @Mixin body referencing it needs it public).
 */
class M3ListItem(ctx: Context) : LinearLayout(ctx) {

    private val textCol = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val titleView = TextView(ctx).apply {
        setTextColor(M3.onSurface); textSize = 16f
        // MD3 label-large：sans-serif-medium（500），不是粗体也不是常规体。
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
    }
    private val subtitleView = TextView(ctx).apply {
        setTextColor(M3.onSurfaceVariant); textSize = 12f
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    private var leadingView: View? = null
    private var trailingView: View? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // MD3 列表项：横向 16dp，标准行高 56dp（可点击目标）。
        setPadding(16.dp, 10.dp, 16.dp, 10.dp)
        setMinimumHeight(56.dp)
        background = M3.ripple(null)
        textCol.addView(titleView)
        textCol.addView(subtitleView)
        addView(textCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Compact density: smaller title/subtitle text + tighter vertical padding (opt-in, for dense settings lists). */
    fun dense(): M3ListItem = apply {
        titleView.textSize = 15f
        subtitleView.textSize = 11f
        setPadding(16.dp, 6.dp, 16.dp, 6.dp)
        setMinimumHeight(48.dp)
    }

    fun title(text: CharSequence): M3ListItem = apply { titleView.text = text }

    fun subtitle(text: CharSequence?): M3ListItem = apply {
        if (text.isNullOrEmpty()) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.text = text; subtitleView.visibility = View.VISIBLE
        }
    }

    /** Place a leading view (e.g. avatar) at the start; [sizeDp] is its square size (0 = wrap). */
    fun leading(view: View, sizeDp: Int = 40): M3ListItem = apply {
        leadingView?.let { removeView(it) }
        leadingView = view
        val lp = if (sizeDp > 0) LayoutParams(sizeDp.dp, sizeDp.dp) else LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.rightMargin = 16.dp
        addView(view, 0, lp)
    }

    /** Place a trailing view (e.g. switch / chevron / badge) at the end. */
    fun trailing(view: View): M3ListItem = apply {
        trailingView?.let { removeView(it) }
        trailingView = view
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.leftMargin = 16.dp
        addView(view, lp)
    }
}
