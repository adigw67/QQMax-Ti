package momoi.mod.qqpro.hook.contact

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.widget.SingleLineTextView
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

/**
 * Enhances the name + id block at the top of the chat-settings panel (SettingFrame, shown for BOTH
 * friend and group chats):
 *
 *  - The header name (`tvNick`, a single-line custom widget) is replaced by a multiline [TextView]
 *    that wraps long names and can be long-pressed to copy.
 *  - The id line below it (`tvPeerId`, formatted by the app as `QQ号(昵称)` for friends, or just the
 *    group number for groups) is split into two stacked, multiline TextViews — the QQ number and the
 *    nickname — each long-pressable to copy independently.
 *
 * Both originals keep receiving the app's async updates; we leave them in place (hidden) and mirror
 * their text onto our views every frame, so later nick/remark changes still show without us hooking
 * the widgets' setText. Gated by [momoi.mod.qqpro.Settings.profileNameMultiline].
 */
object ProfileNameView {
    private const val TAG_NAME = "qqpro_profile_name_ml"
    private const val TAG_PEER = "qqpro_profile_peer_box"
    private const val TAG_CARD_NAME = "qqpro_card_name_ml"

    // `QQ号(昵称)` — capture the leading digits and the parenthesised remainder (greedy to the last
    // ')' so nicks containing parentheses still split correctly).
    private val PEER_RE = Regex("^\\s*(\\d+)\\s*\\((.*)\\)\\s*$", RegexOption.DOT_MATCHES_ALL)

    fun enhance(root: ViewGroup) {
        runCatching {
            // The header nick is the SingleLineTextView whose parent also holds the avatar view
            // (avatar + nick + uin sit together in one LinearLayout). Fall back to the first
            // SingleLineTextView if that shape ever changes.
            val nick = (root.findAll { v ->
                v is SingleLineTextView &&
                    (v.parent as? ViewGroup)?.let { p ->
                        (0 until p.childCount).any {
                            p.getChildAt(it).javaClass.simpleName.contains("Avatar")
                        }
                    } == true
            } ?: root.findAll { it is SingleLineTextView }) as? SingleLineTextView
            if (nick == null) {
                Utils.log("ProfileNameView: nick view not found")
                return
            }
            val parent = nick.parent as? LinearLayout ?: run {
                Utils.log("ProfileNameView: nick parent not a LinearLayout")
                return
            }
            enhanceNick(parent, nick)
            enhancePeerId(parent)
            Utils.log("ProfileNameView: header enhanced")
        }.onFailure { Utils.log("ProfileNameView.enhance failed: $it") }
    }

    /**
     * Profile/member card variant: the single-line [nick] sits inside a wrapper (e.g. a
     * ConstraintLayout) that is itself a child of a vertical LinearLayout. Hide that wrapper and drop
     * a multiline, long-press-copyable TextView into the column in its place. Must live here (not in
     * the @Mixin class) — anonymous listeners declared inside a @Mixin body crash with
     * IllegalAccessError (see [[qqpro-mixin-anon-class]]).
     */
    fun enhanceCardName(nick: SingleLineTextView, fallbackColor: Int, textSizeSp: Float = 13f) {
        runCatching {
            val wrapper = nick.parent as? View ?: return
            val column = wrapper.parent as? LinearLayout ?: return
            column.findAll { it.tag == TAG_CARD_NAME }?.let { (it.parent as? ViewGroup)?.removeView(it) }
            val ctx = nick.context
            val ml = TextView(ctx).apply {
                tag = TAG_CARD_NAME
                textSize = textSizeSp
                setTextColor(fallbackColor)
                gravity = Gravity.CENTER
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                text = nick.text
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnLongClickListener { copyAndToast(ctx, (it as TextView).text, "已复制昵称"); true }
            }
            column.addView(ml, column.indexOfChild(wrapper) + 1)
            wrapper.visibility = View.GONE
            mirror(ml) { runCatching { nick.text }.getOrNull() }
            Utils.log("ProfileNameView: card name shown multiline")
        }.onFailure { Utils.log("ProfileNameView.enhanceCardName failed: $it") }
    }

