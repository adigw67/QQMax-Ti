package momoi.mod.qqpro.hook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.core.content.FileProvider
import com.tencent.mobileqq.aio.msglist.holder.base.PicSize
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ext.AIOPicDownloader
import com.tencent.watch.aio_impl.ui.cell.video.WatchVideoMsgItem
import download
import momoi.mod.qqpro.child
import momoi.mod.qqpro.msg.getImageUrl
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Outgoing half of the two-way share feature: hand the selected message's content to the system
 * share sheet (ACTION_SEND / ACTION_SEND_MULTIPLE) so it can be shared into any other app.
 *
 * Media is resolved with the SAME APIs the built-in "保存" (save-to-album) uses, so whatever can be
 * saved can be shared — original (animated) GIFs included:
 *   - image  → [AIOPicDownloader.d] (the original local path, == WatchPicElementExtKt.C0); if it
 *              isn't on disk yet we trigger the real kernel download [AIOPicDownloader.a] (needs the
 *              [WatchAIOMsgItem]) exactly like 保存 does, falling back to an HTTP fetch.
 *   - video  → WatchPicElementExtKt.b1(videoElement) (the saved local path).
 *   - voice  → PttElement.filePath.
 * Every file is then COPIED into the app's external-files "share" dir (QQ's FileProvider grants
 * external-files-path ".") — handing QQ's own `…/Tencent/MobileQQ/…` paths to FileProvider throws.
 */
fun View.shareMessage(msg: MsgRecord, msgItem: WatchAIOMsgItem?) {
    val ctx = context.applicationContext
    val text = msg.elements
        ?.mapNotNull { it.textElement?.content }
        ?.joinToString("")
        ?.takeIf { it.isNotBlank() }
    val elements = msg.elements ?: emptyList()
    val hasMedia = elements.any { it.picElement != null || it.videoElement != null || it.pttElement != null }

    if (!hasMedia) {
        if (text != null) startSend(ctx, ArrayList(), ArrayList(), text)
        else Utils.toast(context, "没有可分享的内容")
        return
    }

    Utils.toast(context, "正在准备分享…")
    Thread {
        val staged = ArrayList<Pair<File, String>>() // file -> mime
        runCatching {
            elements.forEach { el ->
                when {
                    el.picElement != null -> resolvePicFile(ctx, el, msgItem)
                        ?.let { src -> stagePic(ctx, src)?.let { staged.add(it) } }

                    el.videoElement != null -> resolveVideoFile(el, msgItem)
                        ?.let { src -> stageAs(ctx, src, "mp4", "video/mp4")?.let { staged.add(it) } }
                        ?: Utils.log("shareMessage: video unavailable")

                    el.pttElement != null -> localFile(el.pttElement.filePath)?.let { src ->
                        stageCopy(ctx, src, mimeForExt(src.extension))?.let { staged.add(it) }
                    } ?: Utils.log("shareMessage: voice file missing ${el.pttElement.filePath}")
                }
            }
        }.onFailure { Utils.log("shareMessage: staging error: $it") }

        val uris = ArrayList<Uri>()
        val mimes = ArrayList<String>()
        staged.forEach { (file, mime) ->
            runCatching { fileUri(ctx, file) }
                .onSuccess { uris.add(it); mimes.add(mime) }
                .onFailure { Utils.log("shareMessage: uri failed for ${file.path}: $it") }
        }
        runOnUi {
            when {
                uris.isNotEmpty() -> startSend(ctx, uris, mimes, text)
                text != null -> startSend(ctx, ArrayList(), ArrayList(), text)
                else -> Utils.toast(context, "分享内容不可用")
            }
        }
    }.start()
}

/**
 * Batch system-share: stage every selected message's media into ONE share sheet (joined text +
 * ACTION_SEND_MULTIPLE for the files). Reuses the same private stagers as [shareMessage]; pic
 * resolution passes a null msgItem (on-disk → md5 cache → HTTP fallback, no kernel download).
 */
