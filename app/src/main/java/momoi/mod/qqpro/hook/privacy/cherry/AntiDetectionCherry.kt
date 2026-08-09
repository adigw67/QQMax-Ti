package momoi.mod.qqpro.hook.privacy.cherry

import android.content.Context
import com.tencent.turingfd.sdk.xq.Cherry
import momoi.anno.mixin.StaticHook

/**
 * Neutralizes TuringFD's VPN / proxy detection.
 *
 *  - [Cherry.a] (no-arg) : VPN check — enumerates network interfaces and returns
 *      true if any active interface matches `tun\d+`.
 *  - [Cherry.a] (Context): proxy check — true if `http.proxyHost`/`proxyPort`
 *      system properties are set, or the connected WiFi network has a STATIC/PAC
 *      proxy configured.
 *
 * Force both to false so a VPN/proxy environment reports clean. Kept in its own
 * sub-package so the `Cherry.a(Context)` hook does not collide at Kotlin compile
 * time with the `Virgo.a(Context)` hook in the turing package.
 */

@StaticHook(Cherry::class)
fun a(): Boolean = false

@StaticHook(Cherry::class)
fun a(context: Context): Boolean = false
