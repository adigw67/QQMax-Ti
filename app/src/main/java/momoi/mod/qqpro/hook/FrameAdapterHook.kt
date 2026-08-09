package momoi.mod.qqpro.hook

import android.os.Bundle
import androidx.fragment.app.Fragment
import com.tencent.watch.aio_impl.ui.frames.FrameAdapter
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings

/**
 * When [Settings.attachmentOverlay] is on, drop the attachment (MenuFrame) page from the chat
 * ViewPager — it's reachable via the input bar "+" overlay instead. The settings page is kept.
 *
 * Native getItemCount: 3 (好友: 聊天+附件+设置) / 2 (非好友, menuPageConfig on) / 1.
 * Remove one page: 好友 -> 2 (聊天+设置), 非好友 -> 1 (仅聊天). createFragment (`f`) remaps
 * position 1 to the settings page (super.f(2)), skipping MenuFrame at native position 1.
 */
@Mixin
class FrameAdapterHook(p0: Fragment, p1: Bundle, p2: (Int) -> Unit) : FrameAdapter(p0, p1, p2) {
    override fun getItemCount(): Int {
        if (!Settings.attachmentOverlay.value) return super.getItemCount()
        return if (super.getItemCount() >= 3) 2 else 1
    }

    override fun f(position: Int): Fragment {
        if (!Settings.attachmentOverlay.value) return super.f(position)
        return if (position == 0) super.f(0) else super.f(2)
    }

    // R8 renamed FragmentStateAdapter.createFragment to `f` in the target apk (which is what
    // actually runs), but the compile-time androidx classpath still sees `createFragment` as
    // abstract. Provide a delegating impl so this class compiles; it is dead at runtime.
    override fun createFragment(position: Int): Fragment = f(position)
}
