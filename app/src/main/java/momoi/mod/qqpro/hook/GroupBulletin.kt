package momoi.mod.qqpro.hook

import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.watch.aio_impl.ui.frames.SettingFrame
import momoi.mod.qqpro.hook.style.cardMargin
import momoi.mod.qqpro.hook.view.GroupBulletinFragment
import momoi.mod.qqpro.util.Utils

private const val BULLETIN_LABEL = "群公告"

/**
 * Add a "群公告" entry to a group's info page ([SettingFrame], the rightmost page that also hosts
 * 搜索聊天记录 / 群成员 / 群设置). Group only (chatType==2): DMs have no announcement. Tapping it
 * opens [GroupBulletinFragment], which fetches the group's active announcements via the kernel.
 *
 * Lives outside any @Mixin class so the click lambda compiles into this package rather than being
 * copied into the target package (anonymous classes inside a @Mixin body crash). Called from the
 * SettingFrame hook's onViewCreated (see [GroupAvatarPreview]).
 */
fun addGroupBulletinEntry(fragment: SettingFrame) {
    runCatching {
        val args = fragment.arguments ?: return
        // 2 = group chat; single chats (1) have no group announcement.
        if (args.getInt("key_bundle_chat_type") != 2) return
        val groupCode = args.getString("key_bundle_peer_id")?.trim()?.toLongOrNull() ?: return

        val scroll = fragment.i ?: return
        val container = scroll.getChildAt(0) as? LinearLayout ?: return
        val ctx = fragment.requireContext()
        val res = ctx.resources
        val pkg = ctx.packageName
        val descId = res.getIdentifier("desc", "id", pkg)

        // onViewCreated may re-fire on the same container — avoid a duplicate row.
        for (i in 0 until container.childCount) {
            val desc = container.getChildAt(i).findViewById<TextView>(descId)
            if (desc?.text?.toString() == BULLETIN_LABEL) return
        }

        val layoutId = res.getIdentifier("setting_item", "layout", pkg)
        if (layoutId == 0) {
            Utils.log("GroupBulletin: setting_item layout not found")
            return
        }
        val row = LayoutInflater.from(ctx).inflate(layoutId, container, false)
        // Reuse an existing icon (no dedicated announcement asset); fall back gracefully.
        val iconId = sequenceOf("icon_notice", "icon_announce", "icon_search")
            .map { res.getIdentifier(it, "drawable", pkg) }
            .firstOrNull { it != 0 } ?: 0
        if (iconId != 0) {
            row.findViewById<ImageView>(res.getIdentifier("icon", "id", pkg))?.setImageResource(iconId)
        }
        row.findViewById<TextView>(descId)?.text = BULLETIN_LABEL
        row.setOnClickListener {
            runCatching {
                GroupBulletinFragment(groupCode).show(fragment.childFragmentManager, "group_bulletin")
            }.onFailure { Utils.log("GroupBulletin: open failed: $it") }
        }
        // Header views are avatar(0), nick(1), peerId(2), info(3); insert before the menu items.
        container.addView(row, minOf(4, container.childCount))
        row.cardMargin()
        Utils.log("GroupBulletin: entry added (gc=$groupCode)")
    }.onFailure { Utils.log("GroupBulletin: addEntry failed: $it") }
}
