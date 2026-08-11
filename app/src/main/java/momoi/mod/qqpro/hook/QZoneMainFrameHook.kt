package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.tencent.watch.qzone_impl.feed.model.BusinessFeedData
import com.tencent.watch.qzone_impl.frame.QZoneMainFrame
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.qzone.QzoneFeedM3
import momoi.mod.qqpro.util.Utils

/**
 * Hooks [QZoneMainFrame] (动态/QZone feed, 3rd main page) to add a Material-style top bar
 * matching the contacts page bar pattern.
 *
 * The original page buries three action rows (发布, 通知, 我的空间) inside the feed adapter's
 * headViewContainer. This moves them into a compact overlay bar above the list:
 *   [self avatar → my QZone] | [edit → publish] | [bell → notifications]
 *
 * Anonymous listener classes MUST NOT live inside a @Mixin class (IllegalAccessError). All
 * click handling is delegated to public methods here, invoked by [QZoneTopBar].
 */
@Mixin
class QZoneMainFrameHook : QZoneMainFrame() {

    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val orig = super.Y(inflater, container, savedInstanceState)
        // Publish ourselves so a repeat-tap on the qzone nav cell can open the 通知 screen.
        MainNav.qzoneFragment = java.lang.ref.WeakReference<Any>(this)
        if (Settings.materializeQzone.value) {
            runCatching { QzoneFeedM3.installMain(this) }
                .onFailure { Utils.log("QZoneMainFrameHook install: $it") }
        }
        if (!Settings.materialQZoneBar.value) return orig
        return runCatching { QZoneTopBar.wrap(this, orig) }
            .onFailure { Utils.log("QZoneMainFrameHook Y: $it") }
            .getOrDefault(orig)
    }

    /** Native feed-data callback — mirror the native adapter list into the M3 adapter. */
    override fun O(p0: MutableList<BusinessFeedData>, p1: Boolean) {
        super.O(p0, p1)
        if (Settings.materializeQzone.value) {
            runCatching { QzoneFeedM3.feedMain(this) }
                .onFailure { Utils.log("QZoneMainFrameHook O: $it") }
        }
    }

    /** Publish bar-tap: open the from-scratch single-page compose when materialized, else the native flow. */
    fun barPublish() {
        if (Settings.materializeQzone.value) {
            runCatching { momoi.mod.qqpro.hook.qzone.QzoneCompose.open(this) }
                .onFailure { Utils.log("QZoneMainFrameHook compose: $it") }
            return
        }
        runCatching { QZoneTopBar.publishRow?.performClick() }
            .onFailure { Utils.log("QZoneMainFrameHook barPublish: $it") }
    }

    /** Delegates notify bar-tap to the original item_notify row click listener. */
    fun barNotify() {
        runCatching { QZoneTopBar.notifyRow?.performClick() }
            .onFailure { Utils.log("QZoneMainFrameHook barNotify: $it") }
    }

    /** Delegates avatar bar-tap to the original layout_qzone_item_owner row click listener. */
    fun barMyQzone() {
        runCatching { QZoneTopBar.ownerRow?.performClick() }
            .onFailure { Utils.log("QZoneMainFrameHook barMyQzone: $it") }
    }
}
