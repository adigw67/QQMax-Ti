package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.tencent.watch.qzone_impl.feed.model.BusinessFeedData
import com.tencent.watch.qzone_impl.frame.QZoneMineFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.qzone.QzoneFeedM3
import momoi.mod.qqpro.util.Utils

/**
 * Hooks [QZoneMineFragment] — the page that shows a *specific user's* QZone (it reads `key_uin` from
 * its arguments and builds a feed engine for that uin). Two tweaks:
 *
 *  - [o] (footer-refresh): the native code pops a "没有更多内容" toast whenever there's nothing more to
 *    load, which fires the moment you open a user with a short feed — spammy. We call `super.o(true)`
 *    (which finishes the footer and takes the no-toast branch) then restore the real load-more flag.
 *  - [Y] (onCreateView): the native view is built in code with a bottom-pinned empty indicator and a
 *    plain "返回" text button. We post-process it via [QZoneEmptyState] to centre + materialize the
 *    empty state and turn the back button into a Material arrow.
 */
@Mixin
class QZoneMineFragmentHook : QZoneMineFragment() {

    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = super.Y(inflater, container, savedInstanceState)
        runCatching { QZoneEmptyState.materialize(this, root) }
            .onFailure { Utils.log("QZoneMineFragmentHook Y: $it") }
        if (Settings.materializeQzone.value) {
            runCatching { QzoneFeedM3.installMine(this) }
                .onFailure { Utils.log("QZoneMineFragmentHook install: $it") }
        }
        return root
    }

    /** Native feed-data callback — after super updates the native adapter list, mirror it into the M3 adapter. */
    override fun O(p0: MutableList<BusinessFeedData>, p1: Boolean) {
        super.O(p0, p1)
        if (Settings.materializeQzone.value) {
            runCatching { QzoneFeedM3.feedMine(this) }
                .onFailure { Utils.log("QZoneMineFragmentHook O: $it") }
        }
    }

    override fun o(hasMore: Boolean) {
        // true → run the native footer-finish path but take the no-toast branch.
        super.o(true)
        if (!hasMore) {
            runCatching {
                val srl = QZoneMineFragment::class.java.getDeclaredField("i")
                    .apply { isAccessible = true }
                    .get(this) ?: return
                // SmartRefreshLayout.H = enableLoadMore; super forced it true, restore the real value.
                srl.javaClass.getField("H").setBoolean(srl, false)
            }.onFailure { Utils.log("QZoneMineFragmentHook: H fixup failed: $it") }
        }
        Utils.log("QZoneMineFragmentHook: suppressed 没有更多内容 toast (hasMore=$hasMore)")
    }
}
