package momoi.mod.qqpro

import android.content.SharedPreferences
import androidx.core.content.edit
import momoi.mod.qqpro.util.Utils

object Settings {
    val sp: SharedPreferences = Utils.application.getSharedPreferences("qqpro", 0)
    val wear: SharedPreferences = Utils.application.getSharedPreferences("wearqq", 0)

    // ===== QQ Pro 设置 (by java30433) =====
    val scale = FloatPref("scale", 0.7f)
    val chatScale = FloatPref("chatScale", 0.8f)
    val enableSmoothScroll = BooleanPref("enableSmoothScroll", true)
    // Multiplier applied to the rotary-encoder scroll distance (1.0 = system default).
    val encoderScrollSpeed = FloatPref("encoderScrollSpeed", 1.0f)
    val blockBack = BooleanPref("blockBack", false)
    val disableSwipeBack = BooleanPref("disableSwipeBack", false)
    val swapCenterKeyboard = BooleanPref("swapCenterKeyboard", true)

    // ===== QQ Max 设置 (by AILIFE) =====
    // Material 主题色 (the M3 accent/primary), as a hex string (#RRGGBB or RRGGBB). Blank = the
    // built-in default (M3.DEFAULT_PRIMARY). Read live by M3.primary, which derives the tonal/onPrimary tokens
    // from it, so a change rethemes every materialized non-chat screen the next time it's built.
    val themeColor = StringPref("themeColor", "")
    // The rest of the M3 color tokens, each an optional hex override (blank = built-in default; the
    // primary-derived ones auto-derive from themeColor when blank). Read live by the M3 object, so
    // a change rethemes every materialized non-chat screen the next time it's built. Edited via the
    // 外观主题 settings category's color pickers.
    val themeOnPrimary = StringPref("themeOnPrimary", "")
    val themePrimaryContainer = StringPref("themePrimaryContainer", "")
    val themeOnPrimaryContainer = StringPref("themeOnPrimaryContainer", "")
    val themeSurface = StringPref("themeSurface", "")
    val themeSurfaceContainer = StringPref("themeSurfaceContainer", "")
    val themeSurfaceContainerHigh = StringPref("themeSurfaceContainerHigh", "")
    val themeSurfaceVariant = StringPref("themeSurfaceVariant", "")
    val themeOnSurface = StringPref("themeOnSurface", "")
    val themeOnSurfaceVariant = StringPref("themeOnSurfaceVariant", "")
    val themeOnSurfaceTip = StringPref("themeOnSurfaceTip", "")
    val themeHint = StringPref("themeHint", "")
    val themeOutline = StringPref("themeOutline", "")
    val themeOutlineVariant = StringPref("themeOutlineVariant", "")
    val themeError = StringPref("themeError", "")
    // Light vs dark baseline palette. false (default) = the dark M3 palette the app has always used;
    // true = a light M3 baseline (recommended neutral light surfaces + a deeper blue accent). Read live
    // by the M3 object: it picks each token's default by this flag, while per-token 外观主题 overrides
    // still win. Chosen via the 外观模式 selector; rethemes materialized screens the next time built.
    val lightMode = BooleanPref("lightMode", false)
    // Follow the system Material You dynamic color palette (Android 12+ / API 31+) when the device
    // exposes it. On (default): each M3 token with no explicit 外观主题 override is seeded from the
    // system's wallpaper-derived accent/neutral tones instead of the built-in default, mode-aware
    // (light/dark). A per-token color override still wins. On devices without dynamic color (older
    // OS — e.g. most watches) this is a no-op and the built-in defaults are used. Read live by M3.
    val followSystemTheme = BooleanPref("followSystemTheme", true)
    // ===== 联网字体包 (by QQMax) =====
    // 可选：从官方源下载 MiSans（优先显示）+ Unifont（缺失字形兜底），仅本应用进程内生效，
    // 防止生僻字/扩展区字形缺失。默认关闭；需先下载字体包。
    val fontPackEnabled = BooleanPref("fontPackEnabled", false)
    // All theme-token prefs together (for "restore defaults" — clears every custom M3 color).
    val themeTokens: List<StringPref> get() = listOf(
        themeColor, themeOnPrimary, themePrimaryContainer, themeOnPrimaryContainer,
        themeSurface, themeSurfaceContainer, themeSurfaceContainerHigh, themeSurfaceVariant,
        themeOnSurface, themeOnSurfaceVariant, themeOnSurfaceTip, themeHint,
        themeOutline, themeOutlineVariant, themeError,
    )
    val showGroupAvatar = BooleanPref("showGroupAvatar", true)
    // Also show avatar + two-line nick header for your own messages, like others.
    val showSelfAvatar = BooleanPref("showSelfAvatar", false)
    // Group chat avatar size, as a multiple of the nickname text size. Default 2.5x.
    val avatarSizeScale = FloatPref("avatarSizeScale", 2.5f)
    val hideRepeatedSender = BooleanPref("hideRepeatedSender", true)
    // Show a "+1" pill on the latest message when it repeats the previous one (tap to send it again).
    val plusOneButton = BooleanPref("plusOneButton", true)
    // Replace the group message sender NAME with our resolved 群名片/备注/昵称. Off by default:
    // keep the native sender name (the role/头衔 tag is appended either way — it's independent).
    val replaceGroupNick = BooleanPref("replaceGroupNick", false)
    // Show the member's group LV<n> level badge in the nick tag. No UI toggle; off by default
    // because it needs a per-member detail query (getMemberInfoForMqq) that the bulk member list
    // doesn't carry. Enable manually to render levels.
    val showMemberLevel = BooleanPref("showMemberLevel", false)
    val inlineSendButton = BooleanPref("inlineSendButton", true)
    val inlineChatInput = BooleanPref("inlineChatInput", true)
    // Fully replace the InputMethodFragment with the inline EditText: @/图片/回复/编辑/STT
    // are all represented inline (atomic @xxx and [图片] spans, a reply/edit banner above the
    // input box) so the keyboard page never opens. Requires inlineChatInput.
    val fullInlineInput = BooleanPref("fullInlineInput", true)
    // Show an emoji button in the inline input pill while typing. Tapping it collapses the keyboard
    // and opens a sysface picker at the keyboard position that inserts faces into the EditText.
    val inlineEmojiButton = BooleanPref("inlineEmojiButton", true)
    // Keep the input bar (and its EditText) pinned while scrolling chat history. Natively the bar
    // collapses to just the up-arrow whenever the list isn't at the bottom; with this on we keep it in
    // the floating overlay mode so it stays over the chat. Default off (native behavior).
    val keepInputBarOnScroll = BooleanPref("keepInputBarOnScroll", false)
    // Remember the inline input box contents (typed text, @/image tokens and the reply target)
    // per chat. Leaving a chat with an unsent draft and coming back restores it. Requires
    // fullInlineInput. Drafts are kept in memory for the app session, cleared once the message is sent.
    val rememberDraft = BooleanPref("rememberDraft", true)
    // When picking an emoji / sticker / image-gif from QQ's native emoji selector, insert it into
    // the inline EditText as a token instead of sending it immediately. Lets you keep composing
    // (mix text + faces + images) and send once. Only applies while fullInlineInput is active.
    val emojiPickerToInput = BooleanPref("emojiPickerToInput", true)
    // Materialize the chat screen chrome (not the scrolling content, which is already M3): the inline
    // input pill (filled surface-container field, M3 symbol icons, primary send circle), the
    // reply/edit banner (M3 surface + reply/edit/close symbols) and the per-message "sending" spinner
    // (M3 arc instead of the native APNG). Off keeps the current translucent pill / PNG icons / native
    // spinner. Visual only; behaviour is identical. Only has an effect with 聊天页直接输入 on (the inline
    // EditText must exist). Takes effect next time a chat opens. Default on.
    val materializeChat = BooleanPref("materializeChat", true)
    // Replace the QR-code login page with a from-scratch Material 3 layout (QQ Max brand + app icon,
    // framed QR, M3 status line + post-scan account avatar/number, refresh & phone-login buttons), and
    // skip the first-launch privacy-agreement interstitial. The native login engine (QR generation /
    // refresh / the wtlogin state machine) is untouched — only the chrome is rebuilt. Default on.
    val materializeLogin = BooleanPref("materializeLogin", true)
    // Screen rounded-corner diameter (in dp). Adds left/right margin of this
    // width to the inline chat EditText so the side buttons aren't clipped by a
    // round watch screen's corners.
    val screenCornerDiameter = FloatPref("screenCornerDiameter", 15f)
    // Rich titlebar side margin (in dp). Adds left/right inset to the titlebar
    // name/count row and the floating unread badge so they aren't clipped by a
    // round watch screen's corners. Separate from the chat EditText spacing above.
    val titlebarSideMargin = FloatPref("titlebarSideMargin", 15f)
    // Hide the voice (microphone) button in the chat input bar entirely, in all input
    // modes (inline and non-inline) and regardless of whether text has been typed.
    val hideVoiceButton = BooleanPref("hideVoiceButton", false)
    // When a group has 全员禁言 (whole-group mute) on and the current user is NOT owner/admin, hide
    // the bottom input bar and show a "全员禁言中" hint instead (you can't send anyway). Default on.
    val muteHideInputBar = BooleanPref("muteHideInputBar", true)
    // Show a 全员禁言 (whole-group mute) switch in the M3 group settings, for the owner/admin only.
    // Syncs to the current mute state and toggles it via the kernel. Requires useM3Settings. Default on.
    val groupWholeMute = BooleanPref("groupWholeMute", true)
    // Online-presence display (friends + group members). Presence needs kernel status polling, so it
    // only starts when at least one of these is on. Default on. See OnlineStatus.kt.
    val onlineStatusMainList = BooleanPref("onlineStatusMainList", true)   // DM avatar dot in conversation list
    val onlineStatusContactList = BooleanPref("onlineStatusContactList", true) // status text in friend list
    val onlineStatusTitlebar = BooleanPref("onlineStatusTitlebar", true)  // status line in DM chat titlebar
    val onlineStatusProfile = BooleanPref("onlineStatusProfile", true)    // status line on profile card (friend + group member)
    // Show the system status bar (time/battery) instead of the app's fullscreen window; content is
    // inset below it (edge-to-edge safe on Android 15+). Default off. Applied in StatusBarOption.
    val showStatusBar = BooleanPref("showStatusBar", false)
    // 横屏模式：开启后整个应用固定横屏显示（不跟随传感器自动旋转），关闭固定竖屏。
    // 默认关闭。在设置里切换后，主界面与本设置页立即按新方向重建。
    val landscapeMode = BooleanPref("landscapeMode", false)
    val backToFirstPage = BooleanPref("backToFirstPage", true)
    // When tapping a reply to jump to its source message, drop the page-load cap (normally ~1000
    // pages) and keep paging up until the source is found or the top of history is reached. Lets
    // very old reply sources be located, at the cost of a possibly long load.
    val replyFullSearch = BooleanPref("replyFullSearch", false)
    // Replace the input bar's emoji button with a "+" button that opens the attachment
    // list as an overlay over the chat (like the long-press menu). Removes the attachment
    // ViewPager page (友: 聊天+设置 两页; 非好友: 仅聊天), and moves 表情 into the overlay list.
    val attachmentOverlay = BooleanPref("attachmentOverlay", true)
    // Material style for the "+" attachment menu rows (M3 symbols + accent + surface card),
    // matching the long-press menu. Default on.
    val materialAttachmentMenu = BooleanPref("materialAttachmentMenu", true)
    // Material style for the chat long-press menu (M3 symbols + accent + one surface card). Off uses
    // semi-transparent dark rows with white text (the "material disabled" look of the + menu).
    val materialLongPressMenu = BooleanPref("materialLongPressMenu", true)
    // Customized order + visibility of the chat message long-press menu and the "+" attachment menu,
    // edited in the 菜单自定义 settings screen (drag to reorder; drag below the separator to hide).
    // Format: "visibleKey,visibleKey,…|hiddenKey,hiddenKey,…" — blank = every item shown in the
    // default order. See momoi.mod.qqpro.hook.menu.MenuConfig. This replaced the old per-feature
    // show/hide toggles (长按菜单翻译 / 长按菜单总结 / 聊天截图 / 消息多选).
    val longPressMenuOrder = StringPref("longPressMenuOrder", "")
    val attachmentMenuOrder = StringPref("attachmentMenuOrder", "")
    // Rich chat titlebar: replaces the top page-indicator strip with a bar holding a back
    // button, the indicator dots, other-chats unread count, group member count and the
    // group/contact name. titlebarHeight (dp) defaults to the current strip height (16).
    val enableTitlebar = BooleanPref("enableTitlebar", true)
    // Place the titlebar as an overlay inside the chat list page (page 0 of the AIO ViewPager)
    // instead of at the fragment root. When on (default), it shows only on the chat screen and
    // slides away when paging to the settings frame (which already shows all that info). When off,
    // it sits at the root across all pages and re-pads the ViewPager (legacy placement).
    val titlebarChatOnly = BooleanPref("titlebarChatOnly", true)
    // Show the other-chats unread count badge in the chat titlebar. When off, the
    // titlebar shows only the name + member count (no red badge).
    val titlebarShowUnread = BooleanPref("titlebarShowUnread", true)
    // Show the other-chats unread badge floating over the chat's top-left corner instead of in
    // the titlebar header. Works even when the titlebar is off; when on, the header badge is hidden.
    val floatUnreadInChat = BooleanPref("floatUnreadInChat", false)
    val titlebarHeight = FloatPref("titlebarHeight", 16f)
    // Hide the chat titlebar while typing in the inline input (the IME pans the window up, pushing the
    // titlebar off the top of the screen / overlapping the field). Restored when the field loses focus.
    // Default on; turn off to keep the titlebar visible while typing.
    val hideTitlebarWhenTyping = BooleanPref("hideTitlebarWhenTyping", true)
    // When the titlebar name is too long, scroll it as marquee instead of truncating with "…".
    val titlebarMarquee = BooleanPref("titlebarMarquee", false)
    // Titlebar background: when on (default), a surface gradient fading to transparent at the bottom
    // (chat content disappears gradually behind the bar); when off, a solid opaque surface fill.
    val titlebarGradient = BooleanPref("titlebarGradient", true)
    // Step the "↑ X条新消息" jump chip through the important unread messages (@我/回复/新文件/新公告)
    // one at a time, bottom→top, before the final jump to the first unread. Off = the chip jumps
    // straight to the first unread as before.
    val chatImportantJump = BooleanPref("chatImportantJump", true)
    // Master switch for the custom main-page navigation. On = replace the native page-indicator
    // strip with our rebuilt nav (all the options below apply). Off = leave the native nav as-is.
    val mainNavCustom = BooleanPref("mainNavCustom", true)
    // Move the home/main page's page-indicator navigation to the bottom of the screen.
    val bottomMainNav = BooleanPref("bottomMainNav", true)
    // Phone-style labeled bottom nav: show each page's category name as a text label under a
    // modestly-sized icon (a standard bottom navigation bar) instead of the compact watch icon
    // strip. Best when running the app on a phone. Ignores 导航高度 (uses a fixed phone-appropriate
    // icon size) and implies showing every page's icon. Default off.
    val mainNavLabels = BooleanPref("mainNavLabels", false)
    // Main-page (home) navigation height in dp. Controls the icon/bar size of the page-indicator
    // navigation independently of 标题栏高度. Default 16 (matches the original strip height).
    val mainNavHeight = FloatPref("mainNavHeight", 16f)
    // Square/spread mode: distribute the navigation icons evenly across the full width instead of
    // grouping them close together in the center. Default off.
    val mainNavSquare = BooleanPref("mainNavSquare", false)
    // Show an icon for EVERY page (not just the current one), with the currently selected page
    // tinted blue and the others a muted white. Off = native style (only the current page's icon,
    // dots for the rest). Default on.
    val mainNavAllIcons = BooleanPref("mainNavAllIcons", true)
    // Show each page's unread count as a red badge in the navigation (except the last/settings page).
    // Messages page shows total unread; contacts page shows friend+group notification counts.
    val mainNavUnread = BooleanPref("mainNavUnread", true)
    // Tapping a nav cell for the page you're ALREADY on jumps to that page's pending item: messages
    // page scrolls the next unread conversation to the top (cycling), contacts/qzone open their
    // notification screen. Requires 显示未读数 (mainNavUnread). Default on.
    val mainNavUnreadJump = BooleanPref("mainNavUnreadJump", true)
    // Use the in-app camera for 拍照. When off, launch the system camera app (third-party)
    // via an intent for photos. Video recording always uses the system app (the in-app
    // camera can't record video).
    val useInAppCamera = BooleanPref("useInAppCamera", true)
    // Sort the image/gallery picker by date taken (EXIF capture time) instead of
    // the default date_modified. Falls back to date_modified when a file has no
    // capture time recorded.
    val gallerySortByDateTaken = BooleanPref("gallerySortByDateTaken", false)
    // Quick-send: a single tap on a gallery image/video sends it immediately (through the input-bar
    // preview). When off, the gallery is always in multi-select mode — a tap toggles selection
    // (even for a single item) and you press 发送 to send. Default on.
    val galleryQuickSend = BooleanPref("galleryQuickSend", true)
    // When sending a SINGLE image, first show a preview where you can 发送 (send now) or 编辑 (open the
    // image editor). Only affects single-image quick-send. Default off.
    val editSingleImageBeforeSend = BooleanPref("editSingleImageBeforeSend", false)
    // Use the system image picker (Android photo picker if available, otherwise the
    // SAF document picker) for 相册 instead of QQ's in-app gallery. Avoids needing
    // storage permission and works around in-app picker problems on some devices.
    // Supports selecting multiple images at once.
    val useSystemImagePicker = BooleanPref("useSystemImagePicker", false)
    // Use the system file picker (ACTION_GET_CONTENT, audio/*) for the 音频文件 panel
    // item instead of QQPro's in-app audio browser. Off (default) shows the in-app
    // browser that lists local audio files via MediaStore; on uses the system picker.
    val useSystemAudioPicker = BooleanPref("useSystemAudioPicker", false)
    // Ask before opening a tapped link in the browser.
    val confirmOpenLink = BooleanPref("confirmOpenLink", true)
    // Also detect links without an http(s):// prefix (e.g. "example.com/x").
    val wideUrlMatch = BooleanPref("wideUrlMatch", true)
    // Make bare 6–15 digit numbers (QQ/group numbers) tappable to search a
    // friend/group. Independent of the URL-matching settings.
    val parseNumber = BooleanPref("parseNumber", true)
    // In group chats, make @member mentions (and member names highlighted in grey
    // system tips) tappable — opens the member's profile card, same as tapping their
    // avatar/name.
    val parseAtMember = BooleanPref("parseAtMember", true)
    // When an @mention in a group message targets YOU, paint it in the Material error
    // color so a mention of yourself stands out from ordinary mentions. Needs
    // parseAtMember on (the mention must be linkified first).
    val highlightSelfMention = BooleanPref("highlightSelfMention", true)
    // Try to resolve a client-side preview (icon/title/description) for links in
    // messages and show it below the text. Makes a network request per unique link.
    val enableLinkPreview = BooleanPref("enableLinkPreview", true)
    // Max display height for chat images, as a fraction of the screen height. Caps tall
    // images so they don't fill the watch screen. Default 0.5 (half the screen).
    val picMaxHeightRatio = FloatPref("picMaxHeightRatio", 0.5f)
    // Long-screenshot support in the full-screen image viewer. When a picture is at least 2x
    // taller than the screen (a long screenshot), open it fitted to the screen WIDTH (so the
    // text is readable) anchored at the top, instead of the native fit-whole-height (a thin
    // column). Double-tap then cycles: level 1 = match height (whole image), level 2 = match
    // width (scroll up/down), level 3 = zoom in even more.
    val longScreenshot = BooleanPref("longScreenshot", true)
    // Rounded-corner radius (in dp) for chat bubbles, the merged-forward/chat-history
    // blocks and the reply block. 0 = square.
    val bubbleCornerRadius = FloatPref("bubbleCornerRadius", 10f)
    // Override chat-bubble fill color, as a hex string (#RRGGBB or #AARRGGBB / with or
    // without the leading #). Blank keeps the original bubble color (sampled per side).
    val bubbleColorSelf = StringPref("bubbleColorSelf", "")
    val bubbleColorOther = StringPref("bubbleColorOther", "")
    // Chat message text color per side, as a hex string (#RRGGBB or #AARRGGBB / with or without the
    // leading #). Blank = auto: contrast against that side's bubble color. `textColor` is the OTHER
    // (对方) side (kept under the old key so existing values carry over); `textColorSelf` is 我的.
    val textColor = StringPref("textColor", "")
    val textColorSelf = StringPref("textColorSelf", "")
    // Override the color of tappable links/numbers/@mentions in chat text. Blank keeps the
    // platform default link color.
    val linkColor = StringPref("linkColor", "")
    // Contacts page (2nd main page): show "好友"/"群聊" section headers, split the single
    // "我的通知" entry into separate friend/group notification entries (each with its own
    // count and direct navigation), and drop the trailing group icon on every group row.
    val contactSections = BooleanPref("contactSections", true)
    // Apply the Material redesign to the contacts page (top bar with search/add/notify buttons,
    // M3-styled section headers and list rows). Requires contactSections. Default on.
    val materialContactsList = BooleanPref("materialContactsList", true)
    // Apply Material colors to the message/conversation list (1st page): M3.surface page background,
    // surface-container row cards, and M3 text colors (title/time/preview). Default on.
    val materialChatList = BooleanPref("materialChatList", true)
    // Apply the Material top bar to the QZone feed page (动态, 3rd tab): replaces the three
    // header rows (发布/通知/我的空间) with compact icon buttons above the feed. Default on.
    val materialQZoneBar = BooleanPref("materialQZoneBar", true)
    // QZone top bar button layout: spread evenly across the full bar width (true), or group
    // the three buttons close together in the center (false, better for round screens).
    val qzoneBarSpread = BooleanPref("qzoneBarSpread", false)
    // QZone single-video posts: play inline in the feed cell (tap to start/pause) instead of
    // opening the fullscreen viewer. Default off.
    val qzoneInlineVideo = BooleanPref("qzoneInlineVideo", false)
    // QZone mini-app (小程序) shares: instead of the "请在手机QQ查看" placeholder, fetch the share
    // landing page and render the real app name/icon/description as a card. Default on.
    val qzoneMiniAppCard = BooleanPref("qzoneMiniAppCard", true)
    // Fully materialize QZone (空间): replace the entire stock frontend — feed cards (main + per-user
    // space), the per-user profile header, the comment/reply thread screen and the publish/compose
    // page — with a from-scratch Material 3 implementation. Off keeps the native screens (with only
    // the in-place tweaks like the top bar / like icons / mini-app card above). Default off; opt-in
    // escape hatch since it owns the whole feed rendering. Takes effect next time a QZone screen opens.
    val materializeQzone = BooleanPref("materializeQzone", true)
    // QZone feed: truncate long post text to 5 lines with a 查看全文 expander. Off shows full text.
    val qzoneTruncatePost = BooleanPref("qzoneTruncatePost", true)
    // QZone feed: truncate a multi-image post to two square thumbnails (2nd darkened with +N) instead
    // of the full 3-column square grid. Off shows the 3-wide grid.
    val qzoneTruncateImages = BooleanPref("qzoneTruncateImages", true)
    // Chat-settings panel (好友/群资料页 header name): show the contact/group name on multiple
    // lines instead of truncating to one, and allow long-pressing it to copy. Replaces the
    // single-line nick view with a multiline TextView that mirrors the original's (async) text.
    // Opt-in escape hatch since it swaps a custom widget; takes effect next time the panel opens.
    val profileNameMultiline = BooleanPref("profileNameMultiline", true)
    // Fully replace QQ's simple profile-card page with a rebuilt Material-style page that also shows
    // the contact's age / birthday / zodiac / location / signature (fetched from the kernel). When
    // off, the original page is kept (with only the minor enrich tweaks). Takes effect next open.
    val useRichProfile = BooleanPref("useRichProfile", true)
    // Redesign the settings screens — the self/me page (主页第4页), the friend (DM) chat-settings
    // page and the group chat-settings page — into a fully Material 3 layout (header card, M3 list
    // rows, themed M3 switches). Also routes the "change info" text edits (改群名/备注/昵称) through
    // a Material input dialog instead of QQ's native full-screen keyboard page. Off keeps the native
    // screens (with only the card-margin/theming tweaks). Takes effect next time a screen opens.
    val useM3Settings = BooleanPref("useM3Settings", true)
    // How much to darken the chat background image for readability.
    // 0 = original image, 0.9 = almost black. Applied as a black overlay on top
    // of the picked image. Takes effect the next time a chat is opened.
    val chatBgDarken = FloatPref("chatBgDarken", 0.35f)

