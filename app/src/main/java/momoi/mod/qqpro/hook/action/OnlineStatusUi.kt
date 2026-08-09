package momoi.mod.qqpro.hook.action

import android.content.Context
import android.graphics.drawable.GradientDrawable
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

    /**
     * A filled presence dot with a thin surface-coloured ring so it stands out when overlaid on the
     * top-left corner of a list avatar. [sizeDp] is the dot diameter (ring drawn inside it).
     */
    fun dot(ctx: Context, online: Boolean, sizeDp: Int = 9): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color(online))
        setStroke(1.5f.dpf.toInt(), M3.surface)
        val s = sizeDp.dp
        setSize(s, s)
    }
}
