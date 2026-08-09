package momoi.mod.qqpro.hook.aio_cell

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import momoi.mod.qqpro.hook.view.CallConfirmFragment
import momoi.mod.qqpro.util.Utils

/**
 * Gate a call-record bubble's tap behind a confirmation. Native `WatchQavItemCell.d` sets an onClick that
 * immediately redials (`IWatchQavFacade.goToAVScene`) — far too easy to trigger by accident. We wrap that
 * click with the shared [CallConfirmFragment] ("确定要发起…吗？"); the native call only runs on confirm.
 *
 * A plain object (NOT a @Mixin body) so the `setOnClickListener` anonymous classes are safe. Called from
 * [AIOCell.HookCell.i] on every bind: `d()` freshly (re)sets the native listener each bind (before our
 * gating runs), so we always capture the correct per-message listener — no stale-recycle risk.
 */
object CallRecordConfirm {

    fun gate(tv: TextView, isVideo: Boolean) {
        val native = readOnClickListener(tv) ?: return
        // A detached proxy view carries the native call logic; on confirm we perform ITS click, which
        // invokes native.onClick(tv) with the REAL, attached TextView — the native handler resolves the
        // chat fragment + context from that view, so it must be the attached one.
        val proxy = View(tv.context)
        proxy.setOnClickListener { native.onClick(tv) }
        tv.setOnClickListener {
            runCatching {
                val fm = tv.findFragmentManager() ?: return@runCatching
                CallConfirmFragment(
                    if (isVideo) "确定要发起视频通话吗？" else "确定要发起语音通话吗？",
                    proxy,
                ).show(fm, "qqpro_call_record_confirm")
            }.onFailure { Utils.log("CallRecordConfirm: show failed: $it") }
        }
    }

    private fun View.findFragmentManager(): FragmentManager? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is FragmentActivity) return c.supportFragmentManager
            c = c.baseContext
        }
        return null
    }

    /** Read a View's current OnClickListener via reflection (framework greylist; null if unavailable). */
    private fun readOnClickListener(v: View): View.OnClickListener? = runCatching {
        val getInfo = View::class.java.getDeclaredMethod("getListenerInfo").apply { isAccessible = true }
        val info = getInfo.invoke(v) ?: return null
        val field = info.javaClass.getDeclaredField("mOnClickListener").apply { isAccessible = true }
        field.get(info) as? View.OnClickListener
    }.getOrNull()
}
