package momoi.mod.qqpro.hook.aio_cell

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.ArkElement
import loadPicUrl
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.confirmOpenUrl
import momoi.mod.qqpro.showDialog
import momoi.mod.qqpro.hook.openAddSearch
import momoi.mod.qqpro.hook.style.heightLimit
import momoi.mod.qqpro.hook.view.loadOsmStaticMap
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.adjustViewBounds
import momoi.mod.qqpro.lib.background
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.margin
import momoi.mod.qqpro.lib.marginVertical
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.scaleType
import momoi.mod.qqpro.lib.size
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.lib.width
import momoi.mod.qqpro.util.Json
import momoi.mod.qqpro.util.Utils

class CardMsgView(context: Context) : LinearLayout(context) {
    private lateinit var mGeneric: LinearLayout
    private lateinit var mTvTitle: TextView
    private lateinit var mTvTag: TextView
    private lateinit var mTvDesc: TextView
    private lateinit var mIvIcon: ImageView
    private lateinit var mIvPreview: ImageView

    // Dedicated name-card (推荐联系人) views: avatar on the LEFT, text on the right.
    private lateinit var mContact: LinearLayout
    private lateinit var mIvAvatar: ImageView
    private lateinit var mTvCardNick: TextView
    private lateinit var mTvCardAccount: TextView
    private lateinit var mTvCardTag: TextView

    init {
        vertical()
        padding(2.dp)
        content {
            mGeneric = add<LinearLayout>().vertical().width(FILL).content {
                // Top row: icon on the LEFT, title + description stacked on the right.
                add<LinearLayout>()
                    .width(FILL)
                    .gravity(Gravity.CENTER_VERTICAL)
                    .content {
                        val iconSize = (28f * Settings.chatScale.value).toInt().dp
                        mIvIcon = add<ImageView>()
                            .size(iconSize)
                            .scaleType(ImageView.ScaleType.FIT_CENTER)
                        add<LinearLayout>().vertical().weight(1f).margin(left = 4.dp).content {
                            mTvTitle = add<TextView>()
                                .textSize(12f * Settings.chatScale.value)
                                .textColor(M3.onSurface)
                            mTvDesc = add<TextView>()
                                .textSize(10f * Settings.chatScale.value)
                                .textColor(M3.onSurfaceVariant)
                        }
                    }
                add<View>()
                    .size(width = FILL, height = 1)
                    .background(M3.outline)
                    .marginVertical(1.dp)
                mIvPreview = add<ImageView>()
                    .width(FILL)
                    .scaleType(ImageView.ScaleType.FIT_CENTER)
                    .adjustViewBounds()
                    .apply { maxHeight = heightLimit.toInt() }
                mTvTag = add<TextView>()
                    .width(FILL)
                    .textSize(9f * Settings.chatScale.value)
                    .textColor(M3.onSurface)
                    .text(" ")
            }
            mContact = add<LinearLayout>().width(FILL).gravity(Gravity.CENTER_VERTICAL).content {
                val avatarSize = (36f * Settings.chatScale.value).toInt().dp
                mIvAvatar = add<ImageView>()
                    .size(avatarSize)
                    .scaleType(ImageView.ScaleType.CENTER_CROP)
                add<LinearLayout>().vertical().weight(1f).margin(left = 6.dp).content {
                    mTvCardNick = add<TextView>()
                        .textSize(13f * Settings.chatScale.value)
                        .textColor(M3.onSurface)
                    mTvCardAccount = add<TextView>()
                        .textSize(10f * Settings.chatScale.value)
                        .textColor(M3.onSurfaceVariant)
                    mTvCardTag = add<TextView>()
                        .textSize(9f * Settings.chatScale.value)
                        .textColor(M3.onSurfaceTip)
                }
            }
            mContact.visibility = GONE
        }
    }

