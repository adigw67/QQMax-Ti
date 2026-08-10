package momoi.mod.qqpro.lib.material

import android.content.Context
import android.view.Gravity
import android.graphics.Typeface
import android.widget.TextView
import momoi.mod.qqpro.lib.dp

/**
 * A Material 3 Expressive button with **spring press-recoil** — on press it scales to 0.96 and on
 * release springs back to 1.0 with a slight overshoot (under-damped [SpringAnimator], not a linear
 * tween). Self-contained: no com.google.android.material dependency (the watch theme isn't
 * MaterialComponents-based, so a real Material button would crash).
 *
 * Variants mirror MD3:
 *  - [FILLED]            solid primary, on-primary label (high emphasis)
 *  - [TONAL]             primary container (medium emphasis)
 *  - [TEXT]              no container, accent label (low emphasis)
 *  - [OUTLINED]          outline stroke, accent label
 *  - [ERROR]             translucent error container, error label
 *
 * Public on purpose: a @Mixin body referencing it needs it public (else runtime IllegalAccessError).
 */
class M3Button(ctx: Context) : TextView(ctx) {

    enum class Variant { FILLED, TONAL, TEXT, OUTLINED, ERROR }

    private var pressSpring: SpringAnimator? = null
    private val releaseDelayMs = 100L  // small delay so the recoil feels intentional, not a glitch

    init {
        gravity = Gravity.CENTER
        isSingleLine = true
        textSize = 14f
        // MD3 label-large：14sp sans-serif-medium（500），不是粗体。
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(20.dp, 10.dp, 20.dp, 10.dp)
        minHeight = 40.dp
        isClickable = true
        isFocusable = true
        // Spring press-recoil: 0.96 on down, spring back with gentle overshoot (low stiffness +
        // medium-bouncy damping reads as lively but never twitchy). releaseDelayMs before the
        // release animation makes the recoil feel like a deliberate "lift", not an instant snap.
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressSpring?.cancel()
                    v.removeCallbacks(releaseRunnable)
                    scaleX = 0.96f; scaleY = 0.96f
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    pressSpring?.cancel()
                    v.removeCallbacks(releaseRunnable)
                    v.postDelayed(releaseRunnable, releaseDelayMs)
                }
            }
            false // let click listener still fire
        }
        variant(Variant.FILLED)
    }

    private val releaseRunnable = Runnable {
        val v = this
        pressSpring = SpringAnimator()
            .stiffness(M3Motion.SpringStiffnessLow)        // slower settle — visible, expressive
            .dampingRatio(M3Motion.SpringDampingMediumBouncy) // one soft overshoot
            .startFrom(0.96f)
            .apply { animateTo(1f) { s -> v.scaleX = s; v.scaleY = s } }
    }

    fun variant(v: Variant): M3Button = apply {
        val (container, label) = when (v) {
            Variant.FILLED           -> M3.primary to M3.onPrimary
            Variant.TONAL            -> M3.primaryContainer to M3.onPrimaryContainer
            Variant.TEXT             -> 0 to M3.primary
            Variant.OUTLINED         -> 0 to M3.primary
            Variant.ERROR            -> ((M3.error and 0x00FFFFFF) or 0x33_000000) to M3.error
        }
        setTextColor(label)
        val base = when (v) {
            Variant.OUTLINED -> M3.outlined(M3.outline, M3.radiusPill)
            else -> M3.rounded(container, M3.radiusPill)
        }
        background = M3.ripple(base)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}
