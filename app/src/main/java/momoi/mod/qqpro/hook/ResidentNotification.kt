package momoi.mod.qqpro.hook

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.tencent.mobileqq.msf.service.MsfService
import momoi.anno.mixin.Mixin
import momoi.anno.mixin.PrivateCall
import momoi.mod.qqpro.util.Utils

/**
 * 常驻通知 (resident / foreground-service) notification. The native
 * [MsfService.startForegroundCompat] builds it with title "WearQQ" and content "手表QQ正在后台运行".
 * Replace that body so the notification carries ONLY a title ("QQMax-Ti 正在后台运行") and no content
 * line — same gate (allow_notification && resident_notification), channel, icon and click intent.
 *
 * `startForegroundCompat` is private in the target; ApkMixin matches the replacement by signature
 * (not the Kotlin `override` keyword, which can't target a private method), and [PrivateCall] keeps
 * the merged method a direct method so the original internal `invoke-direct` callers still resolve.
 */
@Mixin
class ResidentNotification : MsfService() {
    @PrivateCall
    fun startForegroundCompat() {
        runCatching {
            val sp = getSharedPreferences("wearqq", Context.MODE_PRIVATE)
            if (!sp.getBoolean("allow_notification", false) || !sp.getBoolean("resident_notification", false)) {
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel("WearQQ", "QQMax-Ti 常驻通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.createNotificationChannel(channel)
            }
            val iconId = resources.getIdentifier("ic_mtrl_chip_close_circle", "drawable", packageName)
            val intent = Intent().apply {
                component = ComponentName(applicationContext, "com.tencent.qqnt.watch.app.JumpActivity")
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getActivity(this, 0, intent, flags)
            // Title only — no setContentText, so the resident notification shows a single line.
            val notif = NotificationCompat.Builder(this, "WearQQ")
                .setContentTitle("QQMax-Ti 正在后台运行")
                .setSmallIcon(if (iconId != 0) iconId else android.R.drawable.sym_def_app_icon)
                .setContentIntent(pi)
                .build()
            startForeground(1, notif)
        }.onFailure { Utils.log("ResidentNotification.startForegroundCompat failed: ${it.message}") }
    }
}
