package momoi.mod.qqpro.hook.qzone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.tencent.watch.qzone_impl.feed.model.BusinessFeedData
import download
import momoi.mod.qqpro.safeCacheDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import com.tencent.watch.qzone_impl.frame.IAdapterHost
import com.tencent.watch.qzone_impl.publish.business.publishqueue.QZonePublishQueue
import com.tencent.watch.qzone_impl.publish.business.task.QZoneDeleteFeedTask
import com.tencent.watch.qzone_impl.publish.business.task.QZoneLikeFeedTask
import com.tencent.watch.qzone_impl.service.QZoneWriteOperationService
import com.tencent.watch.qzone_impl.utils.UinUtils
import momoi.mod.qqpro.hook.navigateDest
import momoi.mod.qqpro.hook.view.MediaItem
import momoi.mod.qqpro.hook.view.MediaPagerFragment
import momoi.mod.qqpro.hook.view.PartialCopyFragment
import momoi.mod.qqpro.util.Utils
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Shared QZone action helpers for the materialized feed ([Settings.materializeQzone]). NOT a @Mixin
 * class, so anonymous classes / lambdas are fine here (they must never live in a @Mixin body).
 *
 * Everything routes through the proven native paths:
 *  - like  → [QZoneWriteOperationService.LikeParams] + [QZoneLikeFeedTask] + [QZonePublishQueue]
 *            (the exact path the native FeedCommentViewHolder uses), with an optimistic local update.
 *  - media → build [MediaItem]s from the post's pictures/video and show the shared [MediaPagerFragment].
 *  - comments → the custom [QzoneCommentThread] screen (M3 list + reply bar).
 *  - overflow (⋮) → a [QzoneOverflowFragment]: copy text / free-copy / download / repost.
 *
 * All dialogs are shown via `host.b().childFragmentManager` (the host fragment), matching the rest
 * of the app's [MyDialogFragment] pattern.
 */
object QzoneActions {

    /** The author/avatar URL for a uin, matching the QZone top-bar avatar source. */
    fun avatarUrl(uin: Long): String = "https://thirdqq.qlogo.cn/headimg_dl?dst_uin=$uin&spec=100"

    fun isFake(data: BusinessFeedData): Boolean =
        runCatching { data.localInfo?.isFake == true }.getOrDefault(false)

    /** True when [data] is the current user's own post (its author/owner uin is self). */
    fun isSelf(data: BusinessFeedData): Boolean = runCatching {
        val u = data.user?.uin ?: data.owner_uin
        u != 0L && u == UinUtils.b()
    }.getOrDefault(false)

    /** Plain text of a post (its summary), following the forwarded original when this is a repost. */
    fun postText(data: BusinessFeedData): String {
        val t = runCatching { data.originalInfo }.getOrNull() ?: data
        return runCatching { t.cellSummaryV2?.summary }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { data.cellSummaryV2?.summary }.getOrNull()
            ?: ""
    }

    /**
     * Toggle like on [data], optimistically updating its [CellLikeInfo] then enqueueing the write
     * task. Returns the new liked state (or the unchanged state if the post is still publishing).
     */
    fun toggleLike(host: IAdapterHost, data: BusinessFeedData): Boolean {
        val like = runCatching { data.likeInfo }.getOrNull() ?: return false
        if (isFake(data)) {
            Utils.toast(host.requireContext(), "正在发布, 请稍后")
            return like.isLiked
        }
        val newLiked = !like.isLiked
        like.isLiked = newLiked
        like.likeNum = (like.likeNum + if (newLiked) 1 else -1).coerceAtLeast(0)
        runCatching {
            val fc = data.feedCommInfo
            val p = QZoneWriteOperationService.LikeParams()
            p.a = fc.ugckey
            p.b = fc.curlikekey
            p.c = fc.orglikekey
            p.d = newLiked
            p.e = fc.appid
            p.f = data.operationInfo?.busiParam?.let { HashMap(it) }
            p.g = -1
            p.h = data
            p.i = data.feedType
            val task = QZoneLikeFeedTask(null, p, 1)
            runCatching { task.javaClass.getField("clientKey").set(task, fc.clientkey) }
            QZonePublishQueue.e().b(task)
            Utils.log("QzoneActions: like ${fc.feedskey} -> $newLiked (num=${like.likeNum})")
        }.onFailure { Utils.log("QzoneActions toggleLike failed: $it") }
        return newLiked
    }

    /** Open the custom comment-thread screen for [data]. */
    fun openComments(host: IAdapterHost, data: BusinessFeedData) {
        runCatching {
            QzoneCommentThread(data, host).show(host.b().childFragmentManager, "qzcomments")
        }.onFailure {
            Utils.log("QzoneActions openComments: $it")
            // Fall back to the native comment input if the custom screen fails.
            runCatching { host.z(host.b().requireView(), 1, data, 0, null) }
        }
    }

