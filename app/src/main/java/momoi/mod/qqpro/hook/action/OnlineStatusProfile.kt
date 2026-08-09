package momoi.mod.qqpro.hook.action

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import momoi.mod.qqpro.Settings

/**
 * Build a presence line (e.g. "手机在线") for a profile card — used by both the Material rebuild
 * ([momoi.mod.qqpro.hook.RichProfilePage]) and the legacy enrich path. Returns null when the profile
 * status surface is off or there's no uid. The view starts hidden and reveals itself once presence is
 * known; it observes [OnlineStatus] for live pushes and detaches its observer with the view.
 *
 * Works for BOTH friend and group-member cards (the kernel delivers member presence too).
 */
fun profileOnlineStatusView(ctx: Context, uid: String): TextView? {
    if (!Settings.onlineStatusProfile.value || uid.isEmpty()) return null
    OnlineStatus.start()
    OnlineStatus.prime(listOf(uid))
    val tv = TextView(ctx).apply {
        textSize = 11f
        gravity = Gravity.CENTER
        visibility = View.GONE
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
    val update = {
        val desc = OnlineStatus.describe(uid)
        if (desc != null) {
            tv.text = desc
            tv.setTextColor(OnlineStatusUi.color(OnlineStatus.isOnline(uid)))
            tv.visibility = View.VISIBLE
        } else {
            tv.visibility = View.GONE
        }
    }
    update()
    val observer: () -> Unit = { tv.post { update() }; Unit }
    OnlineStatus.addObserver(observer)
    tv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) { OnlineStatus.removeObserver(observer) }
    })
    return tv
}
