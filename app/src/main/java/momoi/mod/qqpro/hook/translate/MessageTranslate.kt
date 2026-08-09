package momoi.mod.qqpro.hook.translate

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.action.SelfContact
import momoi.mod.qqpro.lib.InlineTag
import momoi.mod.qqpro.lib.material.ButtonSpinner
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * In-chat message translation.
 *
 *  • Manual: a 翻译 long-press menu entry toggles [toggleManual] for one message.
 *  • Auto: [TranslateAll] enabled for the chat translates every visible text bubble.
 *
 * Rendering is done INSIDE the bubble's own content TextView (not a separate appended view) so the
 * translation inherits the message's exact text size/scaling and the bubble's existing measure logic
 * sizes the bubble to fit it (a separate child was under-measured → truncated, and its size drifted on
 * recycle). Per [Settings.translateReplaceInPlace]:
 *  • off (default) — original text, then a thin divider, then the translation below it (same TextView);
 *  • on            — replace the bubble text with the translation.
 *
 * RecyclerView-reuse safe: the native cell re-sets the original text on every (re)bind, so we re-derive
 * from it each time; [baseText] snapshots that original for the async callback. Results are cached by
 * (target,text); an async result is applied only if the cell is still bound to the same msgId.
 */
object MessageTranslate {
    private val boundMsg = WeakHashMap<AIOCellGroupWidget, Long>()
    /** (msgId, original text) of the cell we translated, so we can RESTORE it when hiding (a manual
     *  toggle doesn't trigger a native rebind) and not re-capture our own translation as the base. */
    private val origin = WeakHashMap<AIOCellGroupWidget, Pair<Long, CharSequence>>()
    private val cache = HashMap<String, String>()
    /** msgId → its currently-bound cell, so a manual toggle can re-render the on-screen cell. */
    private val widgetForMsg = HashMap<Long, WeakReference<AIOCellGroupWidget>>()
    /** Messages the user manually asked to translate (independent of the per-chat auto switch). */
    private val manual = HashSet<Long>()

    fun isManual(msgId: Long): Boolean = manual.contains(msgId)

    /** Toggle manual translation for [msg] and re-render its on-screen cell (if any). */
    fun toggleManual(msg: MsgRecord) = setManual(msg, !manual.contains(msg.msgId))

    /** Explicitly set manual translation for [msg] (used by 消息多选 batch translate) and re-render. */
    fun setManual(msg: MsgRecord, on: Boolean) {
        val id = msg.msgId
        if (on) manual.add(id) else manual.remove(id)
        val w = widgetForMsg[id]?.get()
        if (w != null && boundMsg[w] == id) render(w, msg)
    }

    /**
     * Translate [base] (an already-built display text) into the view language and render the result
     * INTO [content] inline — loader → result / failure — exactly like an in-chat bubble. For surfaces
     * that have no live [AIOCellGroupWidget] (the 合并转发 history viewer); see [HistoryTranslate]. The
     * caller owns [content]'s lifecycle, so there's no recycle bookkeeping here — it just renders once.
     */
    fun translateInto(content: TextView, base: CharSequence) {
        val src = base.toString().trim()
        if (src.isEmpty()) return
        val target = Settings.translateViewLang.value
        val replace = Settings.translateReplaceInPlace.value
        val key = "$target $src"
        cache[key]?.let { applyResult(content, base, it, replace); return }
        applyExtra(content, base, "翻译中…", M3.onSurfaceVariant)
        Translator.translate(src, target) { result ->
            runOnUi {
                if (result != null) cache[key] = result
                if (result != null) applyResult(content, base, result, replace)
                else applyExtra(content, base, "翻译失败", M3.error)
            }
        }
    }

    /** Entry point from the cell bind (AIOCell.HookCell.i). */
    fun bind(widget: AIOCellGroupWidget, item: WatchAIOMsgItem) {
        runCatching { render(widget, item.d) }
            .onFailure { Utils.log("MessageTranslate.bind failed: $it") }
    }

