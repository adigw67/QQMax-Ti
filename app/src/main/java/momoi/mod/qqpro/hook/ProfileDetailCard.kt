package momoi.mod.qqpro.hook

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.nativeinterface.ProfileBizType
import com.tencent.qqnt.kernel.nativeinterface.Source
import com.tencent.qqnt.msg.KernelServiceUtil
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.rippleTouch
import android.widget.ImageView
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils
import mqq.app.MobileQQ

/**
 * Fetches a user's extended profile (age / birthday / zodiac / location / bio) from the QQ NT kernel
 * and renders it as Material list rows. The page layout itself lives in [RichProfilePage]; this object
 * is just the data fetch + row rendering so both can stay free of layout duplication.
 *
 * Lives outside the @Mixin class: the async [fetchUserDetailInfo] callback compiles to an anonymous
 * class, and anonymous classes declared inside a @Mixin method body crash at runtime with
 * IllegalAccessError. Top-level objects are public, so the hook (a different package) can reach it.
 *
 * The synchronous `getCoreAndBaseInfo` only returns local cache (age/birthday often 0), so the real
 * source is the async server fetch — verified on-watch: it returns code=0 with all fields populated.
 */
object ProfileDetailCard {
    private val ZODIAC = arrayOf(
        "", "水瓶座", "双鱼座", "白羊座", "金牛座", "双子座", "巨蟹座",
        "狮子座", "处女座", "天秤座", "天蝎座", "射手座", "摩羯座",
    )
    private val SHENGXIAO = arrayOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")

    data class Info(
        val coreNick: String,
        val age: Int,
        val sex: Int,
        val birthYear: Int,
        val birthMonth: Int,
        val birthDay: Int,
        val constellation: Int,
        val country: String,
        val province: String,
        val city: String,
        val bio: String,
    )

    /** Populate [rows] with one Material list item per non-empty field; returns the number of rows added. */
    fun bindInto(ctx: Context, rows: LinearLayout, info: Info): Int {
        rows.removeAllViews()

        // 年龄 · 性别 — short summary line.
        val summary = buildList {
            if (info.age > 0) add("${info.age}岁")
            sexLabel(info.sex)?.let { add(it) }
        }
        if (summary.isNotEmpty()) addRow(ctx, rows, MaterialSymbols.cake, summary.joinToString("  ·  "))

        // 星座 · 生肖 — kept together on one row.
        val signs = buildList {
            zodiac(info)?.let { add(it) }
            shengXiao(info.birthYear)?.let { add("属$it") }
        }
        if (signs.isNotEmpty()) addRow(ctx, rows, MaterialSymbols.star, signs.joinToString("  ·  "))

        birthday(info)?.let { addRow(ctx, rows, MaterialSymbols.calendar_month, it) }
        location(info)?.let { addRow(ctx, rows, MaterialSymbols.location_on, it) }
        // Signature/bio: no leading icon, free-flowing text.
        info.bio.trim().takeIf { it.isNotEmpty() }?.let { addRow(ctx, rows, null, it) }
        return rows.childCount
    }

