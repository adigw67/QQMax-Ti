package momoi.mod.qqpro.hook

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.PttElement
import com.tencent.qqnt.kernel.nativeinterface.QQNTWrapperUtil
import com.tencent.qqnt.kernel.nativeinterface.RichMediaFilePathInfo
import com.tencent.qqnt.kernel.nativeinterface.VideoElement
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.watch.aio_impl.ext.FileUtils
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.GalleryMultiSelectState
import momoi.mod.qqpro.util.Utils
import java.io.File
import java.io.FileOutputStream

/**
 * Capture a photo/video with the SYSTEM camera app (via intent) and send it to the current chat.
 * Used when the "use in-app camera" setting is off. When that setting is on, photos use QQ's
 * in-app CameraFragment and video uses our own VideoRecordFragment ([sendInAppVideo]).
 *
 * These are plain top-level functions (NOT inside a @Mixin body) so the background threads /
 * callbacks they create live in this package and won't hit the @Mixin anonymous-class issue.
 */

const val REQ_SYS_PHOTO = 0x7301
const val REQ_SYS_VIDEO = 0x7302
const val REQ_PICK_AUDIO = 0x7303
const val REQ_PICK_IMAGES = 0x7304

object CameraCapture {
    var pendingPhotoPath: String? = null
    var pendingVideoPath: String? = null
}

/**
 * Resolve the stable host fragment that should issue startActivityForResult / receive the result.
 * The menu panel ([MenuFrame]) is torn down by the attachment overlay's onPause when a picker
 * activity opens, so a result delivered to it is lost. Walk up to the [WatchAIOFragment] (the chat
 * fragment), which stays attached across the picker activity and whose onActivityResult is hooked.
 */
private fun resultHost(fragment: Fragment): Fragment {
    var f: Fragment? = fragment
    while (f != null) {
        if (f is com.tencent.watch.aio_impl.ui.WatchAIOFragment) return f
        f = f.parentFragment
    }
    return fragment
}

private fun outputFileAndUri(fragment: Fragment, subDir: String, ext: String): Pair<String, Uri> {
    val ctx = fragment.requireContext()
    val dir = ctx.getExternalFilesDir(subDir) ?: ctx.filesDir
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "qqpro_${System.currentTimeMillis()}.$ext")
    if (file.exists()) file.delete()
    file.createNewFile()
    val authority = ctx.applicationContext.packageName + ".fileprovider"
    return file.path to FileProvider.getUriForFile(ctx, authority, file)
}

fun launchSystemPhoto(fragment: Fragment) {
    runCatching {
        val (path, uri) = outputFileAndUri(fragment, "photos", "jpg")
        CameraCapture.pendingPhotoPath = path
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        resultHost(fragment).startActivityForResult(intent, REQ_SYS_PHOTO)
        Utils.log("camera: launch system photo -> $path")
    }.onFailure {
        Utils.log("camera: launch system photo failed: $it")
        runCatching { Utils.toast(fragment.requireContext(), "未找到可拍照的相机应用") }
    }
}

fun launchSystemVideo(fragment: Fragment) {
    runCatching {
        val (path, uri) = outputFileAndUri(fragment, "videos", "mp4")
        CameraCapture.pendingVideoPath = path
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        resultHost(fragment).startActivityForResult(intent, REQ_SYS_VIDEO)
        Utils.log("camera: launch system video -> $path")
    }.onFailure {
        Utils.log("camera: launch system video failed: $it")
        runCatching { Utils.toast(fragment.requireContext(), "未找到可录像的相机应用") }
    }
}

