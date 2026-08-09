package momoi.mod.qqpro.hook.view

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import com.tencent.mobileqq.activity.fling.`TopGestureLayout$OnGestureListener`
import com.tencent.qqlive.module.videoreport.inject.fragment.ReportAndroidXDialogFragment
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.SwipeBackLayout

open class MyDialogFragment :  ReportAndroidXDialogFragment(),
    `TopGestureLayout$OnGestureListener` {
    override fun f() {
        dismiss()
    }

    override fun p() {
        dismiss()
    }

    /**
     * Wrap [content] so a right-swipe-back dismisses this dialog (handy on watches with no hardware
     * back button; the cancel/close button still works too). Vertical scroll and taps pass through —
     * only a horizontal rightward drag is grabbed — and it honours the global "屏蔽应用内右滑返回"
     * setting via [SwipeBackLayout]. Return this from a dialog's onCreateView instead of the raw view.
     */
    protected fun swipeBackWrap(content: View): View =
        SwipeBackLayout(content.context).apply {
            onSwipeBack = { dismiss() }
            addView(content, FILL, FILL)
        }

    @SuppressLint("ResourceType")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 2115174655)
    }
}