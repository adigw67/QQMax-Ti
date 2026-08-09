package momoi.mod.qqpro.drawable

import android.graphics.drawable.Drawable
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols

/*
 * Chat "+" panel / input-bar icons, now backed by official Material Symbols (Outlined) via
 * [MaterialSymbol] instead of hand-drawn shapes. Signatures (no-arg, returning Drawable) are kept so
 * every existing call site is unchanged. Default white tint reads on the dark watch theme.
 */

private const val WHITE = 0xFF_FFFFFF.toInt()

/** Album / gallery. */
fun galleryIconDrawable(): Drawable = MaterialSymbol(MaterialSymbols.image, WHITE)

/** Take photo. */
fun cameraIconDrawable(): Drawable = MaterialSymbol(MaterialSymbols.photo_camera, WHITE)

/** Voice call. */
fun phoneIconDrawable(): Drawable = MaterialSymbol(MaterialSymbols.call, WHITE)

/** Record video / video call. */
fun recordIconDrawable(): Drawable = MaterialSymbol(MaterialSymbols.videocam, WHITE)

/** Send audio file. */
fun audioFileIconDrawable(): Drawable = MaterialSymbol(MaterialSymbols.audio_file, WHITE)

/** Mention member (@). */
fun atIconDrawable(): Drawable = MaterialSymbol(MaterialSymbols.alternate_email, WHITE)

/** Titlebar back. */
fun backIconDrawable(): Drawable = MaterialSymbol(MaterialSymbols.arrow_back, WHITE)

/** Attachment / open-panel (+). */
fun plusIconDrawable(): Drawable = MaterialSymbol(MaterialSymbols.add, WHITE)

/** Emoji panel. */
fun emojiIconDrawable(): Drawable = MaterialSymbol(MaterialSymbols.mood, WHITE)

/** GIF search. */
fun gifSearchIconDrawable(): Drawable = MaterialSymbol(MaterialSymbols.gif, WHITE)

/** Video call. */
fun videoIconDrawable(): Drawable = MaterialSymbol(MaterialSymbols.videocam, WHITE)
