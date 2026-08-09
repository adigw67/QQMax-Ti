package momoi.mod.qqpro.hook.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings as ASettings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tencent.activitys.BeInvitedActivity
import kotlin.concurrent.thread
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import java.net.URL

/**
 * 来电通知修复 — post a reliable incoming-call notification that carries the caller's name + avatar and
 * has 接听 / 拒绝 action buttons, so the call is answerable straight from the notification without relying
 * on QQ's flaky full-screen-intent path. When 使用全屏来电 ([Settings.callFullScreenIntent]) is on, a
 * full-screen intent is ALSO attached (launches the native answer screen as a takeover); when off, the
 * heads-up notification with buttons is the whole experience.
 *
 * Posted from [CallServiceHook] (QavManageService.onStartCommand) where the caller uin/nick/isOnlyAudio
 * are available from the intent extras. The accept/reject buttons fire [CallActionReceiver], which binds
 * the running QavManageService and drives its IQavInterface binder.
 */
object CallNotification {
    private const val CHANNEL = "qqpro_call_v1"
    const val NOTIFY_ID = 0x51C0 // 20928 — clear of NotificationReply's per-chat ids (512-521)

    const val ACTION_ACCEPT = "momoi.mod.qqpro.CALL_ACCEPT"
    const val ACTION_REJECT = "momoi.mod.qqpro.CALL_REJECT"
    const val EXTRA_UIN = "qqpro_call_uin"
    const val EXTRA_ONLY_AUDIO = "qqpro_call_only_audio"

    /**
     * Try to launch the native answer screen full-screen straight away (a real phone-call takeover),
     * bypassing the OS's "only show heads-up when unlocked" behaviour. Requires the draw-over-other-apps
     * permission for the background-activity-launch exemption on Android 10+. Returns true if we launched
     * (so the caller can skip posting the ringing notification), false to fall back to the notification.
     */
    fun launchFullScreen(context: Context, peerUin: String, isOnlyAudio: Boolean): Boolean {
        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ASettings.canDrawOverlays(context)
        if (!canOverlay) return false
        return runCatching {
            context.startActivity(
                Intent(context, BeInvitedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("key_peer_uin", peerUin)
                    putExtra("key_only_audio", isOnlyAudio)
                },
            )
            Utils.log("CallNotification: launched full-screen BeInvitedActivity (overlay)")
            true
        }.onFailure { Utils.log("CallNotification: full-screen launch failed: $it") }.getOrDefault(false)
    }

    fun postIncoming(context: Context, peerUin: String, nick: String?, isOnlyAudio: Boolean) {
        runCatching {
            ensureChannel(context)
            val title = nick?.takeIf { it.isNotBlank() } ?: peerUin.takeIf { it.isNotBlank() } ?: "QQ 来电"
            val text = (if (isOnlyAudio) "语音通话" else "视频通话") + " · 正在呼叫你…"

            val openPi = PendingIntent.getActivity(
                context, NOTIFY_ID,
                Intent(context, BeInvitedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("key_peer_uin", peerUin)
                    putExtra("key_only_audio", isOnlyAudio)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
            )
            val acceptPi = actionPi(context, ACTION_ACCEPT, 1, peerUin, isOnlyAudio)
            val rejectPi = actionPi(context, ACTION_REJECT, 2, peerUin, isOnlyAudio)

            val smallIcon = context.resources
                .getIdentifier("notify_newmessage", "mipmap", context.packageName)
                .let { if (it != 0) it else context.applicationInfo.icon }

            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true) // so the async avatar re-post doesn't re-ring
                .setContentIntent(openPi)
                .setTimeoutAfter(60_000L)
                .addAction(android.R.drawable.sym_action_call, "接听", acceptPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒绝", rejectPi)
            if (Settings.callFullScreenIntent.value) builder.setFullScreenIntent(openPi, true)

            NotificationManagerCompat.from(context).notify(NOTIFY_ID, builder.build())
            Utils.log("CallNotification: posted incoming (uin=$peerUin nick=$nick audio=$isOnlyAudio fsi=${Settings.callFullScreenIntent.value})")

            loadAvatarAsync(context, peerUin) { bmp ->
                runCatching {
                    builder.setLargeIcon(bmp)
                    NotificationManagerCompat.from(context).notify(NOTIFY_ID, builder.build())
                }
            }
        }.onFailure { Utils.log("CallNotification: postIncoming failed: $it") }
    }

    fun cancelIncoming(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFY_ID) }
        HeadsetAnswer.stop() // ring dismissed (accepted/rejected/ended) — release the media session
    }

    private fun actionPi(
        context: Context, action: String, requestCode: Int, peerUin: String, isOnlyAudio: Boolean,
    ): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_UIN, peerUin)
            putExtra(EXTRA_ONLY_AUDIO, isOnlyAudio)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
    }

    /** Download the caller's avatar (by uin) off the main thread and hand back the bitmap. Best-effort. */
    private fun loadAvatarAsync(context: Context, uin: String, onLoaded: (android.graphics.Bitmap) -> Unit) {
        if (uin.isBlank()) return
        thread(name = "qqpro-call-avatar") {
            runCatching {
                val url = "https://q.qlogo.cn/headimg_dl?dst_uin=$uin&spec=140"
                URL(url).openStream().use { input ->
                    BitmapFactory.decodeStream(input)?.let { onLoaded(it) }
                }
            }.onFailure { Utils.log("CallNotification: avatar load failed: $it") }
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "来电", NotificationManager.IMPORTANCE_HIGH).apply {
            setShowBadge(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 600, 700, 600)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(ASettings.System.DEFAULT_RINGTONE_URI, attrs)
        }
        nm.createNotificationChannel(ch)
    }

    private fun immutableFlag() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
}
