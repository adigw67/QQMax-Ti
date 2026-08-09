package momoi.mod.qqpro.hook

import androidx.fragment.app.Fragment
import com.tencent.qqnt.watch.ui.componet.tips.TipsUtils
import momoi.anno.mixin.StaticHook
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.view.ConfirmFragment
import momoi.mod.qqpro.util.Utils

/**
 * Single choke point for QQ's destructive confirmation dialog. `TipsUtils.d(...)` is the red/alert
 * variant (icon_alert_red + red confirm button) used by 退出登录 / 清空消息 / 删除聊天记录 / 退出群·解散群
 * — it navigates to a native full-screen TipsFragment. When the M3 redesign is on, show our reusable
 * Material [ConfirmFragment] instead and forward the original positive/negative callbacks (which carry
 * the real action). Falls back to the native dialog if ours can't be shown.
 *
 * Param order mirrors the stub exactly (matched by descriptor): i=message res, str=custom message,
 * i5=positive-button label res, fn0=negative(cancel) callback, fn1=positive(confirm) callback.
 * Top-level (not a @Mixin body) so the lambdas/anonymous classes are safe.
 */
@StaticHook(TipsUtils::class)
fun d(
    self: TipsUtils,
    fragment: Fragment,
    i: Int,
    str: String?,
    i2: Int,
    i3: Int,
    num: Int?,
    num2: Int?,
    i4: Int,
    num3: Int?,
    i5: Int,
    num4: Int?,
    i6: Int,
    num5: Int?,
    i7: Int,
    fn0: (() -> Unit)?,
    fn1: (() -> Unit)?,
    str2: String?,
    str3: String?,
    str4: String?,
    map: HashMap<String, String>?,
    i8: Int,
) {
    if (Settings.useM3Settings.value) {
        val shown = runCatching {
            val title = str?.takeIf { it.isNotBlank() } ?: (if (i != 0) fragment.getString(i) else "")
            val label = if (i5 > 0) runCatching { fragment.getString(i5) }.getOrNull() ?: "确定" else "确定"
            ConfirmFragment(title, label, destructive = true, onCancel = { fn0?.invoke() }) { fn1?.invoke() }
                .show(fragment.parentFragmentManager, "qqpro_tips_confirm")
        }.onFailure { Utils.log("TipsDialogHook: failed, native fallback: $it") }.isSuccess
        if (shown) {
            Utils.log("TipsDialogHook: TipsUtils.d -> M3 confirm dialog")
            return
        }
    }
    TipsUtils.d(self, fragment, i, str, i2, i3, num, num2, i4, num3, i5, num4, i6, num5, i7, fn0, fn1, str2, str3, str4, map, i8)
}

/**
 * `TipsUtils.h(...)` is the single-button INFO variant (same 23-param shape as `d`) — used for
 * 搜索无结果 / 搜索失败 / 需要蓝牙 etc. It navigates to the same native full-screen TipsFragment, whose
 * background isn't themed. When the M3 redesign is on, show our Material [ConfirmFragment] in single-
 * button mode instead and forward the action callback. Falls back to native if ours can't be shown.
 */
@StaticHook(TipsUtils::class)
fun h(
    self: TipsUtils,
    fragment: Fragment,
    i: Int,
    str: String?,
    i2: Int,
    i3: Int,
    num: Int?,
    num2: Int?,
    i4: Int,
    num3: Int?,
    i5: Int,
    num4: Int?,
    i6: Int,
    num5: Int?,
    i7: Int,
    fn0: (() -> Unit)?,
    fn1: (() -> Unit)?,
    str2: String?,
    str3: String?,
    str4: String?,
    map: HashMap<String, String>?,
    i8: Int,
) {
    if (Settings.useM3Settings.value) {
        val shown = runCatching {
            val title = str?.takeIf { it.isNotBlank() } ?: (if (i != 0) fragment.getString(i) else "")
            val label = if (i5 > 0) runCatching { fragment.getString(i5) }.getOrNull() ?: "返回" else "返回"
            // The single button carries the action (e.g. 重试); no-result has none → just dismiss.
            ConfirmFragment(title, label, destructive = false, singleButton = true) { (fn1 ?: fn0)?.invoke() }
                .show(fragment.parentFragmentManager, "qqpro_tips_info")
        }.onFailure { Utils.log("TipsDialogHook.h: failed, native fallback: $it") }.isSuccess
        if (shown) {
            Utils.log("TipsDialogHook: TipsUtils.h -> M3 info dialog")
            return
        }
    }
    TipsUtils.h(self, fragment, i, str, i2, i3, num, num2, i4, num3, i5, num4, i6, num5, i7, fn0, fn1, str2, str3, str4, map, i8)
}
