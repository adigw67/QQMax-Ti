package momoi.mod.qqpro.hook

import android.content.Intent
import com.tencent.qqnt.watch.mainframe.MainActivity
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.util.Utils

/**
 * Makes notification-tap → open-chat redirect reliable across cold AND warm starts.
 *
 * The two native consumers of the tap intent disagree on the type of `key_peerUin`:
 *  - COLD start (process was dead): `MainActivity.onCreate` only shows the splash; then
 *    `SplashFragment.onResume` reads `intent.getLongExtra("key_peerUin", 0L)` and bails on `0`.
 *    → it needs a **long**.
 *  - WARM start (activity already alive): `MainActivity.onNewIntent` reads
 *    `intent.getStringExtra("key_peerUin")` and returns early on `null`.
 *    → it needs a **String**.
 *
 * A single Intent extra can only hold one type, so no single stored value satisfies both. QQ's own
 * notifications store a long (so cold start works); our earlier "store a String" fix repaired warm
 * start but silently broke cold start — the reported "tap doesn't reliably open the chat".
 *
 * Fix: [NotificationReply] now stores the long (cold start works natively, same as QQ's own). Here we
 * bridge the warm path — before the native `onNewIntent` runs, synthesize the String form it expects
 * from the long. Only touches our own open-chat intents; everything else passes straight through.
 *
 * Sibling `@Mixin` classes already override `MainActivity` methods (更新检查/onCreate,
 * 屏蔽返回键·滚轮适配/onResume); ApkMixin chains them, so adding this onNewIntent override is safe.
 */
@Mixin
class NotificationClickRedirect : MainActivity() {
    override fun onNewIntent(intent: Intent?) {
        runCatching {
            if (intent != null &&
                intent.getBooleanExtra("open_chatfragment", false) &&
                intent.getStringExtra("key_peerUin") == null
            ) {
                val uin = intent.getLongExtra("key_peerUin", 0L)
                if (uin != 0L) {
                    intent.putExtra("key_peerUin", uin.toString())
                    Utils.log("NotificationClickRedirect: bridged key_peerUin long→String ($uin)")
                }
            }
        }
        super.onNewIntent(intent)
    }
}
