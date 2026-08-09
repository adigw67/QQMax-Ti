package momoi.mod.qqpro.hook.privacy.bugly

import com.tencent.bugly.proguard.aj
import momoi.anno.mixin.StaticHook

/**
 * Neutralizes Bugly's root detection so it reports a clean device.
 *
 * Bugly (Tencent's crash/telemetry SDK embedded in QQ) probes for root signals
 * and uploads the verdict with crash/RMonitor reports:
 *
 *  - [aj.q] / [aj.r] : root indicators — non-empty result of a su-binary / root
 *                      probe list (`new i().a()` / `new k().a()`).
 *  - [aj.s]          : root — true if any path in the su list exists OR
 *                      `Build.TAGS` contains "test-keys".
 *
 * We force all three to report "clean". The beacon-side `isRooted` value is
 * handled separately in AntiDetectionBeacon, and Bugly's emulator scorer
 * (`au.a`) in [AntiDetectionBuglyEmulator].
 *
 * IMPORTANT (ApkMixin principle): a source file's top-level @StaticHook functions
 * are all mixed into ONE target class — ApkMixin keys the source→target mapping by
 * the file's synthetic *Kt class, so a file that names two different target classes
 * silently keeps only the last and mis-attaches the rest. Therefore each file here
 * targets exactly one class: this one only `aj`; `au` lives in its own file.
 */

@StaticHook(aj::class)
fun q(): Boolean = false

@StaticHook(aj::class)
fun r(): Boolean = false

@StaticHook(aj::class)
fun s(): Boolean = false
