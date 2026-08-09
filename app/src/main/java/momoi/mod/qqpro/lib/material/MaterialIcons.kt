package momoi.mod.qqpro.lib.material

/**
 * The named toolbar icons, now backed by official Google Material Symbols (Outlined) — see
 * [MaterialSymbol] / [MaterialSymbols] — instead of the previous hand-drawn shapes. Class names and
 * the `(color)` constructor are kept so existing call sites (e.g. [momoi.mod.qqpro.hook.contact.ContactTopBar])
 * are unchanged. Reuse with [MaterialIconButton].
 */
private const val WHITE = 0xFF_FFFFFF.toInt()

class AddPersonIcon(color: Int = WHITE) : MaterialSymbol(MaterialSymbols.person_add, color)
class PersonIcon(color: Int = WHITE) : MaterialSymbol(MaterialSymbols.person, color)
class GroupIcon(color: Int = WHITE) : MaterialSymbol(MaterialSymbols.group, color)
class FriendNotifyIcon(color: Int = WHITE) : MaterialSymbol(MaterialSymbols.notifications, color)
class GroupNotifyIcon(color: Int = WHITE) : MaterialSymbol(MaterialSymbols.group, color)
class SearchIcon(color: Int = WHITE) : MaterialSymbol(MaterialSymbols.search, color)
class BackArrowIcon(color: Int = WHITE) : MaterialSymbol(MaterialSymbols.arrow_back, color)