/** Open the system file picker to choose an audio file to send as a voice message. */
fun launchPickAudio(fragment: Fragment) {
    runCatching {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .setType("audio/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
        resultHost(fragment).startActivityForResult(intent, REQ_PICK_AUDIO)
        Utils.log("audio: launch picker")
    }.onFailure {
        Utils.log("audio: launch picker failed: $it")
        runCatching { Utils.toast(fragment.requireContext(), "未找到可选择音频的应用") }
    }
}

/**
 * Open the system image picker to choose one or more images to send. Prefers the Android photo
 * picker (ACTION_PICK_IMAGES, no permission needed) when the device provides it, and falls back
 * to the SAF document picker (ACTION_GET_CONTENT) otherwise. Both are configured for multi-select.
 */
fun launchSystemImagePicker(fragment: Fragment) {
    runCatching {
        val pm = fragment.requireContext().packageManager
        // Photo picker (API 33+, or backported on some devices). Prefer it when resolvable.
        // No type filter on the photo picker → it shows BOTH images and videos.
        val photoPicker = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 9)
        }
        val intent = if (photoPicker.resolveActivity(pm) != null) {
            Utils.log("imagepick: using system photo picker")
            photoPicker
        } else {
            Utils.log("imagepick: photo picker unavailable, using SAF")
            Intent(Intent.ACTION_GET_CONTENT)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        resultHost(fragment).startActivityForResult(intent, REQ_PICK_IMAGES)
    }.onFailure {
        Utils.log("imagepick: launch failed: $it")
        runCatching { Utils.toast(fragment.requireContext(), "未找到可选择图片的应用") }
    }
}

/** Collect all picked URIs from a picker result (handles both single and multi-select). */
private fun pickedUris(data: Intent?): List<Uri> {
    val out = ArrayList<Uri>()
    val clip = data?.clipData
    if (clip != null) {
        for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { out.add(it) }
    } else {
        data?.data?.let { out.add(it) }
    }
    return out
}

/** Handle a system capture result. Returns true if [requestCode] was one of ours. */
fun handleCaptureResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    val fallback = data?.data
    when (requestCode) {
        REQ_PICK_AUDIO -> {
            val uri = data?.data
            if (resultCode == -1 && uri != null) {
                GalleryMultiSelectState.goToChatOnResume = true
                Thread { sendAudio(uri) }.start()
            }
            return true
        }
        REQ_PICK_IMAGES -> {
            val uris = pickedUris(data)
            if (resultCode == -1 && uris.isNotEmpty()) {
                GalleryMultiSelectState.goToChatOnResume = true
                Thread { sendPickedImages(uris) }.start()
            }
            return true
        }
        REQ_SYS_PHOTO -> {
            val path = CameraCapture.pendingPhotoPath
            CameraCapture.pendingPhotoPath = null
            if (resultCode == -1 && path != null) {
                // System camera is a separate activity; when the chat (WatchAIOFragment) resumes,
                // switch the ViewPager back to the chat page — same mechanism multi-select uses.
                GalleryMultiSelectState.goToChatOnResume = true
                Thread { sendPhoto(path, fallback) }.start()
            }
            return true
        }
        REQ_SYS_VIDEO -> {
            val path = CameraCapture.pendingVideoPath
            CameraCapture.pendingVideoPath = null
            if (resultCode == -1 && path != null) {
                GalleryMultiSelectState.goToChatOnResume = true
                Thread { sendVideo(path, fallback) }.start()
            }
            return true
        }
    }
    return false
}

private fun ensureFile(path: String, fallback: Uri?): Boolean {
    val file = File(path)
    if ((!file.exists() || file.length() == 0L) && fallback != null) {
        runCatching {
            Utils.application.contentResolver.openInputStream(fallback)?.use { input ->
                FileOutputStream(file).use { input.copyTo(it) }
            }
        }.onFailure { Utils.log("camera: copy from result uri failed: $it") }
    }
    return file.exists() && file.length() > 0L
}

/**
 * Send a video recorded by the in-app recorder ([VideoRecordFragment]). Runs the build/send on a
 * background thread (off the UI thread) and flags the chat to return to page 0 on resume.
 */
fun sendInAppVideo(path: String) {
    GalleryMultiSelectState.goToChatOnResume = true
    Thread { sendVideo(path, null) }.start()
    Utils.log("camera: send in-app recorded video -> $path")
}

private fun send(element: MsgElement) {
    MsgUtil.msgService.sendMsg(
        CurrentContact, 0L, arrayListOf(element),
        IOperateCallback { code, msg -> Utils.log("camera send result=$code msg=$msg") }
    )
}

private fun sendPhoto(path: String, fallback: Uri?) {
    runCatching {
        if (!ensureFile(path, fallback)) { Utils.log("camera: photo empty $path"); return }
        send(com.tencent.watch.aio_impl.ext.MsgUtil().a(path, 0))
    }.onFailure { Utils.log("camera: sendPhoto failed: $it") }
}

