package momoi.mod.qqpro.hook

import android.content.Context
import android.os.Bundle
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.tencent.qqnt.watch.gallery.preview.RFWLayerLaunchUtilKt
import com.tencent.richframework.gallery.bean.RFWLayerItemMediaInfo
import momoi.anno.mixin.StaticHook
import momoi.mod.qqpro.hook.view.MediaItem
import momoi.mod.qqpro.hook.view.MediaPagerFragment
import momoi.mod.qqpro.util.Utils
import java.io.File

/**
 * Intercept QQ's native RFW gallery launch (`RFWLayerLaunchUtilKt.d`, → mediaEntryFragment →
 * WatchPreviewMainFragment) and route it to our materialized [MediaPagerFragment] instead: zoomable
 * images, M3 progress, and a pinch-zoomable video player. This is the viewer the QZone feed grid
 * (`ContentPicVideoContainerViewHolder`) opens for both photos and videos — the chat viewers are
 * already replaced earlier (at the cell click), so they never reach here.
 *
 * Falls back to the original native gallery when we can't build any usable media item (so anything
 * unexpected still works). Top-level @StaticHook fn — same name/signature as the target static; call
 * [RFWLayerLaunchUtilKt.d] to invoke the original.
 */
/**
 * One-shot bypass: when the chat video player can't decode a video it hands off to the native viewer
 * by replaying the cell's native onClick, which routes back through [d]. Set this first so [d] passes
 * straight through to the original native gallery (a different, more capable player) instead of
 * re-opening our own player and looping. Consumed (reset) on the next [d] call.
 */
object RFWGalleryFallback {
    @Volatile @JvmField var forceNative = false
}

@StaticHook(RFWLayerLaunchUtilKt::class)
fun d(
    context: Context,
    fragment: Fragment,
    imageView: ImageView?,
    allMediaInfo: List<RFWLayerItemMediaInfo>,
    index: Int,
    bundle: Bundle?,
) {
    if (RFWGalleryFallback.forceNative) {
        RFWGalleryFallback.forceNative = false
        Utils.log("RFWGalleryHook: forced native fallback (${allMediaInfo.size} media)")
        RFWLayerLaunchUtilKt.d(context, fragment, imageView, allMediaInfo, index, bundle)
        return
    }
    val items = runCatching { allMediaInfo.map { it.toMediaItem() } }.getOrDefault(emptyList())
    val usable = items.any { !it.imageUrl.isNullOrEmpty() || it.imageLocalPath != null || !it.videoUrl.isNullOrEmpty() }
    if (!usable) {
        Utils.log("RFWGalleryHook: no usable media (${allMediaInfo.size}), fall back to native")
        RFWLayerLaunchUtilKt.d(context, fragment, imageView, allMediaInfo, index, bundle)
        return
    }
    Utils.log("RFWGalleryHook: open MediaPager items=${items.size} index=$index")
    val viewer = MediaPagerFragment(items, index.coerceIn(0, maxOf(0, items.size - 1)))
    runCatching { viewer.show(fragment.parentFragmentManager, "qqpro_rfw_gallery") }
        .onFailure {
            Utils.log("RFWGalleryHook: show failed ($it), fall back to native")
            RFWLayerLaunchUtilKt.d(context, fragment, imageView, allMediaInfo, index, bundle)
        }
}

// RFWLayerItemMediaInfo.b = layerVideoInfo, .c = layerPicInfo.
// RFWLayerPicInfo: c=_currentPicInfo, d=bigPicInfo, e=originPicInfo (each RFWPicInfo: b=localPath, c=url).
// RFWLayerVideoInfo: c=currentVideoUrl, d=videoOriginUrl, e=localPath.
private fun RFWLayerItemMediaInfo.toMediaItem(): MediaItem {
    val pic = this.c
    val picInfos = listOfNotNull(pic?.d, pic?.c, pic?.e) // bigPicInfo, currentPicInfo, originPicInfo
    val imageUrl = picInfos.firstNotNullOfOrNull { p -> p.c?.takeIf { it.isNotEmpty() } }
    val imageLocal = picInfos.firstNotNullOfOrNull { p ->
        p.b?.takeIf { it.isNotEmpty() && File(it).exists() }
    }
    val vid = this.b
    val videoUrl = vid?.let {
        sequenceOf(it.d, it.c, it.e).firstOrNull { u -> !u.isNullOrEmpty() }
    }
    return MediaItem(imageUrl, imageLocal, videoUrl)
}
