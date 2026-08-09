package momoi.mod.qqpro.hook.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.tencent.richframework.widget.matrix.RFWMatrixImageView
import loadPicUrl
import momoi.mod.qqpro.lib.applyLongScreenshotFit
import momoi.mod.qqpro.lib.bitmapDecodeFile
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.imageFitsHorizontally
import momoi.mod.qqpro.lib.isTapOutsideImage
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.TapObserverLayout
import momoi.mod.qqpro.lib.material.M3CircularProgress
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils
import java.io.File

/** One swipeable media page: an image (by URL or local file) that may also be a playable video. */
class MediaItem(
    val imageUrl: String?,
    val imageLocalPath: String?,
    val videoUrl: String?,
    // Full-resolution URL for the fullscreen viewer. The feed/grid preview uses the smaller [imageUrl]
    // (faster, lighter); fullscreen loads this. Null → fall back to [imageUrl].
    val fullUrl: String? = null,
)

/**
 * Shared, materialized full-screen media viewer used by every non-chat gallery (QZone single-pic
 * browser AND QZone grid / group-album RFW gallery). Builds a [ViewPager2] (so multiple photos
 * flip, like the originals) of zoomable [RFWMatrixImageView]s, each with an M3 determinate progress
 * circle while it downloads. Video items get a Material play button that opens [VideoPlayerFragment]
 * (pinch-zoomable; streams the HTTP url directly via MediaPlayer).
 *
 * [build] returns the root view so it can back either a host fragment's onCreateView (QZone browser)
 * or a standalone dialog ([MediaPagerFragment], for the RFW gallery). All adapter/listener code is
 * here in OUR package, so it must NOT be inlined into a @Mixin body.
 */
object MediaPager {

