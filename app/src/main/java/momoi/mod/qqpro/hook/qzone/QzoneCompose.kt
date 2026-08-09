package momoi.mod.qqpro.hook.qzone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.tencent.mobileqq.activity.photo.LocalMediaInfo
import com.tencent.watch.qzone_impl.feed.model.BusinessFeedData
import com.tencent.watch.qzone_impl.frame.IAdapterHost
import com.tencent.watch.qzone_impl.publish.business.model.ImageInfo
import com.tencent.watch.qzone_impl.publish.business.model.MediaWrapper
import com.tencent.watch.qzone_impl.publish.business.model.QzoneShuoShuoParams
import com.tencent.watch.qzone_impl.publish.business.model.ShuoshuoVideoInfo
import com.tencent.watch.qzone_impl.publish.business.publishqueue.QZonePublishQueue
import com.tencent.watch.qzone_impl.publish.business.task.QZoneUploadShuoShuoTask
import com.tencent.watch.qzone_impl.service.QZoneWriteOperationService
import com.tencent.qqnt.watch.contact.api.IContactRuntimeService
import com.tencent.qqnt.watch.ui.componet.permission.PermissionUtils
import downloadExecutor
import mqq.app.MobileQQ
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.hook.view.VideoRecordFragment
import momoi.mod.qqpro.lib.AtTag
import momoi.mod.qqpro.lib.HorizontalDragWidget
import momoi.mod.qqpro.lib.bitmapDecodeFile
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.lib.material.M3QQEditText
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.symbolImage
import momoi.mod.qqpro.util.Utils
import java.io.File
import java.util.UUID

/**
 * Single-page Material 3 QZone publish/compose screen ([Settings.materializeQzone]). One M3QQEditText
 * (type or hold-to-mic) plus a horizontally-scrollable chip row — 图片 / 拍照 / 录视频 / @ / 位置 —
 * each opening a clean system flow (gallery / camera / video capture / location) that OVERLAYS the
 * dialog (via startActivityForResult, not NavController), so the typed text is never lost. The draft
 * (text / media / location) is also persisted across config/process changes.
 *
 * Publishing goes straight through the kernel: text-only → [QZoneWriteOperationService.i]; with media
 * → [QZoneUploadShuoShuoTask] + [QZonePublishQueue]. Location → QzoneShuoShuoParams.d (geo_idname).
 */
object QzoneCompose {
    fun open(host: IAdapterHost) {
        runCatching { ComposeFragment(host).show(host.b().childFragmentManager, "qzcompose") }
            .onFailure { Utils.log("QzoneCompose open: $it") }
    }

    /** Open compose pre-filled with a quote of [data] (watch QZone has no native forward UI). */
    fun openRepost(host: IAdapterHost, data: BusinessFeedData) {
        val author = runCatching { (data.originalInfo ?: data).user?.nickName }.getOrNull()
        val quote = QzoneActions.postText(data)
        val prefill = if (author != null) "//@$author: $quote" else "//$quote"
        runCatching { ComposeFragment(host, prefill).show(host.b().childFragmentManager, "qzrepost") }
            .onFailure { Utils.log("QzoneCompose repost: $it") }
    }
}

/** Horizontal scroller marked as a [HorizontalDragWidget] so [SwipeBackLayout] yields the horizontal
 *  gesture to it (chip row scroll) instead of triggering swipe-back dismiss. */
class HChipScroll(ctx: Context) : HorizontalScrollView(ctx), HorizontalDragWidget

