package momoi.mod.qqpro.hook

import android.view.ViewGroup
import android.widget.FrameLayout
import com.tencent.watch.qzone_impl.feed.model.BusinessFeedData
import com.tencent.watch.qzone_impl.frame.IAdapterHost
import com.tencent.watch.qzone_impl.frame.contentViewHolder.ContentPicVideoContainerViewHolder
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.view.InlineVideoView
import momoi.mod.qqpro.util.Utils

/**
 * 单视频内联播放: when 设置 "单视频帖子内联播放" is on, a QZone feed post that is a single video plays
 * inline in the feed cell (tap to start, tap to pause) instead of opening the fullscreen viewer.
 *
 * We @Mixin the feed's video container holder ([ContentPicVideoContainerViewHolder]) and, after each
 * data bind, overlay an [InlineVideoView] on the cell ([QZoneInlineVideo.bind]). The overlay's tap
 * also suppresses the cell's fullscreen launch (it consumes the touch before the cell's onTouchEvent).
 *
 * Helper logic lives here (our package) — never inlined in the @Mixin body (anonymous classes there
 * would be copied into the target package and crash; see memory qqpro-mixin-anon-class).
 */
@Mixin
class QZoneInlineVideoCell(viewType: Int, host: IAdapterHost) :
    ContentPicVideoContainerViewHolder(viewType, host) {
    override fun k(data: BusinessFeedData) {
        super.k(data)
        QZoneInlineVideo.bind(this)
    }
}

object QZoneInlineVideo {
    // ContentPicVideoContainerViewHolder fields: t=isVideo, l=drawables, q=showData (resolved).
    // We attach the inline player directly onto the cell (h()). The cell's onMeasure never measures
    // children, so InlineVideoView self-sizes to the cell (see its onAttachedToWindow); we don't wrap
    // the cell, to avoid disturbing its custom drawable-bounds handling (which distorted grid images).
    fun bind(holder: ContentPicVideoContainerViewHolder) {
        val cell = runCatching { holder.h() }.getOrNull() as? ViewGroup ?: return

        // Drop any prior inline player first (the holder is recycled across feed items).
        for (i in cell.childCount - 1 downTo 0) {
            val child = cell.getChildAt(i)
            if (child is InlineVideoView) { child.release(); cell.removeView(child) }
        }

        if (!Settings.qzoneInlineVideo.value) return
        if (!holder.t) return                       // not a video post
        if ((holder.l?.size ?: 0) != 1) return      // not a single media item

        val vi = holder.q?.let { runCatching { it.getVideoInfo() }.getOrNull() } ?: return
        val url = runCatching { vi.videoUrl?.url }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        Utils.log("QZoneInlineVideo: attach inline player")
        cell.addView(
            InlineVideoView(cell.context, url),
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }
}
