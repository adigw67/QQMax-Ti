package momoi.mod.qqpro.hook

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.tencent.mobileqq.ptt.IQQRecorder
import com.tencent.mobileqq.ptt.IQQRecorderUtils
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.mobileqq.utils.RecordParams
import com.tencent.mobileqq.utils.RecordParams.RecorderParam
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.watch.ptt.AudioFileWriterNT
import com.tencent.qqnt.watch.ptt.PttRecordCallback
import com.tencent.qqnt.watch.ptt.api.ITranslateTextService
import com.tencent.qqnt.watch.ui.componet.permission.PermissionUtils
import androidx.core.content.ContextCompat
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.isGroup
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.util.Utils
import mqq.app.MobileQQ
import java.io.File
import java.lang.ref.WeakReference
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.ButtonSpinner
import momoi.mod.qqpro.lib.material.M3ProgressDrawable
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols

/**
 * 完全行内输入 voice messaging. Holding the voice button in the inline input bar records a PTT
 * directly — no native [VoiceInputFragment] page is opened. While held, two floating action targets
 * appear above the bar (slide-to-cancel on the left, slide-to-转文字/STT on the right) plus a live
 * timer and a volume pulse driven by the recorder's amplitude callback.
 *
 *  - release on the mic           → send as a voice (PTT) message
 *  - slide onto 取消 then release   → discard
 *  - slide onto 文 (STT) release    → recognize speech in place; the text is inserted into the inline
 *                                     box for editing (the recording itself is discarded)
 *
 * Recording mirrors VoiceInputFragment: [IQQRecorderUtils.createQQRecorder] driven with the watch's
 * default 16kHz SILK [RecorderParam]; STT mirrors VoiceInputFragment.startTranslate via
 * [ITranslateTextService.translateText] over the recorded file + its pcmforvad.pcm.
 *
 * This is a normal package object (NOT a @Mixin body), so the anonymous recorder/STT listener
 * classes it creates are fine.
 *
 * The recorder engine itself is context-agnostic: everything chat-specific (where the overlay is
 * hosted, which fragment requests RECORD_AUDIO, whether release-on-mic sends a PTT, and where STT
 * text streams to) is funnelled through a [VoiceHost]. The chat input bar uses [ChatVoiceHost]
 * (the default); other callers — e.g. the reusable [momoi.mod.qqpro.lib.material.M3QQEditText]
 * field — supply their own host so the same hold-to-record / slide-to-STT UI works in a plain
 * dialog with no chat context (release-on-mic just转文字 into the field).
 */
interface VoiceHost {
    /** Full-bounds container the recording overlay (scrim + slide targets) is attached to. */
    fun overlayContainer(anchor: View): ViewGroup?

    /** AndroidX fragment used to request RECORD_AUDIO (PermissionUtils navigates from it). */
    fun permissionFragment(anchor: View): Fragment?

    /** group flag passed to [ITranslateTextService.translateText]. */
    val isGroup: Boolean

    /** When false, releasing on the mic does STT (转文字) instead of sending a PTT message. */
    val supportsPtt: Boolean

    /** Send a finished recording as a PTT voice message. Only called when [supportsPtt]. */
    fun sendPtt(path: String, durationMs: Long)

    /** Streaming STT sink — begin/update(cumulative)/end, mirroring the native progressive fill. */
    fun beginStreaming()
    fun updateStreaming(text: String)
    fun endStreaming()
}

/** Default host: records/sends/STT-streams against the current chat ([CurrentContact] / inline box). */
object ChatVoiceHost : VoiceHost {
    override fun overlayContainer(anchor: View): ViewGroup? = AttachmentOverlay.aioContainer(anchor)
    override fun permissionFragment(anchor: View): Fragment? = AttachmentOverlay.aioFragment(anchor)
    override val isGroup: Boolean get() = CurrentContact.isGroup
    override val supportsPtt: Boolean get() = true
    override fun sendPtt(path: String, durationMs: Long) {
        runCatching {
            val element = buildPttElement(path, durationMs)
            val contact = Contact(CurrentContact.chatType, CurrentContact.peerUid, CurrentContact.guildId)
            MsgUtil.msgService.sendMsg(contact, 0L, arrayListOf(element),
                IOperateCallback { code, msg -> Utils.log("voice send result=$code msg=$msg") })
        }.onFailure { Utils.log("ChatVoiceHost sendPtt failed: $it") }
    }
    override fun beginStreaming() = InlineInput.beginStreamingText()
    override fun updateStreaming(text: String) = InlineInput.updateStreamingText(text)
    override fun endStreaming() = InlineInput.endStreamingText(null)
}

