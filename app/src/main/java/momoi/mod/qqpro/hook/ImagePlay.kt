package momoi.mod.qqpro.hook

import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tencent.mobileqq.aio.msglist.holder.base.PicSize
import com.tencent.qqnt.kernel.nativeinterface.FileTransNotifyInfo
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.PicElement
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ext.AIOPicDownloader
import com.tencent.watch.aio_impl.ui.cell.base.BaseWatchItemCell
import com.tencent.watch.aio_impl.ui.cell.mix.WatchMixMsgItem
import com.tencent.watch.aio_impl.ui.cell.pic.WatchPicGroupWidget
import com.tencent.watch.aio_impl.ui.cell.pic.WatchPicMsgItem
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import com.tencent.watch.aio_impl.ui.widget.RoundBubbleImageView
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.hook.aio_cell.BigImageFragment
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import java.io.File

/**
 * 点击聊天里的图片——替换原生 MediaBrowser/RFW 相册查看器,改用我们自己的 [BigImageFragment]:
 *  - 复用原生可缩放的 RFWMatrixImageView(双指缩放),并显示 M3 加载圈(基于内核下载进度的确定性进度),
 *    替换原生那条彩色进度条。图片由内核负责下载,我们绝不自行 HTTP 下载。
 *  - 同时覆盖独立图片消息([WatchPicMsgItem])与图文混排气泡内的图片([WatchMixMsgItem]):后者在
 *    cell 视图树里递归找出所有 [WatchPicGroupWidget],按顺序对应到混排里的各 PicElement。
 *
 * 和 [VideoPlay] 一样挂在每次绑定都会走的 BaseWatchItemCell.i() 通用钩子上;点击监听放在顶层 helper 里
 * (不能写在 @Mixin 方法体内的内联 lambda——会被合进 BaseWatchItemCell 与 VideoPlay 的 i$lambda$0 冲突)。
 */
@Mixin
abstract class ImagePlay : BaseWatchItemCell<WatchAIOMsgItem, View>() {
    override fun i(
        view: View,
        item: WatchAIOMsgItem,
        p2: Int,
        p3: List<Any>,
        p4: Lifecycle,
        p5: LifecycleOwner?
    ) {
        super.i(view, item, p2, p3, p4, p5)
        // Publish kernel download progress (RICH_MEDIA bind payload) so the open viewer can show a
        // determinate circle — same mechanism as VideoPlay.
        runCatching { MediaDownloadProgress.capture(item.d.msgId, p3) }
        bindPicClicks(view, item)
    }
}

// 以下全部为非 @Mixin 顶层函数:内部 lambda/匿名类生成在本包,不会被 ApkMixin 拷进目标包。

/** Each tappable picture in this cell, paired with the PicElement + its MsgElement (for the kernel). */
private class PicTarget(val cover: View, val pic: PicElement, val element: MsgElement)

/**
 * Which image the user last touched, so a long-press menu on a multi-image bubble can act on THAT
 * image instead of always the first. A message with several picElements (a mixed/grouped image bubble)
 * carries no "which one was pressed" info anywhere — even the native menu falls back to elements.first()
 * — so we record the cover under the finger on ACTION_DOWN (the down that precedes the long-press) and
 * the menu reads it back by msgId within a short window.
 */
object PressedImage {
    private var msgId = 0L
    private var element: MsgElement? = null
    private var at = 0L
    fun record(msgId: Long, element: MsgElement) {
        this.msgId = msgId; this.element = element; this.at = android.os.SystemClock.uptimeMillis()
    }
    /** The MsgElement (carrying picElement) the user just pressed for [msgId], or null if none/stale. */
    fun elementFor(msgId: Long): MsgElement? =
        if (this.msgId == msgId && element != null &&
            android.os.SystemClock.uptimeMillis() - at < 8000L) element else null
}

private fun bindPicClicks(view: View, item: WatchAIOMsgItem) {
    when (item) {
        // Standalone image message: the WatchPicGroupWidget is the cell's CONTENT widget (reached via
        // getContentWidget, NOT a child in the View tree) — resolve it directly.
        is WatchPicMsgItem -> {
            val cover = picWidgetOf(view)?.let { runCatching { it.picView }.getOrNull() } ?: return
            val el = runCatching { item.t() }.getOrNull() ?: return
            val pic = runCatching { item.w() }.getOrNull() ?: return
            attachPicClick(PicTarget(cover, pic, el), item)
        }
        // Image(s) inside a text bubble: the content widget is a mix group holding pic sub-widgets.
        is WatchMixMsgItem -> {
            val pairs = runCatching {
                // item.p = MixItemList; .d = ArrayList<MixChildItem>; child.b = the pic MsgElement.
                item.p.d.mapNotNull { child ->
                    val el = (child as? WatchMixMsgItem.MixChildItem)?.b ?: return@mapNotNull null
                    el.picElement?.let { it to el }
                }
            }.getOrDefault(emptyList())
            val covers = ArrayList<View>()
            collectPicViews(view, covers)
            Utils.log("ImagePlay: mix bubble pairs=${pairs.size} covers=${covers.size} view=${view.javaClass.simpleName}")
            if (pairs.isEmpty() || covers.isEmpty()) return
            covers.forEachIndexed { idx, cover ->
                val (pic, el) = pairs.getOrElse(idx) { pairs.first() }
                attachPicClick(PicTarget(cover, pic, el), item)
            }
        }
        else -> return
    }
}