    private fun addRow(ctx: Context, rows: LinearLayout, iconPath: String?, value: String) {
        if (rows.childCount > 0) {
            // 1px Material divider (themed so it's visible on both light and dark card surfaces)
            rows.addView(View(ctx).apply {
                setBackgroundColor(M3.outlineVariant)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 9.dp, 0, 9.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            // Long-press anywhere on the field to copy it, with an M3 press ripple as touch response.
            isLongClickable = true
            rippleTouch(clip = false)
            setOnLongClickListener { Utils.copyToClipboard(ctx, value, "已复制"); true }
        }
        if (iconPath != null) {
            row.addView(ImageView(ctx).apply {
                setImageDrawable(MaterialSymbol(iconPath, M3.onSurfaceVariant))
                layoutParams = LinearLayout.LayoutParams(15.dp, 15.dp).apply { rightMargin = 8.dp }
            })
        }
        row.addView(TextView(ctx).apply {
            text = value
            textSize = 12.5f
            setTextColor(M3.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            // Long-press is handled by the parent row (so the whole field shows a ripple) — see addRow.
        })
        rows.addView(row)
    }

    private fun sexLabel(sex: Int): String? = when (sex) {
        1 -> "♂ 男"
        2 -> "♀ 女"
        else -> null
    }

    private fun zodiac(info: Info): String? {
        val c = info.constellation
        if (c in 1..12) return ZODIAC[c]
        // Derive from birthday when the kernel didn't set it.
        if (info.birthMonth in 1..12 && info.birthDay in 1..31) {
            return ZODIAC[constellationOf(info.birthMonth, info.birthDay)]
        }
        return null
    }

    /** 1=水瓶 … 12=摩羯, matching the kernel's encoding. */
    private fun constellationOf(month: Int, day: Int): Int {
        val edge = intArrayOf(20, 19, 21, 20, 21, 22, 23, 23, 23, 24, 23, 22)
        val before = intArrayOf(12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        val onOrAfter = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        return if (day < edge[month - 1]) before[month - 1] else onOrAfter[month - 1]
    }

    private fun shengXiao(year: Int): String? {
        if (year < 1900) return null
        return SHENGXIAO[((year - 4) % 12 + 12) % 12]
    }

    private fun birthday(info: Info): String? = when {
        info.birthYear > 0 -> "%d-%02d-%02d".format(info.birthYear, info.birthMonth, info.birthDay)
        info.birthMonth > 0 && info.birthDay > 0 -> "%02d-%02d".format(info.birthMonth, info.birthDay)
        else -> null
    }

    private fun location(info: Info): String? = listOf(info.country, info.province, info.city)
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        .takeIf { it.isNotEmpty() }?.joinToString(" ")

    /**
     * Compact plain-text lines (no row icons) for embedding in a non-card context (e.g. the DM
     * settings page). When [includeGenderBirthday] is false, gender and birthday are omitted (the
     * native page already shows them) — leaving age · zodiac · 生肖, location, and bio.
     */
    fun infoLines(info: Info, includeGenderBirthday: Boolean): List<String> {
        val out = mutableListOf<String>()
        val tags = buildList {
            if (info.age > 0) add("${info.age}岁")
            if (includeGenderBirthday) sexLabel(info.sex)?.let { add(it) }
            zodiac(info)?.let { add(it) }
            shengXiao(info.birthYear)?.let { add("属$it") }
        }
        if (tags.isNotEmpty()) out.add(tags.joinToString(" · "))
        if (includeGenderBirthday) birthday(info)?.let { out.add("生日 $it") }
        location(info)?.let { out.add(it) }
        info.bio.trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    // Public field formatters (null when unavailable) for callers that lay fields out themselves
    // (e.g. the DM settings chips) rather than using [bindInto].
    fun zodiacText(info: Info): String? = zodiac(info)
    fun locationText(info: Info): String? = location(info)
    fun shengXiaoText(info: Info): String? = shengXiao(info.birthYear)?.let { "属$it" }
    fun birthdayText(info: Info): String? = birthday(info)

    private fun profileService() =
        (MobileQQ.sMobileQQ?.peekAppRuntime()?.getRuntimeService(IKernelService::class.java, "") as? IKernelService)?.profileService

    /** Logged-in user's current QQ nickname from the local cache, or null. (Pre-fills 修改昵称.) */
    fun selfNick(): String? = runCatching {
        val uid = MobileQQ.sMobileQQ?.peekAppRuntime()?.currentUid ?: return null
        profileService()?.getCoreAndBaseInfo("qqpro_prefill", arrayListOf(uid))
            ?.get(uid)?.coreInfo?.nick?.takeIf { it.isNotEmpty() }
    }.onFailure { Utils.log("ProfileDetailCard.selfNick error: $it") }.getOrNull()

    /** Current buddy remark for [uin] from the local cache (uin→uid→coreInfo.remark), or null. */
    fun remarkByUin(uin: Long): String? = runCatching {
        val ps = profileService() ?: return null
        val uid = ps.getUidByUin("qqpro_prefill", arrayListOf(uin))?.get(uin) ?: return null
        ps.getCoreAndBaseInfo("qqpro_prefill", arrayListOf(uid))
            ?.get(uid)?.coreInfo?.remark?.takeIf { it.isNotEmpty() }
    }.onFailure { Utils.log("ProfileDetailCard.remarkByUin error: $it") }.getOrNull()

    /** Resolve uin → uid from the local cache, or null. A non-null result means the user is in the
     *  local contact cache (i.e. an existing friend) — strangers don't resolve. */
    fun uidByUin(uin: Long): String? = runCatching {
        profileService()?.getUidByUin("qqpro_prefill", arrayListOf(uin))?.get(uin)?.takeIf { it.isNotEmpty() }
    }.onFailure { Utils.log("ProfileDetailCard.uidByUin error: $it") }.getOrNull()

    /** Resolve uin → uid then [fetch] the detail by uid. Tries the local cache first (instant for
     *  friends); on a miss falls back to the kernel server resolver ([resolveUidServer]) so strangers
     *  (not in the local contact cache) can also be fetched. */
    fun fetchByUin(uin: Long, cb: (Info?) -> Unit) {
        runCatching {
            val app = MobileQQ.sMobileQQ?.peekAppRuntime()
            val ks = app?.getRuntimeService(IKernelService::class.java, "") as? IKernelService
            val ps = ks?.profileService
            val uid = ps?.getUidByUin("qqpro_profile", arrayListOf(uin))?.get(uin)
            Utils.log("ProfileDetailCard.fetchByUin uin=$uin uid=$uid (local)")
            if (!uid.isNullOrEmpty()) { fetch(uid, cb); return }
            // Local cache miss (stranger) → resolve via server, then fetch.
            resolveUidServer(uin) { serverUid ->
                if (serverUid.isNullOrEmpty()) cb(null) else fetch(serverUid, cb)
            }
        }.onFailure { Utils.log("ProfileDetailCard.fetchByUin error: $it"); cb(null) }
    }

    /** Resolve uin → uid: local cache first (instant, friends), else the kernel server (works for
     *  strangers). [cb] may run on a binder thread, so UI callers should re-post to the main thread. */
    fun resolveUid(uin: Long, cb: (String?) -> Unit) {
        val local = uidByUin(uin)
        if (!local.isNullOrEmpty()) { cb(local); return }
        resolveUidServer(uin, cb)
    }

    /** Resolve uin → uid via the kernel UixConvert service (a server call that works for any uin,
     *  including strangers not in the local cache). [cb] runs on a binder thread. */
    private fun resolveUidServer(uin: Long, cb: (String?) -> Unit) {
        runCatching {
            val uix = KernelServiceUtil.g()?.uixConvertService
            if (uix == null) { cb(null); return }
            uix.getUid(hashSetOf(uin)) { map ->
                val uid = map?.get(uin)?.takeIf { it.isNotEmpty() }
                Utils.log("ProfileDetailCard.resolveUidServer uin=$uin uid=$uid (server)")
                cb(uid)
            }
        }.onFailure { Utils.log("ProfileDetailCard.resolveUidServer error: $it"); cb(null) }
    }

    /** Async server fetch; parses into [Info]; invokes [cb] (possibly on a binder thread → caller posts). */
    fun fetch(uid: String, cb: (Info?) -> Unit) {
        runCatching {
            val app = MobileQQ.sMobileQQ?.peekAppRuntime()
            val ks = app?.getRuntimeService(IKernelService::class.java, "") as? IKernelService
            val ps = ks?.profileService
            if (ps == null) { cb(null); return }
            ps.fetchUserDetailInfo(
                "qqpro_profile", arrayListOf(uid), Source.KSERVER,
                arrayListOf(ProfileBizType.KALL),
            ) { code, msg, _, map ->
                val d = map?.get(uid)
                val bi = d?.simpleInfo?.baseInfo
                val ce = d?.commonExt
                Utils.log("ProfileDetailCard.fetch uid=$uid code=$code msg='$msg' detail=${d != null}")
                if (d == null) { cb(null); return@fetchUserDetailInfo }
                cb(
                    Info(
                        coreNick = d.simpleInfo?.coreInfo?.nick ?: "",
                        age = bi?.age ?: 0,
                        sex = bi?.sex ?: 0,
                        birthYear = bi?.birthdayYear ?: 0,
                        birthMonth = bi?.birthdayMonth ?: 0,
                        birthDay = bi?.birthdayDay ?: 0,
                        constellation = ce?.constellation ?: 0,
                        country = ce?.country ?: "",
                        province = ce?.province ?: "",
                        city = ce?.city ?: "",
                        bio = bi?.longNick ?: "",
                    )
                )
            }
        }.onFailure { Utils.log("ProfileDetailCard.fetch error: $it"); cb(null) }
    }
}