    /** Collect a post's media as [MediaItem]s (pictures first, then a single video). */
    fun mediaItems(data: BusinessFeedData): List<MediaItem> {
        val t = runCatching { data.originalInfo }.getOrNull() ?: data
        val items = ArrayList<MediaItem>()
        runCatching {
            t.pictureInfo?.pics?.forEach { pi ->
                // Preview (feed card) uses the SMALLER currentUrl so it downloads fast and a GIF plays at
                // low res; fullscreen uses the full originUrl. Each falls back through the variants.
                val small = pi.currentUrl?.url?.takeIf { it.isNotEmpty() }
                    ?: pi.bigUrl?.url?.takeIf { it.isNotEmpty() }
                    ?: pi.originUrl?.url?.takeIf { it.isNotEmpty() }
                val full = pi.originUrl?.url?.takeIf { it.isNotEmpty() }
                    ?: pi.bigUrl?.url?.takeIf { it.isNotEmpty() }
                    ?: pi.currentUrl?.url?.takeIf { it.isNotEmpty() }
                if (!small.isNullOrEmpty()) items.add(MediaItem(imageUrl = small, imageLocalPath = null, videoUrl = null, fullUrl = full))
            }
        }
        runCatching {
            val url = t.videoInfo?.videoUrl?.url
            if (!url.isNullOrEmpty()) items.add(MediaItem(imageUrl = null, imageLocalPath = null, videoUrl = url))
        }
        return items
    }

    fun openMedia(host: IAdapterHost, data: BusinessFeedData, index: Int) {
        val items = mediaItems(data)
        if (items.isEmpty()) return
        runCatching {
            MediaPagerFragment(items, index.coerceIn(0, items.size - 1))
                .show(host.b().childFragmentManager, "qzmedia")
        }.onFailure { Utils.log("QzoneActions openMedia: $it") }
    }

    /** The ⋮ overflow menu for a post: copy text / free-copy / download / repost. */
    fun showOverflowMenu(host: IAdapterHost, data: BusinessFeedData) {
        val ctx = host.requireContext()
        val text = postText(data)
        val rows = ArrayList<Pair<String, () -> Unit>>()
        rows.add("复制文本" to {
            copyToClipboard(ctx, text); Utils.toast(ctx, "已复制")
        })
        rows.add("自由复制" to {
            runCatching { PartialCopyFragment(text).show(host.b().childFragmentManager, "qzcopy") }
        })
        if (mediaItems(data).isNotEmpty()) {
            rows.add("下载图片/视频" to { downloadMedia(host, data) })
        }
        // 转发 (repost).
        rows.add("转发" to { repost(host, data) })
        // Own post → 删除 (confirm); others' post → 举报 (confirm). Both stay at the end.
        if (isSelf(data)) {
            rows.add("删除" to {
                QzoneConfirmDialog("确定删除这条说说？", "删除", destructive = true) { deletePost(host, data) }
                    .show(host.b().childFragmentManager, "qzdelfeed")
            })
        } else {
            rows.add("举报" to {
                QzoneConfirmDialog("确定举报这条说说？", "举报", destructive = true) { reportPost(host, data) }
                    .show(host.b().childFragmentManager, "qzreport")
            })
        }
        runCatching {
            QzoneOverflowFragment(rows, destructive = setOf("删除", "举报"))
                .show(host.b().childFragmentManager, "qzmenu")
        }.onFailure { Utils.log("QzoneActions overflow: $it") }
    }

    /** Delete the current user's own 说说 (mirrors the native QZoneDeleteFeedTask enqueue path). */
    fun deletePost(host: IAdapterHost, data: BusinessFeedData) {
        runCatching {
            val fc = data.feedCommInfo
            val appid = fc.appid
            val cellId = runCatching { data.idInfo?.cellId }.getOrNull() ?: ""
            // appid 4 uses srcSubId "2"; 311/6100 need busiParam key 10="1" (native handleDeleteFeed).
            val subId = if (appid == 4) "2" else (runCatching { data.idInfo?.subId }.getOrNull() ?: "")
            val busi = runCatching { data.operationInfo?.busiParam }.getOrNull()?.let { HashMap(it) }
            if ((appid == 311 || appid == 6100) && busi != null) busi[10] = "1"
            val task = QZoneDeleteFeedTask(appid, UinUtils.b(), cellId, subId, 0, busi, 4)
            runCatching { task.javaClass.getField("clientKey").set(task, fc.clientkey) }
            QZonePublishQueue.e().b(task)
            Utils.toast(host.requireContext(), "已删除")
            Utils.log("QzoneActions: delete feed ${fc.feedskey} appid=$appid cell=$cellId sub=$subId")
        }.onFailure { Utils.log("QzoneActions deletePost: $it"); Utils.toast(host.requireContext(), "删除失败") }
    }