/**
 * Copy each picked image [uris] into our files dir and send them to the current chat as a single
 * message (multiple image elements), mirroring QQ's own multi-image send. Runs on a background
 * thread; the copy must finish before the send, so it is done here rather than via the IME preview.
 */
private fun sendPickedImages(uris: List<Uri>) {
    runCatching {
        val ctx = Utils.application
        val dir = ctx.getExternalFilesDir("photos") ?: ctx.filesDir
        if (!dir.exists()) dir.mkdirs()
        val elements = ArrayList<MsgElement>()
        for ((idx, uri) in uris.withIndex()) {
            runCatching {
                val mime = ctx.contentResolver.getType(uri) ?: ""
                val isVideo = mime.startsWith("video/")
                val ext = when {
                    mime == "image/png" -> "png"
                    mime == "image/gif" -> "gif"
                    mime == "image/webp" -> "webp"
                    mime == "video/3gpp" -> "3gp"
                    mime == "video/webm" -> "webm"
                    isVideo -> "mp4"
                    else -> "jpg"
                }
                val file = File(dir, "qqpro_${System.currentTimeMillis()}_$idx.$ext")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { input.copyTo(it) }
                }
                if (file.exists() && file.length() > 0L) {
                    // Build a video element for videos, an image (pic) element otherwise, so a mixed
                    // image+video selection all sends correctly in one message.
                    elements.add(
                        if (isVideo) buildVideoElement(file.path)
                        else com.tencent.watch.aio_impl.ext.MsgUtil().a(file.path, 0)
                    )
                } else {
                    Utils.log("imagepick: empty $uri")
                }
            }.onFailure { Utils.log("imagepick: copy/build failed for $uri: $it") }
        }
        if (elements.isEmpty()) { Utils.log("imagepick: nothing to send"); return }
        MsgUtil.msgService.sendMsg(
            CurrentContact, 0L, ArrayList(elements),
            IOperateCallback { code, msg -> Utils.log("imagepick send result=$code msg=$msg count=${elements.size}") }
        )
    }.onFailure { Utils.log("imagepick: sendPickedImages failed: $it") }
}

/**
 * Build+send a video picked from the in-app gallery to the current chat. The native gallery tap
 * path only pops the picker without sending in our launch context, so we send it ourselves. Runs
 * the (heavy) build/send on a background thread and flags the chat to return to page 0 on resume.
 */
fun sendGalleryVideo(path: String) {
    GalleryMultiSelectState.goToChatOnResume = true
    Thread { sendVideo(path, null) }.start()
    Utils.log("gallery: send video -> $path")
}

private fun sendVideo(origPath: String, fallback: Uri?) {
    runCatching {
        if (!ensureFile(origPath, fallback)) { Utils.log("camera: video empty $origPath"); return }
        send(buildVideoElement(origPath))
    }.onFailure { Utils.log("camera: sendVideo failed: $it") }
}

/**
 * Send an audio [uri] picked by the in-app audio browser ([AudioPickerFragment]) to the current
 * chat as a voice (PTT). Runs the copy/build/send on a background thread. The in-app browser is a
 * dialog over the chat (it doesn't pause the AIO fragment), so no page switch is needed.
 */
fun sendPickedAudio(uri: Uri) {
    Thread { sendAudio(uri) }.start()
    Utils.log("audio: send in-app picked -> $uri")
}

/** Copy a picked audio [uri] into our files dir and send it to the current chat as a voice (PTT). */
private fun sendAudio(uri: Uri) {
    runCatching {
        val ctx = Utils.application
        val ext = when {
            uri.toString().endsWith(".amr", true) -> "amr"
            uri.toString().endsWith(".silk", true) || uri.toString().endsWith(".slk", true) -> "silk"
            uri.toString().endsWith(".m4a", true) -> "m4a"
            else -> "mp3"
        }
        val dir = ctx.getExternalFilesDir("audios") ?: ctx.filesDir
        if (!dir.exists()) dir.mkdirs()
        val src = File(dir, "qqpro_${System.currentTimeMillis()}.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(src).use { input.copyTo(it) }
        }
        if (!src.exists() || src.length() == 0L) { Utils.log("audio: empty $uri"); return }
        send(buildPttElement(src.path))
    }.onFailure { Utils.log("audio: sendAudio failed: $it") }
}

