package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.tencent.watch.aio_impl.ui.frames.AIOContentFrame
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.util.Utils

/**
 * Hosts the rich titlebar as an overlay inside the chat list page (page 0 of the AIO ViewPager).
 *
 * [AIOContentFrame] is the ViewPager's first frame — the actual chat screen (it embeds the
 * ChatFragment with the message list + input bar). Adding the bar to *its* view (rather than the
 * outer [WatchAIOFragment] root) keeps the bar page-local: it slides away with the chat list when
 * the user pages to the 附件/设置 frames, which already show that info, and reappears on return.
 *
 * Active only when [Settings.titlebarChatOnly] is on (default). When off, the legacy root-level
 * placement in [WatchAIOPageReset] is used instead. The bar floats over the list — the content is
 * deliberately not re-padded so messages scroll up behind the material gradient.
 */
@Mixin
class AIOContentFrameTitlebar(p0: Int) : AIOContentFrame(p0) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Settings.enableTitlebar.value && Settings.titlebarChatOnly.value) {
            Utils.log("AIOContentFrameTitlebar: building chat-only titlebar overlay")
            RichTitlebar.build(this, view as ViewGroup, 6.dp)
        }
    }
}