    @SuppressLint("ClickableViewAccessibility")
    fun build(
        ctx: Context,
        fm: FragmentManager,
        items: List<MediaItem>,
        initPos: Int,
        onBack: () -> Unit,
    ): View {
        Utils.log("MediaPager: build items=${items.size} initPos=$initPos")
        val root = FrameLayout(ctx)
        root.setBackgroundColor(Color.BLACK)

        // The app's ViewPager2 is R8-minified: setCurrentItem(int,boolean) and
        // registerOnPageChangeCallback are renamed/stripped (memory main-nav-feature), so we use
        // single-arg setCurrentItem(int) and an OnScrollChangedListener.
        val pager = ViewPager2(ctx)
        pager.layoutParams = ViewGroup.LayoutParams(-1, -1)
        // A tap on the empty (letterbox) area outside the current page's image dismisses the viewer.
        pager.adapter = MediaAdapter(fm, items, onTapOutside = onBack)
        if (items.isNotEmpty()) pager.setCurrentItem(initPos.coerceIn(0, items.size - 1))
        root.addView(pager)

        // Back: a Material arrow icon (replaces the old "返回" text).
        val back = ImageView(ctx).apply {
            setImageDrawable(MaterialSymbol(MaterialSymbols.arrow_back, Color.WHITE))
            setOnClickListener { runCatching { onBack() } }
        }
        root.addView(back, FrameLayout.LayoutParams(28.dp, 28.dp, Gravity.START or Gravity.TOP).apply {
            val m = 8.dp; setMargins(m, m, m, m)
        })

        // Page indicator: top-center (round-screen friendly) with a translucent pill so it stays
        // legible over any image.
        val indicator = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(10.dp, 3.dp, 10.dp, 3.dp)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x80_000000.toInt())
                cornerRadius = 100f
            }
        }
        root.addView(indicator, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = 6.dp
        })
        fun updateIndicator(pos: Int) {
            val show = items.size > 1
            indicator.visibility = if (show) View.VISIBLE else View.GONE
            if (show) indicator.text = "${pos + 1}/${items.size}"
        }
        updateIndicator(initPos)
        pager.viewTreeObserver.addOnScrollChangedListener {
            runCatching { updateIndicator(pager.currentItem) }
        }

        // Right-swipe back to dismiss, but only on the FIRST page and only while that page's image
        // has no horizontal pan room (fully zoomed out / fits horizontally). On later pages or while
        // zoomed in, the horizontal drag belongs to the ViewPager2 / native matrix view instead.
        val swipe = SwipeBackLayout(ctx)
        swipe.onSwipeBack = onBack
        swipe.canSwipe = {
            pager.currentItem == 0 && (currentPageImage(pager)?.let { it.imageFitsHorizontally() } ?: true)
        }
        swipe.addView(root, FrameLayout.LayoutParams(-1, -1))
        return swipe
    }

    /** The [RFWMatrixImageView] of the page currently shown by [pager], or null if not laid out yet. */
    private fun currentPageImage(pager: ViewPager2): RFWMatrixImageView? {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        val vh = rv.findViewHolderForAdapterPosition(pager.currentItem) as? PageHolder
        return vh?.image
    }

    private class MediaAdapter(
        private val fm: FragmentManager,
        private val items: List<MediaItem>,
        private val onTapOutside: () -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val page = TapObserverLayout(ctx).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }
            val image = RFWMatrixImageView(ctx, null).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            // Tap on the empty area outside the image dismisses (observed, not consumed).
            page.onSingleTap = { x, y -> if (image.isTapOutsideImage(x, y)) runCatching { onTapOutside() } }
            val spinner = M3CircularProgress(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(40.dp, 40.dp, Gravity.CENTER)
            }
            val play = ImageView(ctx).apply {
                setImageDrawable(MaterialSymbol(MaterialSymbols.play_arrow, Color.WHITE))
                layoutParams = FrameLayout.LayoutParams(56.dp, 56.dp, Gravity.CENTER)
                visibility = View.GONE
            }
            page.addView(image)
            page.addView(spinner)
            page.addView(play)
            return PageHolder(page, image, spinner, play)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val h = holder as PageHolder
            val item = items[position]

            // Show the still image first (thumbnail for videos): local file if we have one, else URL.
            val localFile = item.imageLocalPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
            if (localFile != null) {
                h.spinner.visibility = View.GONE
                h.image.bitmapDecodeFile(localFile)
                h.image.applyLongScreenshotFit()
            } else if (!(item.fullUrl ?: item.imageUrl).isNullOrEmpty()) {
                // Fullscreen loads the FULL-resolution URL (feed preview used the smaller one).
                val fullSrc = item.fullUrl ?: item.imageUrl!!
                h.spinner.indeterminate = true
                h.spinner.visibility = View.VISIBLE
                h.image.loadPicUrl(
                    fullSrc,
                    // Stable, URL-derived cache name so swiping back hits the disk cache instead of
                    // re-downloading every bind (the default name is time-based → never cached).
                    cacheFileName = "qzmedia_${fullSrc.hashCode()}",
                    onDone = { h.spinner.visibility = View.GONE; h.image.applyLongScreenshotFit() },
                    onProgress = { p -> h.spinner.indeterminate = false; h.spinner.progress = p },
                )
            } else {
                h.spinner.visibility = View.GONE
            }

            val videoUrl = item.videoUrl
            if (!videoUrl.isNullOrEmpty()) {
                h.play.visibility = View.VISIBLE
                h.play.setOnClickListener {
                    Utils.log("MediaPager: play video $videoUrl")
                    val player = VideoPlayerFragment(initialPath = videoUrl)
                    runCatching { player.show(fm, "qqpro_media_video") }
                        .onFailure { Utils.log("MediaPager: show player failed: $it") }
                }
            } else {
                h.play.visibility = View.GONE
                h.play.setOnClickListener(null)
            }
        }
    }

    private class PageHolder(
        page: View,
        val image: RFWMatrixImageView,
        val spinner: M3CircularProgress,
        val play: ImageView,
    ) : RecyclerView.ViewHolder(page)
}
