package momoi.mod.qqpro.hook.aio_cell

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.StructMsgElement
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.openAddSearch
import java.lang.ref.WeakReference
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.background
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.lib.width
import momoi.mod.qqpro.util.Utils

/**
 * Renders [StructMsgElement] (msgType 4) XML cards that the watch otherwise
 * dumps to the "view on phone" placeholder. Covers group invites
 * (serviceID 128, "邀请你加入群聊") and falls back to title/summary for other
 * struct shares.
 */
class StructMsgView(context: Context) : LinearLayout(context) {
    private lateinit var mTvTitle: TextView
    private lateinit var mTvSummary: TextView

    init {
        vertical()
        padding(6.dp)
        background(roundCornerDrawable(0x33_FFFFFF, Settings.bubbleCornerRadius.value.dpf))
        content {
            mTvTitle = add<TextView>()
                .width(FILL)
                .textSize(13f * Settings.chatScale.value)
                .textColor(M3.onSurface)
            mTvSummary = add<TextView>()
                .width(FILL)
                .textSize(11f * Settings.chatScale.value)
                .textColor(M3.onSurfaceVariant)
        }
    }

    fun loadData(struct: StructMsgElement) {
        try {
            val xml = struct.xmlContent ?: ""
            val title = attr(xml, "groupname")
                ?: tag(xml, "title")
                ?: attr(xml, "brief")
                ?: "[链接]"
            val invite = tag(xml, "title") ?: attr(xml, "brief")?.removePrefix("[链接]")
            val groupCode = attr(xml, "groupcode")

            mTvTitle.text = title
            val summary = buildString {
                if (!invite.isNullOrEmpty()) append(invite)
                if (!groupCode.isNullOrEmpty()) {
                    if (isNotEmpty()) append("  ")
                    append("群号 $groupCode")
                }
            }
            if (summary.isNotEmpty()) {
                mTvSummary.visibility = VISIBLE
                mTvSummary.text = summary
            } else {
                mTvSummary.visibility = GONE
            }

            // Tapping a group invite opens the add/search numeric pad with the
            // group code prefilled (the search also returns groups).
            if (!groupCode.isNullOrEmpty()) {
                isClickable = true
                setOnClickListener { openAddSearch(groupCode, type = 1) }
            } else {
                setOnClickListener(null)
                isClickable = false
            }
        } catch (e: Exception) {
            Utils.log("StructMsgView error: ${e.message}")
        }
    }

    private fun attr(xml: String, name: String): String? =
        Regex("$name=\"([^\"]*)\"").find(xml)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name[^>]*>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
}
