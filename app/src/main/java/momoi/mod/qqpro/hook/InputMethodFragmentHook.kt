package momoi.mod.qqpro.hook

import android.view.View
import android.view.ViewGroup
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.watch.ime.InputMethodFragment
import moye.wearqq.AtElementArg
import moye.wearqq.IMEOperation
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.MessageEdit
import momoi.mod.qqpro.util.Utils

@Mixin
class InputMethodFragmentHook : InputMethodFragment() {
    // Set in J() when this send carries gallery attachment(s); consumed in onDestroy() to switch
    // the chat ViewPager2 back to page 0. We do it in onDestroy (not J) and skip the usual
    // fixViewPagerSync for this case, because that sync reads the mid-animation scroll position and
    // would otherwise immediately revert our setCurrentItem(0). See VpSync / commit f6e2b31.
    private var switchToChatOnClose = false

    // Send callback (KeyboardPresenter.OnSendTextListener). When editing one of our
    // own messages, recall (撤回) the original first, then let the normal send proceed
    // so the edited text takes its place.
    override fun J(str: String?) {
        val editId = MessageEdit.editingMsgId
        // Recall the original when editing — also for an image-only edit (str is blank but a staged
        // attachment is being sent), otherwise the edit would add a copy instead of replacing.
        if (editId != 0L && (!str.isNullOrBlank() || IMEOperation.extraMsg.isNotEmpty())) {
            MessageEdit.consume()
            Utils.log("message edit: recall original msgId=$editId then send")
            runCatching { KernelServiceUtil.c()?.recallMsg(CurrentContact, editId, null) }
                .onFailure { Utils.log("message edit recall failed: $it") }
        }
        // When sending with attached gallery image(s), the IME is a child fragment of the chat
        // activity so WatchAIOFragment.onResume does NOT fire on dismissal — flag the switch and
        // perform it in onDestroy. (Plain text send leaves the page untouched; cancelling the
        // preview never reaches J, so the user stays put.)
        if (IMEOperation.extraMsg.isNotEmpty()) {
            switchToChatOnClose = true
            Utils.log("IME send with attachment(s): will switch chat to page 0 on close")
        } else if (IMEOperation.INSTANCE.extra.any { it is AtElementArg }) {
            // @成员 send (opened from the member picker): jump to the chat page on close, the same
            // way image sends do. Only fires on an actual send — J isn't reached when the user backs
            // out, and an @mention always carries a sendable extra so this won't trigger on a no-op.
            switchToChatOnClose = true
            Utils.log("IME send with @mention: will switch chat to page 0 on close")
        }
        super.J(str)
    }

    private fun findViewPager2(root: View): View? {
        if (root.javaClass.name.endsWith("ViewPager2")) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findViewPager2(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun switchToFirstPage(root: View) {
        val vp = findViewPager2(root) ?: run { Utils.log("IME switch: ViewPager2 not found"); return }
        // The bundled (R8-minified) ViewPager2 only exposes setCurrentItem(int).
        runCatching { vp.javaClass.getMethod("setCurrentItem", Int::class.java).invoke(vp, 0) }
            .onFailure { Utils.log("IME switchToFirstPage failed: $it") }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Drop any pending edit state if the page closed without sending (e.g. back press).
        MessageEdit.consume()
        val act = activity ?: return
        val decor = act.window?.decorView ?: return
        if (switchToChatOnClose) {
            switchToChatOnClose = false
            // Our controlled image send: jump to the chat page. Do NOT run fixViewPagerSync here —
            // it would revert this by syncing currentItem back to the still-visible panel page.
            Utils.log("VP sync: InputMethodFragment onDestroy switching to chat page 0")
            decor.post { switchToFirstPage(decor) }
        } else {
            Utils.log("VP sync: InputMethodFragment onDestroy")
            decor.post { fixViewPagerSync(decor) }
        }
    }
}
