package momoi.mod.qqpro.hook.imageeditor

/**
 * A curated set of system emoji used as the image-editor's "贴纸" (sticker) palette. Rendered directly
 * with the platform font (color emoji on API 26+), so no bundled image assets are needed — each entry
 * is dropped onto the canvas as a [StickerOp] and drawn with a plain text paint at its target size.
 */
object EmojiStickers {
    val LIST: List<String> = listOf(
        "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😎", "🤔", "😏",
        "😢", "😭", "😡", "🥰", "😴", "🤗", "😇", "🙃", "😳", "🥳",
        "👍", "👎", "👏", "🙏", "💪", "👌", "✌️", "🤝", "🫶", "👀",
        "❤️", "💔", "💕", "💯", "🔥", "✨", "⭐", "🌟", "💦", "💤",
        "🎉", "🎁", "🎈", "🌸", "🌺", "🍀", "🌈", "☀️", "🌙", "⚡",
        "🐶", "🐱", "🐼", "🦊", "🐰", "🐻", "🦁", "🐷", "🐸", "🐵",
        "🍎", "🍔", "🍟", "🍕", "🍰", "🍺", "☕", "🍉", "🍓", "🍜",
    )
}
