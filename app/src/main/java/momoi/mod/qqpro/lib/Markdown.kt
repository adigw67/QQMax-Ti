package momoi.mod.qqpro.lib

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import momoi.mod.qqpro.lib.material.M3

/**
 * Tiny, dependency-free Markdown → styled text renderer for short AI output (e.g. the chat summary).
 * No external library (Markwon etc.) is pulled in — the patched APK only needs a handful of common
 * constructs, so this hand-rolls them as spans:
 *
 *  - headings `#`..`######`        → bold, sized down per level
 *  - **bold** / __bold__, *italic* / _italic_, ~~strike~~, `inline code`, [text](url)
 *  - `- ` / `* ` / `+ ` bullets, `1.` numbered lists (indent honoured for nesting)
 *  - `> ` block quotes, ``` fenced code blocks ```, `---` horizontal rules
 *
 * Unknown / malformed markup (e.g. an unclosed `**` while the summary is still streaming) is left as
 * literal text, so re-rendering the growing buffer on every token never throws or flickers badly.
 */
object Markdown {

    private const val SPAN = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    private val HR = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^([*+-])\\s+(.*)$")
    private val NUMBERED = Regex("^(\\d+)([.)])\\s+(.*)$")

    fun render(
        src: String,
        textColor: Int = M3.onSurface,
        accent: Int = M3.primary,
        muted: Int = M3.onSurfaceVariant,
        codeBg: Int = M3.surfaceContainer,
    ): CharSequence {
        val out = SpannableStringBuilder()
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        var inFence = false
        val code = StringBuilder()
        var first = true

        fun newline() { if (!first) out.append('\n'); first = false }

        fun flushCode() {
            val st = out.length
            out.append(code.toString().trimEnd('\n'))
            out.setSpan(TypefaceSpan("monospace"), st, out.length, SPAN)
            out.setSpan(RelativeSizeSpan(0.9f), st, out.length, SPAN)
            out.setSpan(BackgroundColorSpan(codeBg), st, out.length, SPAN)
            code.setLength(0)
        }

        for (raw in lines) {
            val ts = raw.trimStart()
            if (ts.startsWith("```")) {
                if (inFence) { newline(); flushCode(); inFence = false } else inFence = true
                continue
            }
            if (inFence) { code.append(raw).append('\n'); continue }

            newline()
            val indent = raw.length - ts.length
            val line = ts.trimEnd()
            when {
                line.isEmpty() -> {}
                HR.matches(line) -> {
                    val st = out.length
                    out.append("──────────")
                    out.setSpan(ForegroundColorSpan(muted), st, out.length, SPAN)
                }
                HEADING.matches(line) -> {
                    val m = HEADING.matchEntire(line)!!
                    val level = m.groupValues[1].length
                    val st = out.length
                    appendInline(out, m.groupValues[2], accent, codeBg)
                    val size = when (level) { 1 -> 1.4f; 2 -> 1.25f; 3 -> 1.12f; else -> 1.05f }
                    out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, SPAN)
                    out.setSpan(RelativeSizeSpan(size), st, out.length, SPAN)
                }
                line.startsWith(">") -> {
                    val st = out.length
                    appendInline(out, line.removePrefix(">").trimStart(), accent, codeBg)
                    out.setSpan(ForegroundColorSpan(muted), st, out.length, SPAN)
                    out.setSpan(LeadingMarginSpan.Standard(margin(indent) + dp16()), st, out.length, SPAN)
                }
                BULLET.matches(line) -> {
                    val m = BULLET.matchEntire(line)!!
                    val st = out.length
                    out.append("•  ")
                    appendInline(out, m.groupValues[2], accent, codeBg)
                    out.setSpan(LeadingMarginSpan.Standard(margin(indent)), st, out.length, SPAN)
                }
                NUMBERED.matches(line) -> {
                    val m = NUMBERED.matchEntire(line)!!
                    val st = out.length
                    out.append("${m.groupValues[1]}.  ")
                    appendInline(out, m.groupValues[3], accent, codeBg)
                    out.setSpan(LeadingMarginSpan.Standard(margin(indent)), st, out.length, SPAN)
                }
                else -> appendInline(out, line, accent, codeBg)
            }
        }
        if (inFence) { newline(); flushCode() }
        return out
    }

    private fun dp16() = (16 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
    private fun margin(indent: Int) = ((1 + indent / 2) * 10 * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    /** Parse inline markup (recursively, so `**_x_**` nests) into [out]. */
    private fun appendInline(out: SpannableStringBuilder, s: String, accent: Int, codeBg: Int) {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            // backslash escape
            if (c == '\\' && i + 1 < n) { out.append(s[i + 1]); i += 2; continue }
            // `inline code`
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    val st = out.length
                    out.append(s, i + 1, end)
                    out.setSpan(TypefaceSpan("monospace"), st, out.length, SPAN)
                    out.setSpan(BackgroundColorSpan(codeBg), st, out.length, SPAN)
                    i = end + 1; continue
                }
            }
            // **bold** / __bold__
            if ((c == '*' || c == '_') && i + 1 < n && s[i + 1] == c) {
                val end = s.indexOf("$c$c", i + 2)
                if (end > i) {
                    val st = out.length
                    appendInline(out, s.substring(i + 2, end), accent, codeBg)
                    out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, SPAN)
                    i = end + 2; continue
                }
            }
            // *italic* / _italic_
            if (c == '*' || c == '_') {
                val end = s.indexOf(c, i + 1)
                if (end > i + 1) {
                    val st = out.length
                    appendInline(out, s.substring(i + 1, end), accent, codeBg)
                    out.setSpan(StyleSpan(Typeface.ITALIC), st, out.length, SPAN)
                    i = end + 1; continue
                }
            }
            // ~~strikethrough~~
            if (c == '~' && i + 1 < n && s[i + 1] == '~') {
                val end = s.indexOf("~~", i + 2)
                if (end > i) {
                    val st = out.length
                    appendInline(out, s.substring(i + 2, end), accent, codeBg)
                    out.setSpan(StrikethroughSpan(), st, out.length, SPAN)
                    i = end + 2; continue
                }
            }
            // [text](url) — render the text, styled like a link (no click, so selection still works)
            if (c == '[') {
                val close = s.indexOf(']', i + 1)
                if (close > i && close + 1 < n && s[close + 1] == '(') {
                    val urlEnd = s.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        val st = out.length
                        appendInline(out, s.substring(i + 1, close), accent, codeBg)
                        out.setSpan(ForegroundColorSpan(accent), st, out.length, SPAN)
                        out.setSpan(UnderlineSpan(), st, out.length, SPAN)
                        i = urlEnd + 1; continue
                    }
                }
            }
            out.append(c)
            i++
        }
    }

    /**
     * Convenience wrapper for non-streaming content (群公告正文、机器人 markdown 消息)：
     * 渲染 [source] 为 SpannableStringBuilder，空内容返回空串。
     */
    fun toSpannable(source: CharSequence?): SpannableStringBuilder {
        if (source.isNullOrEmpty()) return SpannableStringBuilder()
        return SpannableStringBuilder(render(source.toString()))
    }
}
