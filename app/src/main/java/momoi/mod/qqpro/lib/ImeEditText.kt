package momoi.mod.qqpro.lib

import android.content.Context
import android.net.Uri
import android.os.Build
import android.text.Selection
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import com.tencent.qqnt.kernel.nativeinterface.MsgElement

/**
 * Marker interface for the atomic inline tokens carried inside the chat EditText:
 * an [AtTag] (@member mention) or an [ImageTag] ([图片] placeholder). A span of one of
 * these types is treated as a single indivisible unit — backspacing into it deletes the
 * whole run, and at send time the run is turned into the matching MsgElement instead of text.
 */
interface InlineTag

/** An @member mention spanning the literal text "@nick " in the editor. */
class AtTag(val uid: String, val nick: String, val atType: Int) : InlineTag

/** An attached image/video, shown as "[图片]" in the editor; carries the ready MsgElement. */
class ImageTag(val element: MsgElement) : InlineTag

// EditText that advertises image/* support to the IME (Gboard GIF/sticker picker) and treats
// inline @ / image tokens (InlineTag spans) as atomic on backspace.
// Plain EditText never calls ViewCompat.onCreateInputConnection, so MIME types set via
// ViewCompat.setOnReceiveContentListener are never forwarded to EditorInfo. This subclass
// overrides onCreateInputConnection directly.
@Suppress("DEPRECATION")
class ImeEditText(context: Context) : android.widget.EditText(context) {

    /** IME rich-content commit (Gboard GIF/sticker picker) — an image was inserted via the keyboard. */
    var onImageUri: ((Uri) -> Unit)? = null

    /** Clipboard PASTE of an image (paste menu / Ctrl+V) — carries the pasted image's content URI. */
    var onImagePaste: ((Uri) -> Unit)? = null

    /** The first image content URI on the clipboard, or null (nothing pasteable-as-image). */
    private fun clipboardImageUri(): Uri? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: continue
            if (type.startsWith("image/")) return uri
        }
        return null
    }

    /**
     * Intercept a clipboard PASTE that carries an image: instead of pasting the raw content-URI as
     * text (or nothing), hand the URI to [onImagePaste] so the inline input can splice it in as an
     * atomic "[图片]" token. Falls through to the default text paste when there is no image on the
     * clipboard (or no handler wired).
     */
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            val handler = onImagePaste
            if (handler != null) {
                val uri = clipboardImageUri()
                if (uri != null) { handler(uri); return true }
            }
        }
        return super.onTextContextMenuItem(id)
    }

    /**
     * Invoked when backspace is pressed while the field is already empty. Return true to consume the
     * keypress (e.g. cancel an active reply/edit). Covers the soft-keyboard (deleteSurroundingText /
     * sendKeyEvent) and hardware ([onKeyDown]) DEL paths.
     */
    var onBackspaceWhenEmpty: (() -> Boolean)? = null

    /** True when DEL on an empty field was handled by [onBackspaceWhenEmpty]. */
    private fun consumeBackspaceWhenEmpty(): Boolean =
        editableText.isNullOrEmpty() && onBackspaceWhenEmpty?.invoke() == true

    /**
     * If the caret sits immediately after an [InlineTag] span (and there is no selection),
     * delete that whole span and return true. Used so a single backspace removes an entire
     * "@nick " mention or "[图片]" token in one go.
     */
    fun deleteTagBeforeCursor(): Boolean {
        val ed = editableText ?: return false
        val start = Selection.getSelectionStart(ed)
        val end = Selection.getSelectionEnd(ed)
        if (start != end || start <= 0) return false
        val tags = ed.getSpans(start - 1, start, InlineTag::class.java)
        // Pick a tag whose span ends exactly at the caret (the token just to the left).
        val tag = tags.firstOrNull { ed.getSpanEnd(it) == start } ?: return false
        val s = ed.getSpanStart(tag)
        val e = ed.getSpanEnd(tag)
        if (s < 0 || e < 0 || s >= e) return false
        ed.delete(s, e)
        return true
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // Don't let the caret rest strictly inside an inline token — snap it to the nearer edge so
        // the user can't type in the middle of an "@nick " mention or "[图片]" placeholder.
        val ed = text ?: return
        if (selStart != selEnd) return
        val span = ed.getSpans(selStart, selStart, InlineTag::class.java)
            .firstOrNull { ed.getSpanStart(it) < selStart && selStart < ed.getSpanEnd(it) } ?: return
        val s = ed.getSpanStart(span)
        val e = ed.getSpanEnd(span)
        val target = if (selStart - s <= e - selStart) s else e
        if (target != selStart) Selection.setSelection(ed, target)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Hardware / fallback DEL path.
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (deleteTagBeforeCursor()) return true
            if (consumeBackspaceWhenEmpty()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(editorInfo) ?: return null
        // Soft-keyboard backspace usually arrives as deleteSurroundingText(1, 0) (or a DEL
        // key event), not onKeyDown — intercept both so inline tokens delete atomically.
        val deleting = object : InputConnectionWrapper(base, false) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0) {
                    if (deleteTagBeforeCursor()) return true
                    if (consumeBackspaceWhenEmpty()) return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                    if (deleteTagBeforeCursor()) return true
                    if (consumeBackspaceWhenEmpty()) return true
                }
                return super.sendKeyEvent(event)
            }
        }
        EditorInfoCompat.setContentMimeTypes(editorInfo, arrayOf("image/*"))
        return InputConnectionCompat.createWrapper(deleting, editorInfo) { contentInfo, flags, _ ->
            if (Build.VERSION.SDK_INT >= 25 &&
                (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
            ) {
                runCatching { contentInfo.requestPermission() }
            }
            onImageUri?.invoke(contentInfo.contentUri)
            true
        }
    }
}
