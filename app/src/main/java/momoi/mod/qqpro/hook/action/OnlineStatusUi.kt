package momoi.mod.qqpro.hook.action

import android.content.Context
import android.graphics.drawable.GradientDrawable
import java.util.HashMap
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.lib.material.M3

/**
 * Small shared render helpers for [OnlineStatus] — the presence dot drawable and the status-text
 * colour — so the conversation list, contacts list, titlebar and profile card all look consistent.
 */
object OnlineStatusUi {
    /** Green online indicator; offline uses a muted grey (both readable on any avatar). */
    val ONLINE = 0xFF3CCB5A.toInt()
    val OFFLINE get() = M3.onSurfaceTip

    fun color(online: Boolean): Int = if (online) ONLINE else OFFLINE

    // 状态点原型缓存（按 颜色+描边色+尺寸），每行绑定用 constantState.newDrawable() 克隆，
    // 避免列表滚动时每行都新建 GradientDrawable。
    private val dotPrototypes = HashMap<Long, GradientDrawable>()

    /**
     * A filled presence dot with a thin surface-coloured ring so it stands out when overlaid on the
     * top-left corner of a list avatar. [sizeDp] is the dot diameter (ring drawn inside it).
     */
    fun dot(ctx: Context, online: Boolean, sizeDp: Int = 9): GradientDrawable {
        val c = color(online)
        val ring = M3.surface
        val key = (if (online) 1L else 0L) or
            ((c.toLong() and 0xFFFFFF) shl 1) or
            ((ring.toLong() and 0xFFFFFF) shl 25) or
            (sizeDp.toLong() shl 49)
        val proto = dotPrototypes.getOrPut(key) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(1.5f.dpf.toInt(), ring)
                val s = sizeDp.dp
                setSize(s, s)
            }
        }
        return proto.constantState?.newDrawable() as? GradientDrawable ?: proto
    }
}
