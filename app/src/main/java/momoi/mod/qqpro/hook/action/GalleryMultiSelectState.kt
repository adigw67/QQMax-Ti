package momoi.mod.qqpro.hook.action

import androidx.fragment.app.Fragment
import com.tencent.qqnt.kernel.nativeinterface.MsgElement

/**
 * Cross-activity signal: the multi-select gallery (separate activity) sets this true after
 * sending images, so when the chat activity's WatchAIOFragment resumes it switches its
 * ViewPager back to the chat page (page 0).
 */
object GalleryMultiSelectState {
    @Volatile
    var goToChatOnResume = false

    /**
     * Set true after gallery picks attach image(s) to [moye.wearqq.IMEOperation.extraMsg].
     * When the chat's WatchAIOFragment resumes (after the gallery pops) it opens the input-bar
     * preview so the user can review and send/cancel — without pre-switching to the chat page.
     */
    @Volatile
    var pendingOpenIme = false

    /**
     * Images picked in the gallery, staged HERE (not in [moye.wearqq.IMEOperation.extraMsg])
     * because QQ's [com.tencent.watch.aio_impl.coreImpl.vb.WatchAIOListVB.n] reassigns
     * `IMEOperation.extraMsg = new ArrayList()` on every msg-list UI-state render — which fires
     * when the chat resumes after the gallery pops, wiping anything we added before the pop. We
     * keep our own list QQ never touches and copy it into `extraMsg` at the last moment, right
     * before [moye.wearqq.IMEOperation.openIME], so that wipe can't race away the picked images.
     * Read-and-cleared by [consumePendingImages].
     */
    @Volatile
    var pendingImages: List<MsgElement> = emptyList()

    /** Read-and-clear [pendingImages]. */
    fun consumePendingImages(): List<MsgElement> {
        val v = pendingImages
        pendingImages = emptyList()
        return v
    }

    /**
     * Set true immediately before the chat "+" panel opens QQ's native gallery (相册). QQ's
     * [com.tencent.qqnt.watch.gallery.GalleryFragment] is shared by several flows (chat send,
     * avatar/profile-picture change, etc.); each non-chat flow registers its own
     * `setFragmentResult` listener and works natively. Our multi-select tap-interception must run
     * ONLY for the chat flow — whose listener lives on the [MenuFrame] that the attachment overlay
     * tears down, so the native result never arrives. Consumed (read-and-cleared) by
     * [GalleryMultiSelect] at gallery creation: when false, the gallery keeps its native behavior
     * so avatar/status pickers still return their selection. See [consumeChatLaunch].
     */
    @Volatile
    var chatLaunch = false

    // Per-fragment memo of the chat-launch decision. The global [chatLaunch] flag is read-and-cleared
    // at the first onCreateView, but a GalleryFragment instance gets onCreateView called MORE than
    // once (view recreation, and crucially re-show of a cached/back-stacked instance the user
    // re-opens WITHOUT going back through the "+ → 相册" tap that sets the flag). Those later calls
    // would see the flag already false and fall back to the native path → the picker pops without
    // routing the pick into the input bar (the reported "closes without putting it in the box" bug).
    // Remembering the decision per instance keeps every onCreateView of a chat-launched gallery on
    // our interception path. WeakHashMap so destroyed fragments are GC'd, never leaking to avatar
    // pickers (different instances; also excluded by their requestKey).
    private val chatLaunchByFragment = java.util.WeakHashMap<Fragment, Boolean>()

    /**
     * Whether the given gallery [fragment] is a chat-send launch. Sticky per instance: returns the
     * remembered decision if this fragment was already classified; otherwise reads-and-clears the
     * global [chatLaunch] flag, remembers it for this instance, and returns it.
     */
    fun isChatLaunch(fragment: Fragment): Boolean {
        chatLaunchByFragment[fragment]?.let { return it }
        val v = chatLaunch
        chatLaunch = false
        chatLaunchByFragment[fragment] = v
        return v
    }
}
