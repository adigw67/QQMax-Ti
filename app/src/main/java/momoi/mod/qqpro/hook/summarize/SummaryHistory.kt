package momoi.mod.qqpro.hook.summarize

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.watch.aio_impl.ui.frames.SettingFrame
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.style.cardMargin
import momoi.mod.qqpro.hook.view.ConfirmFragment
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ACCENT get() = M3.primary
private val BG get() = M3.surface
private const val ENTRY_LABEL = "总结历史"
private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun styleLabel(code: Int) = when (code) { 1 -> "一句话"; 2 -> "详细"; else -> "要点" }

/**
 * Add a "总结历史" entry to the chat's settings page ([SettingFrame]), shown (alongside 搜索聊天记录)
 * whenever any summarization feature is enabled. Tapping it opens [SummaryHistoryFragment] for the
 * current chat. Mirrors `addChatSearchEntry`.
 */
fun addSummaryHistoryEntry(fragment: SettingFrame) {
    // Show 总结历史 unless the 总结 long-press entry is hidden in 菜单自定义 AND the unread button is off.
    val summarizeHidden = "summarize" in momoi.mod.qqpro.hook.menu.LongPressMenuConfig.resolved().second
    if (summarizeHidden && !Settings.summarizeUnreadButton.value) return
    runCatching {
        val scroll = fragment.i ?: return
        val container = scroll.getChildAt(0) as? LinearLayout ?: return
        val ctx = fragment.requireContext()
        val res = ctx.resources
        val pkg = ctx.packageName
        val descId = res.getIdentifier("desc", "id", pkg)
        for (i in 0 until container.childCount) {
            val desc = container.getChildAt(i).findViewById<TextView>(descId)
            if (desc?.text?.toString() == ENTRY_LABEL) return
        }
        val layoutId = res.getIdentifier("setting_item", "layout", pkg)
        if (layoutId == 0) return
        val row = LayoutInflater.from(ctx).inflate(layoutId, container, false)
        row.findViewById<ImageView>(res.getIdentifier("icon", "id", pkg))
            ?.setImageDrawable(MaterialSymbol(MaterialSymbols.summarize, M3.onSurface))
        row.findViewById<TextView>(descId)?.text = ENTRY_LABEL
        row.setOnClickListener {
            runCatching { SummaryHistoryFragment().show(fragment.childFragmentManager, "summary_history") }
                .onFailure { Utils.log("SummaryHistory: open failed: $it") }
        }
        container.addView(row, minOf(5, container.childCount))
        row.cardMargin()
        Utils.log("SummaryHistory: entry added")
    }.onFailure { Utils.log("SummaryHistory: addEntry failed: $it") }
}

/** Lists past summaries (newest first) for the current chat; tap to view, long-press to delete. */
class SummaryHistoryFragment : MyDialogFragment() {

    private lateinit var root: LinearLayout
    private val chatKey = SummaryStore.keyOf(CurrentContact.chatType, CurrentContact.peerUid)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        rebuild()
        return SwipeBackLayout(ctx).apply {
            onSwipeBack = { dismiss() }
            addView(root, FILL, FILL)
        }
    }

    override fun onStart() {
        super.onStart()
        // Force the dialog window to fill the screen so the SwipeBackLayout covers it entirely.
        // Without this the window wraps its content height; when the history is empty there's no
        // weighted ScrollView to stretch it, so the window collapses to the two short TextViews at
        // the top and a swipe-back anywhere below them is dead — trapping the user on the screen.
        dialog?.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun rebuild() {
        root.removeAllViews()
        val ctx = requireContext()
        val items = SummaryStore.list(chatKey)

        val title = TextView(ctx).apply {
            text = "总结历史 (${items.size})"
            textSize = 15f
            setTextColor(M3.onSurface)
            gravity = Gravity.CENTER
            setPadding(0, 14.dp, 0, 12.dp)
        }
        root.addView(title, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (items.isEmpty()) {
            root.addView(TextView(ctx).apply {
                text = "暂无总结记录"
                textSize = 13f
                setTextColor(M3.hint)
                gravity = Gravity.CENTER
                setPadding(0, 20.dp, 0, 0)
            }, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }

        val sv = ScrollView(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(col, ViewGroup.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.setPadding(12.dp, 0, 12.dp, 16.dp)
        root.addView(sv, LinearLayout.LayoutParams(FILL, 0, 1f))

        for (item in items) {
            col.addView(rowFor(item), LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 8.dp })
        }
    }

    private fun rowFor(item: SummaryStore.Item): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            background = GradientDrawable().apply {
                setColor(M3.surfaceContainerHigh)
                cornerRadius = 16.dp.toFloat()
            }
        }
        card.addView(TextView(ctx).apply {
            text = "${tsFmt.format(Date(item.time))}  ·  ${styleLabel(item.style)}" +
                if (item.range.isNotBlank()) "  ·  ${item.range}" else ""
            textSize = 11f
            setTextColor(ACCENT)
        })
        card.addView(TextView(ctx).apply {
            text = item.content.replace('\n', ' ').let { if (it.length > 80) it.take(80) + "…" else it }
            textSize = 14f
            setTextColor(M3.onSurface)
            maxLines = 2
            setPadding(0, 4.dp, 0, 0)
        })
        card.setOnClickListener {
            runCatching { SummaryViewer.stored(item, chatKey).show(parentFragmentManager, "summary_view") }
        }
        card.setOnLongClickListener {
            runCatching {
                ConfirmFragment("删除这条总结记录？", "删除", destructive = true) {
                    SummaryStore.remove(chatKey, item.time)
                    rebuild()
                }.show(parentFragmentManager, "summary_del")
            }
            true
        }
        return card
    }
}
