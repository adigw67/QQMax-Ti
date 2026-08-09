package momoi.mod.qqpro.hook
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.watch.aio_impl.ui.frames.MenuFrame
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.drawable.atIconDrawable
import momoi.mod.qqpro.drawable.audioFileIconDrawable
import momoi.mod.qqpro.drawable.cameraIconDrawable
import momoi.mod.qqpro.drawable.emojiIconDrawable
import momoi.mod.qqpro.drawable.galleryIconDrawable
import momoi.mod.qqpro.drawable.phoneIconDrawable
import momoi.mod.qqpro.drawable.recordIconDrawable
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.drawable.videoIconDrawable
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.GalleryMultiSelectState
import momoi.mod.qqpro.hook.action.isGroup
import momoi.mod.qqpro.hook.style.cardMargin
import momoi.mod.qqpro.hook.view.CallConfirmFragment
import momoi.mod.qqpro.hook.view.MemberPickerFragment
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.onChildAttached
import momoi.mod.qqpro.util.Utils

/**
 * Chat "+" panel (相册 / 拍照 / 语音通话 / 视频通话). The stock layout is a 2-column grid of
 * icon-over-text cells with poor system icons. Re-lay it out as a single-column vertical list
 * of rounded "icon-left / text-right" cards with freshly drawn icons.
 *
 * We never touch the obfuscated adapter or its click logic: the original OnClickListener stays
 * on the icon ImageView (so gallery / camera / call all still work) — we just restyle each
 * attached cell in place and forward whole-row taps to the icon.
 */
