package momoi.mod.qqpro.drawable

import android.graphics.drawable.Drawable
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols

/*
 * Inline message-action icons, now backed by official Material Symbols (Outlined) via [MaterialSymbol]
 * instead of hand-drawn paths. Signatures are unchanged so existing call sites keep working; the
 * edit/recall icons keep their colored circular background to match the long-press menu items.
 */

/** Paper-plane send icon. */
fun sendIconDrawable(color: Int = 0xFF_FFFFFF.toInt()): Drawable =
    MaterialSymbol(MaterialSymbols.send, color)

/** Pencil "edit" icon on an amber circle. */
fun editIconDrawable(
    color: Int = 0xFF_FFFFFF.toInt(),
    background: Int = 0xFF_FFC107.toInt(),
): Drawable = MaterialSymbol.circled(MaterialSymbols.edit, color, background)

/** Counter-clockwise "undo / recall" icon on a red circle. */
fun recallIconDrawable(
    color: Int = 0xFF_FFFFFF.toInt(),
    background: Int = 0xFF_F44336.toInt(),
): Drawable = MaterialSymbol.circled(MaterialSymbols.undo, color, background)

/** "X" close icon. */
fun closeIconDrawable(color: Int = 0xFF_FFFFFF.toInt()): Drawable =
    MaterialSymbol(MaterialSymbols.close, color)