    // ===== 通话 (by AILIFE) =====
    // Make incoming/ongoing calls reliably notify. QQ only posts an incoming-call full-screen-intent
    // notification when the app is backgrounded + SDK≥26 + not device-blacklisted, and its call
    // foreground service is declared without a foregroundServiceType (suppressed on Android 12+). With
    // this on, QQPro posts its own high-importance incoming-call notification (reusing NotificationAlert's
    // channel/sound/vibrate) regardless of foreground state, and starts the ongoing-call FGS with an
    // explicit type. Default on.
    val callNotifyFix = BooleanPref("callNotifyFix", true)
    // Incoming-call notification also fires a full-screen intent (launches the native answer screen as a
    // full takeover, like a phone call) in addition to the heads-up notification (which already carries
    // the caller name/avatar + 接听/拒绝 buttons). Off = heads-up notification only. Default on. Requires
    // callNotifyFix.
    val callFullScreenIntent = BooleanPref("callFullScreenIntent", true)
    // Route in-call audio to a connected Bluetooth headset (SCO / communication device), and show an
    // in-call output selector (蓝牙/扬声器/听筒) in the call UI. Off keeps QQ's speaker/earpiece-only
    // routing. Default on.
    val callBluetoothRoute = BooleanPref("callBluetoothRoute", true)
    // Allow starting a VIDEO call on a watch with no camera (to at least see the other side). QQ blocks
    // this with "当前设备不支持" at two gates (the "+" panel and goToAVScene); with this on we bypass them
    // and start a receive-only video call (local camera controls hidden). Default on.
    val callCameralessVideo = BooleanPref("callCameralessVideo", true)
    // Fully rebuild the incoming + active call screens into a from-scratch Material 3 UI (embeds the
    // native GL video surface; keeps QQ's call service/lifecycle). Off keeps the native call screens.
    // Opt-in escape hatch (owns the call screen rendering); default off. Takes effect next call.
    val materializeCall = BooleanPref("materializeCall", false)