    fun loadData(ark: ArkElement) {
        try {
            val json = Json(ark.bytesData)
            val meta = json.json("meta")!!
            // Name card / 推荐联系人 (app=com.tencent.contact.lua): meta holds a
            // "contact" object with nickname/contact/avatar/tag instead of the
            // generic title/desc/icon schema, so render it explicitly. Mirrors the
            // dedicated group-invite handling in StructMsgView.
            if (json.str("app") == "com.tencent.contact.lua") {
                mGeneric.visibility = GONE
                mContact.visibility = VISIBLE
                loadContactCard(json, meta)
                return
            }
            // Location share (app=com.tencent.map, view=LocationShare): meta holds a
            // "Location.Search" object with name/address/lat/lng instead of the
            // generic title/desc/icon schema (which would NPE on the missing
            // "title"), so render name + address explicitly and make it tappable.
            if (json.str("app") == "com.tencent.map") {
                mContact.visibility = GONE
                mGeneric.visibility = VISIBLE
                loadLocationCard(json, meta)
                return
            }
            // Group announcement (app=com.tencent.mannounce): meta holds a "mannounce"
            // object whose title/text are base64-encoded, so the generic path would show
            // the raw base64 string. Decode and render the real title + body.
            if (json.str("app") == "com.tencent.mannounce") {
                mContact.visibility = GONE
                mGeneric.visibility = VISIBLE
                loadAnnounceCard(meta, ark.bytesData)
                return
            }
            mContact.visibility = GONE
            mGeneric.visibility = VISIBLE
            val data = meta.json(meta.keys.first())!!
            val desc = data.str("desc")
            val title = data.str("title")!!
            mTvTitle.text = title
            mTvDesc.visibility = VISIBLE
            mTvDesc.text = desc
            val icon = data.str("icon") ?: data.str("tagIcon")
            if (!icon.isNullOrEmpty()) {
                mIvIcon.visibility = VISIBLE
                mIvIcon.loadPicUrl(icon)
            } else {
                mIvIcon.visibility = GONE
            }
            val tag = data.str("tag")
            if (!tag.isNullOrEmpty()) {
                mTvTag.visibility = VISIBLE
                mTvTag.text = tag
            } else {
                mTvTag.visibility = GONE
            }
            val preview = data.str("preview")
            if (!preview.isNullOrEmpty() && json.str("view") != "news") {
                mIvPreview.visibility = VISIBLE
                mIvPreview.loadPicUrl(preview)
            } else {
                mIvPreview.visibility = GONE
            }
            clickable {
                (data.str("jumpUrl") ?: data.str("qqdocurl"))?.let {
                    this@CardMsgView.confirmOpenUrl(it)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Renders a shared contact name card (app=com.tencent.contact.lua) that the
     * watch otherwise dumps to the "view on phone" placeholder. The "contact"
     * meta object carries nickname/contact(账号)/avatar/tag/jumpUrl. Tapping opens
     * the add-friend/group search pad with the uin prefilled — same flow as the
     * group-invite card (StructMsgView).
     */
    private fun loadContactCard(json: Json, meta: Json) {
        try {
            val contact = meta.json("contact") ?: return
            mTvCardNick.text = contact.str("nickname") ?: json.str("prompt") ?: "[名片]"
            val account = contact.str("contact")
            if (!account.isNullOrEmpty()) {
                mTvCardAccount.visibility = VISIBLE
                mTvCardAccount.text = account
            } else {
                mTvCardAccount.visibility = GONE
            }
            val tag = contact.str("tag")
            if (!tag.isNullOrEmpty()) {
                mTvCardTag.visibility = VISIBLE
                mTvCardTag.text = tag
            } else {
                mTvCardTag.visibility = GONE
            }
            val avatar = contact.str("avatar")
            if (!avatar.isNullOrEmpty()) {
                mIvAvatar.loadPicUrl(avatar)
            }
            // The uin lives in the "账号：xxxxx" string (or the jumpUrl uin param).
            val uin = (account?.let { Regex("\\d{5,}").find(it)?.value }
                ?: contact.str("jumpUrl")?.let { Regex("uin=(\\d+)").find(it)?.groupValues?.get(1) })
            if (!uin.isNullOrEmpty()) {
                isClickable = true
                setOnClickListener { openAddSearch(uin, type = 1) }
            } else {
                setOnClickListener(null)
                isClickable = false
            }
        } catch (e: Exception) {
            Utils.log("CardMsgView contact card error: ${e.message}")
        }
    }

    /**
     * Renders a shared group announcement card (app=com.tencent.mannounce). The "mannounce"
     * meta object carries base64-encoded title/text (otherwise shown as a raw base64 string),
     * plus gc (group code), fid and pics. We decode and show title + full body inline.
     */
    private fun loadAnnounceCard(meta: Json, raw: String) {
        try {
            val a = meta.json("mannounce")
                ?: meta.keys.firstOrNull()?.let { meta.json(it) }
                ?: return
            val title = decodeB64(a.str("title"))?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "群公告"
            val text = decodeB64(a.str("text"))?.trim().orEmpty()

            mTvTitle.text = title
            mIvIcon.visibility = GONE
            if (text.isNotEmpty()) {
                mTvDesc.visibility = VISIBLE
                mTvDesc.text = text
            } else {
                mTvDesc.visibility = GONE
            }
            mTvTag.visibility = VISIBLE
            mTvTag.text = "[群公告]"

            // pic is a JSON array (no array support in Json), so pull the first id from the raw
            // bytes: "pic":[{"height":..,"url":"<id>","width":..}]. Images load from the same
            // qpic endpoint as group-bulletin images: gdynamic.qpic.cn/gdynamic/<id>/0.
            val picId = Regex("\"pic\"\\s*:\\s*\\[\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\"")
                .find(raw)?.groupValues?.get(1)
            if (!picId.isNullOrEmpty()) {
                val url = "http://gdynamic.qpic.cn/gdynamic/$picId/0"
                mIvPreview.visibility = VISIBLE
                mIvPreview.loadPicUrl(url)
                clickable {
                    (mIvPreview.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
                        this@CardMsgView.showDialog(BigImageFragment(bmp = it))
                    }
                }
            } else {
                mIvPreview.visibility = GONE
                setOnClickListener(null)
                isClickable = false
            }
        } catch (e: Exception) {
            Utils.log("CardMsgView announce card error: ${e.message}")
        }
    }

    /** Decode a base64 string used by mannounce title/text; null if absent or invalid. */
    private fun decodeB64(s: String?): String? = s?.takeIf { it.isNotEmpty() }?.let {
        runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrNull()
    }

    /**
     * Renders a shared location card (app=com.tencent.map, view=LocationShare)
     * that the watch otherwise dumps to the "view on phone" placeholder. The
     * "Location.Search" meta object carries name/address/lat/lng. Tapping opens
     * the point in a map via the Tencent map marker URI.
     */
    private fun loadLocationCard(json: Json, meta: Json) {
        try {
            val loc = meta.json("Location.Search")
                ?: meta.keys.firstOrNull()?.let { meta.json(it) }
                ?: return
            val name = loc.str("name")
                ?: json.str("prompt")?.removePrefix("[位置]")
                ?: "[位置]"
            val address = loc.str("address")
            val lat = loc.str("lat")
            val lng = loc.str("lng")

            mTvTitle.text = name
            mIvIcon.visibility = GONE
            if (!address.isNullOrEmpty()) {
                mTvDesc.visibility = VISIBLE
                mTvDesc.text = address
            } else {
                mTvDesc.visibility = GONE
            }
            mTvTag.visibility = VISIBLE
            mTvTag.text = "📍 位置"

            // Static map thumbnail stitched from keyless OSM tiles. Cached by
            // lat/lng so the same location isn't re-rendered on every rebind.
            val latD = lat?.toDoubleOrNull()
            val lngD = lng?.toDoubleOrNull()
            if (latD != null && lngD != null) {
                mIvPreview.visibility = VISIBLE
                mIvPreview.loadOsmStaticMap(latD, lngD, cacheKey = "osmmap_${lat}_$lng")
            } else {
                mIvPreview.visibility = GONE
            }

            if (!lat.isNullOrEmpty() && !lng.isNullOrEmpty()) {
                clickable {
                    val url = "https://apis.map.qq.com/uri/v1/marker?" +
                        "marker=coord:$lat,$lng;title:$name;addr:${address ?: name}&referer=QQ"
                    this@CardMsgView.confirmOpenUrl(url)
                }
            } else {
                setOnClickListener(null)
                isClickable = false
            }
        } catch (e: Exception) {
            Utils.log("CardMsgView location card error: ${e.message}")
        }
    }
}
