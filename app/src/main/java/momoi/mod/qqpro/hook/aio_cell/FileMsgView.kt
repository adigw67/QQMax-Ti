package momoi.mod.qqpro.hook.aio_cell

import android.content.Context
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.FileElement
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.lib.background
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.margin
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.size
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.util.Utils

/**
 * Renders file-transfer messages (msgType 3 [FILE] / 21 [ONLINEFILE]) that the
 * watch otherwise dumps to the orange "view on phone" placeholder
 * ("[文件]\n文件名：…\n文件大小：…"). Shows an extension badge on the left and the
 * file name + human-readable size on the right. Mirrors the dedicated card
 * handling in [StructMsgView] / [CardMsgView].
 */
class FileMsgView(context: Context) : LinearLayout(context) {
    private lateinit var mTvBadge: TextView
    private lateinit var mTvName: TextView
    private lateinit var mTvSize: TextView

    init {
        gravity(Gravity.CENTER_VERTICAL)
        padding(6.dp)
        background(roundCornerDrawable(0x33_FFFFFF, Settings.bubbleCornerRadius.value.dpf))
        content {
            val badgeSize = (34f * Settings.chatScale.value).toInt().dp
            mTvBadge = add<TextView>()
                .size(badgeSize)
                .gravity(Gravity.CENTER)
                .textSize(9f * Settings.chatScale.value)
                .textColor(M3.onPrimary) // label sits on the primary-filled badge
                .apply {
                    background = roundCornerDrawable(M3.primary, 4.dpf)
                }
            add<LinearLayout>().vertical().margin(left = 8.dp).content {
                // Root card is WRAP_CONTENT inside the bubble warp, so a weight
                // can't bound the name — cap its width instead so long names
                // wrap to 2 lines / ellipsize rather than blowing out the bubble.
                mTvName = add<TextView>()
                    .textSize(12f * Settings.chatScale.value)
                    .textColor(M3.onSurface)
                    .apply {
                        maxWidth = (150f * Settings.chatScale.value).toInt().dp
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    }
                mTvSize = add<TextView>()
                    .textSize(10f * Settings.chatScale.value)
                    .textColor(M3.onSurfaceVariant)
            }
        }
    }

    fun loadData(file: FileElement) {
        try {
            val name = file.fileName?.takeIf { it.isNotEmpty() } ?: "[文件]"
            mTvName.text = name
            mTvSize.text = Formatter.formatShortFileSize(context, file.fileSize)
            val ext = name.substringAfterLast('.', "").take(4).uppercase()
            mTvBadge.text = if (ext.isEmpty()) "FILE" else ext
        } catch (e: Exception) {
            Utils.log("FileMsgView error: ${e.message}")
        }
    }
}
