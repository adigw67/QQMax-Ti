package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.tencent.watch.qzone_impl.frame.QZoneMediaBrowserFragment
import momoi.anno.mixin.Mixin

/**
 * 空间图片/视频查看器 materialization. Replace the native QZone media browser's view with our own
 * (zoomable images + M3 progress + real video playback) — see [QZoneMediaViewer] for the rationale.
 *
 * `Y` is the (R8-renamed) onCreateWatchView of WatchFragment. We replace the body entirely and never
 * call super, so the original CENTER_CROP/no-zoom/no-video ViewPager is not built. All non-trivial
 * view code lives in [QZoneMediaViewer] (our package); this @Mixin body just delegates, keeping it
 * free of anonymous classes/lambdas that would be copied into the target package and crash.
 */
@Mixin
class QZoneMediaBrowser : QZoneMediaBrowserFragment() {
    override fun Y(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return QZoneMediaViewer.build(this, inflater)
    }
}
