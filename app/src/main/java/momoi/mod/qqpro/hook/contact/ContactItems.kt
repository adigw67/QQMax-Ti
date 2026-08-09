package momoi.mod.qqpro.hook.contact

import com.tencent.qqnt.watch.contact.ui.item.ContactBaseItem

// Trailing-edge notify icons from the target APK (see res/values/public.xml). The compile-time R
// has no target resources, so reference them by their packed id (matches the project's convention,
// e.g. ChatSearch.ICON_SEARCH / 长按菜单调整 icon_share).
private const val ICON_FRIEND_NOTIFICATION = 0x7e080597 // R.drawable.icon_friend_notification
private const val ICON_GROUP_NOTIFICATION = 0x7e080599  // R.drawable.icon_group_notification

/**
 * Custom rows for the contacts page (2nd main page) redesign — see [Settings.contactSections] and
 * [ContactListFragmentHook]. All implement the obfuscated [ContactBaseItem] interface:
 *   a() = trailing ext_icon (-1 = none), b() = leading avatar/icon (-1 = load real avatar),
 *   c() = unread count (<=0 = no badge), getTitle() = the row text.
 */

/** Non-interactive section header row ("好友" / "群聊"); blank leading slot, no trailing icon/badge. */
class SectionHeaderItem(private val text: String) : ContactBaseItem {
    override fun a(): Int = -1
    // -1 = the avatar-load path: the adapter only configures an avatar target and issues no load
    // for a non-Contact/Group item, so nothing is rasterized (a sized icon or ColorDrawable here
    // would make WatchAvatarView crash with "width and height must be > 0").
    override fun b(): Int = -1
    override fun c(): Int = -1
    // Plain text; the large/bold/no-card look is applied per-row by HeaderStyler (the shared
    // item_contact layout can't be restyled from here).
    override fun getTitle(): CharSequence = text

    override fun equals(other: Any?): Boolean = other is SectionHeaderItem && other.text == text
    override fun hashCode(): Int = text.hashCode()
}

/** "好友通知" entry — friend-request notifications, with its own unread count. */
class FriendNotifyItem(val count: Int) : ContactBaseItem {
    override fun a(): Int = -1
    override fun b(): Int = ICON_FRIEND_NOTIFICATION
    override fun c(): Int = count
    override fun getTitle(): CharSequence = "好友通知"

    override fun equals(other: Any?): Boolean = other is FriendNotifyItem && other.count == count
    override fun hashCode(): Int = count
}

/** "群通知" entry — group join/notification requests, with its own unread count. */
class GroupNotifyItem(val count: Int) : ContactBaseItem {
    override fun a(): Int = -1
    override fun b(): Int = ICON_GROUP_NOTIFICATION
    override fun c(): Int = count
    override fun getTitle(): CharSequence = "群通知"

    override fun equals(other: Any?): Boolean = other is GroupNotifyItem && other.count == count
    override fun hashCode(): Int = count
}
