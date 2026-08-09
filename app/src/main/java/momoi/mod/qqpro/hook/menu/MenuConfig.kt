package momoi.mod.qqpro.hook.menu

import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.StringPref
import momoi.mod.qqpro.lib.material.MaterialSymbols

/**
 * Per-menu customization (order + visibility) for the chat message long-press menu and the chat
 * "+" attachment menu. Edited in the 菜单自定义 settings screen (drag to reorder; drag an item below
 * the separator to hide it). This replaced the old scattered per-feature show/hide toggles
 * (长按菜单翻译 / 长按菜单总结 / 聊天截图 / 消息多选).
 *
 * The whole config is one string per menu, persisted in [Settings.longPressMenuOrder] /
 * [Settings.attachmentMenuOrder]:
 *
 *     visibleKey,visibleKey,…|hiddenKey,hiddenKey,…
 *
 * The "|" separates the shown items (in display order) from the hidden ones. A blank value means
 * "every catalog item visible, in catalog order" (the default). Stored keys unknown to the catalog
 * are ignored; catalog keys missing from a stored string are treated as newly-added and appended to
 * the visible section — so a feature added in a later update shows up by default instead of vanishing.
 */

/** One configurable menu item shown in the editor catalog (stable [key] + display [label]/[symbol]). */
class MenuItemSpec(val key: String, val label: String, val symbol: String)

class MenuConfig(val pref: StringPref, val catalog: List<MenuItemSpec>) {

    fun specFor(key: String): MenuItemSpec? = catalog.find { it.key == key }

    /**
     * The current config merged against the catalog: the ordered visible keys and the hidden set.
     * Unknown stored keys are dropped; catalog keys absent from storage are appended to visible.
     */
    fun resolved(): Pair<List<String>, Set<String>> {
        val raw = pref.value
        val known = catalog.map { it.key }.toSet()
        if (raw.isBlank()) return catalog.map { it.key } to emptySet()
        val parts = raw.split("|", limit = 2)
        fun tokens(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() && it in known }
        val visible = tokens(parts[0]).toMutableList()
        val hidden = tokens(parts.getOrNull(1) ?: "").toMutableList()
        val seen = (visible + hidden).toMutableSet()
        // A newly-added catalog key (after an app update) is mentioned nowhere → show it by default.
        catalog.forEach { if (seen.add(it.key)) visible.add(it.key) }
        return visible to hidden.toSet()
    }

    /** Persist a fresh ordering. [visible] is the shown order, [hidden] the hidden order. */
    fun save(visible: List<String>, hidden: List<String>) {
        pref.value = visible.joinToString(",") + "|" + hidden.joinToString(",")
    }

    /**
     * Reorder [entries] by the saved visible order and drop the hidden ones. Entries whose key is not
     * in the config keep their relative input order at the end (stable sort).
     */
    fun <T> arrange(entries: List<T>, keyOf: (T) -> String): List<T> {
        val (visible, hidden) = resolved()
        val rank = visible.withIndex().associate { (i, k) -> k to i }
        return entries.filterNot { keyOf(it) in hidden }
            .sortedBy { rank[keyOf(it)] ?: Int.MAX_VALUE }
    }
}

/**
 * Catalog + config for the chat message long-press menu. Keys match the [Entry.key]s emitted by
 * [buildMessageActions]. Default order mirrors the historical menu order.
 */
val LongPressMenuConfig = MenuConfig(
    Settings.longPressMenuOrder,
    listOf(
        MenuItemSpec("reply", "回复", MaterialSymbols.reply),
        MenuItemSpec("copy", "复制", MaterialSymbols.content_copy),
        MenuItemSpec("recall", "撤回", MaterialSymbols.undo),
        MenuItemSpec("edit", "编辑", MaterialSymbols.edit),
        MenuItemSpec("partial_copy", "部分复制", MaterialSymbols.content_copy),
        MenuItemSpec("copy_image", "复制图片", MaterialSymbols.image),
        MenuItemSpec("edit_image", "编辑图片", MaterialSymbols.brush),
        MenuItemSpec("forward", "转发", MaterialSymbols.forward),
        MenuItemSpec("repeat", "复读", MaterialSymbols.repeat),
        MenuItemSpec("multiselect", "多选", MaterialSymbols.check),
        MenuItemSpec("share", "系统分享", MaterialSymbols.send),
        MenuItemSpec("screenshot", "截图", MaterialSymbols.image),
        MenuItemSpec("fav", "收藏", MaterialSymbols.star),
        MenuItemSpec("save", "保存", MaterialSymbols.download),
        MenuItemSpec("translate", "翻译", MaterialSymbols.translate),
        MenuItemSpec("speak", "朗读", MaterialSymbols.volume_up),
        MenuItemSpec("summarize", "总结", MaterialSymbols.summarize),
        MenuItemSpec("delete", "删除", MaterialSymbols.delete),
    ),
)

/**
 * Catalog + config for the chat "+" attachment menu. The native panel items carry only a label, so
 * [keyForAttachmentLabel] maps a label back to a catalog key for ordering/hiding in MenuPanelLayout.
 */
val AttachmentMenuConfig = MenuConfig(
    Settings.attachmentMenuOrder,
    listOf(
        MenuItemSpec("emoji", "表情", MaterialSymbols.mood),
        MenuItemSpec("sticker", "贴纸", MaterialSymbols.interests),
        MenuItemSpec("mention", "艾特成员", MaterialSymbols.alternate_email),
        MenuItemSpec("gallery", "相册", MaterialSymbols.image),
        MenuItemSpec("camera", "拍照", MaterialSymbols.photo_camera),
        MenuItemSpec("record", "录像", MaterialSymbols.videocam),
        MenuItemSpec("audio", "音频文件", MaterialSymbols.audio_file),
        MenuItemSpec("voice_call", "语音通话", MaterialSymbols.call),
        MenuItemSpec("video_call", "视频通话", MaterialSymbols.videocam),
    ),
)

/**
 * Reorder the "+" attachment panel's backing item list in place to match [AttachmentMenuConfig]:
 * drop hidden items and sort the rest by the saved visible order. Items whose label maps to no
 * catalog key are left at the end in their original relative order.
 */
fun arrangeAttachmentItems(items: MutableList<com.tencent.watch.aio_impl.ui.frames.MenuItem>) {
    val (visible, hidden) = AttachmentMenuConfig.resolved()
    val rank = visible.withIndex().associate { (i, k) -> k to i }
    fun keyOf(item: com.tencent.watch.aio_impl.ui.frames.MenuItem) =
        keyForAttachmentLabel(runCatching { item.b() }.getOrNull() ?: "")
    items.removeAll { keyOf(it)?.let { k -> k in hidden } == true }
    val sorted = items.sortedBy { keyOf(it)?.let { k -> rank[k] } ?: Int.MAX_VALUE }
    items.clear()
    items.addAll(sorted)
}

/** Map a native attachment-panel item label to its catalog key (null = leave it where it is). */
fun keyForAttachmentLabel(label: String): String? = when {
    label.contains("贴纸") -> "sticker"
    label.contains("表情") -> "emoji"
    label.contains("艾特") -> "mention"
    label.contains("音频") -> "audio"
    label.contains("录") -> "record"
    label.contains("相册") -> "gallery"
    label.contains("拍") -> "camera"
    label.contains("视频") -> "video_call"
    label.contains("语音") || label.contains("通话") -> "voice_call"
    else -> null
}
