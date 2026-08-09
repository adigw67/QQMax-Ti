package momoi.mod.qqpro.hook.call

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import momoi.mod.qqpro.util.Utils

/**
 * 蓝牙耳机接听 — answer an incoming QQ call with the headset's physical (hook / play-pause) button.
 *
 * QQ's watch call UI registers no media-button handling, so a headset hook press does nothing while a call
 * rings. We hold an active [MediaSession] for the duration of the ring; the system routes the headset
 * hook / MEDIA_PLAY(_PAUSE) key to the most-recently-active session, and we translate it into an accept by
 * broadcasting to [CallActionReceiver] (the same binder-based accept path the notification button uses).
 *
 * A plain object (not a @Mixin body) so the [MediaSession.Callback] anonymous class is safe on this ROM.
 * [startRinging] is called when an incoming call arrives; [stop] when it's answered, rejected, or ends.
 */
object HeadsetAnswer {

    private var session: MediaSession? = null
    @Volatile private var handled = false

    fun startRinging(context: Context, uin: String, onlyAudio: Boolean) {
        if (uin.isEmpty()) return
        stop() // clear any stale session from a previous ring
        val app = context.applicationContext
        handled = false
        runCatching {
            val s = MediaSession(app, "qqpro_call")
            s.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val ev = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    }
                    if (ev != null && ev.action == KeyEvent.ACTION_UP && isAnswerKey(ev.keyCode)) {
                        Utils.log("HeadsetAnswer: hook key ${ev.keyCode} -> accept $uin")
                        accept(app, uin, onlyAudio)
                        return true
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }

                // Some routers deliver the hook as a transport onPlay() instead of a raw key event.
                override fun onPlay() {
                    Utils.log("HeadsetAnswer: onPlay -> accept $uin")
                    accept(app, uin, onlyAudio)
                }
            })
            // A session must advertise a playback state + actions to be eligible for media-button routing.
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build(),
            )
            s.isActive = true
            session = s
            Utils.log("HeadsetAnswer: ringing session active for $uin (audio=$onlyAudio)")
        }.onFailure { Utils.log("HeadsetAnswer: start failed: $it") }
    }

    fun stop() {
        val s = session ?: return
        runCatching { s.isActive = false; s.release() }
        session = null
    }

    private fun isAnswerKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        keyCode == KeyEvent.KEYCODE_CALL

    private fun accept(context: Context, uin: String, onlyAudio: Boolean) {
        if (handled) return
        handled = true
        runCatching {
            context.sendBroadcast(
                Intent(context, CallActionReceiver::class.java).apply {
                    action = CallNotification.ACTION_ACCEPT
                    putExtra(CallNotification.EXTRA_UIN, uin)
                    putExtra(CallNotification.EXTRA_ONLY_AUDIO, onlyAudio)
                },
            )
        }.onFailure { Utils.log("HeadsetAnswer: accept broadcast failed: $it") }
        stop()
    }
}
