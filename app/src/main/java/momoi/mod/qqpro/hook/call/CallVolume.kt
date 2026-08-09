package momoi.mod.qqpro.hook.call

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Card
import momoi.mod.qqpro.lib.material.M3Slider
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * 通话音量 — an in-call volume selector, opened by LONG-PRESSING the audio-output button
 * ([CallOutputSelector] / the M3 call UI's selector). The watch has no volume rocker and QQ's call screen
 * has no volume control, so there's otherwise no way to change the call volume during a call.
 *
 * A plain object (not a @Mixin body) so its views + listeners are safe. It drives `STREAM_VOICE_CALL`
 * directly ([AudioManager.setStreamVolume]) via an [M3Slider] inside a floating card; tap the scrim (or
 * long-press again) to dismiss.
 */
object CallVolume {

    private const val TAG = "qqpro_call_volume"

    private fun am(ctx: Context) = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun content(activity: Activity): ViewGroup? =
        activity.findViewById<View>(android.R.id.content) as? ViewGroup

    /** Toggle the volume popup: show if hidden, dismiss if already up. */
    fun toggle(activity: Activity) {
        val root = content(activity) ?: return
        val existing = root.findViewWithTag<View>(TAG)
        if (existing != null) { root.removeView(existing); return }
        runCatching { show(activity, root) }.onFailure { Utils.log("CallVolume: show failed: $it") }
    }

    private fun show(activity: Activity, root: ViewGroup) {
        val audio = am(activity)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        val dm = activity.resources.displayMetrics
        val minDim = minOf(dm.widthPixels, dm.heightPixels)
        val pad = (minDim * 0.05f).toInt()
        val iconPx = (minDim * 0.12f).toInt()

        val scrim = FrameLayout(activity).apply {
            tag = TAG
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            setOnClickListener { root.removeView(this) } // tap outside dismisses
        }

        val icon = ImageView(activity).apply {
            setImageDrawable(MaterialSymbol(volumeIcon(audio), M3.onSurface).also { it.setBounds(0, 0, iconPx, iconPx) })
        }
        val slider = M3Slider(activity).apply {
            this.max = max
            progress = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            onProgressChanged = { value, fromUser ->
                if (fromUser) {
                    runCatching { audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, value, 0) }
                    icon.setImageDrawable(MaterialSymbol(if (value <= 0) MaterialSymbols.volume_off else MaterialSymbols.volume_up, M3.onSurface).also { it.setBounds(0, 0, iconPx, iconPx) })
                }
            }
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LinearLayout.LayoutParams(iconPx, iconPx).apply { marginEnd = pad })
            addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val card = M3Card(activity).raised().contentPadding(pad).apply {
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Swallow taps on the card so they don't fall through to the scrim's dismiss.
            isClickable = true
        }

        scrim.addView(
            card,
            FrameLayout.LayoutParams((minDim * 0.82f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun volumeIcon(audio: AudioManager) =
        if (audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) <= 0) MaterialSymbols.volume_off else MaterialSymbols.volume_up
}
