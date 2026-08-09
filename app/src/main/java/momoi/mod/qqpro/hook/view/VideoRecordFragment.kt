package momoi.mod.qqpro.hook.view

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import momoi.mod.qqpro.hook.sendInAppVideo
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.util.Utils
import java.io.File

/**
 * Self-contained in-app video recorder. QQ's in-app camera (CameraFragment) is photo-only and
 * qqcamerakit's recording paths are stubbed on this ROM, so we drive a plain legacy [Camera] +
 * [MediaRecorder] against a [SurfaceView] preview (SurfaceHolder is the documented-reliable combo
 * for Camera+MediaRecorder; TextureView's surface conflicts with the encoder). On confirm the mp4
 * is handed to [sendInAppVideo] and the chat ViewPager is switched back to page 0.
 *
 * This is NOT a @Mixin class (it lives in our package), so the anonymous listeners below are safe.
 *
 * [host] is the "+" panel fragment (MenuFrame); its view sits inside the chat ViewPager2, which we
 * walk up to in order to return to the chat page (a DialogFragment dismiss does NOT fire the chat
 * fragment's onResume, so the goToChatOnResume flag alone can't switch the page here).
 */
class VideoRecordFragment(
    private val host: Fragment? = null,
    // Photo mode: a single still capture instead of video recording (in-app camera for QZone 拍照).
    private val photo: Boolean = false,
    // When set, the recorded/captured file path is returned here instead of being sent to the current
    // chat (used by the QZone compose screen to attach the media to a post).
    private val onComplete: ((String) -> Unit)? = null,
) : MyDialogFragment() {

    private var camera: Camera? = null
    private var recorder: MediaRecorder? = null
    private var holder: SurfaceHolder? = null
    private var outputPath: String? = null

    private var previewing = false
    private var recording = false
    private var recordStartAt = 0L
    private val ui = Handler(Looper.getMainLooper())

    private var timerText: TextView? = null
    private var recordButton: View? = null
    private var confirmBar: LinearLayout? = null

    private val timerTick = object : Runnable {
        override fun run() {
            if (!recording) return
            timerText?.text = formatTime(SystemClock.elapsedRealtime() - recordStartAt)
            ui.postDelayed(this, 500)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        val root = FrameLayout(ctx)
        root.setBackgroundColor(Color.BLACK)

        val sv = SurfaceView(ctx)
        root.addView(sv, FrameLayout.LayoutParams(-1, -1))
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                holder = h
                openCamera(h)
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { holder = null }
        })

        val timer = TextView(ctx).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(12.dp, 4.dp, 12.dp, 4.dp)
            // Semi-transparent pill so the time stays legible over bright scenes.
            background = GradientDrawable().apply {
                setColor(0x80_000000.toInt())
                cornerRadius = 999f
            }
            visibility = View.GONE
        }
        timerText = timer
        root.addView(timer, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 12.dp
        })

        val btn = View(ctx).apply {
            background = recordButtonDrawable(stop = false)
            setOnClickListener { if (photo) takePhoto() else toggleRecording() }
        }
        recordButton = btn
        root.addView(btn, FrameLayout.LayoutParams(56.dp, 56.dp).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 16.dp
        })

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        bar.addView(m3Button(ctx, "重拍", M3Button.Variant.TONAL) { retake() })
        bar.addView(m3Button(ctx, "发送", M3Button.Variant.FILLED) { sendRecorded() })
        confirmBar = bar
        root.addView(bar, FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 14.dp
        })

        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun openCamera(h: SurfaceHolder) {
        runCatching {
            val cam = Camera.open()
            camera = cam
            cam.setDisplayOrientation(90)
            cam.setPreviewDisplay(h)
            cam.startPreview()
            previewing = true
            Utils.log("recorder: camera preview started")
        }.onFailure {
            Utils.log("recorder: openCamera failed: $it")
            runCatching { Utils.toast(requireContext(), "无法打开相机") }
            dismissAllowingStateLoss()
        }
    }

    /** Photo mode: capture one still, save a JPEG, and show the 重拍/发送 confirm bar. */
    private fun takePhoto() {
        val cam = camera ?: return
        recordButton?.visibility = View.GONE
        runCatching {
            // takePicture ignores setDisplayOrientation, so set the JPEG rotation explicitly or the
            // saved photo comes out rotated 90°.
            runCatching {
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(0, info)
                val rot = requireActivity().windowManager.defaultDisplay.rotation
                val deg = when (rot) {
                    android.view.Surface.ROTATION_90 -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val jpeg = (info.orientation - deg + 360) % 360
                val p = cam.parameters
                p.setRotation(jpeg)
                cam.parameters = p
                Utils.log("recorder: jpeg rotation=$jpeg (sensor=${info.orientation} dev=$deg)")
            }
            cam.takePicture(null, null, Camera.PictureCallback { data, _ ->
                runCatching {
                    val dir = File(requireContext().getExternalFilesDir("qzone_photo") ?: requireContext().cacheDir, "")
                    dir.mkdirs()
                    val f = File(dir, "qz_photo_${System.currentTimeMillis()}.jpg")
                    f.outputStream().use { it.write(data) }
                    outputPath = f.path
                    previewing = false
                    Utils.log("recorder: photo saved ${f.path} (${f.length()} bytes)")
                    confirmBar?.visibility = View.VISIBLE
                }.onFailure { Utils.log("recorder: save photo failed: $it"); retake() }
            })
        }.onFailure {
            Utils.log("recorder: takePicture failed: $it")
            recordButton?.visibility = View.VISIBLE
            runCatching { cam.startPreview() }
        }
    }

    private fun toggleRecording() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val cam = camera
        val h = holder
        if (cam == null || h == null) { Utils.log("recorder: start ignored, camera/holder null"); return }
        runCatching {
            val dir = requireContext().getExternalFilesDir("videos") ?: requireContext().filesDir
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "qqpro_rec_${System.currentTimeMillis()}.mp4")
            outputPath = file.path

            cam.unlock()
            val rec = MediaRecorder()
            rec.setOnErrorListener { _, what, extra -> Utils.log("recorder: MediaRecorder error what=$what extra=$extra") }
            rec.setOnInfoListener { _, what, extra -> Utils.log("recorder: MediaRecorder info what=$what extra=$extra") }
            rec.setCamera(cam)
            rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            rec.setVideoSource(MediaRecorder.VideoSource.CAMERA)
            rec.setProfile(pickProfile())
            rec.setOutputFile(file.path)
            rec.setPreviewDisplay(h.surface)
            rec.setOrientationHint(90)
            rec.prepare()
            rec.start()
            recorder = rec
            previewing = false

            recording = true
            recordStartAt = SystemClock.elapsedRealtime()
            recordButton?.background = recordButtonDrawable(stop = true)
            timerText?.visibility = View.VISIBLE
            timerText?.text = "00:00"
            ui.post(timerTick)
            Utils.log("recorder: started -> ${file.path}")
        }.onFailure {
            Utils.log("recorder: startRecording failed: $it")
            runCatching { Utils.toast(requireContext(), "录像启动失败") }
            recording = false
            releaseRecorder()
            recoverPreview()
        }
    }

    private fun stopRecording() {
        // Guard against stopping before the encoder has captured anything (causes stop() to throw).
        val recordedMs = SystemClock.elapsedRealtime() - recordStartAt
        if (recordedMs < 1200) {
            Utils.log("recorder: stop ignored, only ${recordedMs}ms recorded")
            runCatching { Utils.toast(requireContext(), "录制时间太短") }
            return
        }
        recording = false
        ui.removeCallbacks(timerTick)
        var ok = false
        runCatching {
            recorder?.stop()
            ok = true
        }.onFailure { Utils.log("recorder: stop failed: $it") }
        releaseRecorder()
        recordButton?.visibility = View.GONE

        val valid = ok && (outputPath?.let { File(it).length() > 0 } == true)
        if (valid) {
            confirmBar?.visibility = View.VISIBLE
            // Switch the chat underneath to page 0 now, so it's already showing once the dialog closes.
            switchChatToFirstPage()
            Utils.log("recorder: stopped ok, awaiting confirm -> $outputPath (${File(outputPath!!).length()} bytes)")
        } else {
            Utils.log("recorder: stop produced no valid file, retaking")
            runCatching { Utils.toast(requireContext(), "录制失败，请重试") }
            retake()
        }
    }

    private fun retake() {
        outputPath?.let { runCatching { File(it).delete() } }
        outputPath = null
        confirmBar?.visibility = View.GONE
        recordButton?.background = recordButtonDrawable(stop = false)
        recordButton?.visibility = View.VISIBLE
        timerText?.visibility = View.GONE
        recoverPreview()
    }

    /** Re-acquire the live preview after a (failed) record attempt so the user can retry. */
    private fun recoverPreview() {
        val cam = camera ?: return
        runCatching {
            cam.lock()
            holder?.let { cam.setPreviewDisplay(it) }
            cam.startPreview()
            previewing = true
            Utils.log("recorder: preview recovered")
        }.onFailure { Utils.log("recorder: recoverPreview failed: $it") }
    }

    private fun sendRecorded() {
        val path = outputPath
        if (path == null) { dismissAllowingStateLoss(); return }
        outputPath = null // prevent onDestroyView from deleting the file we're sending/returning
        val cb = onComplete
        if (cb != null) {
            runCatching { cb(path) }.onFailure { Utils.log("recorder onComplete: $it") }
            dismissAllowingStateLoss()
            return
        }
        sendInAppVideo(path)
        switchChatToFirstPage()
        dismissAllowingStateLoss()
    }

    /** Walk up the host panel's view tree to the chat ViewPager2 and switch to page 0. */
    private fun switchChatToFirstPage() {
        runCatching {
            var v: View? = host?.view
            while (v != null) {
                if (v.javaClass.name == "androidx.viewpager2.widget.ViewPager2") {
                    v.javaClass.getMethod("setCurrentItem", Int::class.java).invoke(v, 0)
                    Utils.log("recorder: switched chat to page 0")
                    return
                }
                v = v.parent as? View
            }
            Utils.log("recorder: ViewPager2 not found from host view")
        }.onFailure { Utils.log("recorder: switchChatToFirstPage failed: $it") }
    }

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ui.removeCallbacks(timerTick)
        if (recording) runCatching { recorder?.stop() }
        recording = false
        releaseRecorder()
        runCatching { if (previewing) camera?.stopPreview() }
        runCatching { camera?.release() }
        camera = null
        previewing = false
        outputPath?.let { runCatching { File(it).delete() } }
    }

    private fun pickProfile(): CamcorderProfile = when {
        CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_480P) ->
            CamcorderProfile.get(CamcorderProfile.QUALITY_480P)
        CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_LOW) ->
            CamcorderProfile.get(CamcorderProfile.QUALITY_LOW)
        else -> CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH)
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }

    // Shutter: a red dot (idle) → white dot (recording = tap to stop), ringed in white; themed via M3.
    private fun recordButtonDrawable(stop: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (stop) M3.onSurface else M3.error)
        setStroke(4.dp, 0x66_FFFFFF.toInt())
    }

    private fun m3Button(
        ctx: android.content.Context,
        label: String,
        variant: M3Button.Variant,
        onClick: () -> Unit
    ) = M3Button(ctx).variant(variant).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = 8.dp; marginEnd = 8.dp }
    }
}