fun View.shareMessages(msgs: List<MsgRecord>) {
    val ctx = context.applicationContext
    val text = msgs.mapNotNull { m ->
        m.elements?.mapNotNull { it.textElement?.content }?.joinToString("")?.takeIf { it.isNotBlank() }
    }.joinToString("\n").takeIf { it.isNotBlank() }
    val allEls = msgs.flatMap { it.elements ?: emptyList() }
    val hasMedia = allEls.any { it.picElement != null || it.videoElement != null || it.pttElement != null }
    if (!hasMedia) {
        if (text != null) startSend(ctx, ArrayList(), ArrayList(), text) else Utils.toast(context, "没有可分享的内容")
        return
    }
    Utils.toast(context, "正在准备分享…")
    Thread {
        val staged = ArrayList<Pair<File, String>>()
        runCatching {
            allEls.forEach { el ->
                when {
                    el.picElement != null -> resolvePicFile(ctx, el, null)?.let { src -> stagePic(ctx, src)?.let { staged.add(it) } }
                    el.videoElement != null -> resolveVideoFile(el, null)?.let { src -> stageAs(ctx, src, "mp4", "video/mp4")?.let { staged.add(it) } }
                    el.pttElement != null -> localFile(el.pttElement.filePath)?.let { src -> stageCopy(ctx, src, mimeForExt(src.extension))?.let { staged.add(it) } }
                }
            }
        }.onFailure { Utils.log("shareMessages: staging error: $it") }
        val uris = ArrayList<Uri>()
        val mimes = ArrayList<String>()
        staged.forEach { (file, mime) ->
            runCatching { fileUri(ctx, file) }.onSuccess { uris.add(it); mimes.add(mime) }
                .onFailure { Utils.log("shareMessages: uri failed for ${file.path}: $it") }
        }
        runOnUi {
            when {
                uris.isNotEmpty() -> startSend(ctx, uris, mimes, text)
                text != null -> startSend(ctx, ArrayList(), ArrayList(), text)
                else -> Utils.toast(context, "分享内容不可用")
            }
        }
    }.start()
}

/**
 * Copy the message's (first) image to the system clipboard as a content URI, so it can be pasted
 * into other apps. Resolves the original file the same way [shareMessage] does (on-disk → kernel
 * download → HTTP), copies it into the FileProvider-shared dir, then puts a [ClipData.newUri] on the
 * clipboard. Blocking work runs off the UI thread.
 */
fun View.copyImageToClipboard(msg: MsgRecord, msgItem: WatchAIOMsgItem?, prefer: MsgElement? = null) {
    val ctx = context.applicationContext
    // Use the specifically-pressed image (multi-image bubble) when given, else the first.
    val picEl = prefer?.takeIf { it.picElement != null }
        ?: (msg.elements ?: emptyList()).firstOrNull { it.picElement != null }
    if (picEl == null) {
        Utils.toast(context, "没有可复制的图片")
        return
    }
    Utils.toast(context, "正在准备图片…")
    Thread {
        val staged = runCatching { resolvePicFile(ctx, picEl, msgItem)?.let { stagePic(ctx, it) } }
            .getOrElse { Utils.log("copyImage: staging error: $it"); null }
        val uri = staged?.let { (file, _) ->
            runCatching { fileUri(ctx, file) }
                .onFailure { Utils.log("copyImage: uri failed for ${file.path}: $it") }
                .getOrNull()
        }
        runOnUi {
            if (uri == null) {
                Utils.toast(context, "图片不可用")
                return@runOnUi
            }
            runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "image", uri))
                Utils.toast(context, "已复制图片")
            }.onFailure { Utils.log("copyImage: setPrimaryClip failed: $it"); Utils.toast(context, "复制失败") }
        }
    }.start()
}

/**
 * Copy an already-rendered image [file] (e.g. a market-face/sticker drawable we captured) to the
 * system clipboard as a content URI. Unlike [copyImageToClipboard] this takes a ready file rather
 * than resolving a picElement, so it works for messages that carry no picElement.
 */
fun View.copyImageFileToClipboard(file: File) {
    val ctx = context.applicationContext
    Thread {
        val uri = runCatching {
            val dst = File(shareDir(ctx), file.name)
            file.copyTo(dst, overwrite = true)
            fileUri(ctx, dst)
        }.onFailure { Utils.log("copyImageFile: stage/uri failed: $it") }.getOrNull()
        runOnUi {
            if (uri == null) { Utils.toast(context, "图片不可用"); return@runOnUi }
            runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "image", uri))
                Utils.toast(context, "已复制图片")
            }.onFailure { Utils.log("copyImageFile: setPrimaryClip failed: $it"); Utils.toast(context, "复制失败") }
        }
    }.start()
}

