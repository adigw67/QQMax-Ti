package momoi.mod.qqpro.hook.call

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.tencent.activitys.QQNTC2CWatchActivity
import com.tencent.aidl.IQavInterface
import com.tencent.service.QavManageService
import momoi.mod.qqpro.util.Utils

/**
 * Handles the 接听 / 拒绝 buttons on the incoming-call notification ([CallNotification]). Both actions
 * need QQ's `IQavInterface` binder (the same one the native answer screen uses), so we bind the running
 * [QavManageService] and, on connect:
 * - **accept** → `binder.v(uin, isOnlyAudio)` then launch [QQNTC2CWatchActivity] with `isAccept=true`
 *   (mirrors `BeInvitedActivity`'s answer button);
 * - **reject** → `binder.E(uin, 1)`.
 *
 * A plain receiver (not a @Mixin) so the anonymous [ServiceConnection] is safe. Uses goAsync so the bind
 * callback can complete after onReceive returns.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != CallNotification.ACTION_ACCEPT && action != CallNotification.ACTION_REJECT) return
        val uin = intent.getStringExtra(CallNotification.EXTRA_UIN) ?: return
        val onlyAudio = intent.getBooleanExtra(CallNotification.EXTRA_ONLY_AUDIO, true)
        val accept = action == CallNotification.ACTION_ACCEPT
        val app = context.applicationContext
        CallNotification.cancelIncoming(app)

        val pending = goAsync()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                runCatching {
                    val binder = service as? IQavInterface
                    if (binder == null) {
                        Utils.log("CallActionReceiver: binder not IQavInterface")
                    } else if (accept) {
                        binder.v(uin, onlyAudio)
                        app.startActivity(
                            Intent(app, QQNTC2CWatchActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra("isAccept", true)
                            },
                        )
                        Utils.log("CallActionReceiver: accepted $uin (audio=$onlyAudio)")
                    } else {
                        binder.E(uin, 1)
                        Utils.log("CallActionReceiver: rejected $uin")
                    }
                }.onFailure { Utils.log("CallActionReceiver: $action failed: $it") }
                runCatching { app.unbindService(this) }
                pending.finish()
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val bound = runCatching {
            app.bindService(Intent(app, QavManageService::class.java), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            Utils.log("CallActionReceiver: bind QavManageService failed")
            pending.finish()
        }
    }
}