object VoiceRecord {
    private val main = Handler(Looper.getMainLooper())

    private val colorIdle = M3.surfaceContainerHigh
    private val colorBlue get() = M3.primary
    private val colorRed = M3.error
    private val scrim = 0xAA_000000.toInt()

    private enum class Target { SEND, CANCEL, STT }

    private var recorder: IQQRecorder? = null
    private var recording = false
    private var audioPath: String? = null
    private var pcmPath: String? = null
    private var startUptime = 0L
    private var target = Target.SEND
    private var pendingTarget = Target.SEND

    // The host driving the in-flight gesture (where the overlay lives, how it sends / streams STT).
    // Captured on ACTION_DOWN from the attached button's closure so several buttons (chat bar + a
    // dialog field) can be wired at once without one stealing the other's context. Only one
    // recording happens at a time, so a single shared field is sufficient.
    private var activeHost: VoiceHost = ChatVoiceHost

    // The inline mic button + its idle icon, so STT can swap it to a spinner while converting.
    private var buttonRef: WeakReference<ImageView>? = null
    private var buttonIcon: Drawable? = null
    private var sttRunning = false

    /** True while a recording is being converted to text (the mic shows a spinner). */
    val isConverting: Boolean get() = sttRunning

    /**
     * Set by the inline input bar; invoked on the main thread whenever [isConverting] flips. The bar
     * re-evaluates its mic/send button visibility so the spinner stays put until conversion finishes
     * (otherwise the first recognized character flips the mic to the send button, hiding the spinner).
     */
    var onConvertStateChanged: (() -> Unit)? = null

    // overlay views
    private var overlay: FrameLayout? = null
    private var cancelCircle: ImageView? = null
    private var sttCircle: ImageView? = null
    private var timerView: TextView? = null
    private var hintView: TextView? = null
    private var wave: WaveView? = null
    private var anchorContainerRef: WeakReference<ViewGroup>? = null

    private val timerTick = object : Runnable {
        override fun run() {
            if (!recording) return
            timerView?.text = formatMs(SystemClock.uptimeMillis() - startUptime)
            main.postDelayed(this, 100)
        }
    }

    // Hard stop at 60s, same as VoiceInputFragment. Treat as a normal release (send).
    private val autoStop = Runnable {
        if (recording) { target = Target.SEND; finish() }
    }

    /**
     * Wire a voice button to hold-to-record. Replaces the native PTT delegate. [host] decides where
     * the overlay lives and what release-on-mic does (chat: send a PTT; a plain field: 转文字 into it).
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(button: ImageView, host: VoiceHost = ChatVoiceHost) {
        buttonRef = WeakReference(button)
        button.setOnTouchListener { v, e ->
            // While a previous recording is being converted to text, the mic shows a spinner —
            // swallow touches so it can't be pressed again mid-conversion.
            if (sttRunning) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Adopt this button's host for the whole gesture (and the async STT that follows).
                    activeHost = host
                    buttonRef = WeakReference(button)
                    // The custom recorder talks to the mic directly (no native VoiceInputFragment),
                    // so RECORD_AUDIO must be granted first. If it isn't, kick off the same
                    // permission request the native PTT button uses and abort this gesture — the
                    // user presses again once granted.
                    if (!hasRecordPermission(v)) { requestRecordPermission(v); return@setOnTouchListener true }
                    // Stop the chat RecyclerView (and any other ancestor) from intercepting the
                    // gesture for scroll/overscroll — otherwise MOVE/UP get stolen the moment the
                    // finger slides toward the 取消/STT targets and we never see them.
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    start(v); true
                }
                MotionEvent.ACTION_MOVE -> { updateTarget(e.rawX, e.rawY); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    finish(); true
                }
                else -> false
            }
        }
    }

    // ---- permission ----

    private fun hasRecordPermission(anchor: View): Boolean =
        ContextCompat.checkSelfPermission(anchor.context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Request RECORD_AUDIO via the host AIO fragment, mirroring AudioTouchViewNTProcessor — it
     * navigates to QQ's permissionFragment and reports the result. We can't start recording in the
     * same gesture (the request is async), so just toast guidance; the next press records.
     */
    private fun requestRecordPermission(anchor: View) {
        val fragment = activeHost.permissionFragment(anchor)
        if (fragment == null) {
            Utils.log("VoiceRecord: no AIO fragment for permission request")
            Utils.toast(Utils.application, "无法请求录音权限")
            return
        }
        runCatching {
            PermissionUtils.a.a(
                "发送语音消息", fragment,
                arrayListOf(android.Manifest.permission.RECORD_AUDIO)
            ) { granted ->
                Utils.log("VoiceRecord: RECORD_AUDIO granted=$granted")
                if (granted) Utils.toast(Utils.application, "已授权，请再次按住录音")
                else Utils.toast(Utils.application, "未授予录音权限")
            }
        }.onFailure {
            Utils.log("VoiceRecord: permission request failed: $it")
            Utils.toast(Utils.application, "无法请求录音权限")
        }
    }

