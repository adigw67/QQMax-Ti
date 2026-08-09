package momoi.mod.qqpro.hook.call

import com.tencent.activitys.QQNTC2CWatchActivity
import com.tencent.activitys.`QQNTC2CWatchActivity$mCallback$1`
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings

/**
 * Forwards QQ's own call-state callbacks into the Material call UI so state is event-driven (no polling):
 * - `d(time)` — the running call-duration string; QQ only emits it once the peer answers, so mirroring it
 *   makes our timer start at accept time (not at dial).
 * - `M(localHasAudio)` — mic mute state → swaps our mic / mic_off icon.
 * - `S(localHasVideo, remoteHasVideo)` — local camera on/off → swaps our videocam / videocam_off icon.
 *
 * `c` is the outer [QQNTC2CWatchActivity] (the callback's field). Each override calls super first so QQ's
 * native bookkeeping still runs, then updates our overlay. Gated on materializeCall.
 */
@Mixin
class CallCallbackHook(p0: QQNTC2CWatchActivity) : `QQNTC2CWatchActivity$mCallback$1`(p0) {

    override fun d(time: String) {
        super.d(time)
        if (Settings.materializeCall.value) MaterialCallUi.onTimer(c, time)
    }

    override fun M(localHasAudio: Boolean) {
        super.M(localHasAudio)
        if (Settings.materializeCall.value) MaterialCallUi.onMic(c, localHasAudio)
    }

    override fun S(localHasVideo: Boolean, remoteHasVideo: Boolean) {
        super.S(localHasVideo, remoteHasVideo)
        if (Settings.materializeCall.value) MaterialCallUi.onVideo(c, localHasVideo)
    }
}