/** System-share an already-rendered image [file] (market-face/sticker) via the share sheet. */
fun View.shareImageFile(file: File) {
    val ctx = context.applicationContext
    Thread {
        val uri = runCatching {
            val dst = File(shareDir(ctx), file.name)
            file.copyTo(dst, overwrite = true)
            fileUri(ctx, dst)
        }.onFailure { Utils.log("shareImageFile: stage/uri failed: $it") }.getOrNull()
        runOnUi {
            if (uri == null) { Utils.toast(context, "图片不可用"); return@runOnUi }
            startSend(ctx, arrayListOf(uri), listOf("image/png"), null)
        }
    }.start()
}

private fun shareDir(ctx: Context): File =
    (ctx.getExternalFilesDir("share") ?: ctx.filesDir).apply { if (!exists()) mkdirs() }

private fun fileUri(ctx: Context, file: File): Uri =
    FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)

private fun localFile(path: String?): File? =
    path?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }

/**
 * Resolve a pic element to a local original file, mirroring 保存图片(原图): try the on-disk original,
 * else kernel-download it (when we have the [msgItem]), else HTTP fallback. Blocking — off UI thread.
 * Public so the forward/复读 rebuild ([rebuildForForward]) can get a real original even when the image
 * was never opened full-screen (no on-disk file yet) — the cause of "rich media transfer failed".
 */
fun resolveOriginalPicFile(ctx: Context, el: MsgElement, msgItem: WatchAIOMsgItem?): File? =
    resolvePicFile(ctx, el, msgItem)

private fun resolvePicFile(ctx: Context, el: MsgElement, msgItem: WatchAIOMsgItem?): File? {
    // Obfuscated names: AIOPicDownloader.a == the singleton instance; .d() resolves the local
    // original path; .a() kicks off a kernel download. PicSize.e == PIC_DOWNLOAD_ORI (ordinal 3).
    val downloader = AIOPicDownloader.a
    localFile(runCatching { downloader.d(el, PicSize.e) }.getOrNull())
        ?.let { Utils.log("shareMessage: pic on disk ${it.path}"); return it }

    // History images (and others we render) are cached here by md5.
    el.picElement?.md5HexStr?.let { md5 ->
        localFile(ctx.externalCacheDir?.child("$md5.jpg")?.path)
            ?.let { Utils.log("shareMessage: pic from cache ${it.path}"); return it }
    }

    if (msgItem != null) {
        val latch = CountDownLatch(1)
        var resultPath: String? = null
        runOnUi {
            runCatching {
                downloader.a(
                    el, PicSize.e,
                    AIOPicDownloader.DefaultDownPicParamsProvider(msgItem, 0, 2), 1, 0,
                ) { info ->
                    // fileDownType==1 is the terminal download result (not a progress tick).
                    if (info.fileDownType == 1) {
                        if (info.trasferStatus == 4 && !info.filePath.isNullOrEmpty()) resultPath = info.filePath
                        latch.countDown()
                    }
                }
            }.onFailure { Utils.log("shareMessage: pic download start failed: $it"); latch.countDown() }
        }
        latch.await(90, TimeUnit.SECONDS)
        localFile(resultPath)?.let { Utils.log("shareMessage: pic downloaded ${it.path}"); return it }
        Utils.log("shareMessage: kernel pic download produced no file, trying http")
    }

    // Last resort: HTTP fetch of the original URL.
    val pic = el.picElement ?: return null
    val url = runCatching { pic.getImageUrl() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    val dst = File(shareDir(ctx), "dl_${System.currentTimeMillis()}_${pic.md5HexStr}")
    val latch = CountDownLatch(1)
    var ok = false
    download(url, dst) { ok = it; latch.countDown() }
    latch.await(70, TimeUnit.SECONDS)
    Utils.log("shareMessage: pic http download ok=$ok")
    return dst.takeIf { ok && it.exists() && it.length() > 0 }
}

/** Copy a resolved pic into the share dir and name it by its real (sniffed) format. */
private fun stagePic(ctx: Context, src: File): Pair<File, String>? {
    val ext = sniffImageExt(src) ?: src.extension.takeIf { it.isNotEmpty() } ?: "jpg"
    val dst = File(shareDir(ctx), "pic_${System.currentTimeMillis()}.$ext")
    return runCatching { src.copyTo(dst, overwrite = true); dst }
        .getOrNull()?.takeIf { it.exists() && it.length() > 0 }?.let { it to mimeForExt(ext) }
}

/** Copy an existing local media file into the share dir, preserving its name/extension. */
private fun stageCopy(ctx: Context, src: File, mime: String): Pair<File, String>? {
    val dst = File(shareDir(ctx), "media_${System.currentTimeMillis()}_${src.name}")
    return runCatching { src.copyTo(dst, overwrite = true); dst }
        .getOrNull()?.takeIf { it.exists() && it.length() > 0 }?.let { it to mime }
}

/** Copy into the share dir with a forced extension (QQ's media paths often have no extension). */
private fun stageAs(ctx: Context, src: File, ext: String, mime: String): Pair<File, String>? {
    val dst = File(shareDir(ctx), "media_${System.currentTimeMillis()}.$ext")
    return runCatching { src.copyTo(dst, overwrite = true); dst }
        .getOrNull()?.takeIf { it.exists() && it.length() > 0 }?.let { it to mime }
}

/**
 * Resolve the real VIDEO file (not the thumbnail), mirroring 保存视频. `a1` is the video-file path
 * (subType 1); `b1` was the thumbnail (subType 2) — using it produced a JPEG. If the video isn't on
 * disk we trigger the kernel download via [WatchVideoMsgItem.t] and poll for the file. Blocking.
 */
private fun resolveVideoFile(el: MsgElement, msgItem: WatchAIOMsgItem?): File? {
    val ve = el.videoElement ?: return null
    localFile(runCatching { WatchPicElementExtKt.a1(ve) }.getOrNull())
        ?.let { Utils.log("shareMessage: video on disk ${it.path}"); return it }

    val vitem = msgItem as? WatchVideoMsgItem ?: run {
        Utils.log("shareMessage: video not downloaded and no video item")
        return null
    }
    localFile(runCatching { vitem.s() }.getOrNull())
        ?.let { Utils.log("shareMessage: video via s() ${it.path}"); return it }

    Utils.log("shareMessage: triggering video download")
    runOnUi { runCatching { vitem.t(true) }.onFailure { Utils.log("shareMessage: video t() failed: $it") } }
    val deadline = System.currentTimeMillis() + 90_000
    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(1500)
        localFile(runCatching { WatchPicElementExtKt.a1(ve) }.getOrNull())
            ?.let { Utils.log("shareMessage: video downloaded ${it.path}"); return it }
        localFile(runCatching { vitem.y() }.getOrNull())?.let { return it }
    }
    Utils.log("shareMessage: video download timed out")
    return null
}

