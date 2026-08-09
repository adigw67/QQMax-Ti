package momoi.mod.qqpro.hook

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.watch.aio_impl.ui.frames.SettingFrame
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.hook.style.CARD_MARGIN_DP
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

private const val EXTRA_TAG = "qqpro_dm_extra_info"
private const val UNKNOWN = "未知"

/**
 * Append extended profile info to a DM chat-settings page ([SettingFrame], chatType==1), styled to
 * match the native gender/birthday chips (CustomInfoView): a 2-per-row grid of 年龄 | 星座, a
 * full-width 地区 chip, and a full-width 签名 card — all using the native watch_normal_button_white_bg
 * chip background. Missing fields show "未知" like the native gender chip.
 *
 * The page carries the peer uid as key_bundle_peer_id (DM); we fetch the detail by uid via
 * [ProfileDetailCard]. Lives outside any @Mixin class (async callback = anonymous class →
 * IllegalAccessError if copied into the target package); called from [GroupAvatarPreview].
 */
fun addDmExtraInfo(fragment: SettingFrame) {
    runCatching {
        val args = fragment.arguments ?: return
        if (args.getInt("key_bundle_chat_type") != 1) return // 1 = 好友 DM
        val uid = args.getString("key_bundle_peer_id")?.trim().orEmpty()
        if (uid.isEmpty()) return

        val root = fragment.view as? ViewGroup ?: return
        // The native gender/birthday block is a CustomInfoView; insert our rows right after it.
        val anchor = root.findAll { it.javaClass.simpleName == "CustomInfoView" } ?: run {
            Utils.log("ProfileExtraInfo: CustomInfoView not found")
            return
        }
        val parent = anchor.parent as? LinearLayout ?: run {
            Utils.log("ProfileExtraInfo: CustomInfoView parent not a LinearLayout")
            return
        }
        if (parent.findViewWithTag<View>(EXTRA_TAG) != null) return // idempotent

        val ctx = fragment.requireContext()
        val whiteBg = ctx.resources.getIdentifier("watch_normal_button_white_bg", "drawable", ctx.packageName)

        // Container holding our extra rows, tagged for idempotency. No margins of its own — each
        // card carries the same margins the page's CardMarginUnify applies to every other card.
        val box = LinearLayout(ctx).apply {
            tag = EXTRA_TAG
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        parent.addView(box, parent.indexOfChild(anchor) + 1)

        ProfileDetailCard.fetch(uid) { info ->
            box.post {
                if (info == null) return@post
                box.removeAllViews()

                if (Settings.useM3Settings.value) {
                    // M3 redesign: render one clean divider-separated list (性别/生日/年龄/星座/地区/签名)
                    // instead of separate chip cards with mismatched margins. bindInto already includes
                    // gender & birthday, so hide the native CustomInfoView chips to avoid duplication.
                    anchor.visibility = View.GONE
                    ProfileDetailCard.bindInto(ctx, box, info)
                    box.visibility = View.VISIBLE
                    Utils.log("ProfileExtraInfo: M3 info rows shown for uid=$uid")
                    return@post
                }

                val age = if (info.age > 0) "${info.age}岁" else UNKNOWN
                val zodiac = ProfileDetailCard.zodiacText(info) ?: UNKNOWN
                val location = ProfileDetailCard.locationText(info) ?: UNKNOWN
                val bio = info.bio.trim().ifEmpty { UNKNOWN }

                box.addView(twoChipRow(ctx, whiteBg, "年龄", age, "星座", zodiac)) // 年龄 | 星座
                box.addView(fullChip(ctx, whiteBg, "地区", location))           // 地区 (full width)
                box.addView(bioCard(ctx, whiteBg, bio))                         // 签名 (full width)
                box.visibility = View.VISIBLE
                Utils.log("ProfileExtraInfo: chips shown for uid=$uid")
            }
        }
    }.onFailure { Utils.log("ProfileExtraInfo.addDmExtraInfo failed: $it") }
}

/** A single chip mirroring the native gender/birthday cell: bold-blue value over a white label. */
private fun chip(ctx: Context, bgRes: Int, label: String, value: String): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        if (bgRes != 0) setBackgroundResource(bgRes)
        setPadding(6.dp, 5.dp, 6.dp, 5.dp)
        addView(TextView(ctx).apply {
            text = value
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(M3.primary)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(TextView(ctx).apply {
            text = label
            textSize = 10f
            setTextColor(M3.onSurface)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 3.dp }
        })
    }

// Margins matching the page's CardMarginUnify: outer edge = 2*CARD_MARGIN_DP, the gap between two
// side-by-side cards is split (CARD_MARGIN_DP each), and every vertical gap = 2*CARD_MARGIN_DP
// (CARD_MARGIN_DP top + CARD_MARGIN_DP bottom).
private val EDGE get() = (2 * CARD_MARGIN_DP).dp
private val FACE get() = CARD_MARGIN_DP.dp
private val VGAP get() = CARD_MARGIN_DP.dp

/** Two equal-weight chips side by side, spaced like the native gender|birthday split. */
private fun twoChipRow(ctx: Context, bgRes: Int, l1: String, v1: String, l2: String, v2: String): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(chip(ctx, bgRes, l1, v1), LinearLayout.LayoutParams(0, 45.dp, 1f).apply {
            marginStart = EDGE; marginEnd = FACE; topMargin = VGAP; bottomMargin = VGAP
        })
        addView(chip(ctx, bgRes, l2, v2), LinearLayout.LayoutParams(0, 45.dp, 1f).apply {
            marginStart = FACE; marginEnd = EDGE; topMargin = VGAP; bottomMargin = VGAP
        })
    }

/** A single full-width chip row. */
private fun fullChip(ctx: Context, bgRes: Int, label: String, value: String): LinearLayout =
    chip(ctx, bgRes, label, value).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 45.dp).apply {
            marginStart = EDGE; marginEnd = EDGE; topMargin = VGAP; bottomMargin = VGAP
        }
    }

/** Full-width 签名 card: small label over the (wrapping) signature text. */
private fun bioCard(ctx: Context, bgRes: Int, bio: String): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (bgRes != 0) setBackgroundResource(bgRes)
        setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = EDGE; marginEnd = EDGE; topMargin = VGAP; bottomMargin = VGAP }
        addView(TextView(ctx).apply {
            text = "签名"
            textSize = 10f
            setTextColor(M3.onSurfaceVariant)
        })
        addView(TextView(ctx).apply {
            text = bio
            textSize = 12f
            setTextColor(M3.onSurface)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 3.dp }
            isLongClickable = true
            setOnLongClickListener { Utils.copyToClipboard(ctx, bio, "已复制签名"); true }
        })
    }