    // ===== 界面风格选择 (StyleChooserActivity) =====
    // The user's chosen overall UI style, recorded by the 界面风格 chooser screen. 0 = not chosen yet,
    // 1 = Material design, 2 = Original design. Reserved: kept as a separate signal for possible future
    // use (e.g. a style-aware default) — picking a style ALSO flips the individual [styleToggles] below.
    val uiStyle = IntPref("uiStyle", 0)
    // Whether the first-launch style chooser has already been shown. Once true the chooser no longer
    // auto-opens on app start (it stays reachable from settings). Set when a style is picked or the
    // chooser is dismissed. Not exported (internal flag), so it's left out of [all].
    val styleChooserSeen = BooleanPref("styleChooserSeen", false)
    // The toggles the 界面风格 chooser flips together: the whole "Material 化" category, the home-nav
    // method (mainNavCustom), and contactSections (the 联系人分组 dependency materialContactsList needs).
    // Material style = every one true; Original style = every one false.
    val styleToggles: List<Pref<Boolean>> get() = listOf(
        materializeChat, materialAttachmentMenu, materialLongPressMenu,
        materialContactsList, materialChatList, materialQZoneBar, materializeQzone,
        useRichProfile, useM3Settings, materializeLogin, contactSections, mainNavCustom,
    )