    private fun render(widget: AIOCellGroupWidget, msg: MsgRecord) {
        val id = msg.msgId
        boundMsg[widget] = id
        if (id != 0L) {
            if (widgetForMsg.size > 2048) widgetForMsg.clear()
            widgetForMsg[id] = WeakReference(widget)
        }

        val content = textViewOf(widget)
        val src = plainText(msg)
        // Auto (翻译全部) skips your own messages unless translateOwnMessages is on; manual always shows.
        val isSelf = msg.senderUid == SelfContact.peerUid
        val auto = TranslateAll.enabled() && (!isSelf || Settings.translateOwnMessages.value)
        val show = content != null && content.visibility == View.VISIBLE && src != null &&
            (manual.contains(id) || auto)

        val rec = origin[widget]
        if (!show) {
            // Toggling 隐藏翻译 doesn't trigger a native rebind, so if we translated THIS msg in place,
            // restore its original here. A recycled cell now showing a different msg already had its
            // new original set by the native bind — leave it.
            if (rec != null && rec.first == id && content != null) content.text = rec.second
            origin.remove(widget)
            return
        }

        // Base = the message's original text. If we already rendered our translation for this same id,
        // `content` holds OUR text — reuse the captured original; else capture the fresh native one.
        val base: CharSequence = if (rec != null && rec.first == id) rec.second
            else (content!!.text?.let { SpannableStringBuilder(it) } ?: "")
        origin[widget] = id to base

        val target = Settings.translateViewLang.value
        val key = "$target $src"
        val replace = Settings.translateReplaceInPlace.value
        val cached = cache[key]
        if (cached != null) { applyResult(content!!, base, cached, replace); return }

        // Cache miss — show a below-divider "翻译中…" loader (keep the original visible), then fetch.
        applyExtra(content!!, base, "翻译中…", M3.onSurfaceVariant)
        Translator.translate(src!!, target) { result ->
            runOnUi {
                if (result != null) cache[key] = result
                if (boundMsg[widget] != id) return@runOnUi
                val c = textViewOf(widget) ?: return@runOnUi
                if (result != null) applyResult(c, base, result, replace)
                else applyExtra(c, base, "翻译失败", M3.error)
            }
        }
    }

    /**
     * The bubble's text view: the content widget itself when it's a TextView, else the first
     * descendant TextView that carries text — covers image+text / mixed cells whose content widget is
     * a container, not a bare TextView (those rendered nothing before).
     */
    private fun textViewOf(widget: AIOCellGroupWidget): TextView? {
        val cw = runCatching { widget.getContentWidget<View>() }.getOrNull() ?: return null
        if (cw is TextView) return cw
        return firstTextView(cw)
    }

