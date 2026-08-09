package momoi.mod.qqpro.lib

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

fun <T : View> ViewGroup.find(id: Int) = findViewById<T>(id)
fun <T : View> ViewGroup.child(id: Int) = getChildAt(0) as T

/** Replace every occurrence of [from] with [to] in all descendant TextViews' text. */
fun View.replaceTextRecursive(from: String, to: String) {
    if (this is TextView) {
        val current = text?.toString()
        if (current != null && current.contains(from)) {
            text = current.replace(from, to)
        }
    }
    if (this is ViewGroup) {
        for (i in 0 until childCount) getChildAt(i).replaceTextRecursive(from, to)
    }
}

/**
 * Run [action] on every global layout pass. Non-inline so the SAM impl lives in
 * this package, not inside a @Mixin method body in another package.
 *
 * NOTE: this deliberately does NOT remove the listener on detach. For the chat input bar the
 * continuous global-layout churn (the aggregate of every bar instance forcing re-layout) is what
 * keeps the inline EditText from collapsing/disappearing when the window would otherwise settle —
 * the perpetual refresh masks an underlying grow-layout bug. Scoping the listener to attach-state
 * (so it stops firing once idle) makes the EditText vanish, so we keep the always-on behaviour. The
 * per-frame LOG spam this used to cause is handled at the callsite by a per-instance dedup, not by
 * throttling the layout callback.
 */
fun View.onEachLayout(action: () -> Unit) {
    viewTreeObserver.addOnGlobalLayoutListener { action() }
}

/**
 * Run [action] each time this view detaches from the window. Non-inline so the
 * OnAttachStateChangeListener SAM impl lives in this package, not inside a @Mixin
 * method body in another package (which would crash with IllegalAccessError).
 */
fun View.onDetach(action: () -> Unit) {
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) = action()
    })
}

/**
 * Run [action] each time this view attaches to the window. Non-inline so the
 * OnAttachStateChangeListener SAM impl lives in this package, not inside a @Mixin
 * method body in another package (which would crash with IllegalAccessError).
 */
fun View.onAttach(action: () -> Unit) {
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = action()
        override fun onViewDetachedFromWindow(v: View) {}
    })
}