    // ---- recorder lifecycle ----

    private fun start(anchor: View) {
        if (recording) return
        // If the soft keyboard is up, drop it so the recording overlay isn't covered and the
        // slide targets sit at their intended place above the input pill.
        runCatching {
            (anchor.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(anchor.windowToken, 0)
        }
        runCatching {
            val ctx = Utils.application
            val app = MobileQQ.sMobileQQ.peekAppRuntime()
            val p = RecordParams.b(app, false)
            val dir = ctx.getExternalFilesDir("audio") ?: File(ctx.cacheDir, "audio")
            val sub = File(dir, if (p.d == 1) "silk" else "amr")
            if (!sub.exists()) sub.mkdirs()
            audioPath = File(sub, System.currentTimeMillis().toString()).absolutePath
            pcmPath = File(dir, "pcmforvad.pcm").absolutePath

            val rec = QRoute.api(IQQRecorderUtils::class.java).createQQRecorder(ctx)
            recorder = rec
            val cb = PttRecordCallback(null, AudioFileWriterNT(null as TextView?))
            // PttRecordCallback wraps the file writer (field b) and forwards events to the panel
            // listener (field c) — same as VoiceInputFragment's recordPanel assignment.
            cb.c = listener
            rec.c(p)
            rec.d(pcmPath)
            rec.f(cb)
            rec.a(audioPath)

            recording = true
            target = Target.SEND
            startUptime = SystemClock.uptimeMillis()
            showOverlay(anchor)
            main.post(timerTick)
            main.postDelayed(autoStop, 60_000L)
            Utils.log("VoiceRecord: start -> $audioPath fmt=${p.d}")
        }.onFailure {
            Utils.log("VoiceRecord start failed: $it")
            recording = false
            hideOverlay()
            Utils.toast(Utils.application, "无法开始录音")
        }
    }

    private fun finish() {
        if (!recording) return
        recording = false
        pendingTarget = target
        main.removeCallbacks(autoStop)
        main.removeCallbacks(timerTick)
        hideOverlay()
        // stop() finalizes the file and fires listener.g (onRecorderEnd) where we send / STT / drop.
        runCatching { recorder?.stop() }.onFailure { Utils.log("VoiceRecord stop failed: $it") }
        Utils.log("VoiceRecord: release target=$pendingTarget")
    }

    private val listener = object : IQQRecorder.OnQQRecorderListener {
        override fun a() {}
        override fun b(path: String?, slice: ByteArray?, size: Int, maxAmplitude: Int, time: Double, p: RecorderParam?) {
            main.post { onVolume(maxAmplitude) }
        }
        override fun c(path: String?, p: RecorderParam?) {}
        override fun d(path: String?, p: RecorderParam?) {}
        override fun e(path: String?, p: RecorderParam?) {}
        override fun f(): Int = 250
        override fun g(path: String?, p: RecorderParam?, totalTime: Double) {
            main.post { onRecorderEnd(path, totalTime) }
        }
        override fun h(path: String?, p: RecorderParam?, err: String?) {
            Utils.log("VoiceRecord onRecorderError $err")
        }
        override fun i(path: String?, p: RecorderParam?): Int = -1
        override fun j(state: Int) {}
    }

    private fun onRecorderEnd(path: String?, totalTimeMs: Double) {
        val t = pendingTarget
        if (t == Target.CANCEL) { runCatching { path?.let { File(it).delete() } }; return }
        if (path.isNullOrEmpty() || !File(path).exists()) {
            Utils.log("VoiceRecord: no recorded file"); return
        }
        if (totalTimeMs < 500.0) {
            Utils.toast(Utils.application, "说话时间太短")
            runCatching { File(path).delete() }
            return
        }
        when (t) {
            // Release on the mic: send a PTT where the host supports it (chat), otherwise fall back
            // to 转文字 so a voiceless field (e.g. a nickname dialog) still gets the spoken text.
            Target.SEND ->
                if (activeHost.supportsPtt) Thread { sendVoice(path, totalTimeMs.toLong()) }.start()
                else startStt(path)
            Target.STT -> startStt(path)
            Target.CANCEL -> {}
        }
    }

    // ---- outcomes ----

    private fun sendVoice(path: String, durationMs: Long) {
        runCatching { activeHost.sendPtt(path, durationMs) }
            .onFailure { Utils.log("VoiceRecord sendVoice failed: $it") }
    }

    private fun startStt(audioFile: String) {
        val pcm = pcmPath
        if (pcm.isNullOrEmpty() || !File(pcm).exists()) {
            Utils.toast(Utils.application, "转换失败"); return
        }
        Utils.toast(Utils.application, "正在转文字…")
        // Spinner on the mic + a live streaming insertion, so the recognized text fills in
        // progressively (same as the native VoiceInputFragment) and the button can't be re-pressed.
        beginSttUi()
        activeHost.beginStreaming()
        runCatching {
            val app = MobileQQ.sMobileQQ.peekAppRuntime()
            val svc = app.getRuntimeService(ITranslateTextService::class.java, "")
            svc.translateText(activeHost.isGroup, File(pcm), File(audioFile),
                object : ITranslateTextService.AbsTranslateTextCallback() {
                    override fun b(isSuccess: Boolean, isLast: Boolean, text: String, curKey: String) {
                        if (!isSuccess) {
                            main.post { endStt(); Utils.toast(Utils.application, "转换失败") }
                            return
                        }
                        main.post {
                            // text is cumulative — keep replacing the streamed range in real time.
                            activeHost.updateStreaming(text)
                            if (isLast) {
                                activeHost.endStreaming()
                                if (text.isEmpty()) Utils.toast(Utils.application, "没有识别到内容")
                                endStt()
                                runCatching { File(audioFile).delete() }
                            }
                        }
                    }
                })
        }.onFailure {
            Utils.log("VoiceRecord stt failed: $it")
            main.post { endStt(); Utils.toast(Utils.application, "转换失败") }
        }
    }

    /** Swap the inline mic icon for an indeterminate M3 spinner while STT runs. */
    private fun beginSttUi() {
        sttRunning = true
        onConvertStateChanged?.invoke()
        val btn = buttonRef?.get() ?: return
        ButtonSpinner.start(btn)
    }

    /** Restore the mic icon and re-enable presses; also resets any half-finished stream state. */
    private fun endStt() {
        sttRunning = false
        activeHost.endStreaming()
        buttonRef?.get()?.let { ButtonSpinner.stop(it) }
        // Now that conversion is done, let the bar flip the mic → send button for the inserted text.
        onConvertStateChanged?.invoke()
    }

    // ---- gesture target tracking ----

    private fun updateTarget(rawX: Float, rawY: Float) {
        if (!recording) return
        val t = when {
            overCircle(cancelCircle, rawX, rawY) -> Target.CANCEL
            overCircle(sttCircle, rawX, rawY) -> Target.STT
            else -> Target.SEND
        }
        if (t != target) { target = t; refreshTargetState() }
    }

    private fun overCircle(v: View?, x: Float, y: Float): Boolean {
        v ?: return false
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        val cx = loc[0] + v.width / 2f
        val cy = loc[1] + v.height / 2f
        val r = v.width * 0.9f          // generous hit radius (slide doesn't need precision)
        val dx = x - cx; val dy = y - cy
        return dx * dx + dy * dy <= r * r
    }

    // ---- overlay UI (Material-ish circular targets + timer + volume pulse) ----

    private fun circleBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun makeCircle(symbolPath: String): ImageView =
        ImageView(Utils.application).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = 17.dp
            setPadding(pad, pad, pad, pad)
            // Icon sits on the dark idle circle (and on red/blue when a target is active), so use
            // onSurface (white) — NOT onPrimary, which is the filled-accent label color and resolves
            // to a dark tone on light accents (invisible on the dark circle).
            setImageDrawable(MaterialSymbol(symbolPath, M3.onSurface))
            background = circleBg(colorIdle)
        }

