package momoi.mod.qqpro.hook.aio_cell

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.qqnt.kernel.nativeinterface.ReplyElement
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.view.BubbleTextView
import momoi.mod.qqpro.hook.view.smoothScrollToStartAndFlash
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3CircularProgress
import momoi.mod.qqpro.util.Utils

class ReplyClick(
    val widget: AIOCellGroupWidget,
    val reply: ReplyElement
) : View.OnClickListener {
    private var finding = false
    // Floating spinner + "加载中 …" pill shown while we page up looking for the source message, same
    // idea as the jump-to-first-unread progress. Removed once the search finishes.
    private var loading: TextView? = null
    private var loadingPill: View? = null

    override fun onClick(v: View?) {
        val rv = widget.parent as RecyclerView
        if (finding) {
            return
        }
        finding = true
        // Remember where we are now and show the back-down button so the user can return here.
        BubbleTextView.beginJumpUp()
        showLoading(rv, "加载中…")
        // Optionally drop the page-load cap so very old reply sources can still be located.
        val limit = if (Settings.replyFullSearch.value) Int.MAX_VALUE else 1000
        CurrentMsgList.findMsg(
            seq = reply.replayMsgSeq,
            onProgress = { loaded -> loading?.text = "加载中 $loaded" },
            result = { item ->
                finding = false
                hideLoading()
                if (item != null) {
                    rv.smoothScrollToStartAndFlash(CurrentMsgList.getMsgIndex(item))
                } else {
                    Utils.toast(rv.context, "无法定位消息")
                }
            },
            repeatCount = limit
        )
    }

    @SuppressLint("SetTextI18n")
    private fun showLoading(rv: RecyclerView, text: String) {
        val decor = rv.rootView as? ViewGroup ?: return
        val spinner = M3CircularProgress(rv.context).apply {
            layoutParams = LinearLayout.LayoutParams(18.dp, 18.dp).apply { rightMargin = 8.dp }
        }
        val tv = TextView(rv.context).apply {
            this.text = text
            textSize = 13f
            setTextColor(M3.onSurface)
            gravity = Gravity.CENTER
        }
        val pill = LinearLayout(rv.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(18.dp, 12.dp, 20.dp, 12.dp)
            background = roundCornerDrawable(0xDD_303030.toInt(), 16.dp.toFloat())
            addView(spinner)
            addView(tv)
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        decor.addView(pill, lp)
        loading = tv
        loadingPill = pill
    }

    private fun hideLoading() {
        loadingPill?.let { (it.parent as? ViewGroup)?.removeView(it) }
        loading = null
        loadingPill = null
    }
}