@Mixin
class MenuPanelLayout(p0: (Int) -> Unit, p1: Boolean) : MenuFrame(p0, p1) {

    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        val list = (root as? ViewGroup)?.findAll { it is RecyclerView } as? RecyclerView
        if (list != null) {
            list.layoutManager = GridLayoutManager(list.context, 1)
            list.clipToPadding = false
            if (Settings.materialAttachmentMenu.value) {
                // One M3 surface card holding every row (matches the long-press menu): the rounded
                // card is the RecyclerView itself, centered with side margins; rows scroll inside it.
                list.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(M3.surfaceContainer); cornerRadius = M3.radiusLg
                }
                list.setClipToOutlineCompat(true)
                list.setPadding(0, 6.dp, 0, 6.dp)
                list.layoutParams = android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
                ).apply { val m = 14.dp; setMargins(m, m, m, m) }
            } else {
                list.setPadding(8.dp, 0, 8.dp, 0)
            }
        }
        Utils.log("MenuPanelLayout: switched to vertical list")
        return root
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        handleCaptureResult(requestCode, resultCode, data)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = (view as? ViewGroup)?.findAll { it is RecyclerView } as? RecyclerView
        list?.onChildAttached { restyleCell(it) }
        // Inject a "录像" item right after 拍照 in the native adapter's data list.
        list?.let { injectRecordItem(it) }
    }

    private fun injectRecordItem(list: RecyclerView) {
        runCatching {
            val adapter = list.adapter ?: return
            val field = adapter.javaClass.declaredFields.firstOrNull {
                List::class.java.isAssignableFrom(it.type)
            } ?: return
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val items = field.get(adapter) as? MutableList<com.tencent.watch.aio_impl.ui.frames.MenuItem>
                ?: return
            if (items.any { it is RecordMenuItem }) return
            val camIndex = items.indexOfFirst { it.b().contains("拍") }
            val at = if (camIndex >= 0) camIndex + 1 else items.size
            items.add(at, RecordMenuItem(this))
            // 音频文件 goes right below 录像.
            items.add(at + 1, AudioMenuItem(this))
            // @成员 only in group chats; placed at the top of the panel.
            if (CurrentContact.isGroup) items.add(0, MentionMenuItem(this))
            // 表情 — only when shown as the input bar "+" overlay (the native emoji button is
            // replaced by "+", so emoji moves into this list). Placed at the very top.
            if (Settings.attachmentOverlay.value && items.none { it is EmojiMenuItem }) {
                items.add(0, EmojiMenuItem())
            }
            // 贴纸 — our store-sticker (商城表情) picker. Just below 表情.
            if (items.none { it is StickerMenuItem }) {
                val emojiAt = items.indexOfFirst { it is EmojiMenuItem }
                items.add(if (emojiAt >= 0) emojiAt + 1 else 0, StickerMenuItem(this))
            }
            // Apply the user's 菜单自定义 order + visibility to the assembled panel.
            momoi.mod.qqpro.hook.menu.arrangeAttachmentItems(items)
            adapter.notifyDataSetChanged()
            Utils.log("MenuPanelLayout: injected 录像/音频文件 at $at")
        }.onFailure { Utils.log("MenuPanelLayout: inject 录像 failed: $it") }
    }

    private fun restyleCell(cell: View) {
        val ll = cell as? LinearLayout ?: return
        var icon: ImageView? = null
        var label: TextView? = null
        for (i in 0 until ll.childCount) {
            val c = ll.getChildAt(i)
            if (icon == null && c is ImageView) icon = c
            if (label == null && c is TextView) label = c
        }
        if (icon == null || label == null) return

        val labelText = label.text?.toString().orEmpty()
        val material = Settings.materialAttachmentMenu.value

        ll.orientation = LinearLayout.HORIZONTAL
        ll.gravity = Gravity.CENTER_VERTICAL
        if (material) {
            // A plain ripple row INSIDE the single surface card (the RecyclerView) — no per-row card.
            ll.background = M3.ripple(null, 0x33_888888)
            // Tighter vertical padding → smaller gap between rows.
            ll.setPadding(16.dp, 8.dp, 16.dp, 8.dp)
        } else {
            // R.drawable.watch_normal_button_white_bg — the original per-row (non-material) card.
            ll.background = androidx.core.content.ContextCompat.getDrawable(ll.context, 0x7e080ea8)
                ?: roundCornerDrawable(0x80_242424.toInt(), 14.dpf)
            ll.cardMargin()
            ll.setPadding(14.dp, 10.dp, 14.dp, 10.dp)
        }

        // Icon on the left: Material symbol (accent) or the original hand-drawn drawable.
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.setImageDrawable(
            if (material) MaterialSymbol(materialSymbolFor(labelText), M3.primary) else iconFor(labelText))
        icon.layoutParams = LinearLayout.LayoutParams(28.dp, 28.dp)

        // Label fills the rest, left-aligned.
        label.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        label.textSize = 14f
        label.setTextColor(M3.onSurface)
        label.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 12.dp
        }

        // Voice / video calls go through a confirmation first to avoid accidental triggers;
        // everything else forwards the tap straight to the icon's existing handler.
        val label0 = label.text?.toString().orEmpty()
        when {
            label0.contains("通话") -> {
                icon.isClickable = false
                ll.clickable { confirmCall(icon, label0) }
            }
            // 拍照 with in-app camera off → launch the system camera app instead.
            label0.contains("拍") && !Settings.useInAppCamera.value -> {
                icon.isClickable = false
                ll.clickable { launchSystemPhoto(this) }
            }
            // 相册 with system picker on → launch the system photo/SAF picker instead of QQ's gallery.
            label0.contains("相册") && Settings.useSystemImagePicker.value -> {
                icon.isClickable = false
                ll.clickable { launchSystemImagePicker(this) }
            }
            // 相册 with system picker off → QQ's native gallery. Flag it as a chat launch so
            // GalleryMultiSelect installs our send interception; other gallery callers (avatar /
            // status pickers) never set this flag and keep QQ's native fragment-result behavior.
            label0.contains("相册") -> ll.clickable {
                GalleryMultiSelectState.chatLaunch = true
                icon.performClick()
            }
            else -> ll.clickable { icon.performClick() }
        }
    }

    private fun confirmCall(action: View, label: String) {
        runCatching {
            CallConfirmFragment("确定要发起$label 吗？", action)
                .show(parentFragmentManager, "qqpro_call_confirm")
        }.onFailure { Utils.log("call confirm show failed: $it") }
    }

    /** Material Symbol path per attachment item label (matches the long-press menu's icon set). */
    private fun materialSymbolFor(text: String): String = when {
        text.contains("贴纸") -> MaterialSymbols.interests
        text.contains("表情") -> MaterialSymbols.mood
        text.contains("艾特") -> MaterialSymbols.alternate_email
        text.contains("音频") -> MaterialSymbols.audio_file
        text.contains("录") -> MaterialSymbols.videocam
        text.contains("相册") -> MaterialSymbols.image
        text.contains("拍") -> MaterialSymbols.photo_camera
        text.contains("视频") -> MaterialSymbols.videocam
        text.contains("语音") || text.contains("通话") -> MaterialSymbols.call
        else -> MaterialSymbols.image
    }

    private fun iconFor(text: String) = when {
        text.contains("贴纸") -> MaterialSymbol(MaterialSymbols.interests, M3.primary)
        text.contains("表情") -> emojiIconDrawable()
        text.contains("艾特") -> atIconDrawable()
        text.contains("音频") -> audioFileIconDrawable()
        text.contains("录") -> recordIconDrawable()
        text.contains("相册") -> galleryIconDrawable()
        text.contains("拍") -> cameraIconDrawable()
        text.contains("视频") -> videoIconDrawable()
        text.contains("语音") || text.contains("通话") -> phoneIconDrawable()
        else -> galleryIconDrawable()
    }
}