    private fun firstTextView(v: View): TextView? {
        if (v is TextView && !v.text.isNullOrEmpty()) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstTextView(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Plain text of a message (joined textElement contents), or null if it carries no text. */
    private fun plainText(msg: MsgRecord): String? =
        runCatching { msg.elements?.mapNotNull { it.textElement?.content }?.joinToString("") }
            .getOrNull()?.takeIf { it.isNotBlank() }

    private fun applyResult(content: TextView, base: CharSequence, translation: String, replace: Boolean) {
        if (replace) content.text = translation
        else applyExtra(content, base, translation, content.currentTextColor)
    }

    /** Render [base] then a thin divider then [extra] (in [extraColor]) into the one content TextView. */
    private fun applyExtra(content: TextView, base: CharSequence, extra: CharSequence, extraColor: Int) {
        val d = content.resources.displayMetrics.density
        val sb = SpannableStringBuilder(base)
        sb.append("\n")
        val hrStart = sb.length
        sb.append(" ") // placeholder the divider span draws over (zero advance)
        sb.setSpan(
            HrSpan(M3.outlineVariant, (1 * d).toInt().coerceAtLeast(1), (3 * d).toInt()) {
                (content.width - content.paddingLeft - content.paddingRight).coerceAtLeast(1)
            },
            hrStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.append("\n")
        val tStart = sb.length
        sb.append(extra)
        sb.setSpan(ForegroundColorSpan(extraColor), tStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        content.text = sb
    }

    /** A full-width horizontal rule drawn over a single placeholder char (zero text advance). */
    private class HrSpan(
        private val color: Int,
        private val thicknessPx: Int,
        private val padPx: Int,
        private val widthProvider: () -> Int,
    ) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let {
                val h = thicknessPx + padPx * 2
                it.ascent = -h; it.top = -h; it.descent = 0; it.bottom = 0
            }
            return 0
        }

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
        ) {
            val w = widthProvider().toFloat()
            val cy = (top + bottom) / 2f
            val oldColor = paint.color
            val oldStroke = paint.strokeWidth
            paint.color = color
            paint.strokeWidth = thicknessPx.toFloat()
            canvas.drawLine(x, cy, x + w, cy, paint)
            paint.color = oldColor
            paint.strokeWidth = oldStroke
        }
    }

    /**
     * Long-press send button: translate the input into [Settings.translateSendLang] WITHOUT sending.
     * Shows the M3 spinner on [sendBtn] (same as voice STT) while in flight. Only the plain-text runs
     * are translated — atomic inline tokens (@提及 / [图片] / [表情], carried by [InlineTag] spans) are
     * left untouched so they aren't consumed/garbled. On failure the box is left unchanged.
     */
    fun translateInput(editText: EditText, sendBtn: ImageView) {
        val text = editText.text ?: return
        if (text.isEmpty()) return

        // Plain-text runs = the gaps NOT covered by any inline token span.
        val spans = runCatching {
            text.getSpans(0, text.length, InlineTag::class.java).sortedBy { text.getSpanStart(it) }
        }.getOrDefault(emptyList())
        val runs = ArrayList<IntRange>()
        var i = 0
        for (tag in spans) {
            val s = text.getSpanStart(tag); val e = text.getSpanEnd(tag)
            if (s < i || s < 0 || e <= s) continue
            if (s > i) runs.add(i until s)
            i = e
        }
        if (i < text.length) runs.add(i until text.length)

        // Indices of runs that actually have something to translate (skip whitespace-only gaps).
        val targets = runs.withIndex().filter { text.subSequence(it.value.first, it.value.last + 1).isNotBlank() }
        if (targets.isEmpty()) { Utils.toast(editText.context, "没有可翻译的文字"); return }

        val target = Settings.translateSendLang.value
        val results = arrayOfNulls<String>(runs.size)
        var remaining = targets.size
        var anyFailed = false
        editText.isEnabled = false
        ButtonSpinner.start(sendBtn)

        val finish = {
            editText.isEnabled = true
            ButtonSpinner.stop(sendBtn)
            // Replace each translated run in place, LAST→FIRST so earlier offsets stay valid; tokens
            // and their spans (outside these ranges) are preserved by Editable.replace.
            runCatching {
                for (idx in runs.indices.reversed()) {
                    val rep = results[idx] ?: continue
                    val r = runs[idx]
                    editText.text?.replace(r.first, r.last + 1, rep)
                }
                editText.setSelection(editText.text?.length ?: 0)
            }.onFailure { Utils.log("translateInput apply failed: $it") }
            if (anyFailed) Utils.toast(editText.context, "部分内容翻译失败")
        }

        for ((idx, range) in targets) {
            val runText = text.subSequence(range.first, range.last + 1).toString()
            Translator.translate(runText, target) { res ->
                runOnUi {
                    if (res != null) results[idx] = res else anyFailed = true
                    remaining--
                    if (remaining == 0) finish()
                }
            }
        }
    }
}
