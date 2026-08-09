package momoi.mod.qqpro

import momoi.mod.qqpro.lib.material.M3

/**
 * Legacy color names, kept so existing (incl. chat-screen) call sites keep compiling unchanged.
 * These are now thin aliases over [M3] — the single source of truth. To retheme, edit [M3], not here.
 */
object Colors {
    val replyText get() = M3.replyText
    val replyBackground get() = M3.replyBackground
    val onSurface get() = M3.onSurface
    val onSurfaceTip get() = M3.onSurfaceTip
    val btn get() = M3.legacyBtn
    val atMe get() = M3.atMe

    object NickTag {
        val specialBg get() = M3.NickTag.specialBg
        val specialText get() = M3.NickTag.specialText
        val normalBg get() = M3.NickTag.normalBg
        val normalText get() = M3.NickTag.normalText
        val adminBg get() = M3.NickTag.adminBg
        val adminText get() = M3.NickTag.adminText
        val ownerBg get() = M3.NickTag.ownerBg
        val ownerText get() = M3.NickTag.ownerText
    }
}
