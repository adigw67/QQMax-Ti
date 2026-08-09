package momoi.mod.qqpro.hook

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tencent.qqnt.kernel.nativeinterface.FileTransNotifyInfo
import com.tencent.qqnt.kernel.nativeinterface.VideoElement
import com.tencent.watch.aio_impl.coreImpl.payLoad.AIOMsgItemPayload
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ui.cell.base.BaseWatchItemCell
import com.tencent.watch.aio_impl.ui.cell.video.WatchVideoGroupWidget
import com.tencent.watch.aio_impl.ui.cell.video.WatchVideoMsgItem
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.hook.view.VideoPlayerFragment
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import java.io.File

/**
 * 点击聊天里的视频——替换原生 RFW 相册播放器,改用我们自己的 [VideoPlayerFragment]:
 *  - 支持双指缩放 / 双击放大(原生无此功能)。
 *  - 未下载时不再只弹"正在下载"toast,而是进入播放器显示加载圈,下载完成后自动播放。
 *
 * 实现:不直接继承 WatchVideoItemCell(它带泛型擦除的抽象 d/e,子类化会触发 AbstractMethodError 或
 * 平台声明冲突),而是和 [momoi.mod.qqpro.hook.aio_cell.AIOCell] / 缩小文本 一样挂在 BaseWatchItemCell.i()
 * 这个每次绑定都会走的通用钩子上,绑定视频 cell 时把封面图(原生点击目标 getCoverImage)的点击监听换成我们的。
 * 多个 @Mixin 挂同一方法会经 super.i() 链式调用,互不影响。
 */
@Mixin
abstract class VideoPlay : BaseWatchItemCell<WatchAIOMsgItem, View>() {
    override fun i(
        view: View,
        item: WatchAIOMsgItem,
        p2: Int,
        p3: List<Any>,
        p4: Lifecycle,
        p5: LifecycleOwner?
    ) {
        super.i(view, item, p2, p3, p4, p5)
        if (item !is WatchVideoMsgItem) return
        captureVideoProgress(item, p3)
        val cover: View = when {
            view is WatchVideoGroupWidget -> view.m
            view is AIOCellGroupWidget ->
                (view.getContentWidget<View>() as? WatchVideoGroupWidget)?.m ?: return
            else -> return
        }
        // Attach via a top-level helper, not an inline lambda here (see ImagePlay): an inline lambda
        // in this @Mixin body is merged into BaseWatchItemCell and collides with ImagePlay's.
        attachVideoClick(cover, item)
    }
}

/**
 * Our replacement cover-click listener. A named class (not an inline lambda) so we can recognise it
 * on a recycled cover and recover the *native* listener it superseded — see [attachVideoClick].
 */
private class VideoCoverClick(
    val item: WatchVideoMsgItem,
    val nativeClick: View.OnClickListener?,
) : View.OnClickListener {
    override fun onClick(v: View) = handleVideoClick(item, v, nativeClick)
}

private fun attachVideoClick(cover: View, item: WatchVideoMsgItem) {
    // The native cell wires its RFW-viewer launch onto the cover in the super.i() that already ran;
    // capture it before we overwrite, so we can fall back to it for videos our MediaPlayer can't
    // decode. On a recycled cover the current listener may already be ours — pull the native one
    // back out of it rather than capturing our own listener (which would lose the native path).
    val existing = readOnClickListener(cover)
    val nativeClick = (existing as? VideoCoverClick)?.nativeClick ?: existing
    Utils.log("VideoPlay: attach cover=${cover.javaClass.simpleName} existing=${existing?.javaClass?.name} native=${nativeClick?.javaClass?.name}")
    cover.setOnClickListener(VideoCoverClick(item, nativeClick))
}

/** Read a View's current OnClickListener via reflection (framework greylist; null if unavailable). */
private fun readOnClickListener(v: View): View.OnClickListener? = runCatching {
    val getInfo = View::class.java.getDeclaredMethod("getListenerInfo").apply { isAccessible = true }
    val info = getInfo.invoke(v) ?: return null
    val field = info.javaClass.getDeclaredField("mOnClickListener").apply { isAccessible = true }
    field.get(info) as? View.OnClickListener
}.onFailure { Utils.log("VideoPlay: readOnClickListener failed: $it") }.getOrNull()

/**
 * On each cell rebind, pull the kernel's download progress (FileTransNotifyInfo, carried by a
 * RichMediaPayload) out of the bind payloads and publish it to [VideoDownloadProgress] so the open
 * player can show a determinate circle. Plain for-loop (no lambda — avoids the @Mixin lambda clash).
 */
private fun captureVideoProgress(item: WatchVideoMsgItem, payloads: List<Any>) {
    MediaDownloadProgress.capture(runCatching { item.d.msgId }.getOrDefault(0L), payloads)
}

// 非 @Mixin 普通函数:内部创建的匿名类(Fragment 回调 / lambda / OnClickListener)生成在本包,不会被
// ApkMixin 拷贝进目标包,避免跨包匿名类构造器不可访问的 IllegalAccessError。
private fun handleVideoClick(item: WatchVideoMsgItem, v: View, nativeClick: View.OnClickListener?) {
    if (runCatching { item.A() }.getOrDefault(false)) {
        runCatching { Utils.toast(v.context, "视频已过期") }
        return
    }

    val host = runCatching { WatchPicElementExtKt.W(v) }.getOrNull()
    if (host == null) {
        Utils.log("VideoPlay: host fragment null, ignore")
        return
    }

    val localPath = localVideoPath(item)
    Utils.log("VideoPlay: open player local=$localPath")

    val msgId = runCatching { item.d.msgId }.getOrDefault(0L)
    // When our MediaPlayer can't decode the video (resolution beyond the watch decoder), hand off to
    // the native RFW viewer by replaying the cover's original click listener we captured at bind.
    val fallback: (() -> Unit)? = nativeClick?.let { nc -> { runCatching { nc.onClick(v) } } }
    val fragment = VideoPlayerFragment(
        initialPath = localPath,
        needDownload = { runOnUi { runCatching { item.t(true) }.onFailure { Utils.log("VideoPlay: t() failed: $it") } } },
        resolvePath = { localVideoPath(item) },
        progressProvider = { videoProgress(item, msgId) },
        onUndecodable = fallback,
    )
    runCatching { fragment.show(host.parentFragmentManager, "qqpro_video") }
        .onFailure { Utils.log("VideoPlay: show failed: $it") }
}

/**
 * Live download progress (0..1) for the video, from two sources (whichever has data):
 *  - the cell item's own [FileTransNotifyInfo] field `j` (updated by the kernel as it downloads), and
 *  - the [VideoDownloadProgress] map populated from RichMedia bind payloads.
 * Logs what each source reports so we can see which (if any) actually carries bytes.
 */
private fun videoProgress(item: WatchVideoMsgItem, msgId: Long): Float? =
    MediaDownloadProgress.get(msgId)

/** Resolve the on-disk video file path (subType 1, the real video, not the thumbnail), or null. */
private fun localVideoPath(item: WatchVideoMsgItem): String? {
    fun valid(p: String?): String? =
        p?.takeIf { it.isNotEmpty() && runCatching { File(it).let { f -> f.exists() && f.length() > 0 } }.getOrDefault(false) }

    valid(runCatching { item.s() }.getOrNull())?.let { return it }
    val ve: VideoElement? = runCatching { item.x() }.getOrNull()
    if (ve != null) valid(runCatching { WatchPicElementExtKt.a1(ve) }.getOrNull())?.let { return it }
    valid(runCatching { item.y() }.getOrNull())?.let { return it }
    return null
}
