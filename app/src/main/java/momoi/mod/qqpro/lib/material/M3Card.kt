package momoi.mod.qqpro.lib.material
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.content.Context
import android.widget.LinearLayout
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.vertical

/**
 * A Material 3 surface container card: rounded [M3.surfaceContainer] background, vertical content.
 * Use to group settings rows, profile sections, info blocks. Build children with the [content] DSL.
 *
 *     M3Card(ctx).content { add<TextView>().text("hi") }
 *
 * Public on purpose (a @Mixin body referencing it needs it public).
 */
class M3Card(ctx: Context) : LinearLayout(ctx) {
    init {
        vertical()
        // MD3 卡片圆角 = 12dp（radiusMd）；开启「MD3e 圆表 UI」时用更大的表达性圆角 28dp。
        background = M3.rounded(
            M3.surfaceContainer,
            if (momoi.mod.qqpro.Settings.md3eRound.value) M3.radiusXl else M3.radiusMd,
        )
        setPadding(4.dp, 4.dp, 4.dp, 4.dp)
        setClipToOutlineCompat(false)
    }

    /** Switch to the raised (surfaceContainerHigh) tone. */
    fun raised(): M3Card = apply {
        background = M3.rounded(
            M3.surfaceContainerHigh,
            if (momoi.mod.qqpro.Settings.md3eRound.value) M3.radiusXl else M3.radiusLg,
        )
    }

    /** Inner padding around the card content (default 4dp). */
    fun contentPadding(value: Int): M3Card = apply { setPadding(value, value, value, value) }
}
