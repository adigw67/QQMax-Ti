package momoi.mod.qqpro.hook.translate

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.edit
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.style.CARD_MARGIN_DP
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

/**
 * Per-conversation "翻译全部消息" state. When on for a peer, every visible text bubble in that chat is
 * auto-translated into [Settings.translateViewLang] (see [MessageTranslate]). Stored in its own
 * SharedPreferences keyed by peerUid so it's independent of the global qqpro settings and persists
 * across sessions. The active chat is identified via [CurrentContact] (the global current-chat
 * singleton), the same source the cell renderer reads.
 */
object TranslateAll {
    private val sp = Utils.application.getSharedPreferences("qqpro_translate", 0)

    fun enabled(peerUid: String = CurrentContact.peerUid): Boolean =
        peerUid.isNotEmpty() && sp.getBoolean(peerUid, false)

    fun setEnabled(peerUid: String, value: Boolean) {
        if (peerUid.isEmpty()) return
        sp.edit { putBoolean(peerUid, value) }
        Utils.log("TranslateAll: peer=$peerUid enabled=$value")
    }

    private const val TAG = "qqpro_translate_all_row"

    /**
     * Inject a "翻译全部消息" switch row into the native chat-settings list (好友/群聊设置). Built as a
     * plain native switch row added to `setting_container`: when the M3 settings rebuild is active it
     * gets harvested into the M3 list (via attachLateInjections); otherwise it shows as-is (with the
     * same card margins as the native rows). Gated by [Settings.translateShowAllSwitch]; idempotent.
     */
    fun inject(root: View) {
        runCatching {
            val ctx = root.context
            val id = ctx.resources.getIdentifier("setting_container", "id", ctx.packageName)
            val container = (if (id != 0) root.findViewById<View>(id) else null) as? ViewGroup ?: return
            if (container.findViewWithTag<View>(TAG) != null) return // already injected
            val peer = CurrentContact.peerUid
            if (peer.isEmpty()) { Utils.log("TranslateAll.inject: no current peer"); return }

            val row = LinearLayout(ctx).apply {
                tag = TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp, 12.dp, 16.dp, 12.dp)
                background = M3.rounded(M3.surfaceContainer, M3.radiusMd)
            }
            val title = TextView(ctx).apply {
                text = "翻译全部消息"
                setTextColor(M3.onSurface)
                textSize = 14f
            }
            val sw = Switch(ctx).apply {
                isChecked = enabled(peer)
                setOnCheckedChangeListener { _, v -> setEnabled(peer, v) }
            }
            row.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP))
            // Tapping the row toggles the switch — this is also what the harvested M3 row delegates to.
            row.setOnClickListener { sw.toggle() }

            val h = (2 * CARD_MARGIN_DP).dp
            container.addView(row, LinearLayout.LayoutParams(FILL, WRAP).apply {
                setMargins(h, CARD_MARGIN_DP.dp, h, CARD_MARGIN_DP.dp)
            })
            Utils.log("TranslateAll.inject: row added for peer=$peer")
        }.onFailure { Utils.log("TranslateAll.inject failed: $it") }
    }
}
