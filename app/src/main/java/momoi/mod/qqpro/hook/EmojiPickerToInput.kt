package momoi.mod.qqpro.hook

import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.watch.aio_impl.coreImpl.vm.IListFetcher
import com.tencent.watch.aio_impl.coreImpl.vm.WatchAIOListVM
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils

/**
 * 表情选择器插入输入框 ([Settings.emojiPickerToInput]).
 *
 * QQ's native emoji selector (EmotionDialogFragment: 系统表情 / 收藏 / 大表情 / 热图) funnels every
 * pick — sysface [FaceElement], fav/hot image, market face — through the single send choke point
 * [WatchAIOListVM.E] (verified: SystemEmotionViewModel + the WatchAIOListVM$handleIntent$1 listener
 * all build a MsgElement and call E(arrayListOf(it))). By default it sends immediately.
 *
 * When 完全行内输入 is active and the inline EditText is live, we instead hand those elements to
 * [InlineInput] so they land in the box as atomic [表情]/[图片] tokens — letting the user keep
 * composing (mix text, @, faces, images) and send everything in one message. Every other case
 * (feature off / no inline box) falls through to the original send.
 */
@Mixin
class EmojiPickerToInput(fetcher: IListFetcher) : WatchAIOListVM(fetcher) {

    override fun E(msgElements: ArrayList<MsgElement>) {
        if (Settings.inlineChatInput.value && Settings.fullInlineInput.value &&
            Settings.emojiPickerToInput.value && InlineInput.isReady
        ) {
            Utils.log("EmojiPickerToInput: routing ${msgElements.size} element(s) to inline box")
            if (InlineInput.insertElements(msgElements)) return
        }
        super.E(msgElements)
    }
}