    // Classic QQ palette written when 原始设计 is picked. The native watch UI is DARK (its list bg is
    // #1f2025, white text) — which already matches the M3 dark surface defaults — and the only thing
    // that reads as "Material" is the accent (the M3 default is a soft Material blue #90CAF9). So the
    // classic look is just the genuine QQ vivid blue accent (#12B7F5, native res "btn_blue") over those
    // dark surfaces, with white labels on the blue like the native buttons. All other tokens stay blank
    // → the dark M3-structure defaults. (Earlier "light grey" was wrong: the original is dark grey.)
    private val classicTheme: List<Pair<StringPref, String>> get() = listOf(
        themeColor to "#12B7F5",                // QQ vivid blue (native btn_blue / aio accent)
        themeOnPrimary to "#FFFFFF",            // white label on the blue, matching native buttons
    )

    /**
     * Apply the chooser's pick: flip every [styleToggles] pref, record the reserved [uiStyle], and set
     * the theme palette — Material clears every [themeTokens] override (derive from the Material accent),
     * Original writes the [classicTheme] (vivid QQ blue + light grey). Persists SYNCHRONOUSLY (commit,
     * not apply): the caller cold-restarts the process immediately afterwards (Process.killProcess),
     * which would otherwise drop the still-pending async apply() writes — the original bug where picking
     * a style changed nothing after restart.
     */
    fun applyUiStyle(material: Boolean) {
        // Keep the in-memory Pref fields consistent for the brief moment before the restart.
        styleToggles.forEach { it.value = material }
        uiStyle.value = if (material) 1 else 2
        styleChooserSeen.value = true
        // Baseline: clear every theme override; for Original, then write the classic palette on top.
        themeTokens.forEach { it.value = "" }
        val theme = if (material) emptyList() else classicTheme
        theme.forEach { (pref, v) -> pref.value = v }
        // Chat bubble corner radius (dp): Material = generously rounded; Original = nearly square,
        // closer to the native QQ bubble.
        val bubbleRadius = if (material) 18f else 4f
        bubbleCornerRadius.value = bubbleRadius
        // Force a synchronous disk write of all the keys so they survive the imminent kill.
        sp.edit(commit = true) {
            styleToggles.forEach { putBoolean(it.key, material) }
            putInt(uiStyle.key, if (material) 1 else 2)
            putBoolean(styleChooserSeen.key, true)
            themeTokens.forEach { putString(it.key, "") }
            theme.forEach { (pref, v) -> putString(pref.key, v) }
            putFloat(bubbleCornerRadius.key, bubbleRadius)
        }
    }

