package momoi.mod.qqpro.hook.view
import android.os.Build
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3CircularProgress
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * Lightweight in-feed video player used by the QZone single-video "play in place" feature
 * ([momoi.mod.qqpro.hook.QZoneInlineVideo]). Sits as a transparent, clickable overlay on top of the
 * feed cell's drawn thumbnail+play-icon: the first tap starts streaming the mp4 (MediaPlayer over a
 * [TextureView]), later taps toggle play/pause. Looping, with sound (user-initiated).
 *
 * Intercepting the tap also suppresses the cell's fullscreen viewer (the child consumes the touch
 * before the cell's onTouchEvent runs). Call [release] when the holder rebinds/recycles.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class InlineVideoView(ctx: Context, private val videoUrl: String) : FrameLayout(ctx) {

    private var textureView: TextureView? = null
    private var spinner: M3CircularProgress? = null
    private var pauseIcon: ImageView? = null

    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var started = false
    private var releasing = false
    private var videoW = 0
    private var videoH = 0

    // The cell view we sit on. The cell's onMeasure never measures children (it draws itself on a
    // canvas), so we self-size to it via its layout-change listener.
    private var hostView: View? = null
    private var sizeListener: OnLayoutChangeListener? = null
    private var scrollListener: android.view.ViewTreeObserver.OnScrollChangedListener? = null
    private val visRect = Rect()

    init {
        // Transparent at rest so the cell's own thumbnail + play icon show through.
        isClickable = true
        setOnClickListener { onTap() }
        // Match the feed cell's ~10dp rounded corners (it clips its thumbnail to a round rect).
        setClipToOutlineCompat(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 10.dp.toFloat())
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val p = parent as? View ?: return
        hostView = p
        val l = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitToHost() }
        sizeListener = l
        p.addOnLayoutChangeListener(l)
        post { fitToHost() }
        // Pause playback when the feed scrolls this off-screen or the user switches to another page
        // (the QZone fragment isn't destroyed on page switch, so onDetachedFromWindow won't fire).
        val sl = android.view.ViewTreeObserver.OnScrollChangedListener { pauseIfOffscreen() }
        scrollListener = sl
        viewTreeObserver.addOnScrollChangedListener(sl)
    }

    private fun pauseIfOffscreen() {
        val player = mp ?: return
        if (!player.isPlaying) return
        val visible = isShown && getGlobalVisibleRect(visRect)
        if (!visible) {
            runCatching { player.pause() }
            showPauseIcon(true)
        }
    }

    /**
     * Force-size ourselves to the (un-measuring) cell, since it won't lay us out. Always re-measures
     * so newly-added children (the TextureView added on tap) actually get measured/laid out — the
     * cell ignores their requestLayout, so without this they'd stay 0×0 (audio plays, no video).
     */
    private fun fitToHost() {
        val p = hostView ?: return
        val w = p.width
        val h = p.height
        if (w <= 0 || h <= 0) return
        measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
        layout(0, 0, w, h)
        invalidateOutline()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sizeListener?.let { l -> hostView?.removeOnLayoutChangeListener(l) }
        scrollListener?.let { l -> runCatching { viewTreeObserver.removeOnScrollChangedListener(l) } }
        sizeListener = null
        scrollListener = null
        hostView = null
        release()
    }

    private fun onTap() {
        if (!started) start() else togglePlay()
    }

    private fun start() {
        started = true
        val tv = TextureView(context)
        textureView = tv
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val pb = M3CircularProgress(context)
        spinner = pb
        addView(pb, LayoutParams(32.dp, 32.dp, Gravity.CENTER))
        // Newly-added children won't be laid out by the (non-measuring) cell — re-fit ourselves so
        // the TextureView gets a real size (otherwise: audio only, blank video).
        post { fitToHost() }
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                prepare()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { surface = null; return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun prepare() {
        val s = surface ?: return
        runCatching {
            val player = MediaPlayer()
            player.setSurface(s)
            player.setDataSource(videoUrl)
            player.isLooping = true
            player.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) { videoW = w; videoH = h; resizeTexture() }
            }
            player.setOnPreparedListener {
                spinner?.visibility = View.GONE
                resizeTexture()
                it.start()
                Utils.log("InlineVideoView: playing $videoUrl")
            }
            player.setOnErrorListener { _, what, extra ->
                Utils.log("InlineVideoView: error what=$what extra=$extra")
                if (!releasing) runCatching { Utils.toast(context, "视频播放失败") }
                true
            }
            mp = player
            player.prepareAsync()
        }.onFailure {
            Utils.log("InlineVideoView: setup failed: $it")
            started = false
        }
    }

    private fun togglePlay() {
        val player = mp ?: return
        runCatching {
            if (player.isPlaying) {
                player.pause()
                showPauseIcon(true)
            } else {
                player.start()
                showPauseIcon(false)
            }
        }
    }

    private fun showPauseIcon(show: Boolean) {
        if (show) {
            val icon = pauseIcon ?: ImageView(context).apply {
                setImageDrawable(MaterialSymbol(MaterialSymbols.play_arrow, Color.WHITE))
                addView(this, LayoutParams(48.dp, 48.dp, Gravity.CENTER))
            }.also { pauseIcon = it }
            icon.visibility = View.VISIBLE
        } else {
            pauseIcon?.visibility = View.GONE
        }
    }

    /** Fit the TextureView to the video aspect inside the cell (letterbox), centered. */
    private fun resizeTexture() {
        val tv = textureView ?: return
        if (videoW <= 0 || videoH <= 0) return
        val rw = width
        val rh = height
        if (rw == 0 || rh == 0) { tv.post { resizeTexture() }; return }
        val fit = minOf(rw.toFloat() / videoW, rh.toFloat() / videoH)
        val w = (videoW * fit).toInt()
        val h = (videoH * fit).toInt()
        val lp = tv.layoutParams as LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER
            tv.layoutParams = lp
            // requestLayout is ignored by the cell; re-fit ourselves to apply the new size.
            fitToHost()
        }
    }

    fun release() {
        releasing = true
        runCatching { mp?.setOnErrorListener(null) }
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        mp = null
        runCatching { surface?.release() }
        surface = null
    }
}
