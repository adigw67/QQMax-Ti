package momoi.mod.qqpro.hook.call
import android.os.Build
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.activitys.BeInvitedActivity
import com.tencent.activitys.QQNTC2CWatchActivity
import loadPicUrl
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils
import java.util.WeakHashMap

/**
 * 全新通话界面 (materializeCall) — a call screen built ENTIRELY from scratch for the round watch.
 *
 * We don't re-theme or reuse QQ's native layout. All native call views are DETACHED from the tree (QQ
 * re-shows GONE views on its state callbacks, so hiding isn't enough — only removeView sticks); the control
 * buttons are detached too but still referenced, so our own buttons drive them via `performClick()`. Call
 * state comes from QQ's `IQavBusinessCallback` ([CallCallbackHook] forwards `d`/`M`/`S`) so the timer starts
 * only at answer and the mic/camera icons reflect real state — event-driven, no polling. For a video call
 * the GL surface stays in place (rendering behind our transparent overlay); reparenting it kills its EGL.
 *
 * All sizes are SCREEN-RELATIVE (fractions of the min screen dimension), because the watch runs at a high
 * effective density (a dp is ~2.4px) that made fixed-dp layouts overflow the 480px round face.
 */
object MaterialCallUi {
    private const val STAGE_TAG = "qqpro_call_stage"

    /**
     * Responsive sizing that fits ANY device (round watch or phone). Every size is a fraction of the
     * screen's short edge — so the layout scales with the physical screen, not with density — but each
     * fraction is CAPPED in dp so it can't balloon on a large phone/tablet. On the watch a dp is ~2.4px,
     * so the dp caps are huge numbers there and never bind; the tuned watch fractions pass through
     * unchanged. On a phone a dp is normal, so the caps kick in and keep the controls a sane size.
     */
    private class Sz(
        val avatar: Int, val toggle: Int, val primary: Int, val gap: Int, val inset: Int,
        val namePx: Float, val statusPx: Float,
    )
    private fun sz(activity: Activity): Sz {
        val dm = activity.resources.displayMetrics
        val base = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        fun px(frac: Float, maxDp: Int) = (base * frac).toInt().coerceAtMost(maxDp.dp)
        return Sz(
            avatar = px(0.30f, 104),
            toggle = px(0.21f, 64),
            primary = px(0.235f, 78),
            gap = (base * 0.025f).toInt().coerceAtMost(14.dp),
            // A single edge inset that works everywhere (round-screen detection is unreliable here).
            inset = (base * 0.07f).toInt(),
            namePx = (base * 0.10f).coerceAtMost(30.dpf),
            statusPx = (base * 0.065f).coerceAtMost(20.dpf),
        )
    }

    private class Refs(var timer: TextView? = null, var micIcon: ImageView? = null, var camIcon: ImageView? = null)
    private val refs = WeakHashMap<Activity, Refs>()

    private val medium: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private class Ctrl(val view: View, val icon: ImageView)

    // ---------------------------------------------------------------- active call ------------------

    fun prepActive(activity: QQNTC2CWatchActivity) {
        runCatching {
            activity.window.decorView.setBackgroundColor(M3.surface)
            activity.t?.let { it.j.setBackgroundColor(M3.surface); it.g.setImageDrawable(null) }
        }
    }