    // ===== 翻译 (by AILIFE) =====
    // The long-press "翻译" entry's visibility now lives in 菜单自定义 ([longPressMenuOrder]).
    // Target language (2-letter API code) others' messages are translated into — the language you read
    // in. Used by both the long-press 翻译 entry and the per-chat "翻译全部消息" auto-translate.
    val translateViewLang = StringPref("translateViewLang", "zh")
    // Target language your OWN typed text is translated into when you long-press the send button.
    val translateSendLang = StringPref("translateSendLang", "en")
    // Show the per-conversation "翻译全部消息" switch on the 好友/群聊 settings page. When that per-chat
    // switch is on, every visible text message in that chat is auto-translated. Default on.
    val translateShowAllSwitch = BooleanPref("translateShowAllSwitch", true)
    // When "翻译全部消息" auto-translation is on for a chat, also translate your OWN messages. Default
    // off (only translate the other side's messages). The manual long-press 翻译 always works on own
    // messages regardless of this.
    val translateOwnMessages = BooleanPref("translateOwnMessages", false)
    // How a translated incoming message is shown: false (default) keeps the original and adds the
    // translation below a divider; true replaces the bubble text with the translation in place.
    val translateReplaceInPlace = BooleanPref("translateReplaceInPlace", false)
    // Allow long-pressing the chat send button to translate the input into translateSendLang (without
    // sending). Default on.
    val translateSendButton = BooleanPref("translateSendButton", true)

