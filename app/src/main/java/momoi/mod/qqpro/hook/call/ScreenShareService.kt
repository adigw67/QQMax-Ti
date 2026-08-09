package momoi.mod.qqpro.hook.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import momoi.mod.qqpro.util.Utils

/**
 * `mediaProjection` foreground service that mirrors the screen into an ImageReader, converts each
 * RGBA frame to NV21 and feeds it to [ScreenShare.feedFrame]. Must be a foreground service of type
 * mediaProjection (Android 14+) started BEFORE `getMediaProjection`, which we do in [onStartCommand].
 */
class ScreenShareService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var nv21: ByteArray? = null
    private var plan: ScreenShare.CapturePlan? = null
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        // startForeground(id, notif, foregroundServiceType) is API 29+; the 3-arg overload doesn't
        // exist on older devices (this watch is API 27) → NoSuchMethodError. Only pass the type on 29+.
        val notification = buildNotification()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTI_ID, notification)
        }

        runCatching { startCapture(code, data) }.onFailure {
            Utils.log("ScreenShareService: startCapture failed: $it")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        val metrics = resources.displayMetrics
        val p = ScreenShare.capturePlan(metrics.widthPixels, metrics.heightPixels)
        plan = p
        nv21 = ByteArray(p.outW * p.outH * 3 / 2)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return }
        projection = mp

        handlerThread = HandlerThread("qqpro-screenshare").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        // Required on Android 14+: register a callback so the projection can be revoked cleanly.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Utils.log("ScreenShareService: projection stopped by system"); stopSelf() }
        }, handler)

        val dpi = resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT
        val ir = ImageReader.newInstance(p.captureW, p.captureH, PixelFormat.RGBA_8888, 3)
        reader = ir
        ir.setOnImageAvailableListener({ onFrame(it) }, handler)

        virtualDisplay = mp.createVirtualDisplay(
            "qqpro-screenshare", p.captureW, p.captureH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface, null, handler,
        )
        ScreenShare.onServiceStarted(applicationContext)
        Utils.log("ScreenShareService: capturing ${p.captureW}x${p.captureH} portraitCrop=${p.outW}x${p.outH}@(${p.cropLeft},${p.cropTop}) -> land ${p.outH}x${p.outW} orient=$SEND_ORIENTATION dpi=$dpi")
    }

    private fun onFrame(ir: ImageReader) {
        // If the share is stopping the ImageReader may close mid-frame → "buffer is inaccessible".
        if (busy || !ScreenShare.sharing) { runCatching { ir.acquireLatestImage()?.close() }; return }
        busy = true
        runCatching {
            val p = plan ?: run { busy = false; return }
            val image = ir.acquireLatestImage() ?: run { busy = false; return }
            image.use { img ->
                val plane = img.planes[0]
                val buf = plane.buffer
                rgbaToNv21Rotated(buf, p, plane.pixelStride, plane.rowStride, nv21!!)
            }
            // Sent buffer is the LANDSCAPE encoder frame (portrait crop rotated 90°): w=cropH, h=cropW.
            // Orientation is FIXED (paired with ROTATE_CW) — the live camera value flips between 1 and 3
            // across calls, and since our pre-rotation is fixed, a varying value flips the peer image
            // upside-down. A constant orient + constant rotation is always upright.
            ScreenShare.feedFrame(nv21!!, p.outH, p.outW, SEND_ORIENTATION)
        }.onFailure { Utils.log("ScreenShareService: onFrame failed: $it") }
        busy = false
    }

    /**
     * BT.601 video-range RGBA → NV21 (Y plane + interleaved VU), 2x2 chroma subsample, WITH a 90°
     * rotation. The screen is captured upright/portrait, but the video encoder frame is LANDSCAPE
     * (the camera feeds landscape + an orientation index the peer rotates by). So we rotate the
     * portrait crop (outW x outH, at cropLeft/cropTop in the captured buffer) into a landscape
     * outH x outW buffer here; [ScreenShare.feedFrame] then tags it with the camera's orientation so
     * the peer rotates it back upright. [ROTATE_CW] flips the rotation direction if it comes out
     * upside-down.
     */
    private fun rgbaToNv21Rotated(
        buf: java.nio.ByteBuffer, p: ScreenShare.CapturePlan, pixelStride: Int, rowStride: Int, out: ByteArray,
    ) {
        val cropW = p.outW           // portrait crop width  (e.g. 480)
        val cropH = p.outH           // portrait crop height (e.g. 640)
        val ow = cropH               // landscape output width  (e.g. 640)
        val oh = cropW               // landscape output height (e.g. 480)
        val frameSize = ow * oh
        var yIndex = 0
        var uvIndex = frameSize
        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                // Map landscape output pixel back to the portrait crop, applying a 90° rotation.
                val cx: Int
                val cy: Int
                if (ROTATE_CW) { cx = oy; cy = cropH - 1 - ox } else { cx = cropW - 1 - oy; cy = ox }
                val off = (p.cropTop + cy) * rowStride + (p.cropLeft + cx) * pixelStride
                val r = buf.get(off).toInt() and 0xFF
                val g = buf.get(off + 1).toInt() and 0xFF
                val b = buf.get(off + 2).toInt() and 0xFF
                val yy = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                out[yIndex++] = yy.coerceIn(0, 255).toByte()
                if (oy % 2 == 0 && ox % 2 == 0) {
                    val u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                    val v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128
                    out[uvIndex++] = v.coerceIn(0, 255).toByte()
                    out[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        runCatching { handlerThread?.quitSafely() }
        virtualDisplay = null; reader = null; projection = null; handlerThread = null; handler = null
        ScreenShare.onServiceStopped(applicationContext)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Channels / Notification.Builder(channel) are API 26+; the old watch ROM predates them, so
        // keep the pre-O path building a plain notification (same crash class as getSystemService
        // and finishAndRemoveTask — NoSuchMethodError on the old framework).
        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "屏幕共享", NotificationManager.IMPORTANCE_LOW),
                )
            }
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("屏幕共享进行中")
            .setContentText("正在将屏幕共享到通话")
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "qqpro_screenshare"
        private const val NOTI_ID = 0x5C31

        // Pre-rotation direction into the landscape encoder frame, and the orientation index we tell
        // the peer to rotate BACK by. They are a matched pair: ROTATE_CW=true (pre-rotate +90°) pairs
        // with orient=3 (peer rotates 270°) → net 0 → upright. If a device shows it inverted, flip
        // BOTH together (ROTATE_CW=false with SEND_ORIENTATION=1).
        private const val ROTATE_CW = true
        private const val SEND_ORIENTATION = 3
    }
}
