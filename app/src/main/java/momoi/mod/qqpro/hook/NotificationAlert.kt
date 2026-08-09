package momoi.mod.qqpro.hook

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings as ASettings
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils

/**
 * Plays the new-message alert (vibration + sound) for QQPro-posted notifications.
 *
 * QQPro's [NotificationReply] posts its own notification and bypasses QQ's native
 * `NotifyProcessor`, which is where the original app did its vibration + tone. So the alert has to
 * be re-driven here. We post the visual notification to a dedicated **silent** channel (no channel
 * sound, no channel vibration) so that everything is under our control — otherwise, on SDK > 28 the
 * native `CHANNEL_ID_SHOW_BADGE` channel vibrates on its own, which would fire even in 关闭 mode and
 * double up with our manual vibration.
 *
 * Sound and vibration are chosen independently via [Settings.notifySoundMode] and
 * [Settings.notifyVibrateMode] (each 0=关闭, 1=应用内, 2=系统):
 * - 应用内 → QQ's own message tone (`R.raw.office`) / the app's vibration pattern {100,200,200,100}.
 * - 系统 → the system default notification ringtone / a standard system-style vibration pattern.
 */
object NotificationAlert {
    // FOUR FIXED channels (sound × vibration), created once on fresh ids and NEVER mutated.
    // NotificationChannel sound/vibration are immutable after creation (changing an existing id
    // silently no-ops, and a wrong config even survives delete+recreate via a tombstone) — so we use
    // brand-new ids with a known-good config and never touch them again.
    //
    // 系统 sound/vibration are put ON the channel, so the OS produces them on post exactly like any
    // other app's notification (the canonical, reliable path — the app does nothing). 应用内 is
    // app-driven in [fire] (channel silent for that aspect); 关闭 is silent both on channel and app.
    // We pick the channel matching (soundMode==系统, vibrateMode==系统):
    const val CHANNEL_SOUND_VIBE = "qqpro_alert2_sv"   // 系统 sound + 系统 vibrate
    const val CHANNEL_SOUND = "qqpro_alert2_s"         // 系统 sound only
    const val CHANNEL_VIBE = "qqpro_alert2_v"          // 系统 vibrate only
    const val CHANNEL_QUIET = "qqpro_alert2_n"         // neither (silent channel)

    private const val MODE_OFF = 0
    private const val MODE_IN_APP = 1
    private const val MODE_SYSTEM = 2

    /** App message tone (in-app mode). Matches the native NotifyProcessor's R.raw.office. */
    private const val IN_APP_SOUND_RES = "office"
    private val IN_APP_VIBRATE = longArrayOf(100, 200, 200, 100)
    // Vibration pattern for the system-vibrate channels. No API exposes the user's configured system
    // pattern, so use a standard "double buzz". Must be concrete & non-empty —
    // setVibrationPattern(null) sets mVibrationEnabled=false.
    private val SYSTEM_VIBRATE = longArrayOf(0, 250, 250, 250)

    /** Held so the previous tone can be released before a new one starts (avoids overlap/leak). */
    private var player: MediaPlayer? = null

    private val ALL_CHANNELS = listOf(CHANNEL_SOUND_VIBE, CHANNEL_SOUND, CHANNEL_VIBE, CHANNEL_QUIET)

    /**
     * Create the four fixed channels (once) and return the id to post to for the current modes. The
     * 系统 sound and 系统 vibration are carried by the channel, so the OS produces them on post — the
     * app does nothing for 系统. Below Oreo there are no channels and [fire] does everything manually.
     */
    fun ensureChannel(ctx: Context): String {
        val sysSound = Settings.notifySoundMode.value == MODE_SYSTEM
        val sysVibe = Settings.notifyVibrateMode.value == MODE_SYSTEM
        val id = when {
            sysSound && sysVibe -> CHANNEL_SOUND_VIBE
            sysSound -> CHANNEL_SOUND
            sysVibe -> CHANNEL_VIBE
            else -> CHANNEL_QUIET
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Remove obsolete channels from earlier designs (legacy single channel + per-mode channels),
        // keeping only our four fixed ones.
        runCatching {
            nm.notificationChannels.map { it.id }
                .filter { it.startsWith("qqpro_") && it !in ALL_CHANNELS }
                .forEach { nm.deleteNotificationChannel(it) }
        }.onFailure { Utils.log("NotificationAlert: channel cleanup failed: ${it.message}") }

        ensureOneChannel(nm, CHANNEL_SOUND_VIBE, "消息提醒（声音+震动）", sound = true, vibrate = true)
        ensureOneChannel(nm, CHANNEL_SOUND, "消息提醒（声音）", sound = true, vibrate = false)
        ensureOneChannel(nm, CHANNEL_VIBE, "消息提醒（震动）", sound = false, vibrate = true)
        ensureOneChannel(nm, CHANNEL_QUIET, "消息提醒", sound = false, vibrate = false)

        val ch = nm.getNotificationChannel(id)
        Utils.log("NotificationAlert: ensureChannel -> $id (sysSound=$sysSound sysVibe=$sysVibe " +
            "shouldVibrate=${ch?.shouldVibrate()} channelSound=${ch?.sound})")
        return id
    }

    private fun ensureOneChannel(
        nm: NotificationManager, id: String, name: String, sound: Boolean, vibrate: Boolean,
    ) {
        if (nm.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            setShowBadge(true)
            enableLights(false)
            if (vibrate) {
                enableVibration(true)
                vibrationPattern = SYSTEM_VIBRATE   // concrete pattern → mVibrationEnabled=true
            } else {
                enableVibration(false)
            }
            if (sound) {
                val attrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(ASettings.System.DEFAULT_NOTIFICATION_URI, attrs)
            } else {
                setSound(null, null)
            }
        }
        nm.createNotificationChannel(channel)
        Utils.log("NotificationAlert: created channel $id (sound=$sound vibrate=$vibrate " +
            "shouldVibrate=${channel.shouldVibrate()} channelSound=${channel.sound})")
    }

    /**
     * Fire the APP-driven part of the alert once, after the first post (not on re-posts). 系统 is
     * produced by the channel itself (the app must NOT also fire it, or it would double); this only
     * handles 应用内 (manual tone / pattern). 关闭 does nothing.
     */
    fun fire(ctx: Context) {
        if (Settings.notifyVibrateMode.value == MODE_IN_APP) vibrateInApp(ctx)
        if (Settings.notifySoundMode.value == MODE_IN_APP) playInAppTone(ctx)
    }

    private fun vibrateInApp(ctx: Context) {
        val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(IN_APP_VIBRATE, -1),
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(IN_APP_VIBRATE, -1)
            }
        } catch (e: Throwable) {
            Utils.log("NotificationAlert: vibrate failed: ${e.message}")
        }
    }

    /** Play QQ's own message tone (R.raw.office) for in-app mode. */
    @Synchronized
    private fun playInAppTone(ctx: Context) {
        try {
            val resId = ctx.resources.getIdentifier(IN_APP_SOUND_RES, "raw", ctx.packageName)
            if (resId == 0) {
                Utils.log("NotificationAlert: in-app tone resource not found")
                return
            }
            runCatching { player?.release() }
            player = null
            val mp = MediaPlayer.create(ctx, resId) ?: return
            mp.setOnCompletionListener { p ->
                player = null
                runCatching { p.release() }
            }
            mp.isLooping = false
            mp.start()
            player = mp
        } catch (e: Throwable) {
            Utils.log("NotificationAlert: in-app tone failed: ${e.message}")
        }
    }
}