    /** Build the whole active-call screen from scratch (run from onServiceConnected). */
    fun rebuildActive(activity: QQNTC2CWatchActivity) {
        runCatching {
            if (stageExists(activity)) return
            val b = activity.t
            val s = sz(activity)
            val isVideo = runCatching { activity.z }.getOrDefault(false)
            val uin = runCatching { activity.i }.getOrNull()
            val name = runCatching { b?.i?.text?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { activity.j }.getOrNull() ?: ""
            val accepted = runCatching { activity.o }.getOrDefault(false)

            val mic = runCatching { activity.q as View? }.getOrNull()
            val hangup = runCatching { activity.r as View? }.getOrNull()
            val camera = runCatching { activity.p as View? }.getOrNull()

            val r = Refs()
            val timer = statusView(s, isVideo).apply { text = if (accepted) "接通中…" else "等待接听…" }
            r.timer = timer

            val toggles = buildList {
                if (isVideo && camera != null) {
                    val c = ctrl(activity, s.toggle, M3.surfaceContainerHigh, MaterialSymbols.videocam, M3.onSurface) { CameraCycle.advance(activity, camera) }
                    // Long-press starts screen share; while sharing, a plain tap stops it (no long-press).
                    c.view.setOnLongClickListener { if (!ScreenShare.sharing) ScreenShare.toggle(activity); true }
                    r.camIcon = c.icon; add(c.view)
                }
                if (mic != null) {
                    val c = ctrl(activity, s.toggle, M3.surfaceContainerHigh, MaterialSymbols.mic, M3.onSurface) { mic.performClick() }
                    r.micIcon = c.icon; add(c.view)
                }
                add(outputButton(activity, s))
            }
            val primary = listOf(ctrl(activity, s.primary, M3.error, MaterialSymbols.call_end, Color.WHITE) { hangup?.performClick() }.view)

            buildStage(activity, s, isVideo, b, uin, name, timer, toggles, primary)
            refs[activity] = r
            Utils.log("MaterialCallUi: built active call (video=$isVideo, accepted=$accepted)")
        }.onFailure { Utils.log("MaterialCallUi: rebuildActive failed: $it") }
    }

    // ---------------------------------------------------------------- incoming call ----------------

    fun prepIncoming(activity: BeInvitedActivity) {
        runCatching { activity.window.decorView.setBackgroundColor(M3.surface) }
    }

    fun rebuildIncoming(activity: BeInvitedActivity) {
        runCatching {
            if (stageExists(activity)) return
            val s = sz(activity)
            val uin = runCatching { activity.g }.getOrNull()
            val isVideo = runCatching { !activity.h }.getOrDefault(false)
            val name = runCatching { view<TextView>(activity, "nickname")?.text?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: runCatching { activity.j }.getOrNull() ?: ""
            val confirm = view<View>(activity, "confirm")
            val cancel = view<View>(activity, "cancel")

            val status = statusView(s, false).apply { text = if (isVideo) "邀请你视频通话" else "邀请你语音通话" }
            val primary = listOf(
                ctrl(activity, s.primary, M3.primary, if (isVideo) MaterialSymbols.videocam else MaterialSymbols.call, Color.WHITE) { confirm?.performClick() }.view,
                ctrl(activity, s.primary, M3.error, MaterialSymbols.call_end, Color.WHITE) { cancel?.performClick() }.view,
            )
            buildStage(activity, s, isVideo = false, binding = null, uin = uin, name = name, status = status, toggles = emptyList(), primary = primary)
            Utils.log("MaterialCallUi: built incoming call")
        }.onFailure { Utils.log("MaterialCallUi: rebuildIncoming failed: $it") }
    }

    // ---------------------------------------------------------------- state callbacks (no polling) --

    fun onTimer(activity: Activity?, time: String) {
        val tv = refs[activity]?.timer ?: return
        tv.post { tv.text = time }
    }

    fun onMic(activity: Activity?, on: Boolean) {
        val iv = refs[activity]?.micIcon ?: return
        iv.post {
            iv.setImageDrawable(MaterialSymbol(if (on) MaterialSymbols.mic else MaterialSymbols.mic_off, M3.onSurface))
            iv.alpha = if (on) 1f else 0.6f
        }
    }

    fun onVideo(activity: Activity?, localOn: Boolean) {
        // Icon reflects the tri-state cycle (front/back/off), read from the real camera state.
        (activity as? Activity)?.let { CameraCycle.refreshIcon(it) }
    }

    /** Camera button icon for [activity], so [CameraCycle] can update it to front/back/off. */
    internal fun camIcon(activity: Activity): ImageView? = refs[activity]?.camIcon

    /** Refresh the camera button icon on any live call stage (e.g. when screen share starts/stops). */
    fun refreshCameraIcons() = refs.keys.toList().forEach { CameraCycle.refreshIcon(it) }

    // ---------------------------------------------------------------- from-scratch stage -----------

    private fun buildStage(
        activity: Activity, s: Sz, isVideo: Boolean,
        binding: com.tencent.qqnt.qav_component_impl.databinding.ActivityAcceptedCallBinding?,
        uin: String?, name: String, status: TextView, toggles: List<View>, primary: List<View>,
    ) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val stage = FrameLayout(activity).apply { tag = STAGE_TAG }

        stripNative(activity, binding, keepVideo = isVideo)
        stage.setBackgroundColor(if (isVideo) Color.TRANSPARENT else M3.surface)
        content.addView(stage, matchParent())

        // One vertical, weight-distributed column fills the whole screen. Weighted spacers share the
        // slack so the layout re-centres on any aspect ratio; the side inset keeps it off round edges.
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(s.inset, s.inset, s.inset, s.inset)
        }

        val nameView = TextView(activity).apply {
            text = name
            setTextColor(if (isVideo) Color.WHITE else M3.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, s.namePx)
            typeface = medium; gravity = Gravity.CENTER; setSingleLine()
        }

        if (isVideo) {
            // Video: GL feed shows through the transparent middle; name/timer float on the top scrim,
            // controls on the bottom scrim, the weighted spacer between pushing them apart.
            col.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xB3000000.toInt(), 0x00000000, 0x00000000, 0xB3000000.toInt()))
            col.addView(nameView, wrap())
            col.addView(status, wrap().apply { topMargin = s.gap / 2 })
            col.addView(spacer(activity, 1f))
            if (toggles.isNotEmpty()) col.addView(row(activity, toggles))
            col.addView(row(activity, primary).also {
                (it.layoutParams as LinearLayout.LayoutParams).topMargin = if (toggles.isNotEmpty()) s.gap else 0
            })
        } else {
            // Voice / incoming: avatar + name + status centred in the upper third, the control row
            // biased toward the lower third — both blocks positioned by weighted spacers.
            val avatar = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setClipToOutlineCompat(true)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(M3.surfaceContainerHigh) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) = o.setOval(0, 0, v.width, v.height)
                    }
                }
            }
            if (!uin.isNullOrBlank()) avatar.loadPicUrl("https://q.qlogo.cn/headimg_dl?dst_uin=$uin&spec=140", "call_avatar_$uin")
            // Wrap the avatar in a voice-activity ring that pulses with the call audio (see VoiceActivityRing).
            val ring = VoiceActivityRing(activity).apply {
                addView(avatar, FrameLayout.LayoutParams(s.avatar, s.avatar, Gravity.CENTER))
            }
            val ringSize = (s.avatar * 1.3f).toInt()
            col.addView(spacer(activity, 1.4f))
            col.addView(ring, LinearLayout.LayoutParams(ringSize, ringSize))
            col.addView(nameView, wrap().apply { topMargin = s.gap })
            col.addView(status, wrap().apply { topMargin = s.gap / 2 })
            col.addView(spacer(activity, 0.9f))
            col.addView(row(activity, toggles + primary))
            col.addView(spacer(activity, 0.4f))
        }
        stage.addView(col, matchParent())

        if (isVideo) {
            // Tap an empty area to hide all overlay chrome so nothing blocks the video; tap again to
            // show it. Empty taps fall through `col` (not clickable) to the stage; button taps are
            // consumed by the buttons. Voice mode has no video to reveal, so it stays always-on.
            stage.isClickable = true
            stage.setOnClickListener {
                col.visibility = if (col.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
    }

    /** A weightless-in-width, weighted-in-height filler that shares vertical slack in the column. */
    private fun spacer(activity: Activity, weight: Float) =
        View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight)
        }

    /**
     * A full-width control row that spreads its buttons evenly with weighted flex spacers between and
     * around them (a "space-around" distribution), so spacing scales with the screen instead of a fixed
     * gap. Buttons keep their own intrinsic (clamped) size.
     */
    private fun row(activity: Activity, buttons: List<View>): LinearLayout {
        val r = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun flex() = r.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        flex()
        buttons.forEach { v -> r.addView(v); flex() }
        r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return r
    }

    // ---------------------------------------------------------------- our own circular button ------

    private fun ctrl(activity: Activity, sizePx: Int, bg: Int, iconPath: String, iconColor: Int, onClick: () -> Unit): Ctrl {
        val fl = FrameLayout(activity).apply {
            isClickable = true
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
            setOnClickListener { runCatching { onClick() }.onFailure { Utils.log("call ctrl click failed: $it") } }
        }
        val icon = ImageView(activity).apply {
            setImageDrawable(MaterialSymbol(iconPath, iconColor))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val iconPx = (sizePx * 0.46f).toInt()
        fl.addView(icon, FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER))
        fl.layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        return Ctrl(fl, icon)
    }

    private fun statusView(s: Sz, onVideo: Boolean) = TextView(Utils.application).apply {
        setTextColor(if (onVideo) 0xCCFFFFFF.toInt() else M3.onSurfaceVariant)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, s.statusPx); gravity = Gravity.CENTER
    }

    // ---------------------------------------------------------------- audio output selector --------

    private var outIcon: ImageView? = null

    private fun outputButton(activity: Activity, s: Sz): View {
        val c = ctrl(activity, s.toggle, M3.surfaceContainerHigh, MaterialSymbols.volume_up, M3.onSurface) { CallAudio.cycle(activity) }
        outIcon = c.icon
        // Long-press the output button opens the call-volume selector.
        c.view.setOnLongClickListener { CallVolume.toggle(activity); true }
        // Mirror the ACTUALLY applied route (auto-default to BT, hotplug, cycling) onto our icon.
        CallAudio.setRouteListener { route ->
            outIcon?.setImageDrawable(MaterialSymbol(routeIcon(route), M3.onSurface))
        }
        return c.view
    }

    private fun routeIcon(route: CallAudio.Route) = when (route) {
        CallAudio.Route.BLUETOOTH -> MaterialSymbols.bluetooth
        CallAudio.Route.SPEAKER -> MaterialSymbols.volume_up
        CallAudio.Route.EARPIECE -> MaterialSymbols.phone_in_talk
    }

    // ---------------------------------------------------------------- helpers ----------------------

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun matchParent() =
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun detach(v: View?) { v ?: return; (v.parent as? ViewGroup)?.removeView(v) }

    /** Remove every native call view from the tree (keeping the GL video surface for video calls). */
    private fun stripNative(
        activity: Activity,
        binding: com.tencent.qqnt.qav_component_impl.databinding.ActivityAcceptedCallBinding?,
        keepVideo: Boolean,
    ) = runCatching {
        if (binding != null) {
            detach(binding.g); detach(binding.h); detach(binding.c)
            detach(binding.i); detach(binding.m); detach(binding.d)
            if (keepVideo) binding.j.setBackgroundColor(Color.BLACK) else detach(binding.b)
        } else {
            listOf("imageView", "loading_view", "avatar", "nickname", "tips", "button_container")
                .forEach { detach(view<View>(activity, it)) }
        }
    }.onFailure { Utils.log("MaterialCallUi: stripNative failed: $it") }

    private fun stageExists(activity: Activity): Boolean =
        (activity.findViewById<View>(android.R.id.content) as? ViewGroup)?.findViewWithTag<View>(STAGE_TAG) != null

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> view(activity: Activity, name: String): T? {
        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
        return if (id != 0) activity.findViewById<View>(id) as? T else null
    }
}