    // ===== 聊天总结 (by AILIFE) =====
    // The long-press "总结" entry's visibility now lives in 菜单自定义 ([longPressMenuOrder]).
    // Show a "总结未读" button next to the "↑ X条新消息" jump chip when unread count exceeds 20;
    // tapping it summarizes from the first unread message to the end. Default on.
    val summarizeUnreadButton = BooleanPref("summarizeUnreadButton", true)
    // Summary output style: 0 = bullets (要点, Markdown default), 1 = tldr (一句话), 2 = detailed (详细).
    val summarizeStyle = IntPref("summarizeStyle", 0)
    // Summary output language (2-letter code, or "auto" to match the conversation's own language).
    val summarizeLang = StringPref("summarizeLang", "auto")
    // Stable per-install UUID for the summarization daily quota (X-User-ID). Generated lazily on first
    // use (see Summarizer.userId) and persisted; blank means not yet generated.
    val installUuid = StringPref("installUuid", "")
    // ===== 聊天总结自定义服务 (by QQMax) =====
    // API Key 留空 = 内置 Onyx 服务（每日限量）；填写后总结直连用户自己的 OpenAI 兼容接口
    // （Authorization: Bearer），用于绕开内置服务的故障/配额。
    val summarizeApiKey = StringPref("summarizeApiKey", "")
    // 接口地址（根地址即可，请求时自动补全 /chat/completions）。默认 DeepSeek。
    val summarizeApiBase = StringPref("summarizeApiBase", "https://api.deepseek.com")
    // 发给自定义接口的模型名。
    val summarizeApiModel = StringPref("summarizeApiModel", "deepseek-v4-flash")
    init {
        // v16.1 迁移：默认接口/模型调整。仅在用户尚未配置自定义 Key 时，把旧默认值升级为新默认值，
        // 不覆盖用户自己填过的东西。
        if (summarizeApiKey.value.isBlank()) {
            if (summarizeApiModel.value == "deepseek-chat") summarizeApiModel.value = "deepseek-v4-flash"
            if (summarizeApiBase.value == "https://api.deepseek.com/v1/chat/completions") {
                summarizeApiBase.value = "https://api.deepseek.com"
            }
        }
    }

