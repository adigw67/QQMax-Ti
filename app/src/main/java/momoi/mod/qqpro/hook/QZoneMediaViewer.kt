package momoi.mod.qqpro.hook

import android.view.LayoutInflater
import android.view.View
import com.tencent.watch.qzone_impl.frame.QZoneMediaBrowserFragment
import com.tencent.watch.qzone_impl.frame.model.ZoneMediaBrowserData
import momoi.mod.qqpro.hook.view.MediaItem
import momoi.mod.qqpro.hook.view.MediaPager
import momoi.mod.qqpro.util.Utils

/**
 * Backs the materialized [QZoneMediaBrowserFragment] (the QZone single-pic/video browser). Converts
 * its [ZoneMediaBrowserData] list into [MediaItem]s and hands off to the shared [MediaPager] viewer
 * (zoomable images + M3 progress + real video playback; the native browser only ever showed a
 * video's static thumbnail and never played it).
 *
 * Public on purpose (referenced from the @Mixin body in QZoneMediaBrowser).
 */
object QZoneMediaViewer {

    fun build(fragment: QZoneMediaBrowserFragment, inflater: LayoutInflater): View {
        val data: List<ZoneMediaBrowserData> = runCatching {
            fragment.requireArguments().getParcelableArrayList<ZoneMediaBrowserData>("key_media_list")
        }.getOrNull() ?: emptyList()
        val initPos = runCatching { fragment.requireArguments().getInt("key_init_position") }.getOrDefault(0)
        Utils.log("QZoneMediaViewer: build data=${data.size} initPos=$initPos")

        // ZoneMediaBrowserData.b = pic url, .c = video url.
        val items = data.map { MediaItem(imageUrl = it.b, imageLocalPath = null, videoUrl = it.c) }
        return MediaPager.build(
            inflater.context,
            fragment.childFragmentManager,
            items,
            initPos,
            onBack = { runCatching { fragment.pop() } },
        )
    }
}
