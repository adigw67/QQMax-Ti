package momoi.mod.qqpro.lib.material

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.os.Build
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator

/**
 * Material 3 motion tokens, self-implemented for QQPro's self-drawn View UI (no androidx / Compose /
 * dynamicanimation dependency — this project's androidx libs are incomplete, so we reimplement the
 * M3 / Material 3 Expressive motion language ourselves).
 *
 * The easing curve control points and duration ramp below match the values published in the public
 * Material 3 spec (m3.material.io/styles/motion) — they are plain numbers, so reproducing them here
 * needs no framework. Until now only [momoi.mod.qqpro.hook.MainNav] defined one emphasized curve
 * locally; this centralizes all six easing curves, the 16-step duration ramp, and a self-contained
 * spring animator so every component (buttons, cards, lists, dialogs, switches) shares one motion
 * language — the way baseline M3 / Material 3 Expressive intends.
 *
 * Spring physics: M3 Expressive documents the feel qualitatively ("springier, natural-feeling") but
 * does not publish damping/stiffness design tokens. We provide [SpringAnimator] — a self-contained
 * critically/under-damped harmonic oscillator solver (the standard spring ODE) that needs no
 * dynamicanimation. Use it for press-recoil (button/thumb scale pop) where a linear ValueAnimator
 * reads as mechanical.
 */
object M3Motion {

    // ── Easing (cubic-bezier control points per the M3 spec) ─────────────────────────────────
    /** PathInterpolator is API 21+; this watch is API 19, so fall back to a close decelerating curve. */
    private fun bezier(x1: Float, y1: Float, x2: Float, y2: Float): TimeInterpolator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) PathInterpolator(x1, y1, x2, y2)
        else DecelerateInterpolator(1.8f)

    /** General-purpose emphasized curve. Use for most state transitions. */
    val EasingEmphasized: TimeInterpolator = bezier(0.2f, 0.0f, 0.0f, 1.0f)
    /** Emphasized ENTER — fast-then-slow, things appearing. Pair with longer durations. */
    val EasingEmphasizedDecel: TimeInterpolator = bezier(0.05f, 0.7f, 0.1f, 1.0f)
    /** Emphasized EXIT — slow-then-fast, things disappearing. */
    val EasingEmphasizedAccel: TimeInterpolator = bezier(0.3f, 0.0f, 0.8f, 0.15f)
    /** Standard curve (same shape as emphasized; use for less prominent transitions). */
    val EasingStandard: TimeInterpolator = bezier(0.2f, 0.0f, 0.0f, 1.0f)
    val EasingStandardDecel: TimeInterpolator = bezier(0.0f, 0.0f, 0.0f, 1.0f)
    val EasingStandardAccel: TimeInterpolator = bezier(0.3f, 0.0f, 1.0f, 1.0f)

    // ── Duration (ms, per the M3 spec duration scale) ────────────────────────────────────────
    const val DurationShort1 = 50L
    const val DurationShort2 = 100L
    const val DurationShort3 = 150L
    const val DurationShort4 = 200L
    const val DurationMedium1 = 250L
    const val DurationMedium2 = 300L
    const val DurationMedium3 = 350L
    const val DurationMedium4 = 400L
    const val DurationLong1 = 450L
    const val DurationLong2 = 500L
    const val DurationLong3 = 550L
    const val DurationLong4 = 600L
    const val DurationExtraLong1 = 700L
    const val DurationExtraLong2 = 800L
    const val DurationExtraLong3 = 900L
    const val DurationExtraLong4 = 1000L

    // ── Spring damping presets (Expressive feel) ────────────────────────────────────────────
    // dampingRatio > 1 = over-damped (no overshoot, slow settle); = 1 = critically damped
    // (fastest no-bounce settle); < 1 = under-damped (overshoots once or more). Stiffness raises
    // the speed. These presets match the "natural, not bouncy" feel M3 Expressive describes.
    const val SpringDampingNoBouncy = 1.0f       // critically damped — buttons, switches (no overshoot)
    const val SpringDampingLowBouncy = 0.75f      // gentle settle — FAB, cards (one soft overshoot)
    const val SpringDampingMediumBouncy = 0.5f    // lively — playful scale pops
    const val SpringStiffnessMedium = 1500f
    const val SpringStiffnessLow = 200f
}

/**
 * A self-contained spring animator (no dynamicanimation dependency). Drives a single float from
 * [startFrom] toward a target by sampling the analytic damped-harmonic-oscillator solution each
 * frame, then applies it via the update callback. Use for Expressive press-recoil (button scale,
 * switch thumb pop) where a linear/eased ValueAnimator reads as mechanical.
 *
 *     SpringAnimator().stiffness(M3Motion.SpringStiffnessMedium)
 *         .dampingRatio(M3Motion.SpringDampingLowBouncy)
 *         .startFrom(view.scaleX)
 *         .animateTo(1f) { v -> view.scaleX = v; view.scaleY = v }
 *
 * For dampingRatio >= 1 it uses the over-damped/critical form (no oscillation), for < 1 the
 * under-damped form (with overshoot). On completion the update callback is emitted with exactly
 * the target value, then [onEnd] fires.
 */
