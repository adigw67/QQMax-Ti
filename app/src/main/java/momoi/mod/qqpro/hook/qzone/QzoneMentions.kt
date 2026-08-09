package momoi.mod.qqpro.hook.qzone

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import momoi.mod.qqpro.hook.openUserQzone
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.linkColorResolved

/**
 * Makes `@` mentions in a QZone post body tappable (→ that user's QZone home).
 *
 * QQ sends mentions in the raw summary as `@{uin:12345,nick:Name}` tokens that carry the full uin,
 * but [com.tencent.watch.qzone_impl.utils.StringUtil.a] renders each as plain `@Name` text and
 * THROWS THE UIN AWAY (see AtUserMatcher). So we re-extract the (uin, nick) pairs from the raw token
 * string and re-attach a [ClickableSpan] over each rendered `@Name` run in the already-parsed text.
 */
object QzoneMentions {

    // Same shape StringUtil.a matches: optional '@', uin digits, nick or nickname.
    private val TOKEN = Regex("@?\\{uin:(\\d+),nick(?:name)?:(.*?)\\}")

    /**
     * Given the [raw] token string (emoji already substituted, @ tokens still raw) and the [rendered]
     * output of StringUtil.a (where each token is now `@nick`), return a Spannable with a clickable
     * span over each mention and set [tv]'s movement method. Returns [rendered] unchanged when there
     * are no mentions.
     */
    fun linkify(raw: CharSequence, rendered: CharSequence, tv: TextView): CharSequence {
        val mentions = TOKEN.findAll(raw.toString())
            .mapNotNull { m -> m.groupValues[1].toLongOrNull()?.let { it to m.groupValues[2] } }
            .toList()
        if (mentions.isEmpty()) return rendered

        val sp: Spannable = rendered as? Spannable ?: SpannableStringBuilder(rendered)
        val s = sp.toString()
        var cursor = 0
        var added = false
        for ((uin, nick) in mentions) {
            if (nick.isEmpty() || uin <= 0L) continue
            val needle = "@$nick"
            val idx = s.indexOf(needle, cursor)
            if (idx < 0) continue
            val end = idx + needle.length
            sp.setSpan(mentionSpan(uin), idx, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            cursor = end
            added = true
        }
        if (added) tv.movementMethod = LinkMovementMethod.getInstance()
        return sp
    }

    private fun mentionSpan(uin: Long): ClickableSpan = object : ClickableSpan() {
        override fun onClick(widget: View) {
            runCatching { openUserQzone(widget, uin) }
                .onFailure { Utils.log("QzoneMentions click uin=$uin: $it") }
        }
        override fun updateDrawState(ds: TextPaint) {
            ds.color = linkColorResolved()
            ds.isUnderlineText = false
        }
    }
}
