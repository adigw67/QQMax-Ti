package momoi.mod.qqpro.lib

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import momoi.mod.qqpro.findMethod

open class GroupScope(val group: ViewGroup) {
    inline fun <reified T : View> create(): T {
        return create<T>(
            group.context,
            group.javaClass.findMethod("generateDefaultLayoutParams").apply {
                isAccessible = true
            }.invoke(group) as ViewGroup.LayoutParams
        ).size(WRAP, WRAP)
    }
    inline fun <reified T : View> add(): T {
        val view = create<T>()
        group.addView(view)
        return view
    }

    fun add(view: View) {
        group.addView(view)
    }
}

inline fun <reified T : View> create(
    context: Context,
    params: ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        WRAP_CONTENT,
        WRAP_CONTENT
    )
): T {
    val view = T::class.java.getConstructor(Context::class.java).newInstance(context) as T
    view.layoutParams = params
    return view
}

fun <T : ViewGroup> T.content(block: GroupScope.() -> Unit): T =
    apply { GroupScope(this).apply(block) }