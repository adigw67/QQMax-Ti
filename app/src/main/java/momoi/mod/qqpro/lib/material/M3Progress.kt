package momoi.mod.qqpro.lib.material

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import momoi.mod.qqpro.lib.dp

/**
 * Helpers for showing the [M3CircularProgress] loading indicator anywhere — a centered overlay over a
 * container (image/video, QZone, chat history, member list…) that can be shown and hidden by tag, or a
 * standalone spinner view for list footers / inline use.
 *
 * Public on purpose (referenced from @Mixin bodies).
 */
object M3Progress {
    private const val OVERLAY_TAG = "qqpro_m3_progress_overlay"

    /** A standalone indeterminate spinner sized to [sizeDp], tinted [color]. */
    fun spinner(ctx: Context, sizeDp: Int = 28, color: Int = M3.primary): M3CircularProgress =
        M3CircularProgress(ctx).apply {
            indicatorColor = color
            layoutParams = ViewGroup.LayoutParams(sizeDp.dp, sizeDp.dp)
        }

    /**
     * Show a centered indeterminate spinner over [container] (idempotent — reuses the existing overlay).
     * [scrim] dims the content behind it (0 = none). Returns the spinner so you can flip it to
     * determinate and drive [M3CircularProgress.progress].
     */
    fun show(container: ViewGroup, sizeDp: Int = 32, scrim: Int = 0, color: Int = M3.primary): M3CircularProgress {
        (container.findViewWithTag<View>(OVERLAY_TAG) as? FrameLayout)?.let {
            return it.getChildAt(0) as M3CircularProgress
        }
        val spinner = spinner(container.context, sizeDp, color)
        val overlay = FrameLayout(container.context).apply {
            tag = OVERLAY_TAG
            if (scrim != 0) setBackgroundColor(scrim)
            isClickable = scrim != 0   // a scrim swallows touches; a bare spinner doesn't
            addView(spinner, FrameLayout.LayoutParams(sizeDp.dp, sizeDp.dp, Gravity.CENTER))
        }
        container.addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        return spinner
    }

    /** Remove the overlay spinner from [container], if present. */
    fun hide(container: ViewGroup) {
        container.findViewWithTag<View>(OVERLAY_TAG)?.let { container.removeView(it) }
    }
}
