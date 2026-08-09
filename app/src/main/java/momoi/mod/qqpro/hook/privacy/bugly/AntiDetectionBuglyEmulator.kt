package momoi.mod.qqpro.hook.privacy.bugly

import android.content.Context
import com.tencent.bugly.proguard.au
import momoi.anno.mixin.StaticHook
import momoi.mod.qqpro.util.Utils

/**
 * Neutralizes Bugly's emulator scorer so the emulator confidence never accrues.
 *
 * Original [au.a] walks the device model ("mumu"), CPU abi ("x86") and
 * /system|/sys path ("vbox","virtio") feature lists and bumps an emulator
 * confidence score + reason string persisted to SP_EMULATOR_CONFIDENCE. No-op it:
 * the score field keeps its default of 0, so the device reports as non-emulator.
 *
 * Split out from [AntiDetectionBugly] (the `aj` root probes) because ApkMixin maps
 * a source file's @StaticHook functions to a SINGLE target class (keyed by the
 * file's synthetic *Kt class); `aj` and `au` must therefore live in separate files.
 * Kept in the `bugly` sub-package so `au.a(Context)` doesn't collide at Kotlin
 * compile time with `Virgo.a(Context)` (turing) / `Cherry.a(Context)` (cherry).
 */

@StaticHook(au::class)
fun a(context: Context) {
    Utils.log("AntiDetection: bugly emulator scorer (au.a) neutralized")
}