/**
 * A panel item that records a video. Inserted after 拍照. When the in-app camera setting is on it
 * uses the built-in [VideoRecordFragment]; otherwise it falls back to the system camera app.
 */
class RecordMenuItem(
    private val fragment: androidx.fragment.app.Fragment
) : com.tencent.watch.aio_impl.ui.frames.MenuItem() {
    override fun a() = 0
    override fun b() = "录像"
    override fun d() = 2
    override fun e() {
        if (Settings.useInAppCamera.value) {
            runCatching {
                momoi.mod.qqpro.hook.view.VideoRecordFragment(fragment)
                    .show(fragment.parentFragmentManager, "qqpro_video_record")
            }.onFailure {
                Utils.log("record: show in-app recorder failed: $it")
                launchSystemVideo(fragment)
            }
        } else {
            launchSystemVideo(fragment)
        }
        // The in-app recorder is a dialog over the chat — it doesn't pause the AIO fragment, so
        // the overlay's onPause auto-close never fires. Close the overlay now (the recorder dialog
        // is already shown and independent; it doesn't use the MenuFrame host except for a page-0
        // switch that the overlay feature doesn't need).
        AttachmentOverlay.dismiss()
    }
}

/**
 * A panel item that picks an audio file from local storage and sends it as a voice (PTT) message.
 * Inserted right below 录像.
 */
class AudioMenuItem(
    private val fragment: androidx.fragment.app.Fragment
) : com.tencent.watch.aio_impl.ui.frames.MenuItem() {
    override fun a() = 0
    override fun b() = "音频文件"
    override fun d() = 2
    override fun e() {
        if (Settings.useSystemAudioPicker.value) {
            runCatching { launchPickAudio(fragment) }
                .onFailure { Utils.log("audio: launch system picker from menu failed: $it") }
        } else {
            // In-app audio browser. It's a dialog over the chat — dismiss the attachment overlay
            // now (same as RecordMenuItem), since the AIO fragment isn't paused so the overlay's
            // onPause auto-close never fires.
            runCatching {
                momoi.mod.qqpro.hook.view.AudioPickerFragment()
                    .show(fragment.parentFragmentManager, "qqpro_audio_picker")
            }.onFailure {
                Utils.log("audio: show in-app picker failed, falling back to system picker: $it")
                launchPickAudio(fragment)
            }
            AttachmentOverlay.dismiss()
        }
    }
}

/**
 * A panel item (group chats only) that opens a member list to @-mention someone. Picking a
 * member inserts the @mention into the input box and opens the input method. Shows no
 * invite/add button — selection only.
 */
class MentionMenuItem(
    private val fragment: androidx.fragment.app.Fragment
) : com.tencent.watch.aio_impl.ui.frames.MenuItem() {
    override fun a() = 0
    override fun b() = "艾特成员"
    override fun d() = 2
    override fun e() {
        runCatching {
            MemberPickerFragment().show(fragment.parentFragmentManager, "qqpro_member_picker")
        }.onFailure { Utils.log("mention: show member picker failed: $it") }
    }
}

/**
 * A panel item (overlay mode only) that opens the native emoji/sticker panel. Replaces the
 * input bar's old emoji button, which is now the "+" that opens this overlay. Tapping it
 * dismisses the overlay and fires the native emoji button's click via [AttachmentOverlay.emojiAction].
 */
class EmojiMenuItem : com.tencent.watch.aio_impl.ui.frames.MenuItem() {
    override fun a() = 0
    override fun b() = "表情"
    override fun d() = 2
    override fun e() {
        runCatching { AttachmentOverlay.emojiAction?.invoke() }
            .onFailure { Utils.log("emoji menu item failed: $it") }
    }
}

/**
 * A panel item that opens our store-sticker (商城表情) picker — the user's owned packs (synced from
 * the phone), pick one and send it. See [momoi.mod.qqpro.hook.sticker.StickerPickerFragment].
 */
class StickerMenuItem(
    private val fragment: androidx.fragment.app.Fragment
) : com.tencent.watch.aio_impl.ui.frames.MenuItem() {
    override fun a() = 0
    override fun b() = "贴纸"
    override fun d() = 2
    override fun e() {
        runCatching {
            momoi.mod.qqpro.hook.sticker.StickerPickerFragment()
                .show(fragment.parentFragmentManager, "qqpro_sticker_picker")
        }.onFailure { Utils.log("sticker menu item failed: $it") }
        AttachmentOverlay.dismiss()
    }
}
