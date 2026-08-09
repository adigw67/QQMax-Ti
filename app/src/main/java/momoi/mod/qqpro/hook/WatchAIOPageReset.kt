package momoi.mod.qqpro.hook

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import com.tencent.qqnt.watch.ui.componet.tablayout.CircleIndicator
import com.tencent.watch.aio_impl.ui.WatchAIOFragment
import moye.wearqq.IMEOperation
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.hook.action.GalleryMultiSelectState
import momoi.mod.qqpro.util.ChatBackground
import momoi.mod.qqpro.util.Utils

/**
 * After the multi-select gallery (a separate activity) sends images, the chat activity resumes.
 * Switch the AIO ViewPager back to the chat page (page 0), mirroring what single-image send
 * achieves via MenuFrame's selector.invoke(0).
 */
@Mixin
class WatchAIOPageReset : WatchAIOFragment() {
    // The base WatchFragment builds a full-screen background ImageView (field `d`)
    // behind the chat pages, normally showing R.drawable.bg_blue2white. If the user
    // picked a custom chat background, swap that image in (darkened for readability).
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (ChatBackground.isSet()) {
            Utils.log("WatchAIOFragment.onViewCreated applying custom chat background, bgView=${this.d}")
            ChatBackground.applyTo(this.d)
        } else if (Settings.materializeChat.value) {
            // No custom chat background image → overwrite the native (dark) default bg ImageView with
            // the themed M3 surface, so the chat content area isn't a black background in light mode.
            this.d?.apply { setImageDrawable(null); setBackgroundColor(M3.surface) }
            Utils.log("WatchAIOFragment.onViewCreated default chat background -> M3.surface")
        }
        if (Settings.attachmentOverlay.value) fixIndicatorIcons(view)
        if (Settings.enableTitlebar.value) {
            // The titlebar replaces the page-indicator dots in both placement modes. The dots live in
            // this (WatchAIOFragment) root frame, not in page 0's subtree, so remove them here even for
            // the chat-only overlay (AIOContentFrameTitlebar can't reach them from inside the page).
            val indicator = (view as? ViewGroup)?.findAll { it is CircleIndicator }
            (indicator?.parent as? ViewGroup)?.removeView(indicator)
            if (Settings.titlebarChatOnly.value) {
                // Chat-only overlay: drop the dots-strip top padding so page 0 fills from the very top
                // and the bar (added inside AIOContentFrame) overlays the head of the chat list.
                f?.let { it.setPadding(it.paddingLeft, 0, it.paddingRight, it.paddingBottom) }
            } else {
                // Legacy placement: bar at the fragment root, overlaying every ViewPager page.
                // The chat content (bubbles + input pill) is inset from the screen edge by a small base
                // padding (~4dp) on top of the corner margin. Add it so the titlebar lines up with the pill.
                RichTitlebar.build(this, view as ViewGroup, 6.dp)
                // Push the chat content below the (possibly taller) titlebar.
                val h = Settings.titlebarHeight.value.toInt().dp
                f?.let { it.setPadding(it.paddingLeft, h, it.paddingRight, it.paddingBottom) }
            }
        }
        // Optional unread badge floating over the chat's top-left corner (independent of titlebar).
        if (Settings.floatUnreadInChat.value) RichTitlebar.buildFloating(this, view as ViewGroup)
    }

    /**
     * With the attachment page removed, the page indicator still maps position 1 to the
     * 附件 (service) icon, but position 1 is now the settings page. Rewrite the indicator's
     * icon map so position 1 reuses the existing setting icon (key 2). Best-effort, cosmetic.
     */
    private fun fixIndicatorIcons(view: View) {
        runCatching {
            val indicator = (view as? ViewGroup)?.findAll { it is CircleIndicator } as? CircleIndicator
                ?: run { Utils.log("fixIndicatorIcons: indicator not found"); return }
            // Find the Config object held by the indicator, then its icon HashMap.
            val cfg = indicator.javaClass.declaredFields.firstOrNull {
                it.type.simpleName == "Config"
            }?.apply { isAccessible = true }?.get(indicator)
                ?: run { Utils.log("fixIndicatorIcons: config not found"); return }
            val mapField = cfg.javaClass.declaredFields.firstOrNull {
                java.util.Map::class.java.isAssignableFrom(it.type)
            }?.apply { isAccessible = true } ?: run { Utils.log("fixIndicatorIcons: map not found"); return }
            @Suppress("UNCHECKED_CAST")
            val map = mapField.get(cfg) as? MutableMap<Int, Any> ?: return
            if (map.containsKey(1) && map.containsKey(2)) {
                map[1] = map[2]!!
                runCatching { f?.let { indicator.setViewPager(it) } }
                Utils.log("fixIndicatorIcons: remapped position 1 -> setting icon")
            }
        }.onFailure { Utils.log("fixIndicatorIcons failed: $it") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // System picker/camera results are delivered here (the stable chat fragment) rather than to
        // the MenuFrame that launched them — the attachment overlay tears that MenuFrame down in
        // onPause when the picker activity opens, so it would never receive the result.
        Utils.log("WatchAIOFragment.onActivityResult req=$requestCode result=$resultCode")
        handleCaptureResult(requestCode, resultCode, data)
    }

    override fun onPause() {
        super.onPause()
        // Swipe-back / back-key to exit the chat leaves the soft keyboard open over the previous
        // screen (the inline EditText is popped without the IME being dismissed). Hide it here so it
        // closes with the chat. Best-effort; harmless on the attachment paths below.
        runCatching {
            val v = view
            if (v != null) {
                val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
            }
        }.onFailure { Utils.log("WatchAIOFragment.onPause hideSoftInput failed: $it") }
        // Any attachment action that leaves the chat (gallery / camera / system picker / IME
        // preview) navigates away instead of calling the overlay's selector. Closing the overlay
        // here covers all those paths; direct in-place sends are handled by selector -> dismiss().
        if (AttachmentOverlay.active) {
            Utils.log("WatchAIOFragment.onPause dismissing attachment overlay")
            AttachmentOverlay.dismiss()
        }
    }

    override fun onResume() {
        super.onResume()
        if (GalleryMultiSelectState.goToChatOnResume) {
            GalleryMultiSelectState.goToChatOnResume = false
            Utils.log("MultiSelect WatchAIOFragment.onResume switching to chat page 0, vp=${f}")
            // The bundled (R8-minified) ViewPager2 only exposes setCurrentItem(int);
            // the two-arg smoothScroll overload was stripped, so calling it crashes with NoSuchMethodError.
            f?.setCurrentItem(0)
        }
        if (RichProfilePage.pendingAt) {
            RichProfilePage.pendingAt = false
            Utils.log("RichProfile @ pending -> openIME on chat resume")
            // Defer to next frame so the profile pop + page state settle before the inline route runs
            // consumePending (which reads the staged AtElementArg and inserts the @ inline).
            view?.post {
                runCatching { IMEOperation.INSTANCE.openIME() }
                    .onFailure { Utils.log("RichProfile @ openIME failed: $it") }
            }
        }
        if (GalleryMultiSelectState.pendingOpenIme) {
            GalleryMultiSelectState.pendingOpenIme = false
            Utils.log("Gallery WatchAIOFragment.onResume opening IME preview for attached images")
            // Post so the gallery pop and page state settle before pushing the IME fragment.
            // Note: we deliberately do NOT switch to the chat page here — staying on the current
            // (+ panel) page means cancelling the preview leaves the user where they can pick again.
            view?.post {
                // Copy the picked images into IMEOperation.extraMsg HERE — the last write before
                // openIME — so QQ's WatchAIOListVB.n (which reassigns extraMsg = new ArrayList() on
                // each list re-render and already fired during this resume) can't wipe them. For the
                // inline path, openIME -> consumePending reads extraMsg synchronously on this same UI
                // message, so nothing can interleave between this write and the read.
                val imgs = GalleryMultiSelectState.consumePendingImages()
                if (imgs.isNotEmpty()) {
                    IMEOperation.extraMsg.clear()
                    IMEOperation.extraMsg.addAll(imgs)
                    Utils.log("Gallery onResume staged ${imgs.size} image(s) into extraMsg before openIME")
                }
                Utils.log("Gallery onResume pre-openIME: extraMsg=${IMEOperation.extraMsg.size} " +
                    "InlineInput.isReady=${InlineInput.isReady} currentItem=${runCatching { f?.javaClass?.getMethod("getCurrentItem")?.invoke(f) }.getOrNull()}")
                runCatching { IMEOperation.INSTANCE.openIME() }
                    .onFailure { Utils.log("Gallery openIME failed: $it") }
                Utils.log("Gallery onResume post-openIME returned")
            }
        }
    }
}
