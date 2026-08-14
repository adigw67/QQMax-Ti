package momoi.mod.qqpro.hook.aio_cell

import momoi.mod.qqpro.util.Json
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * B站链接/小程序解析，结构对齐 https://github.com/BestBcz/BiliURL ：
 *
 *  - QQ 小程序卡片：app=com.tencent.miniapp_01 + meta.detail_1.appid=1109937557 时取
 *    detail_1.qqdocurl；meta.news.jumpUrl 是动态/专栏类分享的跳转地址（BiliURL 优先取它）。
 *  - 链接类型：视频（BV/av/b23.tv）、动态（bilibili.com/opus、t.bilibili.com）、
 *    专栏（bilibili.com/read/cv、read/mobile?id=）；b23.tv 短链重定向后分类。
 *  - BV 号按 BiliURL 的 base58 字母表校验；av 号大小写兼容（av/AV），并校验数字合法性。
 *  - 详情接口与 BiliURL 一致：视频 x/web-interface/view、动态 opus SSR → dynamic_svr →
 *    polymer v1/detail 三连、专栏 x/article/viewinfo。
 */
object BiliParser {

    // 有界 IO 线程池：多张 B 站卡片同时解析时不会为每个请求开一个线程（老手表线程/句柄有限）。
    val io = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "qqpro-bili").apply { isDaemon = true }
    }

    /** 一个 bilibili 链接目标：视频 / 动态 / 专栏 / 待重定向的 b23 短链。 */
    sealed class Target {
        /** 视频：bvid / aid / b23 短链，至少一种。 */
        data class Video(val bvid: String?, val aid: Long?, val short: String?) : Target() {
            val key: String
                get() = when {
                    bvid != null -> bvid
                    aid != null -> "av$aid"
                    else -> "b23/$short"
                }

            fun webUrl(): String = when {
                bvid != null -> "https://www.bilibili.com/video/$bvid"
                aid != null -> "https://www.bilibili.com/video/av$aid"
                else -> "https://b23.tv/$short"
            }
        }

        /** 动态：opus/t.bilibili 的 id（同一 id 体系）。 */
        data class Dynamic(val id: String) : Target() {
            val key: String get() = "dyn$id"
            fun webUrl(): String = "https://www.bilibili.com/opus/$id"
        }

        /** 专栏：cv 号（去掉前缀的数字）。 */
        data class Article(val id: String) : Target() {
            val key: String get() = "cv$id"
            fun webUrl(): String = "https://www.bilibili.com/read/cv$id"
        }

        /**
         * b23.tv 短链：类型未知，重定向后分类为上面三种。title/desc 来自 QQ 小程序卡片
         * （meta.detail_1）本身，无网络也能先显示出来。
         */
        data class ShortLink(val code: String, val title: String? = null, val desc: String? = null) : Target() {
            val key: String get() = "b23/$code"
            fun webUrl(): String = "https://b23.tv/$code"
        }
    }

    // BiliURL 的 BV base58 字母表（BV1 + 9 位）。
    private const val BV_ALPHABET = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"

    private val RE_BV = Regex(
        """(?:https?://)?(?:www\.|m\.)?bilibili\.com/video/([Bb][vV]1[$BV_ALPHABET]{9})(?=$|[^0-9A-Za-z])"""
    )
    private val RE_AV = Regex(
        """(?:https?://)?(?:www\.|m\.)?bilibili\.com/video/([aA][vV](\d+))(?=$|[^0-9A-Za-z])"""
    )
    private val RE_B23 = Regex("""(?:https?://)?(?:www\.)?b23\.tv/([A-Za-z0-9]+)""")
    private val RE_OPUS = Regex("""(?:https?://)?(?:www\.|m\.)?bilibili\.com/opus/(\d+)""")
    private val RE_TBILI = Regex("""(?:https?://)?t\.bilibili\.com/(\d+)""")
    private val RE_ARTICLE = Regex(
        """(?:https?://)?(?:www\.|m\.)?bilibili\.com/read/(?:cv(\d+)|mobile\?[^#\s]*id=(\d+))"""
    )
    private val RE_BVID_PARAM = Regex("""bvid=([Bb][vV]1[$BV_ALPHABET]{9})""")
    private val RE_BARE_BV = Regex("""(?<![0-9A-Za-z])[Bb][vV]1[$BV_ALPHABET]{9}(?![0-9A-Za-z])""")
    private val RE_BARE_AV = Regex("""(?<![0-9A-Za-z])[aA][vV](\d+)(?![0-9A-Za-z])""")

    /**
     * 从任意文本提取第一个 bilibili 目标。优先级：动态/专栏 → 视频（BV → av → b23 短链 →
     * bvid 参数 → 裸 BV → 裸 av）。文本常来自小程序 JSON，先反转义 \/ 与 &amp;。
     */
    fun extract(text: CharSequence?): Target? {
        val s = text?.toString() ?: return null
        val n = s.replace("\\/", "/").replace("&amp;", "&")
        RE_OPUS.find(n)?.let { return Target.Dynamic(it.groupValues[1]) }
        RE_TBILI.find(n)?.let { return Target.Dynamic(it.groupValues[1]) }
        RE_ARTICLE.find(n)?.let { m ->
            val id = m.groupValues[1].takeIf { it.isNotBlank() } ?: m.groupValues[2]
            return Target.Article(id)
        }
        RE_BV.find(n)?.let { return Target.Video(it.groupValues[1].uppercase(), null, null) }
        RE_AV.find(n)?.let { m ->
            val av = m.groupValues[2].toLongOrNull()
            if (av != null && av > 0) return Target.Video(null, av, null)
        }
        RE_B23.find(n)?.let { return Target.ShortLink(it.groupValues[1]) }
        RE_BVID_PARAM.find(n)?.let { return Target.Video(it.groupValues[1].uppercase(), null, null) }
        RE_BARE_BV.find(n)?.let { return Target.Video(it.value.uppercase(), null, null) }
        RE_BARE_AV.find(n)?.let { m ->
            val av = m.groupValues[1].toLongOrNull()
            if (av != null && av > 0) return Target.Video(null, av, null)
        }
        return null
    }

    /**
     * 按 BiliURL 的 QQ 小程序卡片结构解析 ark bytesData（JSON）：
     * meta.news.jumpUrl 优先（动态/专栏分享），其次 meta.detail_1.qqdocurl（B站小程序
     * appid=1109937557 的视频分享）。
     */
    fun extractFromMiniApp(jsonStr: String): Target? {
        val root = runCatching { Json(jsonStr) }.getOrNull() ?: return null
        val meta = root.json("meta") ?: return null
        // BiliURL：app==com.tencent.miniapp_01 && detail_1.appid==1109937557 → qqdocurl
        val detail = meta.json("detail_1")
        if (root.str("app") == "com.tencent.miniapp_01" &&
            detail != null && detail.str("appid") == "1109937557"
        ) {
            val title = detail.str("title")?.takeIf { it.isNotBlank() }
            val desc = detail.str("desc")?.takeIf { it.isNotBlank() }
            detail.str("qqdocurl")?.let { url ->
                extract(url)?.let { t ->
                    if (t is Target.ShortLink) {
                        return Target.ShortLink(t.code, title, desc)
                    }
                    return t
                }
            }
        }
        meta.json("news")?.str("jumpUrl")?.let { extract(it)?.let { t -> return t } }
        return null
    }

    /** b23.tv 短链 → 跟随重定向 → 分类为视频/动态/专栏；失败回调 null。 */
    fun resolveShort(code: String, callback: (Target?) -> Unit) {
        io.execute {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("https://b23.tv/$code").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", UA)
                    instanceFollowRedirects = true
                }
                conn.responseCode
                val finalUrl = conn.url.toString()
                val target = extract(finalUrl)
                if (target == null) Utils.log("BiliParser: b23 重定向未识别 $finalUrl")
                runOnUi { callback(target) }
            } catch (e: Exception) {
                Utils.log("BiliParser: b23 resolve failed: ${e.message}")
                runOnUi { callback(null) }
            } finally {
                conn?.disconnect()
            }
        }
    }

    // ------------------------------------------------------------------ 动态

    data class DynamicInfo(
        val id: String,
        val uid: String,
        val author: String,
        val content: String,
        val pictures: List<String>,
        val timestamp: Long,
    ) {
        fun webUrl(): String = "https://www.bilibili.com/opus/$id"
        fun timeText(): String =
            if (timestamp > 0)
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp * 1000))
            else ""
    }

    /** 动态详情：按 BiliURL 顺序 opus SSR → dynamic_svr → polymer v1/detail 三连。 */
    fun fetchDynamic(id: String, callback: (DynamicInfo?) -> Unit) {
        fetchOpusHtml(id) { opus ->
            if (opus != null) {
                callback(opus)
                return@fetchOpusHtml
            }
            fetchDynamicSvr(id) { svr ->
                if (svr != null) {
                    callback(svr)
                    return@fetchDynamicSvr
                }
                fetchDetailV1(id) { v1 -> callback(v1) }
            }
        }
    }

    private fun fetchOpusHtml(id: String, callback: (DynamicInfo?) -> Unit) {
        getText("https://www.bilibili.com/opus/$id") { html ->
            if (html == null) {
                callback(null)
                return@getText
            }
            val m = Regex("""window\.__INITIAL_STATE__=(\{.*\});\(function\(\)""").find(html)
                ?: run { callback(null); return@getText }
            val info = runCatching {
                parseV1Detail(id, JSONObject(m.groupValues[1]).getJSONObject("detail"))
            }.getOrElse {
                Utils.log("BiliParser: opus SSR parse failed: ${it.message}")
                null
            }
            callback(info)
        }
    }

    private fun fetchDynamicSvr(id: String, callback: (DynamicInfo?) -> Unit) {
        getJson(
            "https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/get_dynamic_detail?dynamic_id=$id"
        ) { raw ->
            val info = raw?.let { parseDynamicSvr(id, it) }
            callback(info)
        }
    }

    private fun fetchDetailV1(id: String, callback: (DynamicInfo?) -> Unit) {
        getJson("https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=$id") { raw ->
            val info = raw?.let { parseDetailV1(id, it) }
            callback(info)
        }
    }

    /** polymer v1/detail 的 data 结构（opus 页面 SSR 的 __INITIAL_STATE__ 也用它）。 */
    private fun parseV1Detail(id: String, data: JSONObject): DynamicInfo? = try {
        val item = data.getJSONObject("item")
        val modules = item.getJSONObject("modules")
        val author = modules.getJSONObject("module_author")
        val dyn = modules.getJSONObject("module_dynamic")
        val name = str(author, "name")
        val uid = author.optString("mid", "")
        val ts = if (author.has("pub_ts")) author.optLong("pub_ts", 0) else item.optLong("time", 0)

        var content = str(dyn.optJSONObject("desc"), "text")
        val pictures = ArrayList<String>()
        val major = dyn.optJSONObject("major")
        if (major != null) {
            val opus = major.optJSONObject("opus")
            if (opus != null) {
                val title = str(opus, "title")
                val summary = str(opus.optJSONObject("summary"), "text")
                content = when {
                    title.isNotBlank() && summary.isNotBlank() -> "$title\n$summary"
                    title.isNotBlank() -> title
                    summary.isNotBlank() -> summary
                    else -> content
                }
                opus.optJSONArray("pics")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i)
                        val src = if (p != null) str(p, "url").takeIf { it.isNotBlank() } ?: str(p, "src") else ""
                        if (src.isNotBlank()) pictures.add(src)
                    }
                }
            }
            val draw = major.optJSONObject("draw")
            if (draw != null) {
                draw.optJSONArray("items")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val src = str(arr.optJSONObject(i), "src")
                        if (src.isNotBlank()) pictures.add(src)
                    }
                }
            }
            val archive = major.optJSONObject("archive")
            if (archive != null) {
                val title = str(archive, "title")
                val cover = str(archive, "cover")
                val bvid = str(archive, "bvid")
                val text = if (title.isNotBlank()) "投稿了视频：$title" else ""
                if (text.isNotBlank()) content = if (content.isBlank()) text else "$content\n\n$text"
                if (cover.isNotBlank()) pictures.add(cover)
                if (bvid.isNotBlank()) content += "\nhttps://www.bilibili.com/video/$bvid"
            }
        }
        DynamicInfo(id, uid, name, content.trim(), pictures, ts)
    } catch (e: Exception) {
        Utils.log("BiliParser: v1 detail parse failed: ${e.message}")
        null
    }

    private fun parseDetailV1(id: String, raw: String): DynamicInfo? {
        return try {
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 0) {
                null
            } else {
                parseV1Detail(id, root.getJSONObject("data"))
            }
        } catch (e: Exception) {
            Utils.log("BiliParser: detail v1 json failed: ${e.message}")
            null
        }
    }

    /** 旧版 dynamic_svr：desc 里的用户信息 + card（JSON 字符串）按 type 解析。 */
    private fun parseDynamicSvr(id: String, raw: String): DynamicInfo? {
        return try {
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 0) return null
            val card = root.getJSONObject("data").getJSONObject("card")
            val desc = card.getJSONObject("desc")
            val info = desc.optJSONObject("user_profile")?.optJSONObject("info")
            val uid = if (info != null) info.optString("uid", "") else ""
            val name = if (info != null) str(info, "uname") else ""
            val ts = desc.optLong("timestamp", 0)
            val type = desc.optInt("type", 0)

            val cardObj = runCatching { JSONObject(str(card, "card")) }.getOrNull()
            if (cardObj == null) return DynamicInfo(id, uid, name, "", emptyList(), ts)

            val pictures = ArrayList<String>()
            var content = ""
            when {
                type == 8 -> {
                    val dynamicText = str(cardObj, "dynamic")
                    val title = str(cardObj, "title")
                    val pic = str(cardObj, "pic")
                    content = if (dynamicText.isNotBlank()) "$dynamicText\n\n视频投稿：$title" else "投稿了视频：$title"
                    if (pic.isNotBlank()) pictures.add(pic)
                }
                type == 64 -> {
                    val title = str(cardObj, "title")
                    val summary = str(cardObj, "summary")
                    content = when {
                        title.isNotBlank() && summary.isNotBlank() -> "标题：$title\n内容：$summary"
                        title.isNotBlank() -> "标题：$title"
                        summary.isNotBlank() -> summary
                        else -> ""
                    }
                    cardObj.optJSONArray("origin_image_urls")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val u = arr.optString(i, "")
                            if (u.isNotBlank() && u != "null") pictures.add(u)
                        }
                    }
                }
                else -> {
                    val item = cardObj.optJSONObject("item")
                    if (item != null) {
                        content = str(item, "description")
                        val title = str(item, "title").takeIf { it.isNotBlank() }
                            ?: str(item, "text").takeIf { it.isNotBlank() }
                            ?: str(item, "content").takeIf { it.isNotBlank() }
                        if (title?.isNotBlank() == true) {
                            content = if (content.isNotBlank() && content != title) "标题：$title\n内容：$content" else "标题：$title"
                        }
                        item.optJSONArray("pictures")?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val src = str(arr.optJSONObject(i), "img_src")
                                if (src.isNotBlank()) pictures.add(src)
                            }
                        }
                    }
                }
            }
            DynamicInfo(id, uid, name, content.trim(), pictures, ts)
        } catch (e: Exception) {
            Utils.log("BiliParser: dynamic_svr parse failed: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ 专栏

    data class ArticleInfo(
        val id: String,
        val author: String,
        val title: String,
        val summary: String,
        val cover: String?,
    ) {
        fun webUrl(): String = "https://www.bilibili.com/read/cv$id"
    }

    fun fetchArticle(id: String, callback: (ArticleInfo?) -> Unit) {
        getJson(
            "https://api.bilibili.com/x/article/viewinfo?id=$id&mobi_app=pc&from=web"
        ) { raw ->
            val info = raw?.let { parseArticle(id, it) }
            callback(info)
        }
    }

    private fun parseArticle(id: String, raw: String): ArticleInfo? {
        return try {
            val root = JSONObject(raw)
            if (root.optInt("code", -1) != 0) {
                null
            } else {
                val d = root.getJSONObject("data")
                val cover = d.optJSONArray("origin_image_urls")?.let { arr ->
                    if (arr.length() > 0) arr.optString(0, "").takeIf { it.isNotBlank() && it != "null" } else null
                } ?: str(d, "banner_url").takeIf { it.isNotBlank() }
                val summary = str(d, "summary").takeIf { it.isNotBlank() } ?: str(d, "description")
                ArticleInfo(
                    id = id,
                    author = str(d, "author_name").ifBlank { "未知作者" },
                    title = str(d, "title"),
                    summary = summary,
                    cover = cover,
                )
            }
        } catch (e: Exception) {
            Utils.log("BiliParser: article parse failed: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun str(j: JSONObject?, key: String): String {
        if (j == null) return ""
        val v = j.optString(key, "")
        return if (v == "null") "" else v
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 9; Watch) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private fun getJson(url: String, callback: (String?) -> Unit) = request(url, "application/json", callback)

    private fun getText(url: String, callback: (String?) -> Unit) =
        request(url, "text/html,application/json", callback)

    private fun request(url: String, accept: String, callback: (String?) -> Unit) {
        io.execute {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Referer", "https://www.bilibili.com/")
                    setRequestProperty("Accept", accept)
                }
                val body = if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val out = ByteArrayOutputStream()
                    conn.inputStream.use { it.copyTo(out) }
                    String(out.toByteArray(), Charsets.UTF_8)
                } else {
                    Utils.log("BiliParser: HTTP ${conn.responseCode} $url")
                    null
                }
                runOnUi { callback(body) }
            } catch (e: Exception) {
                Utils.log("BiliParser: request failed: ${e.message}")
                runOnUi { callback(null) }
            } finally {
                conn?.disconnect()
            }
        }
    }
}