/** The cell's [WatchPicGroupWidget] (the content widget), or null. */
private fun picWidgetOf(view: View): WatchPicGroupWidget? = when {
    view is WatchPicGroupWidget -> view
    view is AIOCellGroupWidget -> view.getContentWidget<View>() as? WatchPicGroupWidget
    else -> null
}

/**
 * Recursively collect the picture image views under [v] (used for mixed text+image bubbles, where the
 * images are bare [RoundBubbleImageView]s inside the mix content widget — NOT WatchPicGroupWidgets, so
 * the widget can't be matched by type). Walks both view-tree children and AIOCellGroupWidget content widgets.
 */
private fun collectPicViews(v: View, out: ArrayList<View>) {
    if (v is RoundBubbleImageView) { if (v !in out) out.add(v); return }
    if (v is AIOCellGroupWidget) runCatching { v.getContentWidget<View>() }.getOrNull()?.let {
        if (it !== v) collectPicViews(it, out)
    }
    if (v is ViewGroup) for (i in 0 until v.childCount) collectPicViews(v.getChildAt(i), out)
}

@android.annotation.SuppressLint("ClickableViewAccessibility")
private fun attachPicClick(target: PicTarget, cellItem: WatchAIOMsgItem) {
    target.cover.setOnClickListener { v -> handlePicClick(target, cellItem, v) }
    // Record this image as "pressed" on the down that precedes a long-press, so the long-press menu's
    // save/copy/收藏 act on the image actually pressed (not elements.first()). Returns false so the
    // tap (click) and the bubble's long-press interceptor still work.
    target.cover.setOnTouchListener { _, ev ->
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN)
            PressedImage.record(cellItem.d.msgId, target.element)
        false
    }
}

private fun handlePicClick(target: PicTarget, cellItem: WatchAIOMsgItem, v: View) {
    val host = runCatching { WatchPicElementExtKt.W(v) }.getOrNull()
    if (host == null) { Utils.log("ImagePlay: host fragment null, ignore"); return }
    // Load through the KERNEL (it owns the file) — never download the bytes ourselves.
    val frag = BigImageFragment(target.pic) { onProgress, onDone ->
        kernelLoadPic(target.pic, target.element, cellItem, onProgress, onDone)
    }
    runCatching { frag.show(host.parentFragmentManager, "qqpro_image") }
        .onFailure { Utils.log("ImagePlay: show failed: $it") }
}

/**
 * Resolve the original picture's local file via [AIOPicDownloader] (by [pic]); if it isn't on disk,
 * ask the kernel to download it (best-effort — its source/trigger codes are guessed, so we don't
 * treat its failure status as fatal) and POLL for the file to land, driving determinate progress.
 * Never fetches bytes over HTTP. Calls [onDone] with the final path (or null on timeout/failure).
 */
private fun kernelLoadPic(
    pic: PicElement,
    element: MsgElement,
    cellItem: WatchAIOMsgItem,
    onProgress: (Float) -> Unit,
    onDone: (String?) -> Unit,
) {
    val size = runCatching { PicSize.valueOf("PIC_DOWNLOAD_ORI") }.getOrNull()
    val dl = runCatching { AIOPicDownloader.a }.getOrNull()
    if (size == null || dl == null) { onDone(null); return }

    fun readyPath(): String? = runCatching { dl.e(pic, size) }.getOrNull()
        ?.takeIf { it.isNotEmpty() && File(it).let { f -> f.exists() && f.length() > 0 } }

    readyPath()?.let { Utils.log("ImagePlay: kernel local path ready"); onDone(it); return }

    runCatching {
        // Match the native WatchPicItemCell download call exactly: provider(item, 0, 2),
        // downloadSourceType=1, triggerType=0. (My earlier 0/0/0 guess returned a failed status.)
        val provider = AIOPicDownloader.DefaultDownPicParamsProvider(cellItem, 0, 2)
        Utils.log("ImagePlay: kernel download start")
        dl.a(element, size, provider, 1, 0) { info: FileTransNotifyInfo ->
            Utils.log(
                "ImagePlay: notify status=${info.trasferStatus} progress=${info.fileProgress}/" +
                    "${info.totalSize} err=${info.fileErrCode}/${info.fileSrvErrCode}"
            )
            runOnUi {
                val total = info.totalSize
                if (total > 0) {
                    val p = (info.fileProgress.toFloat() / total).coerceIn(0f, 1f)
                    onProgress(p)
                    Utils.log("ImagePlay: progress ${(p * 100).toInt()}% status=${info.trasferStatus}")
                }
            }
        }
    }.onFailure { Utils.log("ImagePlay: trigger failed: $it") }

    // Poll for the file to land (the native cell preloads originals too); avoids the
    // "error first, reopen fixes it" race when our trigger's status comes back failed.
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    val start = android.os.SystemClock.uptimeMillis()
    handler.postDelayed(object : Runnable {
        override fun run() {
            // Determinate progress from the cell's RICH_MEDIA bind payload (captured in ImagePlay.i).
            MediaDownloadProgress.get(runCatching { cellItem.d.msgId }.getOrDefault(0L))?.let { onProgress(it) }
            val p = readyPath()
            when {
                p != null -> { Utils.log("ImagePlay: kernel path landed via poll"); onDone(p) }
                android.os.SystemClock.uptimeMillis() - start > 30_000L -> {
                    Utils.log("ImagePlay: kernel download timed out"); onDone(null)
                }
                else -> handler.postDelayed(this, 120)
            }
        }
    }, 500)
}
