package momoi.mod.qqpro.hook.qzone

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.util.Utils

/**
 * A small Material confirm dialog (full-screen [MyDialogFragment], matching the rest of the QZone UI)
 * — a message, a filled confirm button, and a text 取消. Used for destructive actions in the
 * materialized feed (delete post / delete comment / delete reply).
 *
 * The callback is passed via the constructor (not Parcelable); a no-arg secondary constructor lets the
 * framework recreate it harmlessly after process death (it just shows an empty dialog then), the same
 * pattern as [QzoneOverflowFragment].
 */
class QzoneConfirmDialog(
    private val message: String = "",
    private val confirmLabel: String = "确定",
    private val destructive: Boolean = false,
    private val onConfirm: (() -> Unit)? = null,
) : MyDialogFragment() {

    constructor() : this("", "确定", false, null)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val edge = if (Utils.isRoundScreen) 22.dp else 14.dp
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(edge, 18.dp, edge, 18.dp)
        }
        column.addView(TextView(ctx).apply {
            text = message
            textSize = 14f
            setTextColor(M3.onSurface)
            gravity = Gravity.CENTER
            setPadding(0, 6.dp, 0, 14.dp)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(M3Button(ctx).apply {
            text = confirmLabel
            variant(if (destructive) M3Button.Variant.ERROR else M3Button.Variant.FILLED)
            setOnClickListener {
                val cb = onConfirm
                dismiss()
                runCatching { cb?.invoke() }.onFailure { Utils.log("QzoneConfirm action: $it") }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp })
        column.addView(M3Button(ctx).apply {
            text = "取消"
            variant(M3Button.Variant.TEXT)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp })

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(M3.surface)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return swipeBackWrap(scroll)
    }
}
