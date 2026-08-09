package momoi.mod.qqpro.hook

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.tencent.qqnt.watch.gallery.GalleryFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.hook.action.GalleryMultiSelectState
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.lib.dp

@Mixin
class GalleryMultiSelect : GalleryFragment() {

    override fun Y(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val baseView = super.Y(inflater, container, savedInstanceState)

        // GalleryFragment is shared by several QQ flows (chat send, avatar/profile-picture change,
        // status pickers, …). Each non-chat flow registers its own setFragmentResult listener and
        // works natively — our tap-interception would hijack their selection and wrongly route it
        // to the current chat (e.g. breaking "change avatar"). Only the chat flow needs us, because
        // its result listener lives on the MenuFrame the attachment overlay tears down. The chat
        // panel sets GalleryMultiSelectState.chatLaunch right before opening the gallery; consume it
        // here. When this is NOT a chat launch (or the args say it's the avatar picker), leave the
        // native gallery untouched so its fragment-result delivery still works.
        val requestKey = runCatching { arguments?.getString("request_key") }.getOrNull()
        // Sticky per-fragment so a re-shown/recreated chat gallery keeps our interception even though
        // the global one-shot flag was already consumed on its first onCreateView.
        val chatLaunch = GalleryMultiSelectState.isChatLaunch(this)
        val frag = System.identityHashCode(this)
        if (!chatLaunch || requestKey == "EditAvatarFragment") {
            Utils.log("GalleryMultiSelect: native launch (chatLaunch=$chatLaunch requestKey=$requestKey frag=$frag globalFlag=${GalleryMultiSelectState.chatLaunch}), skipping interception")
            return baseView
        }
        Utils.log("GalleryMultiSelect: chat launch, installing multi-select interception (frag=$frag)")

        val helper = GalleryMultiSelectHelper(this)

        val rv = findRecyclerView(baseView)
        if (rv != null) {
            helper.setupGestureDetector(rv)
            helper.setupItemDecoration(rv)
        } else {
            Log.e("QQPro", "GalleryMultiSelect: RecyclerView not found")
        }

        val ctx = inflater.context
        val wrapper = FrameLayout(ctx)
        wrapper.layoutParams = ViewGroup.LayoutParams(-1, -1)
        wrapper.addView(baseView, FrameLayout.LayoutParams(-1, -1))

        val btn = helper.buildSendButton(ctx)
        helper.sendButton = btn
        val lp = FrameLayout.LayoutParams(-2, 44.dp).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 16.dp
        }
        wrapper.addView(btn, lp)
        btn.visibility = View.GONE

        if (rv != null) {
            btn.setOnClickListener {
                Log.e("QQPro", "GalleryMultiSelect: send button view clicked")
                Utils.log("MultiSelect send button view clicked")
                helper.onSendClicked(rv)
                Utils.log("MultiSelect onSendClicked returned")
            }
        }

        return wrapper
    }

    private fun findRecyclerView(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view !is ViewGroup) return null
        for (idx in 0 until view.childCount) {
            val found = findRecyclerView(view.getChildAt(idx))
            if (found != null) return found
        }
        return null
    }
}