    /**
     * Report someone else's 说说. The watch can't file a report directly — it navigates to the native
     * report page ([R.id.reportFragment]) which renders a QR code to finish on the phone, exactly like
     * the native feed overflow 举报 (QZoneFeedViewHolderKtKt.handleOperationMore).
     */
    fun reportPost(host: IAdapterHost, data: BusinessFeedData) {
        runCatching {
            val reportUin = (data.user?.uin ?: data.owner_uin).toString()
            val cellId = runCatching { data.idInfo?.cellId }.getOrNull() ?: ""
            val myUin = UinUtils.c()
            // srv_para template the native fills: pid:0 | cid:<urlencoded cellId> | qzone_appid:311 | own_uin:<reportUin>
            val srv = "pid:0|cid:${URLEncoder.encode(cellId, "UTF-8")}|qzone_appid:311|own_uin:$reportUin"
            val ext = JSONObject().apply {
                put("appid", "10252"); put("eviluin", reportUin); put("eviluin_type", "2")
                put("buddyflag", "1"); put("is_anonymous", "0")
                put("content_id", srv); put("srv_para", srv)
            }
            val reqData = JSONObject().apply { put("ext", ext.toString()) }.toString()
            val bundle = android.os.Bundle().apply {
                putString("uin", myUin)
                putString("report_uin", reportUin)
                putString("report_ext_data", reqData)
            }
            val ok = navigateDest(host.b().requireView(), "reportFragment", bundle)
            if (!ok) Utils.toast(host.requireContext(), "无法举报")
            Utils.log("QzoneActions: report feed reportUin=$reportUin cell=$cellId ok=$ok")
        }.onFailure { Utils.log("QzoneActions reportPost: $it"); Utils.toast(host.requireContext(), "无法举报") }
    }

    /** Download every image/video of [data] into the device gallery (Pictures/Movies → QZone). */
    fun downloadMedia(host: IAdapterHost, data: BusinessFeedData) {
        val items = mediaItems(data)
        if (items.isEmpty()) { Utils.toast(host.requireContext(), "没有可下载的媒体"); return }
        val ctx = host.requireContext().applicationContext
        val main = Handler(Looper.getMainLooper())
        Utils.toast(host.requireContext(), "开始下载 ${items.size} 个文件…")
        val cache = ctx.safeCacheDir ?: ctx.cacheDir
        val remaining = AtomicInteger(items.size)
        val okCount = AtomicInteger(0)
        val ts = System.currentTimeMillis()
        items.forEachIndexed { i, item ->
            val isVideo = item.videoUrl != null
            // Save the FULL-resolution source (the feed preview used the smaller currentUrl).
            val url = item.videoUrl ?: item.fullUrl ?: item.imageUrl
            val finish = {
                if (remaining.decrementAndGet() == 0) {
                    val n = okCount.get()
                    main.post { Utils.toast(ctx, if (n > 0) "已保存 $n 个文件到相册" else "下载失败") }
                }
            }
            if (url.isNullOrEmpty()) { finish(); return@forEachIndexed }
            val tmp = File(cache, "qzdl_${ts}_$i.bin")
            runCatching {
                download(url, tmp) { success ->
                    // Save with the file's REAL type — sniffed from the downloaded bytes — not a hard-coded
                    // .jpg/image/jpeg. A GIF saved as JPEG shows static in the gallery (the "not animated"
                    // bug); detecting image/gif keeps it animated.
                    val (ext, mime) = if (isVideo) "mp4" to "video/mp4" else MediaSave.imageTypeOf(tmp)
                    val saved = success && MediaSave.toGallery(
                        ctx, tmp, "QZone_${ts}_$i.$ext", mime, isVideo,
                    )
                    if (saved) okCount.incrementAndGet()
                    runCatching { tmp.delete() }
                    finish()
                }
            }.onFailure { Utils.log("QzoneActions download $i: $it"); finish() }
        }
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("qzone", text))
        }.onFailure { Utils.log("QzoneActions copy: $it") }
    }

    /** Repost: open the compose page pre-filled with a quote of the original 说说. */
    private fun repost(host: IAdapterHost, data: BusinessFeedData) {
        runCatching { QzoneCompose.openRepost(host, data) }
            .onFailure { Utils.log("QzoneActions repost: $it"); Utils.toast(host.requireContext(), "转发失败") }
    }
}
