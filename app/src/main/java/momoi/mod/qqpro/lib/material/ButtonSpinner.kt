package momoi.mod.qqpro.lib.material

import android.graphics.drawable.Drawable
import android.widget.ImageView
import java.util.WeakHashMap

/**
 * Swap an [ImageView] button's icon for an indeterminate [M3ProgressDrawable] spinner while some work
 * runs, then restore the original icon. Reused by the inline voice recorder (mic → spinner during STT)
 * and the send-button translate (send → spinner while translating). [start] stashes the current
 * drawable; [stop] restores it. Idempotent: a second [start] before [stop] is a no-op.
 */
object ButtonSpinner {
    private val savedIcon = WeakHashMap<ImageView, Drawable?>()
    private val savedBg = WeakHashMap<ImageView, Drawable?>()

    fun start(btn: ImageView) {
        if (savedIcon.containsKey(btn)) return
        savedIcon[btn] = btn.drawable
        // Hide the button's own background (e.g. the send button's filled-primary circle) — a
        // primary spinner drawn on a primary disc is invisible. Restored in stop().
        savedBg[btn] = btn.background
        btn.background = null
        val spinner = M3ProgressDrawable()
        btn.setImageDrawable(spinner)
        spinner.setVisible(true, true)
    }

    fun stop(btn: ImageView) {
        (btn.drawable as? M3ProgressDrawable)?.setVisible(false, false)
        if (savedIcon.containsKey(btn)) btn.setImageDrawable(savedIcon.remove(btn))
        if (savedBg.containsKey(btn)) btn.background = savedBg.remove(btn)
    }
}