    private fun showOverlay(anchor: View) {
        val container = activeHost.overlayContainer(anchor) ?: run {
            Utils.log("VoiceRecord: no overlay container"); return
        }
        hideOverlay()
        anchorContainerRef = WeakReference(container)

        val ctx = anchor.context
        val root = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(scrim)
            isClickable = false   // the gesture stays captured by the voice button
            // The volume pulse scales up past its bounds; don't clip it at the container edges.
            clipChildren = false
            clipToPadding = false
        }

        val side = 56.dp
        val edge = 36.dp
        // 取消 (left) and 转文字 (right) targets sit low, just above the input pill, so the
        // finger only has to slide a short distance down-left / down-right to reach them.
        val cancel = makeCircle(MaterialSymbols.close)
        root.addView(cancel, FrameLayout.LayoutParams(side, side).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = edge; bottomMargin = 16.dp
        })
        val stt = makeCircle(MaterialSymbols.record_voice_over)
        root.addView(stt, FrameLayout.LayoutParams(side, side).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = edge; bottomMargin = 16.dp
        })

        // Top column: timer + hint up top, away from the slide targets.
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val timer = TextView(ctx).apply {
            // The overlay sits on the dark [scrim], so contrast against THAT (not the theme surface) —
            // otherwise M3.onSurface turns dark in light mode and the timer vanishes on the dark scrim.
            text = "0:00"; setTextColor(M3.onColor(scrim)); textSize = 18f; gravity = Gravity.CENTER
        }
        column.addView(timer)
        val hint = TextView(ctx).apply {
            text = "松开发送"; setTextColor(0xCC_FFFFFF.toInt()); textSize = 12f; gravity = Gravity.CENTER
        }
        column.addView(hint, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6.dp
        })
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 28.dp
        })

        // Center wave indicator: a flat horizontal line when idle, swelling out from the center
        // like a sine wave while speaking.
        val waveView = WaveView(ctx)
        root.addView(waveView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 80.dp).apply {
            gravity = Gravity.CENTER
        })

        container.addView(root)
        overlay = root
        cancelCircle = cancel
        sttCircle = stt
        timerView = timer
        hintView = hint
        wave = waveView
        waveView.start()
        refreshTargetState()
    }

    private fun refreshTargetState() {
        cancelCircle?.background = circleBg(if (target == Target.CANCEL) colorRed else colorIdle)
        sttCircle?.background = circleBg(if (target == Target.STT) colorBlue else colorIdle)
        hintView?.text = when (target) {
            Target.CANCEL -> "松开取消"
            Target.STT -> "松开转文字"
            Target.SEND -> "松开发送，上滑取消"
        }
    }

    private fun onVolume(maxAmplitude: Int) {
        // Map the raw amplitude (0..~32767) to a 0..1 level driving the wave swell.
        val lvl = (maxAmplitude / 8000f).coerceIn(0f, 1f)
        wave?.setLevel(lvl)
    }

    private fun hideOverlay() {
        wave?.stop()
        val root = overlay
        if (root != null) main.post { (root.parent as? ViewGroup)?.removeView(root) }
        overlay = null; cancelCircle = null; sttCircle = null
        timerView = null; hintView = null; wave = null
    }

    /**
     * Voice level indicator. Idle: a flat horizontal line. While speaking: a sine wave whose
     * amplitude is largest at the center and tapers to zero at the edges, so the line appears to
     * swell outward from the middle. The phase animates continuously; the level smooths toward
     * the latest amplitude and decays back to a line when quiet.
     */
    @SuppressLint("ViewConstructor")
    private class WaveView(ctx: android.content.Context) : View(ctx) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f.dpf
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = M3.primary
        }
        private val path = android.graphics.Path()
        private var phase = 0f
        private var level = 0f        // smoothed current level
        private var target = 0f       // latest amplitude target
        private var animating = false

        private val frame = object : Runnable {
            override fun run() {
                if (!animating) return
                phase += 0.35f
                // ease level toward target, then decay the target so it settles back to a line
                level += (target - level) * 0.3f
                target *= 0.85f
                invalidate()
                postOnAnimation(this)
            }
        }

        fun start() { if (!animating) { animating = true; postOnAnimation(frame) } }
        fun stop() { animating = false; removeCallbacks(frame) }
        fun setLevel(l: Float) { if (l > target) target = l }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f) return
            val midY = h / 2f
            val maxAmp = h * 0.42f
            path.reset()
            val steps = 64
            for (i in 0..steps) {
                val x = w * i / steps
                val t = i.toFloat() / steps           // 0..1
                // window peaks at center (sin over 0..pi), tapers to 0 at both ends
                val window = kotlin.math.sin(t * Math.PI).toFloat()
                val y = midY + (kotlin.math.sin(t * Math.PI * 6 + phase).toFloat()
                    * maxAmp * level * window)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
    }
}
