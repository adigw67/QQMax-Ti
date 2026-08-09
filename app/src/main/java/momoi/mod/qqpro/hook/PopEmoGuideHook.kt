package momoi.mod.qqpro.hook

import com.tencent.watch.aio_impl.coreImpl.helper.PopEmoHelper
import momoi.anno.mixin.Mixin

/**
 * Remove the one-time "长按弹射表情" (long-press to shoot emoji) tutorial that pops up the first time
 * you enter a C2C chat on a fresh install.
 *
 * QQ's [PopEmoHelper] is an AIO lifecycle helper; its `d(state)` shows a full-screen
 * `GuideDialogFragment` when `state == 3` (AIO ready) and the MMKV flag `pop_guide_show<uin>` is
 * still unset. We swallow only that branch — every other state (notably `state == 8`, which cleans
 * up the bubble-animation view holders) still runs through the original, so nothing else breaks.
 */
@Mixin
class PopEmoGuideHook : PopEmoHelper() {
    override fun d(state: Int) {
        if (state == 3) return // suppress the first-run emoji long-press guide
        super.d(state)
    }
}