/** Detect image format from magic bytes so GIFs keep their animated `.gif` form. */
private fun sniffImageExt(f: File): String? {
    val b = ByteArray(12)
    val n = f.inputStream().use { it.read(b) }
    if (n < 12) return null
    fun c(i: Int, ch: Char) = b[i] == ch.code.toByte()
    return when {
        c(0, 'G') && c(1, 'I') && c(2, 'F') -> "gif"
        b[0] == 0x89.toByte() && c(1, 'P') && c(2, 'N') && c(3, 'G') -> "png"
        b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
        c(0, 'R') && c(1, 'I') && c(2, 'F') && c(8, 'W') && c(9, 'E') -> "webp"
        else -> null
    }
}

private fun mimeForExt(ext: String): String = when (ext.lowercase()) {
    "gif" -> "image/gif"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "jpg", "jpeg" -> "image/jpeg"
    "mp4" -> "video/mp4"
    "mp3" -> "audio/mpeg"
    "amr" -> "audio/amr"
    "m4a", "aac" -> "audio/mp4"
    "silk", "slk" -> "audio/silk"
    else -> "*/*"
}

/** Reduce a set of mime types to one share intent type (exact if all equal, else a wildcard). */
private fun unifyMime(mimes: List<String>): String {
    if (mimes.isEmpty()) return "*/*"
    if (mimes.distinct().size == 1) return mimes.first()
    val top = mimes.map { it.substringBefore('/') }.distinct()
    return if (top.size == 1) "${top.first()}/*" else "*/*"
}

private fun startSend(ctx: Context, uris: ArrayList<Uri>, mimes: List<String>, text: String?) {
    val mime = unifyMime(mimes)
    val intent = when {
        uris.isEmpty() -> Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        }
        uris.size == 1 -> Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uris[0])
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        }
        else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(intent, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(chooser) }
        .onFailure { Utils.log("shareMessage: startActivity failed: $it") }
}
