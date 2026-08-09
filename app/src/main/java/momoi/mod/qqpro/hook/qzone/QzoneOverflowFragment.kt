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
 * A simple Material bottom-style action menu shown as a full-screen [MyDialogFragment] (the watch has
 * no real bottom sheet). Each row is a label + click action. Used by [QzoneActions.showOverflowMenu]
 * for the per-post ⋮ menu (copy / free-copy / download / repost).
 *
 * Rows are passed via the constructor (lambdas, not Parcelable) — a no-arg secondary constructor lets
 * the framework re-instantiate it after process death without crashing (it just shows empty then,
 * same approach as [momoi.mod.qqpro.hook.view.PartialCopyFragment]).
 */
class QzoneOverflowFragment(
    private val rows: List<Pair<String, () -> Unit>>,
    private val destructive: Set<String> = emptySet(),
) : MyDialogFragment() {

    constructor() : this(emptyList())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = inflater.context
        val edge = if (Utils.isRoundScreen) 20.dp else 12.dp
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(edge, 10.dp, edge, 10.dp)
        }
        column.addView(TextView(ctx).apply {
            text = "更多操作"
            textSize = 12f
            setTextColor(M3.onSurfaceTip)
            gravity = Gravity.CENTER
            setPadding(0, 4.dp, 0, 8.dp)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        for ((label, action) in rows) {
            column.addView(M3Button(ctx).apply {
                text = label
                variant(if (label in destructive) M3Button.Variant.ERROR else M3Button.Variant.TONAL)
                setOnClickListener {
                    dismiss()
                    runCatching { action() }.onFailure { Utils.log("QzoneOverflow row '$label': $it") }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8.dp
            })
        }

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(M3.surface)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return swipeBackWrap(scroll)
    }
}
