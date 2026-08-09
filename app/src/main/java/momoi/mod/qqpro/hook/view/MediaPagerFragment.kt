package momoi.mod.qqpro.hook.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * Standalone full-screen [MediaPager] viewer, shown as a dialog. Used when we intercept the native
 * RFW gallery launch ([momoi.mod.qqpro.hook.RFWGalleryHook]) so QZone (and other non-chat) media
 * taps open our zoomable, M3-progress viewer instead of QQ's.
 *
 * The media list/index are passed via the constructor (not arguments) — these instances are created
 * and shown immediately and never recreated by the system.
 */
class MediaPagerFragment(
    private val items: List<MediaItem> = emptyList(),
    private val initPos: Int = 0,
) : MyDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return MediaPager.build(
            inflater.context,
            childFragmentManager,
            items,
            initPos,
            onBack = { dismiss() },
        )
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }
}
