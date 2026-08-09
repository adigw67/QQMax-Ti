package momoi.mod.qqpro.hook.style

import android.view.View
import android.widget.TextView
import androidx.core.view.forEach
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ui.cell.base.BaseWatchItemCell
import com.tencent.watch.aio_impl.ui.cell.marketface.WatchMarketFaceMsgItem
import momoi.mod.qqpro.hook.aio_cell.MarketFaceImage
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import me.jessyan.autosize.AutoSizeConfig
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Colors
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.applyChatTextSize
import momoi.mod.qqpro.asGroupOrNull
import momoi.mod.qqpro.util.Utils

@Mixin
abstract class 缩小文本 : BaseWatchItemCell<WatchAIOMsgItem, View>() {
    override fun i(
        view: View,
        item: WatchAIOMsgItem,
        p2: Int,
        p3: List<Any>,
        p4: Lifecycle,
        p5: LifecycleOwner?
    ) {
        super.i(view, item, p2, p3, p4, p5)
        // Market-face (sticker / saved image-emoji) cells render their image into an ImageView and
        // carry no picElement; capture it so it can be opened fullscreen / copied / shared.
        if (item is WatchMarketFaceMsgItem) {
            // The element's dynamicFacePath is the local animated (GIF/WebP) file — pass it so the
            // sticker PLAYS fullscreen instead of opening a frozen single-frame snapshot.
            val animatedPath = runCatching {
                item.d.elements?.firstNotNullOfOrNull { it.marketFaceElement?.dynamicFacePath }
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            MarketFaceImage.onBind(item.d.msgId, view, animatedPath)
        }
        // Grey-tip system messages ("xxx撤回了…", join/time tips) are a WatchGrayTipsCell — a single
        // #99ffffff TextView (NOT an AIOCellGroupWidget), so the cell view IS that TextView. Recolor it
        // to the themed tip color so it isn't near-invisible on a light surface in light mode.
        if (view is TextView && view.currentTextColor == 0x99_FFFFFF.toInt()) {
            view.setTextColor(Colors.onSurfaceTip)
        }
        (view as? AIOCellGroupWidget)?.getContentWidget<View>()?.let { content ->
            content.asGroupOrNull()?.forEach {
                resize(it)
            } ?: resize(content)
        }
    }

    fun resize(view: View) {
        // Resize every body TextView regardless of its themed color. resize only ever walks
        // contentWidget's direct children (the message body), so there's nothing else to exclude.
        // The old `currentTextColor == 0xFFFFFFFF` gate was wrong now that AIOCell.applyMsgTextStyle
        // recolors self-side body text to a non-pure-white contrast color (textColorSelf): self
        // bubbles then failed the gate and rendered un-shrunk at native size while received text
        // (resolved to pure white) shrank — the single sizer must cover both sides uniformly.
        if (view is TextView) {
            // Size in absolute px against AutoSize's stable target scaledDensity rather than the
            // view's ambient scaledDensity. Visiting the Settings activity can leave the shared
            // displayMetrics at a stale density; using SP would then make chat text shrink until a
            // restart. Computing px from the config keeps the size correct regardless. (We must NOT
            // re-adapt the activity at runtime — that desyncs the conversation list RecyclerView.)
            //
            // applyChatTextSize also scales inline face emoji by the SAME factor the text scaled from
            // native, preserving QQ's native emoji-to-text ratio. (QQ bakes faces as fixed-size
            // EmoticonSpans; their rescale was dropped with textSizeScale in 7ead873, so they mismatched
            // the chatScale-sized text. A fixed absolute size instead clipped them — proportional fits.)
            view.applyChatTextSize(chatTextPx(15f * Settings.chatScale.value))
        }
    }

    private fun chatTextPx(sp: Float): Float {
        val cfg = AutoSizeConfig.getInstance()
        val designWidthDp = cfg.designWidthInDp.toFloat()
        if (designWidthDp <= 0f || cfg.screenWidth <= 0 || cfg.initDensity <= 0f) {
            return sp * Utils.application.resources.displayMetrics.scaledDensity
        }
        val targetDensity = cfg.screenWidth / designWidthDp
        val targetScaledDensity = cfg.initScaledDensity / cfg.initDensity * targetDensity
        return sp * targetScaledDensity
    }
}
