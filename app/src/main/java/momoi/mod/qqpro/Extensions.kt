package momoi.mod.qqpro

import android.text.Spanned
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.forEach
import com.tencent.mobileqq.text.QQText
import com.tencent.mobileqq.text.style.EmoticonSpan
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.create
import momoi.mod.qqpro.lib.vertical
import java.io.File
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * QQ bakes inline face emoji as fixed-size [EmoticonSpan]s (sized to QQ's default chat text size),
 * so they don't follow a smaller/larger surrounding text — e.g. a reply quote or a re-sized settings
 * title where the faces end up far bigger than the words. Resize every face span in [cs] to an
 * ABSOLUTE target derived from [textPx] (the host TextView's text size) times [ratio], so a face sits
 * a touch taller than the cap height. Absolute (not multiplicative), so re-running it never compounds
 * — safe to call on every (re)bind / text change.
 */
/**
 * Parse QQ sysface codes in [text] into [EmoticonSpan]s so they render as glyphs instead of □ boxes
 * (this watch build leaves raw codes in plain TextViews — titlebar names, grey tips). QQText copies
 * any existing spans (e.g. clickable grey-tip name spans) and sysface parsing is length-preserving,
 * so their offsets stay valid. [sizeSp] sets the face glyph size. Returns [text] unchanged on failure.
 */
fun renderQQFaces(text: CharSequence?, sizeSp: Int): CharSequence {
    if (text.isNullOrEmpty()) return text ?: ""
    return runCatching { QQText(text, 19, sizeSp, null) as CharSequence }.getOrDefault(text)
}

fun fitEmojiSpans(cs: CharSequence?, textPx: Float, ratio: Float = 1.25f) {
    if (cs !is Spanned || textPx <= 0f) return
    val target = (textPx * ratio).toInt()
    if (target <= 0) return
    cs.getSpans(0, cs.length, EmoticonSpan::class.java).forEach { it.h(target) }
}

// Native (pre-resize) sizes captured once so scaling is always computed from the ORIGINAL value and
// never compounds across rebinds. Kept here (not in the @Mixin cell, which may not hold initialized
// fields) and weak so recycled views / regenerated spans are collected.
private val chatTextBasePx = WeakHashMap<TextView, Float>()
private val chatEmojiBasePx = WeakHashMap<EmoticonSpan, Int>()

/**
 * Set a chat body [TextView]'s text size to [finalTextPx] AND scale its inline face emoji to match,
 * by the SAME factor the text scaled from its native size (`finalTextPx / nativeTextPx`). This
 * preserves QQ's native emoji-to-text ratio, so emoji track the text without being clipped — unlike a
 * fixed absolute `textPx * k`, which can make a face taller than the line box and clip its top/bottom.
 * Native text size and each span's native size are captured once (before the first resize); absolute
 * and idempotent, so it's safe to call on every (re)bind.
 */
fun TextView.applyChatTextSize(finalTextPx: Float) {
    if (finalTextPx <= 0f) return
    val baseText = chatTextBasePx.getOrPut(this) { textSize } // native px, captured before we change it
    setTextSize(TypedValue.COMPLEX_UNIT_PX, finalTextPx)
    val ratio = if (baseText > 0f) finalTextPx / baseText else 1f
    (text as? Spanned)?.getSpans(0, text.length, EmoticonSpan::class.java)?.forEach { span ->
        val emBase = chatEmojiBasePx.getOrPut(span) { span.c }
        val newSize = (emBase * ratio).toInt()
        if (newSize > 0) span.h(newSize)
    }
}

/** Resize this view's face spans to match its own text size, and keep doing so as the text changes. */
fun TextView.keepEmojiFitToText(ratio: Float = 1.25f) {
    fitEmojiSpans(text, textSize, ratio)
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { fitEmojiSpans(text, textSize, ratio) }
    })
}

/**
 * Same as the [TextView] overload but for QQ's [com.tencent.widget.SingleLineTextView] — which is a
 * plain [View] (no TextWatcher), reused as the name/title in the M3 settings header. The name arrives
 * async, so re-fit on every pre-draw: resize the face spans to the view's text size and, when one
 * actually changed, force a layout rebuild. setText() short-circuits on an *equal* instance, so we
 * hand it a fresh SpannableString wrapper (which carries the same, already-resized span objects).
 */
fun com.tencent.widget.SingleLineTextView.keepEmojiFitToText(ratio: Float = 1.25f) {
    fun fit(): Boolean {
        val cs = text as? Spanned ?: return false
        val target = (textSize * ratio).toInt()
        if (target <= 0) return false
        var changed = false
        cs.getSpans(0, cs.length, EmoticonSpan::class.java).forEach {
            if (it.c != target) { it.h(target); changed = true }
        }
        return changed
    }
    viewTreeObserver.addOnPreDrawListener {
        if (fit()) { setText(android.text.SpannableString(text)); false } else true
    }
}

fun View.asGroupOrNull() = this as? ViewGroup
fun ViewParent.asGroup() = this as ViewGroup
fun View.asGroup() = this as ViewGroup
fun <E> List<E>.join(block: (E)->CharSequence) = joinToString("", transform = block)
fun ViewGroup.forEachAll(block: (View) -> Unit) {
    forEach { child ->
        block(child)
        child.asGroupOrNull()?.forEachAll(block)
    }
}
fun ViewGroup.findAll(block: (View) -> Boolean): View? {
    forEach { child ->
        if (block(child)) {
            return@findAll child
        } else child.asGroupOrNull()?.findAll(block)?.let {
            return@findAll it
        }
    }
    return null
}
fun ViewGroup.anyAll(block: (View) -> Boolean) = findAll(block) != null

fun String.removeBefore(key: String) = split(key, limit = 2)[1]
fun String.removeAfter(key: String) = split(key, limit = 2)[0]

fun View.warp(): LinearLayout {
    val lp = this.layoutParams
    val warp = create<LinearLayout>(context).vertical()
    parent.asGroup().let {
        it.removeView(this)
        warp.layoutParams = lp
        this.layoutParams = LinearLayout.LayoutParams(FILL, 0, 1f)
        warp.addView(this)
        it.addView(warp)
    }
    return warp
}

/**
 * Idempotent [warp]: if this view is already inside one of our vertical wrapper LinearLayouts,
 * return that instead of nesting another. Every cell hook (reply/forward/ark/file, link preview,
 * +1) used to wrap the content independently, each guarded only by its own cache — so a recycled
 * cell accumulated nested wrappers, each carrying a leftover `FILL/0/weight=1` lp that ballooned
 * a plain one-line message into a full-screen bubble. Sharing a single wrapper prevents that.
 */
fun View.warpOnce(): LinearLayout = (parent as? LinearLayout) ?: warp()

fun String?.emptyUse(other: String) = if (isNullOrEmpty()) other else this

fun File.child(path: String) = File(this, path)

/**
 * Best-effort cache directory. On some ROMs (e.g. XTC watches) externalCacheDir can be
 * null when external storage isn't mounted, so fall back to internal cache then filesDir.
 */
val android.content.Context.safeCacheDir: File?
    get() = externalCacheDir ?: cacheDir ?: filesDir

fun <T> Class<T>.findMethod(name: String, args: List<Class<Any>>? = null): Method {
    return try {
        if (args == null) getDeclaredMethod(name)
        else getDeclaredMethod(name, *args.toTypedArray())
    } catch (e: Exception) {
        superclass.findMethod(name, args)
    }
}