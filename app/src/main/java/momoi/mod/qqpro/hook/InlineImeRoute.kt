package momoi.mod.qqpro.hook

import androidx.fragment.app.Fragment
import com.tencent.watch.aio_impl.coreImpl.helper.GroupAIOHelper
import com.tencent.watch.ime.util.StartImeUtil
import momoi.anno.mixin.StaticHook
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.view.M3InputDialog
import momoi.mod.qqpro.util.Utils

/** Prefix QQ uses on the friendUin arg of a "set_remark" call that's actually a group-name edit. */
private const val GROUP_NAME_KEY = "key_set_group_name_request-"

/**
 * Current value to pre-fill the rename field with (from the local cache, synchronously):
 * group name, buddy remark, or self nickname depending on the edit. Null = leave blank.
 */
private fun currentEditValue(src: String?, friendUin: String?): String? = runCatching {
    when {
        src == "set_remark" && friendUin?.startsWith(GROUP_NAME_KEY) == true -> {
            val uid = friendUin.removePrefix(GROUP_NAME_KEY)
            GroupAIOHelper.b[uid]?.groupName?.takeIf { it.isNotEmpty() }
        }
        src == "set_remark" -> friendUin?.toLongOrNull()?.let { ProfileDetailCard.remarkByUin(it) }
        src == "modify_nickname" -> ProfileDetailCard.selfNick()
        else -> null
    }
}.getOrNull()

/**
 * Single choke point for "完全行内输入". Every route that would open the keyboard page funnels
 * through [StartImeUtil.a] — the "aio" open (set as IMEOperation.openIME by the input bar) carries
 * reply / @ / image / edit staging, and the "stt" open carries the recognized text as the draft.
 *
 * When fullInlineInput is on and the inline EditText is live, we hand the payload to [InlineInput]
 * and skip navigation entirely; all other sources (set_remark / modify_nickname / qzone_* / feedback)
 * and the non-inline case fall through to the original.
 */
@StaticHook(StartImeUtil::class)
fun a(
    self: StartImeUtil,
    fragment: Fragment,
    src: String?,
    friendUin: String?,
    needEmotion: Boolean,
    draft: String?,
    callback: ((Any?) -> Unit)?,
    flag: Int,
) {
    // 全员禁言: a muted non-admin must not open the keyboard by ANY route that funnels through here —
    // reply / @ / edit / keyboard button (src="aio") or STT (src="stt"). The input bar is hidden
    // elsewhere; this blocks the actual opens (inline AND the native keyboard page below).
    if ((src == "aio" || src == "stt") && momoi.mod.qqpro.hook.style.isWholeMutedForSelf()) {
        Utils.log("InlineImeRoute: blocked src=$src (全员禁言)")
        runCatching { Utils.toast(fragment.requireContext(), "全员禁言中") }
        return
    }
    if ((src == "aio" || src == "stt") &&
        Settings.inlineChatInput.value && Settings.fullInlineInput.value &&
        InlineInput.isReady
    ) {
        Utils.log("InlineImeRoute: intercept src=$src inline")
        if (src == "stt") InlineInput.insertText(draft.orEmpty())
        else InlineInput.consumePending()
        return
    }

    // "Change info" text edits (改群名 / 备注 / 昵称) go through QQ's own full-screen keyboard page
    // (the Android 4 native flow). Pre-fill the rename field with the current value (e.g. the
    // existing group name) via the draft arg.
    val prefill = currentEditValue(src, friendUin) ?: draft

    StartImeUtil.a(self, fragment, src, friendUin, needEmotion, prefill, callback, flag)
}