class SpringAnimator {
    private var stiffness = M3Motion.SpringStiffnessMedium
    private var dampingRatio = M3Motion.SpringDampingNoBouncy
    private var startValue = 0f
    private var targetValue = 0f
    private var onUpdate: ((Float) -> Unit)? = null
    private var onEnd: (() -> Unit)? = null
    private var animator: ValueAnimator? = null

    fun stiffness(s: Float) = apply { stiffness = s }
    fun dampingRatio(r: Float) = apply { dampingRatio = r }
    fun startFrom(v: Float) = apply { startValue = v }
    fun onEnd(action: () -> Unit) = apply { onEnd = action }

    /**
     * Drive [onUpdate] from [startFrom] to [target], sampling the analytic spring solution each
     * frame for an estimated settle duration, then snapping exactly onto [target] (the sampled
     * curve alone would stop wherever the envelope happens to be when the clock runs out).
     */
    fun animateTo(target: Float, onUpdate: (Float) -> Unit) {
        this.targetValue = target
        this.onUpdate = onUpdate
        animator?.cancel()
        val from = startValue
        if (from == target) { onUpdate(target); onEnd?.invoke(); return }
        // Settle time = ~4 time constants of the envelope decay rate. The envelope decays as
        // e^(-ζω·t) when under-damped (slowest root ω·(ζ-√(ζ²-1)) when over-damped) — NOT as
        // e^(-ω·t), so the rate must include the damping ratio or a bouncy spring gets cut off
        // mid-oscillation, away from the target.
        val omega = Math.sqrt(stiffness.toDouble())           // natural angular freq (rad/s)
        val z = dampingRatio.toDouble()
        val decay = if (z < 1.0) z * omega else omega * (z - Math.sqrt(z * z - 1.0))
        val settleMs = (4000.0 / decay).coerceIn(120.0, 1200.0)
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = settleMs.toLong()
            addUpdateListener {
                val t = (it.animatedValue as Float) * settleMs.toFloat() / 1000f  // seconds elapsed
                val v = springValue(t, from.toDouble(), target.toDouble(), stiffness.toDouble(), dampingRatio.toDouble())
                onUpdate(v.toFloat())
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    onUpdate(target)
                    onEnd?.invoke()
                }
            })
        }
        a.start()
        animator = a
    }

    fun cancel() { animator?.cancel(); animator = null }
}

/**
 * Analytic damped-spring position at time [t] (seconds), given [stiffness] k and [dampingRatio] ζ,
 * moving from [from] to [to]. Uses the closed-form solution of m·ẍ + c·ẋ + k·x = 0 with unit mass
 * (so c = 2·ζ·√k). Avoids numeric drift near the settle that an explicit integrator would accumulate.
 */
private fun springValue(t: Float, from: Double, to: Double, stiffness: Double, dampingRatio: Double): Float {
    val omega = Math.sqrt(stiffness)                       // √(k/m), m=1
    val x0 = from - to                                      // displacement from rest
    val x: Double = when {
        dampingRatio >= 1.0 -> {
            // Critically / over-damped: A·e^(-ζω·t) + B·e^(-ω·t·√(ζ²-1)). For ζ==1 collapses to the
            // critical form (1 + ω·t)·e^(-ω·t); we use the general damped form which is continuous there.
            val z2 = dampingRatio * dampingRatio - 1.0
            if (z2 < 1e-6) {
                // critically damped
                (1.0 + omega * t) * Math.exp(-omega * t) * x0
            } else {
                val s = Math.sqrt(z2)
                val r1 = -omega * (dampingRatio - s)
                val r2 = -omega * (dampingRatio + s)
                // x0 = A + B, ẋ0 = 0 → A = -x0·r2/(r1-r2), B = x0·r1/(r1-r2)
                val a = -x0 * r2 / (r1 - r2)
                val b = x0 * r1 / (r1 - r2)
                (a * Math.exp(r1 * t) + b * Math.exp(r2 * t))
            }
        }
        else -> {
            // Under-damped: e^(-ζω·t)·(cos(ωd·t) + (ζ/√(1-ζ²))·sin(ωd·t))·x0, ωd = ω·√(1-ζ²)
            val z = Math.sqrt(1.0 - dampingRatio * dampingRatio)
            val omegaD = omega * z
            Math.exp(-dampingRatio * omega * t) *
                (Math.cos(omegaD * t) + (dampingRatio / z) * Math.sin(omegaD * t)) * x0
        }
    }
    return (to + x).toFloat()
}
