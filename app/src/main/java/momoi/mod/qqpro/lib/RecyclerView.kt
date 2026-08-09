package momoi.mod.qqpro.lib

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import momoi.mod.qqpro.asGroup

fun <T : RecyclerView> T.linearLayout() = apply {
    layoutManager = LinearLayoutManager(context)
}

/**
 * Run [block] for every child view as it is attached to this RecyclerView.
 *
 * The anonymous listener implementation is generated here in the lib package (NOT inline)
 * so that calling this from a @Mixin method body is safe — an anonymous multi-method
 * listener created directly inside a @Mixin method crashes with IllegalAccessError
 * (see qqpro-mixin-anon-class memory).
 */
fun RecyclerView.onChildAttached(block: (View) -> Unit) {
    addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            block(view)
        }

        override fun onChildViewDetachedFromWindow(view: View) {}
    })
}

fun <T : RecyclerView, E> T.content(
    data: List<E>,
    factory: Context.() -> View,
    update: ViewGroup.(E) -> Unit
) = apply {
    adapter = SimpleAdapter(data, factory, update)
}

class SimpleHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

@SuppressLint("NotifyDataSetChanged")
class SimpleAdapter<E>(
    val data: List<E>,
    val factory: Context.() -> View,
    val update: ViewGroup.(E) -> Unit
) : RecyclerView.Adapter<SimpleHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        SimpleHolder(factory(parent.context))

    override fun getItemCount(): Int {
        return data.size
    }

    override fun onBindViewHolder(holder: SimpleHolder, position: Int) {
        update(holder.itemView.asGroup(), data[position])
    }

}