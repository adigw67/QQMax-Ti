package momoi.mod.qqpro.hook.privacy.turing

import android.content.Context
import com.tencent.turingfd.sdk.xq.Virgo
import momoi.anno.mixin.StaticHook

/**
 * Neutralizes TuringFD's debugger / ADB / debug-build detection.
 *
 * TuringFD (Tencent's anti-fraud device SDK in QQ) folds several debug signals
 * into a bitfield reported with its risk token:
 *
 *  - [Virgo.a] (Context): bit0 = ADB enabled (`adb_enabled`),
 *      bit1 = `development_settings_enabled`, bit2 = app debuggable flag,
 *      bit3 = `Debug.isDebuggerConnected()`. Returns the combined int.
 *  - [Virgo.e] (Context): `adb_enabled` boolean, also consumed elsewhere.
 *
 * On this watch ADB / USB-debugging is necessarily ON (we sideload the mod), so
 * these would otherwise always report a debugging environment. Force the clean
 * verdict: 0 (no debug signals) and false (ADB off). We deliberately leave the
 * token-generation path intact — only the leaf verdicts are clamped.
 *
 * Kept in its own sub-package so the `Virgo.a(Context)` hook does not collide at
 * Kotlin compile time with the `au.a(Context)` hook in the bugly package.
 */

@StaticHook(Virgo::class)
fun a(context: Context): Int = 0

@StaticHook(Virgo::class)
fun e(context: Context): Boolean = false
