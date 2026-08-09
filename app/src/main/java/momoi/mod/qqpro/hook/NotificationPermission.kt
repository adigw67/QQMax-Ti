package momoi.mod.qqpro.hook

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import momoi.mod.qqpro.util.Utils

/**
 * Runtime request for the Android 13+ POST_NOTIFICATIONS permission. The target QQ APK never asks
 * for it, so on API 33+ devices every notification this mod posts (per-chat notify, reply, etc.) is
 * silently dropped until the user grants it from system settings. Requesting it on launch fixes that.
 *
 * The permission string and constant (API 33) are referenced as a literal so this still compiles
 * against the lower compile SDK. The matching <uses-permission> is declared in the manifest patch —
 * without it requestPermissions() returns denied immediately with no dialog.
 */
object NotificationPermission {

    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    private const val REQ_CODE = 0x9001

    fun ensure(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return // pre-Tiramisu: notifications are on by default
        runCatching {
            if (activity.checkSelfPermission(POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // After the user denies twice the system turns this into a silent no-op, so calling it
                // on every launch never nags — it only re-prompts while the request is still allowed.
                activity.requestPermissions(arrayOf(POST_NOTIFICATIONS), REQ_CODE)
                Utils.log("NotifPerm: requested POST_NOTIFICATIONS")
            }
        }.onFailure { Utils.log("NotifPerm: request failed: $it") }
    }
}
