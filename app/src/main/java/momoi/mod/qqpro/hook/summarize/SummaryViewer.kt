package momoi.mod.qqpro.hook.summarize

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.FontPack
import momoi.mod.qqpro.hook.forwardText
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.hook.view.PartialCopyFragment
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.Markdown
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3CircularProgress
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi

private val ACCENT get() = M3.primary
private val BG get() = M3.surface

/**
 * Full-screen, watch-friendly summary viewer. Two modes:
 *  - live ([messages] non-null): streams a fresh summary from [Summarizer], showing a loading
 *    spinner until the first token, then rendering text as it grows; on completion it is saved to
 *    [SummaryStore] under [chatKey] and the action buttons (转发/复制/自由复制) appear.
 *  - stored ([stored] non-null): just shows a previously saved summary read-only (with actions).
 *
 * A no-arg secondary constructor exists so the framework can re-instantiate after process death.
 */
class SummaryViewer private constructor(
    private val messages: List<Summarizer.Msg>?,
    private val rangeLabel: String,
    private val chatKey: String,
    private val style: Int,
    private val stored: SummaryStore.Item?,
) : MyDialogFragment() {

    constructor() : this(null, "", "", 0, null)

    private var active = true
    private val sb = StringBuilder()
    private var done = false

    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var col: LinearLayout
    private lateinit var loadRow: LinearLayout
    private lateinit var bodyView: TextView
    private lateinit var spinner: M3CircularProgress
    private lateinit var statusView: TextView
    private lateinit var actionBar: LinearLayout
    private var retryButton: TextView? = null

    companion object {
        /** Stream a fresh summary of [messages] (oldest→newest) into a new viewer. */
        fun live(messages: List<Summarizer.Msg>, rangeLabel: String, chatKey: String): SummaryViewer =
            SummaryViewer(messages, rangeLabel, chatKey, Settings.summarizeStyle.value, null)

        /** Open a previously saved summary read-only. */
        fun stored(item: SummaryStore.Item, chatKey: String): SummaryViewer =
            SummaryViewer(null, item.range, chatKey, item.style, item)
    }

    override fun onDestroyView() {
        active = false
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        // Force the dialog window to fill the screen so the SwipeBackLayout covers it entirely.
        // Without this the window wraps its content height; when there's nothing to summarize (the
        // body stays empty) the weighted ScrollView has no content to stretch it, the window
        // collapses to the short loading/status row at the top, and a swipe-back below it is dead.
        dialog?.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        val edge = if (Utils.isRoundScreen) 16.dp else 10.dp
        root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setPadding(edge, 6.dp, edge, 6.dp)

        val title = TextView(ctx).apply {
            text = "总结"
            textSize = 11f
            setTextColor(M3.hint)
            gravity = Gravity.CENTER
            setPadding(0, 2.dp, 0, 4.dp)
        }
        root.addView(title, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Everything below the title scrolls together: loading row → summary text → action buttons →
        // footer. The buttons live at the END of the scrollable content (not a fixed bottom bar that
        // would steal the screen), so the full summary stays readable and you scroll to the buttons.
        scroll = ScrollView(ctx)
        scroll.isFillViewport = true
        col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col, ViewGroup.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(FILL, 0, 1f))

        // Loading row (spinner + status), hidden once content/finished.
        loadRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 10.dp, 0, 10.dp)
        }
        spinner = M3CircularProgress(ctx).apply { indicatorColor = ACCENT }
        loadRow.addView(spinner, LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginEnd = 8.dp })
        statusView = TextView(ctx).apply {
            text = "总结中…"
            textSize = 13f
            setTextColor(M3.onSurface)
        }
        loadRow.addView(statusView)
        col.addView(loadRow, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))

        bodyView = TextView(ctx).apply {
            textSize = 14f
            setTextColor(M3.onSurface)
            setPadding(2.dp, 2.dp, 2.dp, 8.dp)
            setTextIsSelectable(true)
        }
        col.addView(bodyView, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Action area (hidden until there is content to act on), full-width stacked buttons + footer.
        actionBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6.dp, 0, 2.dp)
            visibility = View.GONE
        }
        actionBar.addView(actionButton("转发") { v -> v.forwardText(sb.toString()); dismiss() })
        actionBar.addView(actionButton("复制") { v -> Utils.copyToClipboard(v.context, sb.toString()); Utils.toast(v.context, "已复制") })
        actionBar.addView(actionButton("自由复制") {
            runCatching { PartialCopyFragment(sb.toString()).show(parentFragmentManager, "qqpro_summary_copy") }
        })
        actionBar.addView(TextView(ctx).apply {
            text = if (Settings.summarizeApiKey.value.isNotBlank()) "自定义 AI 服务" else "Onyx AI 提供技术支持"
            textSize = 10f
            setTextColor(M3.hint)
            gravity = Gravity.CENTER
            setPadding(0, 10.dp, 0, 4.dp)
        }, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(actionBar, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (stored != null) {
            sb.append(stored.content)
            showLoaded()
        } else {
            startStreaming()
        }
        return SwipeBackLayout(ctx).apply {
            onSwipeBack = { dismiss() }
            addView(root, FILL, FILL)
        }
    }

    private fun actionButton(label: String, onClick: (View) -> Unit): TextView {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(M3.onPrimary)
            gravity = Gravity.CENTER
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = 20.dp.toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                FILL, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6.dp }
            setOnClickListener { onClick(this) }
        }
    }

    /** Stored / finished content view: hide spinner, render text, reveal actions. */
    private fun showLoaded() {
        (spinner.parent as? View)?.visibility = View.GONE
        bodyView.text = FontPack.fallback(Markdown.render(sb.toString()))
        actionBar.visibility = View.VISIBLE
        // Land at the start of the summary (streaming auto-scrolled to the bottom to follow the text).
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun startStreaming() {
        val msgs = messages ?: run { statusView.text = "没有可总结的消息"; spinner.visibility = View.GONE; return }
        Summarizer.summarize(msgs, style, Settings.summarizeLang.value, object : Summarizer.Listener {
            override fun onChunk(fragment: String) = runOnUi {
                if (!active) return@runOnUi
                if (sb.isEmpty()) {
                    // First token: drop the loading row.
                    (spinner.parent as? View)?.visibility = View.GONE
                }
                sb.append(fragment)
                bodyView.text = FontPack.fallback(Markdown.render(sb.toString()))
                scroll.post { if (active) scroll.fullScroll(View.FOCUS_DOWN) }
            }

            override fun onDone(used: Int, limit: Int) = runOnUi {
                if (!active) return@runOnUi
                done = true
                if (sb.isEmpty()) { statusView.text = "未生成内容"; return@runOnUi }
                showLoaded()
                if (used >= 0 && limit > 0) Utils.toast(requireContext(), "今日已用 $used/$limit")
                runCatching {
                    SummaryStore.add(chatKey, SummaryStore.Item(System.currentTimeMillis(), rangeLabel, style, sb.toString()))
                }.onFailure { Utils.log("SummaryViewer: save failed: $it") }
            }

            override fun onError(message: String, retryable: Boolean) = runOnUi {
                if (!active) return@runOnUi
                (spinner.parent as? View)?.visibility = View.VISIBLE
                spinner.visibility = View.GONE
                statusView.text = message
                // Non-limit failures (server/network) are retryable — offer a retry button so the
                // user doesn't have to back out and re-open the viewer to try again.
                if (retryable) showRetryButton()
            }
        })
    }

    /** Reveal a one-shot "重试" button below the loading row; tapping it re-streams the summary. */
    private fun showRetryButton() {
        if (retryButton != null) return
        retryButton = actionButton("重试") { retry() }.also {
            val idx = col.indexOfChild(loadRow) + 1
            col.addView(it, idx)
        }
    }

    private fun retry() {
        if (!active) return
        retryButton?.let { col.removeView(it) }
        retryButton = null
        sb.setLength(0)
        done = false
        bodyView.text = ""
        statusView.text = "总结中…"
        spinner.visibility = View.VISIBLE
        (spinner.parent as? View)?.visibility = View.VISIBLE
        startStreaming()
    }
}
