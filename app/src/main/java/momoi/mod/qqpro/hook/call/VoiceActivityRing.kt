package momoi.mod.qqpro.hook.call

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.media.audiofx.Visualizer
import android.widget.FrameLayout
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils
import kotlin.math.sqrt

/**
 * 声音活动指示 — a voice-activity ring drawn AROUND the call avatar in the Material call UI. It softly
 * pulses with the call's audio level so you can see when the other side is talking / the line is live.
 *
 * The level comes from a [Visualizer] on the output mix (session 0): it reads the audio being PLAYED, so
 * it reflects the remote voice and — crucially — never opens the microphone, so it can't disturb the
 * call's capture/uplink. If the Visualizer is unavailable (permission / ROM), the ring simply stays idle.
 *
 * Designed to be small-screen friendly: all sizes derive from the view's own dimensions (it's laid out at
 * ~1.3× the avatar), the ring is thin, and the pulse is subtle. Self-managing: it starts the Visualizer on
 * attach and releases it on detach, so it needs no external lifecycle wiring. Hosts the avatar as its child
 * (add the avatar into this) and draws the ring behind it.
 */
class VoiceActivityRing(context: Context) : FrameLayout(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = M3.primary
    }
    @Volatile private var target = 0f  // latest measured level 0..1
    private var level = 0f             // smoothed level actually drawn
    private var visualizer: Visualizer? = null

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (visualizer != null) return
        runCatching {
            val v = Visualizer(0) // 0 = global output mix
            v.captureSize = Visualizer.getCaptureSizeRange()[0]
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vz: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        waveform ?: return
                        var sum = 0L
                        for (b in waveform) {
                            val a = (b.toInt() and 0xFF) - 128 // bytes centre at 128
                            sum += (a * a).toLong()
                        }
                        val rms = sqrt(sum.toDouble() / waveform.size) / 128.0 // 0..1
                        // Emphasise the quiet-to-loud range a bit so speech reads clearly, cap at 1.
                        target = (rms * 3.5).coerceIn(0.0, 1.0).toFloat()
                        postInvalidateOnAnimation()
                    }

                    override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
                },
                (Visualizer.getMaxCaptureRate() / 2).coerceAtMost(20000),
                true, // waveform
                false, // fft
            )
            v.enabled = true
            visualizer = v
        }.onFailure { Utils.log("VoiceActivityRing: visualizer unavailable: $it") }
    }

    private fun stop() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Smooth: fast attack, slow decay, so the ring swells on speech and eases back.
        level += ((target - level) * if (target > level) 0.5f else 0.12f)
        if (target > 0f || level > 0.01f) postInvalidateOnAnimation()

        val cx = width / 2f
        val cy = height / 2f
        val avatarR = minOf(width, height) / 2f * 0.77f // avatar is ~1/1.3 of the view
        val maxExtra = (minOf(width, height) / 2f) - avatarR
        val stroke = maxExtra * 0.32f
        paint.strokeWidth = stroke

        // A faint static ring, plus an expanding ring that grows + fades with the level.
        paint.alpha = 40
        canvas.drawCircle(cx, cy, avatarR + stroke, paint)
        if (level > 0.02f) {
            paint.alpha = (110 + 120 * level).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, avatarR + stroke + maxExtra * 0.55f * level, paint)
        }

        super.dispatchDraw(canvas) // avatar on top
    }
}
