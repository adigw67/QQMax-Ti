package momoi.mod.qqpro.lib.material

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.LinearScope
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.vertical

/**
 * Full-screen Material 3 dialog scaffold for the round watch screen. A native AlertDialog renders
 * broken here (tiny compile SDK + overridden DPI), so QQPro dialogs are full-screen fragments. This
 * centralizes the shared shape: a scrolling, centered column with an optional title, a content slot,
 * and a trailing button stack — used by About, link-open, and any confirm dialog.
 *
 * Usage inside a fragment's onCreateView:
 *
 *     return M3Dialog(ctx).title("打开链接").body {
 *         add<TextView>().text(url)...
 *     }.actions {
 *         action("浏览器打开", M3Button.Variant.FILLED) { ...; dismiss() }
 *         action("取消", M3Button.Variant.TEXT) { dismiss() }
 *     }
 *
 * Public on purpose (referenced from @Mixin fragment bodies).
 */
class M3Dialog(ctx: Context) : ScrollView(ctx) {

    private val root = LinearLayout(ctx).vertical().apply {
        gravity = Gravity.CENTER
        setPadding(20.dp, 20.dp, 20.dp, 20.dp)
    }
    private val titleView = TextView(ctx).apply {
        textSize = 17f; setTextColor(M3.onSurface); gravity = Gravity.CENTER
        setPadding(0, 0, 0, 12.dp); visibility = View.GONE
    }
    private val actionsRow = LinearLayout(ctx).vertical().apply {
        gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 12.dp, 0, 0)
    }

    private var actionsAttached = false

    init {
        isFillViewport = true
        setBackgroundColor(M3.surface)
        root.addView(titleView, LinearLayout.LayoutParams(FILL, WRAP))
        addView(root, LayoutParams(FILL, WRAP))
    }

    fun title(text: CharSequence): M3Dialog = apply {
        titleView.text = text; titleView.visibility = View.VISIBLE
    }

    /** Build the dialog body; children are appended below the title (call before [actions]). */
    fun body(block: LinearScope.() -> Unit): M3Dialog = apply { LinearScope(root).block() }

    /** Build the trailing action button stack (appended last). */
    fun actions(block: M3ActionScope.() -> Unit): M3Dialog = apply {
        if (!actionsAttached) {
            root.addView(actionsRow, LinearLayout.LayoutParams(FILL, WRAP))
            actionsAttached = true
        }
        M3ActionScope(actionsRow).block()
    }
}

/** DSL scope for stacking [M3Button] actions in an [M3Dialog]. */
class M3ActionScope(private val container: LinearLayout) {
    fun action(label: CharSequence, variant: M3Button.Variant = M3Button.Variant.FILLED, onClick: () -> Unit): M3Button {
        val btn = M3Button(container.context).variant(variant).apply {
            text = label
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(FILL, WRAP)
        lp.topMargin = 8.dp
        container.addView(btn, lp)
        return btn
    }
}
