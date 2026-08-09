package momoi.mod.qqpro.hook

import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import androidx.core.view.MotionEventCompat
import androidx.core.view.ViewConfigurationCompat
import androidx.recyclerview.widget.RecyclerView
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import com.tencent.qqlive.module.videoreport.inject.dialog.ReportDialog
import com.tencent.qqnt.watch.mainframe.MainActivity
import com.tencent.richframework.widget.matrix.RFWMatrixImageView
import me.jessyan.autosize.AutoSizeCompat
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.asGroup
import momoi.mod.qqpro.forEachAll
import momoi.mod.qqpro.util.Utils
import android.os.Looper
import java.util.WeakHashMap
import kotlin.math.roundToInt

private val screenCenterX = Resources.getSystem().displayMetrics.widthPixels / 2
private val point = intArrayOf(Int.MIN_VALUE, 0)

@Mixin
class 滚轮适配配(context: Context) : ReportDialog(context) {
    private var targetView: View? = null
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (targetView?.isInCenter() != true) {
            targetView = window?.decorView?.let { findTarget(it) }
        }
        targetView?.let { applyRotaryScroll(ev, it) }
        return super.dispatchGenericMotionEvent(ev)
    }
}

@Mixin
class 滚轮适配 : MainActivity() {
    private var targetView: View? = null

    // AutoSize only patches the shared/app DisplayMetrics density at activity create/start.
    // Visiting the QQPro settings activity or the system file/image picker resets the shared
    // metrics to the small system density, and any access to it between RecyclerView item binds
    // leaves the list with mixed-size rows (some adapted, some at the system density) until an
    // app restart; the same gap means 缩放倍数 changes only take effect after a restart.
    //
    // Re-pin the adapted density on EVERY resources access (AutoSize's own recommended fix), so
    // every measure/bind pass sees one consistent density for the current scale. Guard to the
    // main thread because AutoSizeCompat asserts it. Then a relayout on resume re-measures any
    // views laid out while the density was stale.
    override fun getResources(): Resources {
        val res = super.getResources()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { AutoSizeCompat.autoConvertDensityOfGlobal(res) }
        }
        return res
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            AutoSizeCompat.autoConvertDensityOfGlobal(resources)
            window?.decorView?.requestLayout()
        }.onFailure { Utils.log("onResume re-adapt failed: $it") }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        // While the attachment overlay is up, scope scrolling to its own list and consume the
        // event below — otherwise the encoder scrolls/turns the chat & pages behind the overlay.
        val overlayRoot = AttachmentOverlay.overlayView
        if (overlayRoot != null) {
            targetView = findTarget(overlayRoot)
        } else if (targetView?.isInCenter() != true) {
            targetView = findTarget(window.decorView)
        }
        targetView?.let { applyRotaryScroll(ev, it) }
        // Consume when the overlay is open so the views beneath never see the scroll.
        return if (overlayRoot != null) true else super.dispatchGenericMotionEvent(ev)
    }
}

/**
 * Apply one rotary-encoder turn to a resolved [target]. Shared by every host (chat MainActivity,
 * the ReportDialog viewers, the settings activity) so the scroll/zoom behaviour stays identical.
 *
 * IMPORTANT: a [RFWMatrixImageView] only ZOOMS — it must never also `scrollBy`, or a zoomed-out
 * image (already at minimumScale) would pan off-screen on every crown turn. So this dispatches by
 * type and does nothing for a view that is none of the three handled kinds.
 */
fun applyRotaryScroll(ev: MotionEvent, target: View) {
    val ctx = target.context
    val delta = -ev.getAxisValue(MotionEventCompat.AXIS_SCROLL) *
        ViewConfigurationCompat.getScaledVerticalScrollFactor(ViewConfiguration.get(ctx), ctx) *
        Settings.encoderScrollSpeed.value
    val d = delta.roundToInt()
    when (target) {
        is RecyclerView -> {
            if (Settings.enableSmoothScroll.value) target.smoothScrollBy(0, d) else target.scrollBy(0, d)
            // Programmatic scrollBy never produces the overscroll gesture that SmartRefreshLayout
            // uses to page; when the crown drives a registered feed to its bottom, kick load-more.
            if (d > 0 && !target.canScrollVertically(1)) EncoderLoadMore.trigger(target)
        }
        is ScrollView -> {
            if (Settings.enableSmoothScroll.value) target.smoothScrollBy(0, d) else target.scrollBy(0, d)
        }
        is RFWMatrixImageView -> {
            target.scale = (target.scale * (1 + 0.001f * delta)).coerceIn(target.minimumScale, target.maximumScale)
        }
    }
}

fun findTarget(rootView: View): View? {
    var target: View? = null
    rootView.asGroup().forEachAll {
        if (target != null) return@forEachAll
        val rv = (it as? RecyclerView)?.layoutManager?.canScrollVertically() == true
        val lv = it is ScrollView
        val iv = it is RFWMatrixImageView
        if ((rv || lv || iv) && it.isInCenter()) {
            target = it
        }
    }
    return target
}

fun View.isInCenter(): Boolean {
    if (!isAttachedToWindow) return false
    getLocationOnScreen(point)
    return point[0] <= screenCenterX && point[0] + width > screenCenterX
}

/**
 * Bridges crown scrolling to the materialized QZone feed's pagination. The feed loads more only
 * through SmartRefreshLayout's native OnLoadMoreListener (fired by a touch overscroll/fling), which
 * a programmatic `scrollBy` never triggers — so the crown would get stuck at the end of a page.
 * [QzoneFeedM3] registers each feed RecyclerView with its SmartRefreshLayout here; when the crown
 * scrolls one to the bottom, we invoke the same `t0.q(srl)` callback the gesture would have.
 */
object EncoderLoadMore {
    private val hosts = WeakHashMap<RecyclerView, SmartRefreshLayout>()
    private var lastTrigger = 0L

    fun register(rv: RecyclerView, srl: SmartRefreshLayout) {
        hosts[rv] = srl
    }

    fun trigger(rv: RecyclerView) {
        val srl = hosts[rv] ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastTrigger < 800L) return  // one page per ~0.8s; the engine guards re-entrancy too
        lastTrigger = now
        runCatching {
            val listener = srl.t0 ?: return
            listener.q(srl)
            Utils.log("EncoderLoadMore: triggered feed load-more")
        }.onFailure { Utils.log("EncoderLoadMore.trigger: $it") }
    }
}
