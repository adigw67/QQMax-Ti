package momoi.mod.qqpro.hook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.watch.aio_impl.ui.frames.SettingFrame
import momoi.mod.qqpro.hook.style.cardMargin
import momoi.mod.qqpro.util.Utils

private const val QZONE_LABEL = "TA的空间"

/**
 * Add a "TA的空间" entry to the bottom of a single chat's (好友) settings page ([SettingFrame],
 * the same rightmost page that hosts 搜索聊天记录 / 消息设置 / 删除好友). Tapping it opens the
 * friend's QZone home feed ([com.tencent.watch.qzone_impl.frame.QZoneMineFragment], nav destination
 * `zone_main_fragment`) with their uin as `key_uin`.
 *
 * DM only (chatType==1): a group has no personal QZone. Called from the SettingFrame hook's
 * onViewCreated (see [GroupAvatarPreview]). Lives outside any @Mixin class so the click lambda
 * compiles into this normal file rather than being copied into the target package (anonymous
 * classes inside a @Mixin body crash with IllegalAccessError).
 */
fun addQzoneEntry(fragment: SettingFrame) {
    runCatching {
        val args = fragment.arguments ?: return
        // 1 = single chat (好友); groups (2) have no personal QZone.
        if (args.getInt("key_bundle_chat_type") != 1) return
        // key_bundle_chat_uin is the peer's QQ number (string) for a DM (see SettingFrame.smali).
        val uin = args.getString("key_bundle_chat_uin")?.trim()?.toLongOrNull() ?: return

        val scroll = fragment.i ?: return
        val container = scroll.getChildAt(0) as? LinearLayout ?: return
        val ctx = fragment.requireContext()
        val res = ctx.resources
        val pkg = ctx.packageName
        val descId = res.getIdentifier("desc", "id", pkg)

        // onViewCreated may fire again on the same container (returning to the page) — guard
        // against appending a duplicate entry row each time.
        for (i in 0 until container.childCount) {
            val desc = container.getChildAt(i).findViewById<TextView>(descId)
            if (desc?.text?.toString() == QZONE_LABEL) return
        }

        val layoutId = res.getIdentifier("setting_item", "layout", pkg)
        if (layoutId == 0) {
            Utils.log("Qzone: setting_item layout not found")
            return
        }
        val row = LayoutInflater.from(ctx).inflate(layoutId, container, false)
        val iconId = res.getIdentifier("icon_qzone", "drawable", pkg)
        if (iconId != 0) {
            row.findViewById<ImageView>(res.getIdentifier("icon", "id", pkg))?.setImageResource(iconId)
        }
        row.findViewById<TextView>(descId)?.text = QZONE_LABEL
        row.setOnClickListener { openUserQzone(row, uin) }
        // Append at the very bottom, after the native menu items (消息设置/删除好友…).
        container.addView(row)
        // The unified card margin pass already ran in onCreateView before this row existed;
        // apply it here too so the row matches the other entries.
        row.cardMargin()
        Utils.log("Qzone: entry added (uin=$uin)")
    }.onFailure { Utils.log("Qzone: addEntry failed: $it") }
}

/** Navigate to the QZone home feed of [uin] via the (obfuscated) androidx NavController. */
fun openUserQzone(anchor: View, uin: Long) {
    // zone_main_fragment maps to QZoneMineFragment, which reads its owner from `key_uin`.
    val ok = navigateDest(anchor, "zone_main_fragment", Bundle().apply { putLong("key_uin", uin) })
    if (ok) Utils.log("Qzone: navigate to zone_main_fragment uin=$uin")
}

/**
 * Generic helper: navigate to the destination named [destName] (a resource id under R.id) with
 * [args], via the obfuscated androidx NavController resolved from [anchor]'s view tree. Returns true
 * on success. Used for QZone home (zone_main_fragment) and the report page (reportFragment).
 */
fun navigateDest(anchor: View, destName: String, args: Bundle): Boolean {
    return runCatching {
        val nav = anchor.findNavControllerFromTree() ?: run {
            Utils.log("Qzone: NavController not found in view tree")
            return false
        }
        val destId = anchor.resources.getIdentifier(destName, "id", anchor.context.packageName)
        if (destId == 0) {
            Utils.log("Qzone: $destName id not found")
            return false
        }
        // navigate(int destId, Bundle args, NavOptions options) — name obfuscated.
        val navigate = nav.javaClass.methods.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == Bundle::class.java
        } ?: run {
            Utils.log("Qzone: navigate(int,Bundle,..) not found on ${nav.javaClass.name}")
            return false
        }
        navigate.invoke(nav, destId, args, null)
        true
    }.getOrElse { Utils.log("Qzone: navigateDest($destName) failed: $it"); false }
}
