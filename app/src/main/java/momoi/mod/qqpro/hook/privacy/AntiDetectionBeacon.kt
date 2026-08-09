package momoi.mod.qqpro.hook.privacy

import android.content.Context
import com.tencent.beacon.core.info.BeaconPubParams
import momoi.anno.mixin.Mixin

/**
 * Forces Beacon's root verdict to "clean".
 *
 * [BeaconPubParams] is the public-params bundle Tencent's Beacon analytics SDK
 * attaches to every uploaded event. Its `isRooted` field ("1"/"0") is exposed
 * via [getIsRooted]. We override the getter to always report "0" (not rooted),
 * regardless of how the field was computed upstream.
 *
 * Only the root verdict is touched here — device identifiers and other params
 * are intentionally left as-is (out of scope: root/debug/emulator only).
 */
@Mixin
class AntiDetectionBeacon(context: Context) : BeaconPubParams(context) {
    override fun getIsRooted(): String = "0"
}