/**
 * Replicates AudioTouchViewNTProcessor's PTT-element build sequence for a local audio file.
 *
 * [knownDurationMs] lets the caller pass an accurate duration (e.g. the recorder's totalTime). When
 * it is negative the duration is probed with [MediaMetadataRetriever], which works for amr/mp3/m4a
 * but NOT for SILK (the watch's native recording format), so recorded SILK voices must pass it in.
 */
internal fun buildPttElement(origPath: String, knownDurationMs: Long = -1L): MsgElement {
    val md5 = QQNTWrapperUtil.CppProxy.genFileMd5Hex(origPath)
    val svc = KernelServiceUtil.c()
    val sendPath = svc?.getRichMediaFilePathForMobileQQSend(
        RichMediaFilePathInfo(4, 3, md5, FileUtils().a(origPath), 1, 0, null, "", true)
    ) ?: ""
    if (!QQNTWrapperUtil.CppProxy.fileIsExist(sendPath)) {
        com.tencent.qqnt.util.file.FileUtils.b(origPath, sendPath)
    }

    val durationMs = if (knownDurationMs >= 0) knownDurationMs else runCatching {
        val r = MediaMetadataRetriever()
        r.setDataSource(origPath)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    }.getOrDefault(0L)

    val ptt = PttElement()
    ptt.fileName = FileUtils().a(sendPath)
    ptt.filePath = sendPath
    ptt.md5HexStr = QQNTWrapperUtil.CppProxy.genFileMd5Hex(sendPath)
    ptt.fileSize = QQNTWrapperUtil.CppProxy.getFileSize(sendPath)
    ptt.duration = Math.max(1, Math.round(durationMs / 1000.0).toInt())
    ptt.formatType = if (origPath.endsWith(".amr", true)) 0 else 1
    ptt.voiceType = 2
    ptt.voiceChangeType = 0
    ptt.canConvert2Text = false
    ptt.fileId = 0
    ptt.fileUuid = ""
    ptt.text = ""
    ptt.waveAmplitudes = ArrayList()

    val element = MsgElement()
    element.elementType = 4
    element.pttElement = ptt
    return element
}

/** Replicates MenuFrame's gallery video-element build sequence. */
internal fun buildVideoElement(origPath: String): MsgElement {
    val fileName = FileUtils().a(origPath)
    val md5 = QQNTWrapperUtil.CppProxy.genFileMd5Hex(origPath)
    val svc = KernelServiceUtil.c()

    val videoPath = svc?.getRichMediaFilePathForMobileQQSend(
        RichMediaFilePathInfo(5, 2, md5, fileName, 1, 0, null, "", true)
    ) ?: ""
    if (!QQNTWrapperUtil.CppProxy.fileIsExist(videoPath)) com.tencent.qqnt.util.file.FileUtils.b(origPath, videoPath)

    val thumbPath = svc?.getRichMediaFilePathForMobileQQSend(
        RichMediaFilePathInfo(5, 1, md5, fileName, 2, 0, null, "", true)
    ) ?: ""
    if (!QQNTWrapperUtil.CppProxy.fileIsExist(thumbPath)) {
        runCatching {
            FileOutputStream(thumbPath).use { out ->
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(origPath)
                retriever.frameAtTime?.compress(Bitmap.CompressFormat.JPEG, 60, out)
                out.flush()
            }
        }.onFailure { Utils.log("camera: thumb extract failed: $it") }
    }

    val opts = BitmapFactory.Options()
    BitmapFactory.decodeFile(thumbPath, opts)

    val video = VideoElement()
    video.filePath = videoPath
    video.videoMd5 = QQNTWrapperUtil.CppProxy.genFileMd5Hex(videoPath)
    video.fileTime = 0
    video.fileSize = QQNTWrapperUtil.CppProxy.getFileSize(videoPath)
    video.fileName = FileUtils().a(videoPath)
    video.fileFormat = 2
    video.thumbSize = QQNTWrapperUtil.CppProxy.getFileSize(thumbPath).toInt()
    video.thumbWidth = opts.outWidth
    video.thumbHeight = opts.outHeight
    video.thumbMd5 = QQNTWrapperUtil.CppProxy.genFileMd5Hex(thumbPath)
    video.thumbPath = hashMapOf(0 to thumbPath)

    val element = MsgElement()
    element.elementType = 5
    element.videoElement = video
    return element
}
