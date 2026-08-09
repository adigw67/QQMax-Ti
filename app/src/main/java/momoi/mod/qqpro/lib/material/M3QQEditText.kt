package momoi.mod.qqpro.lib.material

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.tencent.mobileqq.text.QQText
import momoi.mod.qqpro.hook.InlineEmojiPanel
import momoi.mod.qqpro.hook.VoiceHost
import momoi.mod.qqpro.hook.VoiceRecord
import momoi.mod.qqpro.lib.ImeEditText
import momoi.mod.qqpro.lib.dp

/**
 * A reusable Material 3 text field that carries the same two affordances as the in-chat inline input
 * pill, but decoupled from any chat context: a leading **emoji** button that opens QQ's sysface
 * picker ([InlineEmojiPanel]) and inserts the chosen face into the field, and a trailing **mic**
 * button (shown only while the field is empty) that holds-to-record and 转文字 the speech straight
 * into the field via the shared [VoiceRecord] engine.
 *
 * It reuses the exact same picker + recorder the chat input uses (so they stay in lock-step), wiring
 * them through an [EditorVoiceHost] that streams STT text into [editText] and hosts the recording
 * overlay on the field's own window — no [CurrentContact]/inline-box dependency. Built for the
 * modernised "change info" dialogs (group name / remark / nickname) that lost emoji + voice when
 * they moved off QQ's native keyboard page, and reusable for QZone and any future Material input.
 *
 * Usage:
 * ```
 * val input = M3QQEditText(ctx).apply {
 *     setHint("输入昵称")
 *     setText(current)
 *     permissionFragmentProvider = { thisDialogFragment }   // for the RECORD_AUDIO prompt
 * }
 * // ...later: input.trimmedText()
 * ```
 */
@SuppressLint("ViewConstructor")
class M3QQEditText(context: Context) : LinearLayout(context) {

    companion object {
        /** Field text size in sp; also passed to QQText so rendered faces match the text scale. */
        private const val FIELD_TEXT_SP = 15
    }

    /** The underlying field. Public so callers can read/observe it or tweak input type directly. */
    val editText: ImeEditText = ImeEditText(context)

    /** Current field text size (sp); overridable via [setFieldTextSize], drives emoji glyph sizing too. */
    private var fieldTextSp: Int = FIELD_TEXT_SP

    private val emojiBtn: ImageView
    private val micBtn: ImageView

    /**
     * Supplies the AndroidX fragment used to request RECORD_AUDIO the first time voice is held while
     * the permission is missing (mirrors the chat bar's host fragment). Return the host dialog /
     * fragment; if null, a guidance toast is shown instead.
     */
    var permissionFragmentProvider: () -> Fragment? = { null }

    // Remember whatever the chat bar (or another field) had wired, and restore it when this field
    // detaches, so a dialog opened over a live chat doesn't leave a stale spinner callback behind.
    private var prevConvertCallback: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        // Bottom-align: the icons stay on the baseline row and don't drift up as the field grows
        // multiline. isBaselineAligned=false stops the EditText's text baseline from being aligned
        // against the (baseline-less) icon buttons, which otherwise shifts the text up off-center.
        gravity = Gravity.BOTTOM
        isBaselineAligned = false
        minimumHeight = 40.dp
        // Material outlined text-field container (same look as the dialog's plain field).
        background = M3.outlined(M3.outline, M3.radiusMd).apply { setColor(M3.surfaceContainerHigh) }

        emojiBtn = iconButton(MaterialSymbols.mood).also {
            // Pass our own window as the panel host: in a dialog the editText context is a
            // ContextThemeWrapper (no Activity) and the field isn't at the screen bottom, so the
            // panel anchors to this window's bottom like a soft keyboard.
            it.setOnClickListener {
                InlineEmojiPanel.toggle(editText, rootView as? ViewGroup)
            }
            addView(it)
        }

