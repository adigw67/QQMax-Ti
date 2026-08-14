package momoi.mod.qqpro.hook.aio_cell

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.MarkdownElement
import momoi.mod.qqpro.lib.Markdown
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3

/**
 * 机器人 markdown 消息渲染视图：把 [MarkdownElement.content]（markdown 正文）渲染为可滚动、
 * 可点链接的 TextView。当前由 AIOCell 的 bind 覆盖路径直接渲染到气泡文本上；这个组件保留
 * 给需要独立大图/全屏展示 markdown 的入口复用（如后续机器人卡片详情页）。
 */
class MarkdownMsgView(context: Context) : LinearLayout(context) {

    private val textView = TextView(context).apply {
        textSize = 14f
        setTextColor(M3.onSurface)
        setTextIsSelectable(true)
        gravity = Gravity.TOP or Gravity.START
        setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        setBackgroundColor(Color.TRANSPARENT)
    }

    init {
        orientation = VERTICAL
        val scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(textView, LayoutParams(FILL, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(scroller, LayoutParams(FILL, 0, 1f))
    }

    fun loadData(markdown: MarkdownElement) {
        val content = markdown.content ?: return
        textView.text = Markdown.toSpannable(content)
    }
}