    // ===== 聊天截图 (by AILIFE) =====
    // The "截图" entry's visibility now lives in 菜单自定义 ([longPressMenuOrder]). The options below
    // tune how the rendered screenshot looks.
    // 消息多选: order the selected messages are used in for batch actions (forward / screenshot / …).
    // On = chronological (time) order, matching the conversation; off = the order they were tapped.
    // Default on (by time).
    val multiSelectTimeOrder = BooleanPref("multiSelectTimeOrder", true)
    // 消息多选: prefix each message with the sender's name when copying (复制 / 部分复制). Default off.
    val multiSelectCopySender = BooleanPref("multiSelectCopySender", false)

    // Render a decorative titlebar (contact/group name only — no rounded corner / unread badge) at the
    // top of the screenshot. Default on.
    val screenshotTitlebar = BooleanPref("screenshotTitlebar", true)
    // Render a decorative empty input-bar at the bottom of the screenshot. Default on.
    val screenshotInputBar = BooleanPref("screenshotInputBar", true)
    // Render your OWN messages on the LEFT (as a third party would see them) instead of the right.
    // Default off.
    val screenshotSelfAsOther = BooleanPref("screenshotSelfAsOther", false)
    // Show real nicknames + avatars in the screenshot. Off = anonymize each sender to A/B/C with a
    // random-colored letter avatar. Default on.
    val screenshotShowIdentity = BooleanPref("screenshotShowIdentity", true)
    // Add a "由 QQMax-Ti 生成" watermark line at the bottom of the screenshot. Default on.
    val screenshotWatermark = BooleanPref("screenshotWatermark", true)

    // ===== 调试 =====
    // Enable the main-thread hang watchdog (HangWatcher). When on, a stalled main thread for
    // 8s+ shows a "应用卡死" report. Crash capture is always on; this gates only hang detection,
    // which can false-positive on a watch that suspends/dozes. Read once at install time.
    val watchdogEnabled = BooleanPref("watchdogEnabled", false)
    // Persist Utils.log output to the on-device log file. Always on in debug builds; in release
    // builds logging is off unless this is enabled (default off). Read live by Utils.log.
    val enableLog = BooleanPref("enableLog", false)

    // ===== NWear QQ 设置 (by 爅峫) — backed by the base app's "wearqq" prefs =====
    val singleLineInput = WearBooleanPref("single_line_input", false)
    val sendWithImage = WearBooleanPref("send_with_image", true)
    val replyWithAt = WearBooleanPref("reply_with_at", true)
    val doubleSpeak = WearBooleanPref("double_speak", false)
    val doubleReply = WearBooleanPref("double_reply", true)
    val allowNotification = WearBooleanPref("allow_notification", true)
    val residentNotification = WearBooleanPref("resident_notification", false)
    // New-message alert, chosen independently for sound and vibration. Each mode is
    // 0=关闭 (off), 1=应用内 (QQ's own message tone / the app's vibration pattern), 2=系统 (the
    // system default notification ringtone / vibration pattern). These drive QQPro's own
    // notification path (NotificationReply); they replace the old single 震动提醒 toggle.
    val notifySoundMode = IntPref("notify_sound_mode", 2)
    val notifyVibrateMode = IntPref("notify_vibrate_mode", 2)
    val voiceBtnText = WearStringPref("voice_btn_text", "QQ")

    val text get() = wear.getString("voice_btn_text", "")?.let {
        if (it == "QQ") {
            ""
        } else {
            it
        }
    } ?: ""

    // True when any online-presence surface is enabled — gates whether kernel status polling starts.
    val anyOnlineStatus: Boolean
        get() = onlineStatusMainList.value || onlineStatusContactList.value ||
            onlineStatusTitlebar.value || onlineStatusProfile.value