class ComposeFragment(
    private val host: IAdapterHost? = null,
    private val prefill: String = "",
) : MyDialogFragment() {

    constructor() : this(null, "")

    companion object {
        private const val REQ_GALLERY = 4101
        private const val REQ_PHOTO = 4102
        private const val REQ_LOCATION_PERM = 4104
    }

    private val media = ArrayList<LocalMediaInfo>()
    private var draftText: String = prefill
    private var locationName: String? = null
    private var captureFile: File? = null
    private val atUins = HashMap<String, Long>()   // uid → uin, for @{uin:..,nick:..} on send

    private var input: M3QQEditText? = null
    private var thumbStrip: LinearLayout? = null
    private var locationChip: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        savedInstanceState?.let {
            draftText = it.getString("draft") ?: draftText
            locationName = it.getString("loc") ?: locationName
            // media survives via the instance field across view recreation (the picker-overlay case).
        }
        val ctx = inflater.context
        val edge = if (Utils.isRoundScreen) 16.dp else 8.dp
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(edge, 6.dp, edge, 8.dp)
        }

        // top bar: title + 发表
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(ctx).apply { text = "发表说说"; setTextColor(M3.onSurface); textSize = 15f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(M3Button(ctx).apply {
            text = "发表"; variant(M3Button.Variant.FILLED); setOnClickListener { onSend() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val edit = M3QQEditText(ctx).apply {
            setHint("这一刻的想法…")
            setMultiline(true)
            setFieldTextSize(12)
            editText.maxLines = Integer.MAX_VALUE   // grow infinitely; the page ScrollView handles overflow
            editText.isVerticalScrollBarEnabled = false
            permissionFragmentProvider = { this@ComposeFragment }
            if (draftText.isNotEmpty()) setText(draftText)
        }
        input = edit
        body.addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })

        // selected-image thumbnails
        val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        thumbStrip = strip
        body.addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10.dp })

        // location chip (shown when a location is attached)
        locationChip = TextView(ctx).apply {
            setTextColor(M3.onSurfaceVariant); textSize = 12f
            background = M3.rounded(M3.surfaceContainerHigh, M3.radiusPill)
            setPadding(12.dp, 6.dp, 12.dp, 6.dp); visibility = View.GONE; isClickable = true
            setOnClickListener { locationName = null; refreshLocationChip() }
        }
        body.addView(locationChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })

        // horizontally-scrollable action chips
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chips.addView(chip(ctx, MaterialSymbols.image, "图片") { pickGallery() })
        chips.addView(chip(ctx, MaterialSymbols.photo_camera, "拍照") { onPhoto() })
        chips.addView(chip(ctx, MaterialSymbols.play_arrow, "录视频") { onVideo() })
        chips.addView(chip(ctx, MaterialSymbols.person, "@") { onAt() })
        chips.addView(chip(ctx, MaterialSymbols.location_on, "位置") { pickLocation() })
        // HorizontalDragWidget so SwipeBackLayout doesn't steal the horizontal scroll for swipe-back.
        val scroller = HChipScroll(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        body.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10.dp })

        val scroll = ScrollView(ctx).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        refreshThumbs()
        refreshLocationChip()
        return swipeBackWrap(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draft", currentText())
        outState.putString("loc", locationName)
    }

    private fun currentText(): String = input?.editText?.text?.toString() ?: draftText

    private fun chip(ctx: Context, symbol: String, label: String, onClick: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = M3.rounded(M3.surfaceContainerHigh, M3.radiusPill)
            setPadding(12.dp, 8.dp, 14.dp, 8.dp); isClickable = true
            setOnClickListener { runCatching { onClick() }.onFailure { Utils.log("QzoneCompose chip $label: $it") } }
        }
        row.addView(symbolImage(ctx, symbol, M3.primary, 18))
        row.addView(TextView(ctx).apply { text = label; setTextColor(M3.onSurface); textSize = 13f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 6.dp })
        return LinearLayout(ctx).apply { addView(row); setPadding(0, 0, 8.dp, 0) }
    }

    // ---- pickers (all overlay the dialog via startActivityForResult → text is preserved) ----

    private fun pickGallery() {
        draftText = currentText()
        // In-app = our own M3 grid picker (a dialog over compose — no nav, keeps the draft & shows
        // previews). 使用系统相册 → the OS picker via an ActivityResult launcher.
        if (Settings.useSystemImagePicker.value) pickSystemGallery()
        else runCatching { QzoneMediaPicker { picks -> addMedia(picks) }.show(childFragmentManager, "qzpicker") }
            .onFailure { Utils.log("QzoneCompose picker: $it"); pickSystemGallery() }
    }

    private fun pickSystemGallery() {
        runCatching {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "选择图片/视频"), REQ_GALLERY)
        }.onFailure { Utils.log("QzoneCompose pickSystemGallery: $it"); Utils.toast(requireContext(), "无法打开相册") }
    }

    /**
     * Merge [newItems] into [media] under the rule: a post is EITHER one video OR multiple images.
     * If any video is being added, keep just that single video; adding images drops any prior video.
     */
    private fun addMedia(newItems: List<LocalMediaInfo>) {
        val video = newItems.firstOrNull { it.C == 1 }
        if (video != null) {
            val hadOthers = media.any { it.c != video.c }
            media.clear(); media.add(video)
            if (hadOthers) Utils.toast(requireContext(), "视频只能单独发送")
        } else {
            if (media.any { it.C == 1 }) media.clear()  // had a video, now adding images
            newItems.filter { it.C != 1 }.forEach { item ->
                if (media.none { it.c == item.c }) media.add(item)
            }
        }
        refreshThumbs()
    }

    /** 拍照: 应用内相机 → our own in-app camera dialog ([VideoRecordFragment] photo mode, no nav, keeps
     *  the draft); otherwise the system camera intent. */
    private fun onPhoto() {
        draftText = currentText()
        if (Settings.useInAppCamera.value) {
            runCatching {
                VideoRecordFragment(this, photo = true) { path -> addMedia(listOf(mediaInfo(path, false))) }
                    .show(childFragmentManager, "qzphoto")
            }.onFailure { Utils.log("QzoneCompose inapp photo: $it"); captureSystemPhoto() }
        } else captureSystemPhoto()
    }

    private fun captureSystemPhoto() {
        runCatching {
            val ctx = requireContext()
            val dir = File(ctx.getExternalFilesDir("qzone_photo") ?: ctx.cacheDir, "")
            dir.mkdirs()
            val f = File(dir, "qz_${System.currentTimeMillis()}.jpg")
            captureFile = f
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, REQ_PHOTO)
        }.onFailure { Utils.log("QzoneCompose captureSystemPhoto: $it"); Utils.toast(requireContext(), "无法打开相机") }
    }

    /** 录视频: the in-app recorder ([VideoRecordFragment]) as a dialog; returns the path via callback. */
    private fun onVideo() {
        draftText = currentText()
        runCatching {
            VideoRecordFragment(this) { path -> addMedia(listOf(mediaInfo(path, true))) }
                .show(childFragmentManager, "qzvideo")
        }.onFailure { Utils.log("QzoneCompose onVideo: $it"); Utils.toast(requireContext(), "无法录像") }
    }

    /** @ : open our own friend-picker dialog (over compose — no nav, so the draft isn't lost) and
     *  insert "@nick" on selection. */
    private fun onAt() {
        draftText = currentText()
        runCatching {
            QzoneFriendPicker { uid, uin, nick -> insertAtToken(uid, uin, nick) }
                .show(childFragmentManager, "qzfriend")
        }.onFailure { Utils.log("QzoneCompose onAt: $it"); Utils.toast(requireContext(), "无法打开好友列表") }
    }

    /** Insert "@nick " as an atomic [AtTag] token (ImeEditText snaps the caret out of it and a single
     *  backspace removes the whole mention — same behaviour as the chat input). The uin is remembered
     *  so [onSend] can encode a real @{uin:..,nick:..} mention. */
    private fun insertAtToken(uid: String, uin: Long, nick: String) {
        atUins[uid] = uin
        val et = input?.editText
        val label = "@$nick "
        if (et == null) { draftText = currentText() + label; return }
        val ed = et.text ?: run { insertText(label); return }
        val pos = et.selectionStart.coerceIn(0, ed.length)
        ed.insert(pos, label)
        runCatching {
            ed.setSpan(AtTag(uid, nick, 2), pos, pos + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ed.setSpan(ForegroundColorSpan(M3.primary), pos, pos + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        et.setSelection((pos + label.length).coerceAtMost(ed.length))
        et.requestFocus()
        draftText = ed.toString()
    }

    private fun insertText(s: String) {
        // Keep the draft in sync so the text survives if the dialog's view was recreated by the
        // friend-selector navigation (then onCreateView re-applies draftText).
        draftText = currentText() + s
        val et = input?.editText
        if (et != null) {
            val pos = et.selectionStart.coerceAtLeast(0)
            runCatching { et.text?.insert(pos, s) }
            et.requestFocus()
        } else {
            Utils.log("QzoneCompose insertText: input null, stored in draft ('$s')")
        }
    }

    private fun pickLocation() {
        val ctx = requireContext()
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            openLocationPicker()
            return
        }
        // Not granted: this watch ROM has no OS permission dialog — and QQ's Soso also gates on its own
        // internal QQLocationSetting consent (a bare `pm grant` of the Android permission isn't enough).
        // QQ's PermissionUtils drives both via the in-app permissionFragment. It navigates (destroying
        // this compose dialog), which is fine — permission is a one-time grant.
        val navFragment = host?.b()
        if (navFragment == null) { Utils.toast(ctx, "无法请求位置权限"); return }
        val appCtx = ctx.applicationContext
        Utils.log("QzoneCompose pickLocation: requesting permission via PermissionUtils")
        runCatching {
            PermissionUtils.a.a(
                "位置功能需要获取位置权限",
                navFragment,
                listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            ) { ok ->
                Utils.log("QzoneLocation perm result: $ok")
                Utils.toast(appCtx, if (ok) "已获取位置权限，请重新选择位置" else "未获得位置权限")
            }
        }.onFailure { Utils.log("QzoneCompose pickLocation perm: $it"); Utils.toast(ctx, "无法请求位置权限") }
    }

    private fun openLocationPicker() {
        Utils.log("QzoneCompose pickLocation: opening picker")
        runCatching {
            QzoneLocationPicker { name ->
                locationName = name
                refreshLocationChip()
            }.show(childFragmentManager, "qzloc")
        }.onFailure { Utils.log("QzoneCompose openLocationPicker: $it") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // location: picker already opened in pickLocation(); nothing to re-trigger here.
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Utils.log("QzoneCompose onActivityResult req=$requestCode result=$resultCode")
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            REQ_GALLERY -> addFromGallery(data)
            REQ_PHOTO -> captureFile?.let { addCaptured(it, false) }
        }
    }


    private fun addFromGallery(data: Intent?) {
        val uris = ArrayList<Uri>()
        data?.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { uris.add(it) } }
        data?.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        val ctx = requireContext().applicationContext
        downloadExecutor.execute {
            val added = ArrayList<LocalMediaInfo>()
            for (uri in uris) {
                val isVideo = runCatching { ctx.contentResolver.getType(uri)?.startsWith("video") == true }.getOrDefault(false)
                val f = copyUriToFile(ctx, uri, isVideo) ?: continue
                added.add(mediaInfo(f.absolutePath, isVideo))
            }
            if (added.isNotEmpty()) {
                runCatching { activity?.runOnUiThread { addMedia(added) } }
            }
        }
    }

    private fun addCaptured(f: File, video: Boolean) {
        if (!f.exists() || f.length() == 0L) return
        addMedia(listOf(mediaInfo(f.absolutePath, video)))
    }

    private fun copyUriToFile(ctx: Context, uri: Uri, video: Boolean): File? = runCatching {
        val dir = File(ctx.getExternalFilesDir(if (video) "qzone_video" else "qzone_photo") ?: ctx.cacheDir, "")
        dir.mkdirs()
        val f = File(dir, "qzpick_${System.currentTimeMillis()}_${uri.hashCode()}.${if (video) "mp4" else "jpg"}")
        ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        f.takeIf { it.length() > 0 }
    }.getOrNull()

    private fun mediaInfo(path: String, video: Boolean): LocalMediaInfo = LocalMediaInfo().apply {
        c = path
        C = if (video) 1 else 0
        if (!video) runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            E = o.outWidth; F = o.outHeight
        }
    }

    private fun refreshThumbs() {
        val strip = thumbStrip ?: return
        val ctx = strip.context
        strip.removeAllViews()
        media.take(9).forEach { mi ->
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = M3.rounded(M3.surfaceContainerHigh, M3.radiusSm)
                isClickable = true
                setOnClickListener { media.remove(mi); refreshThumbs() }   // tap a preview to remove it
            }
            val path = mi.c
            if (mi.C == 1) {
                downloadExecutor.execute {
                    val bmp = runCatching {
                        @Suppress("DEPRECATION")
                        android.media.ThumbnailUtils.createVideoThumbnail(path ?: "", MediaStore.Images.Thumbnails.MINI_KIND)
                    }.getOrNull()
                    iv.post { if (bmp != null) iv.setImageBitmap(bmp) }
                }
            } else {
                runCatching { path?.let { iv.bitmapDecodeFile(File(it)) } }
            }
            strip.addView(iv, LinearLayout.LayoutParams(56.dp, 56.dp).apply { marginEnd = 6.dp })
        }
        if (media.isNotEmpty()) strip.addView(TextView(ctx).apply {
            text = "已选 ${media.size} · 点击移除"; setTextColor(M3.onSurfaceTip); textSize = 10f
        })
    }

    private fun refreshLocationChip() {
        val chip = locationChip ?: return
        val name = locationName
        if (name.isNullOrBlank()) { chip.visibility = View.GONE; return }
        chip.visibility = View.VISIBLE
        chip.text = "📍 $name  ✕"
    }

    private fun onSend() {
        // Encode @ tokens as real @{uin:..,nick:..} mentions (plain "@nick" text doesn't notify).
        val ed = input?.editText?.text
        val text = (if (ed != null) buildTextWithMentions(ed) else currentText()).trim()
        if (text.isBlank() && media.isEmpty()) { Utils.toast(requireContext(), "发布内容为空"); return }
        val ok = runCatching {
            val params = QzoneShuoShuoParams()
            params.a = text
            locationName?.takeIf { it.isNotBlank() }?.let { params.d = hashMapOf("geo_idname" to it) }
            if (media.isNotEmpty()) {
                params.c = ArrayList(media)
                params.b = media.mapNotNull { it.c }
                params.e = buildMediaWrappers(text)   // the list the uploader actually reads
                val task = QZoneUploadShuoShuoTask(6, 1, params)
                task.uploadEntrance = 0
                task.refer = null
                runCatching { task.javaClass.getField("clientKey").set(task, UUID.randomUUID().toString()) }
                QZonePublishQueue.e().b(task)
            } else {
                QZoneWriteOperationService.h().i(params)
            }
            Utils.log("QzoneCompose: published (textLen=${text.length}, media=${media.size}, loc=$locationName)")
        }.onFailure { Utils.log("QzoneCompose send: $it") }.isSuccess
        if (ok) {
            Utils.toast(requireContext(), "已发表")
            runCatching { dismiss() }
            // The new post lands on the feed only after the engine reloads — pull-refresh it (a media
            // post uploads via the queue, so give it a little longer than a text-only post).
            QzoneFeedM3.refreshActiveFeed(if (media.isNotEmpty()) 2500L else 1200L)
        } else Utils.toast(requireContext(), "发表失败")
    }

    /** Walk the editor text, replacing each atomic [AtTag] run with @{uin:..,nick:..}. */
    private fun buildTextWithMentions(ed: android.text.Editable): String {
        val sb = StringBuilder()
        var i = 0
        while (i < ed.length) {
            val tag = ed.getSpans(i, i + 1, AtTag::class.java).firstOrNull { ed.getSpanStart(it) == i }
            if (tag != null) {
                val uin = atUins[tag.uid] ?: 0L
                sb.append("@{uin:$uin,nick:${tag.nick}}")
                i = ed.getSpanEnd(tag)
            } else { sb.append(ed[i]); i++ }
        }
        return sb.toString()
    }

    /** Build the MediaWrapper list the publish task uploads (image → ImageInfo, video → ShuoshuoVideoInfo). */
    private fun buildMediaWrappers(desc: String): ArrayList<MediaWrapper> {
        val out = ArrayList<MediaWrapper>()
        media.forEach { mi ->
            val path = mi.c ?: return@forEach
            if (mi.C == 1) {
                val v = ShuoshuoVideoInfo()
                v.mVideoPath = path
                v.mSize = runCatching { File(path).length() }.getOrDefault(0L)
                v.mVideoType = 1
                v.mIsNew = 102
                v.mEstimateSize = runCatching { File(path).length().toDouble() }.getOrDefault(0.0)
                out.add(MediaWrapper(v))
            } else {
                val img = ImageInfo(path)
                img.mDescription = desc
                out.add(MediaWrapper(img))
            }
        }
        return out
    }
}