        editText.apply {
            background = null
            setTextColor(M3.onSurface)
            setHintTextColor(M3.hint)
            textSize = FIELD_TEXT_SP.toFloat()
            // Center text within a single-line height that equals the icon button height, so the
            // bottom-aligned icons sit exactly on the text line (and stay there when multiline grows).
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 40.dp
            setPadding(2.dp, 4.dp, 2.dp, 4.dp)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            maxLines = 1
        }
        addView(editText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.BOTTOM
        })

        micBtn = iconButton(MaterialSymbols.mic).also {
            VoiceRecord.attach(it, EditorVoiceHost())
            addView(it)
        }

        // Mic is shown only while the field is empty; once there is text the trailing slot frees up
        // (the dialog's own 确定 button submits, so no send button is needed here). The emoji button
        // stays put as a permanent leading affordance.
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refreshMic() }
        })
        refreshMic()
    }

    // ---- public API ----

    fun setText(value: CharSequence?) {
        // A prefilled value may contain QQ sysface codes (e.g. an emoji set earlier via the picker).
        // Render them back into EmoticonSpans with QQText (mode 19 = display-a-string-with-faces, the
        // same path nicknames use) so they show as glyphs instead of □ boxes. toString() still yields
        // the raw face codes, so re-submitting round-trips the emoji unchanged.
        val rendered: CharSequence? =
            if (value.isNullOrEmpty()) value
            else runCatching { QQText(value, 19, fieldTextSp, null) as CharSequence }.getOrDefault(value)
        editText.setText(rendered)
        editText.setSelection(editText.text?.length ?: 0)
        refreshMic()
    }

    fun setHint(value: CharSequence?) { editText.hint = value }

    /**
     * Override the field text size (sp), scaling the WHOLE field proportionally — the leading/trailing
     * icon buttons, their padding, the field's vertical padding and min height all shrink/grow with the
     * text so the bar height tracks the text instead of being pinned by the 40dp icons. Also drives
     * emoji glyph sizing in [setText].
     */
    fun setFieldTextSize(sp: Int) {
        fieldTextSp = sp
        val scale = sp.toFloat() / FIELD_TEXT_SP
        editText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        val vpad = (4 * scale).toInt().dp
        editText.setPadding(editText.paddingLeft, vpad, editText.paddingRight, vpad)
        val btn = (40 * scale).toInt().dp
        val ipad = (9 * scale).toInt().dp
        // Keep the row, the editText single-line box, and the icon buttons all the SAME height so the
        // bottom-aligned icons line up exactly with the centered text.
        minimumHeight = btn
        editText.minimumHeight = btn
        for (b in listOf(emojiBtn, micBtn)) {
            b.layoutParams = (b.layoutParams ?: LayoutParams(btn, btn)).apply { width = btn; height = btn }
            b.setPadding(ipad, ipad, ipad, ipad)
        }
        requestLayout()
    }

    /** Trimmed text content (what the change-info callbacks want to write). */
    fun trimmedText(): String = editText.text?.toString()?.trim().orEmpty()

    /** Allow up to 4 growing lines instead of a single line (for longer fields). */
    fun setMultiline(multiline: Boolean) {
        if (multiline) {
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            editText.isSingleLine = false
            editText.maxLines = 4
            gravity = Gravity.BOTTOM
        } else {
            editText.inputType = InputType.TYPE_CLASS_TEXT
            editText.isSingleLine = true
            editText.maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    /** Focus the field and raise the soft keyboard (call after the view is laid out). */
    fun focusAndShowKeyboard() {
        editText.post {
            editText.requestFocus()
            editText.setSelection(editText.text?.length ?: 0)
            imm()?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---- internals ----

    // getSystemService(Class) is API 23+ and this watch ROM doesn't have it (NoSuchMethodError) —
    // use the string form, available since API 1.
    private fun imm(): InputMethodManager? =
        context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun iconButton(symbolPath: String): ImageView = ImageView(context).apply {
        layoutParams = LayoutParams(40.dp, 40.dp).apply { gravity = Gravity.BOTTOM }
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = 9.dp
        setPadding(pad, pad, pad, pad)
        setImageDrawable(MaterialSymbol(symbolPath, M3.onSurfaceVariant))
        background = M3.ripple(null)
    }

    private fun refreshMic() {
        val empty = editText.text.isNullOrEmpty()
        // Keep the mic visible while a conversion is running so its spinner doesn't vanish on the
        // first recognised character streamed in.
        micBtn.visibility = if (empty || VoiceRecord.isConverting) View.VISIBLE else View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prevConvertCallback = VoiceRecord.onConvertStateChanged
        VoiceRecord.onConvertStateChanged = { post { refreshMic() } }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        InlineEmojiPanel.dismiss()
        VoiceRecord.onConvertStateChanged = prevConvertCallback
    }

    /**
     * Streams STT text into [editText] and hosts the recording overlay on this field's own window.
     * No PTT ([supportsPtt] = false), so holding-and-releasing the mic 转文字 straight into the field.
     */
    private inner class EditorVoiceHost : VoiceHost {
        private var sttAnchor = -1
        private var sttLen = 0

        override fun overlayContainer(anchor: View): ViewGroup? = anchor.rootView as? ViewGroup
        override fun permissionFragment(anchor: View): Fragment? = permissionFragmentProvider()
        override val isGroup: Boolean get() = false
        override val supportsPtt: Boolean get() = false
        override fun sendPtt(path: String, durationMs: Long) {}

        override fun beginStreaming() {
            sttAnchor = editText.selectionStart.coerceAtLeast(0)
            sttLen = 0
            editText.requestFocus()
        }

        override fun updateStreaming(text: String) {
            if (sttAnchor < 0) {
                val at = editText.selectionStart.coerceAtLeast(0)
                editText.text?.replace(at, editText.selectionEnd.coerceAtLeast(at), text)
                return
            }
            val len = editText.text?.length ?: 0
            val start = sttAnchor.coerceIn(0, len)
            val end = (sttAnchor + sttLen).coerceIn(start, len)
            editText.text?.replace(start, end, text)
            sttLen = text.length
            editText.setSelection((start + text.length).coerceAtMost(editText.text?.length ?: 0))
        }

        override fun endStreaming() {
            sttAnchor = -1
            sttLen = 0
        }
    }
}