    // Every setting exposed on the settings page, in display order. Used by SettingsBackup to
    // export/import only these custom settings (not unrelated keys like drafts or the chat-bg path).
    // Declared last so all the Pref properties above are already initialised.
    val all: List<Pref<*>> = listOf(
        scale, chatScale, enableSmoothScroll, encoderScrollSpeed, blockBack, disableSwipeBack, swapCenterKeyboard,
        themeColor, themeOnPrimary, themePrimaryContainer, themeOnPrimaryContainer,
        themeSurface, themeSurfaceContainer, themeSurfaceContainerHigh, themeSurfaceVariant,
        themeOnSurface, themeOnSurfaceVariant, themeOnSurfaceTip, themeHint, themeOutline, themeOutlineVariant, themeError, lightMode, followSystemTheme,
        showGroupAvatar, showSelfAvatar, avatarSizeScale, hideRepeatedSender, plusOneButton, replaceGroupNick, showMemberLevel, inlineSendButton,
        inlineChatInput, fullInlineInput, inlineEmojiButton, keepInputBarOnScroll, rememberDraft, emojiPickerToInput, materializeChat,
        screenCornerDiameter, titlebarSideMargin,
        hideVoiceButton, muteHideInputBar, groupWholeMute,
        onlineStatusMainList, onlineStatusContactList, onlineStatusTitlebar, onlineStatusProfile,
        showStatusBar, backToFirstPage, attachmentOverlay, materialAttachmentMenu, materialLongPressMenu, longPressMenuOrder, attachmentMenuOrder, enableTitlebar, titlebarChatOnly, titlebarShowUnread,
        floatUnreadInChat, titlebarHeight, hideTitlebarWhenTyping, chatImportantJump, mainNavCustom, bottomMainNav, mainNavHeight, mainNavSquare, mainNavAllIcons, mainNavUnread, mainNavUnreadJump,
        replyFullSearch, useInAppCamera, gallerySortByDateTaken,
        galleryQuickSend, editSingleImageBeforeSend, useSystemImagePicker, useSystemAudioPicker, confirmOpenLink, wideUrlMatch, parseNumber, parseAtMember, highlightSelfMention, enableLinkPreview,
        picMaxHeightRatio, longScreenshot, bubbleCornerRadius, bubbleColorSelf, bubbleColorOther, textColor, textColorSelf, linkColor, contactSections, materialContactsList, materialChatList, materialQZoneBar, qzoneBarSpread, qzoneInlineVideo, qzoneMiniAppCard, materializeQzone, qzoneTruncatePost, qzoneTruncateImages,
        profileNameMultiline, useRichProfile, useM3Settings, materializeLogin,
        translateViewLang, translateSendLang, translateShowAllSwitch, translateOwnMessages, translateReplaceInPlace, translateSendButton,
        multiSelectTimeOrder, multiSelectCopySender,
        screenshotTitlebar, screenshotInputBar, screenshotSelfAsOther, screenshotShowIdentity, screenshotWatermark,
        callNotifyFix, callFullScreenIntent, callBluetoothRoute, callCameralessVideo, materializeCall,
        chatBgDarken, uiStyle, watchdogEnabled, singleLineInput, sendWithImage, replyWithAt,
        doubleSpeak, doubleReply, allowNotification, residentNotification, notifySoundMode,
        notifyVibrateMode, voiceBtnText, watchdogEnabled, enableLog,
    )
}

abstract class Pref<T>(val key: String, def: T) {
    var value: T = def
        set(value) {
            field = value
            set(value)
        }

    protected abstract fun set(value: T)

    /** Apply a value parsed from a backup string (settings import). Invalid input is ignored. */
    abstract fun importString(raw: String)
}

class FloatPref(key: String, def: Float) :
    Pref<Float>(key, Settings.sp.getFloat(key, def)) {
    override fun set(value: Float) = Settings.sp.edit {
        putFloat(key, value)
    }
    override fun importString(raw: String) { raw.trim().toFloatOrNull()?.let { value = it } }
}

class StringPref(key: String, def: String) :
    Pref<String>(key, Settings.sp.getString(key, def) ?: def) {
    override fun set(value: String) = Settings.sp.edit {
        putString(key, value)
    }
    override fun importString(raw: String) { value = raw }
}

class BooleanPref(key: String, def: Boolean) :
    Pref<Boolean>(key, Settings.sp.getBoolean(key, def)) {
    override fun set(value: Boolean) = Settings.sp.edit {
        putBoolean(key, value)
    }
    override fun importString(raw: String) { value = parseBool(raw) }
}

class IntPref(key: String, def: Int) :
    Pref<Int>(key, Settings.sp.getInt(key, def)) {
    override fun set(value: Int) = Settings.sp.edit {
        putInt(key, value)
    }
    override fun importString(raw: String) { raw.trim().toIntOrNull()?.let { value = it } }
}

/** Lenient boolean parse shared by the boolean prefs: accepts true/false and 1/0. */
internal fun parseBool(raw: String): Boolean {
    val t = raw.trim()
    return t.equals("true", ignoreCase = true) || t == "1"
}

/**
 * Boolean setting stored in the base app's "wearqq" SharedPreferences so the
 * original NWear-QQ code keeps reading it. Seeds [def] on first run when the key
 * is absent, so the requested default actually takes effect (the base app reads
 * the key with its own hard-coded default otherwise).
 */
class WearBooleanPref(key: String, def: Boolean) :
    Pref<Boolean>(key, seed(key, def)) {
    override fun set(value: Boolean) = Settings.wear.edit {
        putBoolean(key, value)
    }
    override fun importString(raw: String) { value = parseBool(raw) }

    companion object {
        private fun seed(key: String, def: Boolean): Boolean {
            if (!Settings.wear.contains(key)) {
                Settings.wear.edit { putBoolean(key, def) }
            }
            return Settings.wear.getBoolean(key, def)
        }
    }
}

class WearStringPref(key: String, def: String) :
    Pref<String>(key, Settings.wear.getString(key, def) ?: def) {
    override fun set(value: String) = Settings.wear.edit {
        putString(key, value)
    }
    override fun importString(raw: String) { value = raw }
}
