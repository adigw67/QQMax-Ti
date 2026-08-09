package momoi.mod.qqpro.hook

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.watch.aio_impl.ui.WatchAIOFragment
import com.tencent.watch.aio_impl.ui.frames.MenuFrame
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.util.Utils

/**
 * Shows the chat attachment list (相册/拍照/录像/… + 表情) as an overlay over the chat,
 * instead of the (now removed) ViewPager attachment page. Triggered by the "+" button that
 * replaces the input bar's emoji button when [Settings.attachmentOverlay] is on.
 *
 * The attachment list is a REAL [MenuFrame], hosted in the [WatchAIOFragment]'s own view tree
 * and childFragmentManager — exactly where the ViewPager would host it — so gallery navigation
 * (which resolves the AIO NavController from the fragment) and all existing QQPro customizations
 * (MenuPanelLayout / CameraSend / gallery flows) keep working unchanged. A plain DialogFragment
 * would put the content in a separate window where the NavController can't be found.
 */
object AttachmentOverlay {
    /** True while the overlay is showing — read by MenuPanelLayout to inject the 表情 item. */
    var active = false
        private set

    /** Action for the injected 表情 list item: dismiss the overlay and open the native emoji panel. */
    var emojiAction: (() -> Unit)? = null
        private set

    private var scrim: FrameLayout? = null
    private var hostFm: FragmentManager? = null

    /**
     * The open overlay's scrim view, or null when closed. Used by the rotary-encoder handler
     * (滚轮适配) to scope scrolling to the overlay's list and consume the event, so the chat/pages
     * beneath don't scroll/turn while the overlay is up.
     */
    val overlayView: View? get() = if (active) scrim else null
    private var hosted: Fragment? = null
    private var backCallback: OnBackPressedCallback? = null

    /** [anchor] = the "+" button view; [emojiView] = the native emoji button (getChildAt(0)). */
    fun show(anchor: View, emojiView: View) {
        // If a previous overlay's host was torn down without dismiss() (e.g. chat closed
        // underneath it), the flag may be stale — clear it so we can open again.
        if (active && scrim?.isAttachedToWindow != true) resetState()
        if (active) return
        runCatching {
            val aio = findAioFragment(anchor) ?: run {
                Utils.log("AttachmentOverlay: WatchAIOFragment not found"); return
            }
            val container = aio.view as? ViewGroup ?: run {
                Utils.log("AttachmentOverlay: AIO container view missing"); return
            }
            val fm = aio.childFragmentManager

            val inner = FrameLayout(container.context).apply { id = View.generateViewId() }
            val box = FrameLayout(container.context).apply {
                background = android.graphics.drawable.ColorDrawable(0x44_000000)
                isClickable = true
                setOnClickListener { dismiss() }
                addView(inner, FrameLayout.LayoutParams(-1, -1))
                // If the host is destroyed without dismiss(), reset our state on detach.
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) { if (active) resetState() }
                })
            }
            container.addView(box, ViewGroup.LayoutParams(-1, -1))

            active = true
            emojiAction = { dismiss(); emojiView.callOnClick() }
            scrim = box
            hostFm = fm

            // Back press should close the overlay, not pop the whole chat fragment.
            val cb = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { dismiss() }
            }
            aio.requireActivity().onBackPressedDispatcher.addCallback(aio.viewLifecycleOwner, cb)
            backCallback = cb

            val menu = MenuFrame({ _: Int -> dismiss() }, true)
            menu.arguments = aio.arguments
            hosted = menu
            fm.beginTransaction().replace(inner.id, menu).commitNow()
            // The hosted list fills the screen and consumes taps over its own bounds, so taps on
            // the empty area inside it never reach the scrim. Detect taps that land on no list
            // item and dismiss too (so any empty part of the panel closes the overlay).
            inner.post {
                (inner.findAll { it is RecyclerView } as? RecyclerView)
                    ?.addOnItemTouchListener(EmptyTapDismiss)
            }
            Utils.log("AttachmentOverlay: shown")
        }.onFailure { Utils.log("AttachmentOverlay.show failed: $it"); resetState() }
    }

    fun dismiss() {
        if (!active) return
        runCatching {
            hosted?.let { f -> hostFm?.beginTransaction()?.remove(f)?.commitNowAllowingStateLoss() }
            (scrim?.parent as? ViewGroup)?.removeView(scrim)
        }.onFailure { Utils.log("AttachmentOverlay.dismiss failed: $it") }
        resetState()
    }

    private fun resetState() {
        backCallback?.remove()
        active = false
        emojiAction = null
        scrim = null
        hostFm = null
        hosted = null
        backCallback = null
    }

    /**
     * Dismisses the overlay when a tap (not a scroll) inside the list lands on empty space — i.e.
     * not on any item. Returns false from intercept so list scrolling and item clicks are
     * unaffected.
     */
    private object EmptyTapDismiss : RecyclerView.OnItemTouchListener {
        private var downX = 0f
        private var downY = 0f
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.hypot(e.x - downX, e.y - downY)
                    if (moved < 16f && rv.findChildViewUnder(e.x, e.y) == null) dismiss()
                }
            }
            return false
        }
        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
        override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
    }

    /**
     * The [WatchAIOFragment]'s root container (a FrameLayout). The reply/edit banner is hosted here
     * — the same parent this overlay's scrim is added to — so the overlay (added later) draws above
     * the banner instead of the banner floating on top. Returns null if [view] isn't in an AIO tree.
     */
    fun aioContainer(view: View): ViewGroup? =
        runCatching { findAioFragment(view)?.view as? ViewGroup }.getOrNull()

    /** The [WatchAIOFragment] hosting [anchor], or null. Public so other hooks (e.g. voice
     *  recording permission requests) can reach the fragment that owns the chat view. */
    fun aioFragment(anchor: View): WatchAIOFragment? = findAioFragment(anchor)

    private fun findAioFragment(anchor: View): WatchAIOFragment? {
        var f: Fragment? = runCatching { FragmentManager.findFragment<Fragment>(anchor) }.getOrNull()
        while (f != null) {
            if (f is WatchAIOFragment) return f
            f = f.parentFragment
        }
        return null
    }
}
