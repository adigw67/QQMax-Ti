package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tencent.watch.aio_impl.ui.frames.AIOContentFrame
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.lib.RoundWatch
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
 *
 * 同时负责聊天消息列表的「圆屏自动宽度」：给消息 RecyclerView 左右套圆形内切矩形缩进并
 * setClipToPadding(false)，气泡在圆屏上不贴边、不溢出（Wear OS 风格的自适应宽度，方案 B——
 * 不引入 com.google.android.support:wearable 依赖，避免给 API 19 的字节码注入增加重依赖）。
 */
@Mixin
class AIOContentFrameTitlebar(p0: Int) : AIOContentFrame(p0) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 圆屏：聊天消息列表自适应宽度——左右留内切矩形安全边距，气泡不贴圆边。
        if (RoundWatch.enabled) {
            runCatching {
                val rv = (view as? ViewGroup)?.findAll { it is RecyclerView } as? RecyclerView
                if (rv != null) {
                    rv.clipToPadding = false
                    val ins = RoundWatch.horizontalInsetPx(rv.context)
                    rv.setPadding(ins, rv.paddingTop, ins, rv.paddingBottom)
                    Utils.log("AIOContentFrameTitlebar: message list round inset=$ins")
                }
            }.onFailure { Utils.log("AIOContentFrameTitlebar: round inset failed: $it") }
        }
        if (Settings.enableTitlebar.value && Settings.titlebarChatOnly.value) {
            Utils.log("AIOContentFrameTitlebar: building chat-only titlebar overlay")
            RichTitlebar.build(this, view as ViewGroup, 6.dp)
        }
    }
}
