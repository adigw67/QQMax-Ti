package momoi.mod.qqpro.hook.style

import com.tencent.watch.aio_impl.ui.cell.superface.WatchAniStickerItemCell
import com.tencent.watch.aio_impl.ui.cell.superface.WatchAniStickerGroupWidget
import com.tencent.watch.aio_impl.ui.cell.superface.WatchAniStickerMsgItem
import momoi.anno.mixin.StaticHook

@StaticHook(WatchAniStickerItemCell::class)
fun n(
    cell: WatchAniStickerItemCell,
    widget: WatchAniStickerGroupWidget,
    msg: WatchAniStickerMsgItem
) {
    msg.r.c = true
    WatchAniStickerItemCell.n(cell, widget, msg)
}