    /** Replace the single-line header nick with a multiline, long-press-copyable TextView. */
    private fun enhanceNick(parent: LinearLayout, nick: SingleLineTextView) {
        // Idempotency: onViewCreated can fire again — drop any view we added before.
        parent.findAll { it.tag == TAG_NAME }?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val ctx = nick.context

        val ml = TextView(ctx).apply {
            tag = TAG_NAME
            textSize = 14f
            setTextColor(M3.onSurface)
            gravity = Gravity.CENTER
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setOnLongClickListener { copyAndToast(ctx, (it as TextView).text, "已复制名称"); true }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        (nick.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            lp.setMargins(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin)
        }
        ml.layoutParams = lp
        parent.addView(ml, parent.indexOfChild(nick) + 1)
        nick.visibility = View.GONE

        mirror(ml) { runCatching { nick.text }.getOrNull() }
    }

    /** Split the `QQ号(昵称)` id line into two stacked, independently-copyable multiline lines. */
    private fun enhancePeerId(parent: LinearLayout) {
        // The id line is the original direct-child plain TextView in this LinearLayout. Exclude our
        // own injected name view (TAG_NAME, also a plain TextView and inserted *before* tvPeerId) so
        // we don't grab it by mistake and shrink the name to the id-line size.
        val peer = (0 until parent.childCount)
            .map { parent.getChildAt(it) }
            .firstOrNull { it.javaClass == TextView::class.java && it.tag != TAG_NAME } as? TextView
        if (peer == null) {
            Utils.log("ProfileNameView: peer id view not found")
            return
        }
        // Idempotency.
        parent.findAll { it.tag == TAG_PEER }?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val ctx = peer.context

        fun line(toast: String) = TextView(ctx).apply {
            textSize = 10f
            setTextColor(M3.onSurface)
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setOnLongClickListener { copyAndToast(ctx, (it as TextView).text, toast); true }
        }
        val uinView = line("QQ号已复制")
        val nickView = line("昵称已复制").apply { setTextColor(M3.onSurfaceVariant) }

        val box = LinearLayout(ctx).apply {
            tag = TAG_PEER
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(uinView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(nickView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 1.dp })
        }
        parent.addView(box, parent.indexOfChild(peer) + 1, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        peer.visibility = View.GONE

        // Mirror + split the (async-updated) id text each frame.
        box.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            var last: CharSequence? = null
            override fun onPreDraw(): Boolean {
                if (box.parent == null) return true
                val cur = runCatching { peer.text }.getOrNull() ?: return true
                if (TextUtils.equals(cur, last)) return true
                last = cur
                val m = PEER_RE.find(cur.toString())
                if (m != null) {
                    uinView.text = m.groupValues[1]
                    nickView.text = m.groupValues[2]
                    nickView.visibility = View.VISIBLE
                } else {
                    uinView.text = cur
                    nickView.visibility = View.GONE
                }
                return true
            }
        })
    }

    /** Mirror an async-updated source CharSequence onto [dst] each frame. */
    private fun mirror(dst: TextView, source: () -> CharSequence?) {
        dst.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            var last: CharSequence? = null
            override fun onPreDraw(): Boolean {
                if (dst.parent == null) return true
                val cur = source()
                if (cur != null && !TextUtils.equals(cur, last)) {
                    last = cur
                    dst.text = cur
                }
                return true
            }
        })
    }

    private fun copyAndToast(ctx: Context, text: CharSequence?, toast: String) {
        val t = text?.toString().orEmpty()
        if (t.isEmpty()) return
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("text", t))
            Utils.toast(ctx, toast)
        }.onFailure { Utils.log("ProfileNameView copy failed: $it") }
    }
}
