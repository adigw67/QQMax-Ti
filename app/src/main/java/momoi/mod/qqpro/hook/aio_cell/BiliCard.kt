package momoi.mod.qqpro.hook.aio_cell

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.BiliDetailActivity
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import momoi.mod.qqpro.warpOnce
import loadPicUrl
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * B站视频卡片：消息文本含 bilibili 链接（b23.tv 短链 / bilibili.com/video/BV.. / av..）时，
 * 在气泡下方渲染视频卡片（封面 + 标题 + 播放/点赞/投币/收藏/评论），点击进入 [BiliDetailActivity]，
 * 详情页可跳转「哔哩终端」客户端或官方客户端。
 *
 * 与 [LinkPreview] 同架构：卡片按 [AIOCellGroupWidget] 缓存，随 RecyclerView 复用更新/隐藏；
 * 视频信息按 ref 缓存，只请求一次。视频信息来自免登录的官方 web 接口。
 */
object BiliCard {
    private val cards = WeakHashMap<AIOCellGroupWidget, Card>()
    private val cache = HashMap<String, VideoInfo?>()

    private val RE_BV = Regex(
        """(?:https?://)?(?:www\.|m\.)?bilibili\.com/video/(BV[0-9A-Za-z]{10})""",
        RegexOption.IGNORE_CASE,
    )
    private val RE_AV = Regex(
        """(?:https?://)?(?:www\.|m\.)?bilibili\.com/video/(av\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val RE_B23 = Regex(
        """(?:https?://)?b23\.tv/([A-Za-z0-9]+)""",
        RegexOption.IGNORE_CASE,
    )
    // 裸 BV 号（小程序 JSON 里常只有 "bvid":"BV1xxxx" 或 pagePath 带 bvid= 参数）。
    private val RE_BARE_BV = Regex(
        """(?<![0-9A-Za-z])BV[0-9A-Za-z]{10}(?![0-9A-Za-z])""",
        RegexOption.IGNORE_CASE,
    )
    private val RE_BVID_PARAM = Regex(
        """bvid=([0-9A-Za-z]{10,})""",
        RegexOption.IGNORE_CASE,
    )

    /** 一个 bilibili 视频引用：bvid / aid / b23 短链，至少一种。 */
    data class BiliRef(val bvid: String?, val aid: Long?, val short: String?) {
        val key: String get() = bvid ?: ("av" + (aid ?: 0L)) ?: ("b23/" + (short ?: ""))
        fun webUrl(): String =
            if (bvid != null) "https://www.bilibili.com/video/$bvid"
            else if (aid != null) "https://www.bilibili.com/video/av$aid"
            else "https://b23.tv/$short"
    }

    /** 从消息文本提取第一个 bilibili 视频链接（BV 优先，其次 av，其次 b23 短链）。 */
    fun extract(text: CharSequence?): BiliRef? {
        val s = text?.toString() ?: return null
        RE_BV.find(s)?.let { return BiliRef(it.groupValues[1], null, null) }
        RE_AV.find(s)?.let { m ->
            val av = m.groupValues[1].removePrefix("av").toLongOrNull()
            if (av != null) return BiliRef(null, av, null)
        }
        RE_B23.find(s)?.let { return BiliRef(null, null, it.groupValues[1]) }
        RE_BVID_PARAM.find(s)?.let { m ->
            val bv = m.groupValues[1].takeIf { it.startsWith("BV", ignoreCase = true) }
            if (bv != null) return BiliRef(bv.uppercase(), null, null)
        }
        RE_BARE_BV.find(s)?.let { return BiliRef(it.value.uppercase(), null, null) }
        return null
    }

    /** 卡片数据：bilibili view 接口返回的信息（tags 单独接口补充）。 */
    data class VideoInfo(
        val bvid: String,
        val aid: Long,
        val title: String,
        val desc: String,
        val pic: String,
        val pubdate: Long,
        val duration: Int,
        val owner: String,
        val tname: String,
        val view: Long,
        val danmaku: Long,
        val reply: Long,
        val favorite: Long,
        val coin: Long,
        val like: Long,
        val share: Long,
        val tags: List<String>,
    ) {
        fun webUrl(): String = "https://www.bilibili.com/video/$bvid"
        fun durationText(): String {
            val h = duration / 3600
            val m = (duration % 3600) / 60
            val s = duration % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%d:%02d".format(m, s)
        }
        fun pubdateText(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(pubdate * 1000))
    }

    /** 数字格式化：1.2万 / 3.4亿。 */
    fun fmt(n: Long): String = when {
        n >= 100000000 -> "%.2f亿".format(n / 1e8)
        n >= 10000 -> "%.1f万".format(n / 1e4)
        else -> n.toString()
    }

    /** 小程序/链接检测：先看气泡文本，再看消息元素（struct XML / ark JSON / text）。 */
    fun refOf(content: TextView?, msg: MsgRecordEx?): BiliRef? {
        if (!Settings.biliCard.value) return null
        extract(content?.text)?.let { return it }
        return msg?.let { extractFromRecord(it) }
    }

    /** 从消息元素里提取 bilibili 链接（小程序分享：struct xmlContent / ark bytesData）。 */
    fun extractFromRecord(msg: MsgRecordEx): BiliRef? {
        runCatching { msg.elements }.getOrNull()?.forEach { el ->
            runCatching { el.structMsgElement?.xmlContent }.getOrNull()?.let {
                extract(it)?.let { r -> return r }
                extract(urldecode(it))?.let { r -> return r }
            }
            runCatching { el.arkElement?.bytesData }.getOrNull()?.let {
                extract(it)?.let { r -> return r }
                extract(urldecode(it))?.let { r -> return r }
            }
            runCatching { el.arkElement?.linkInfo?.desc }.getOrNull()?.let {
                extract(it)?.let { r -> return r }
            }
            runCatching { el.arkElement?.linkInfo?.title }.getOrNull()?.let {
                extract(it)?.let { r -> return r }
            }
            runCatching { el.textElement?.content }.getOrNull()?.let {
                extract(it)?.let { r -> return r }
            }
        }
        return null
    }

    /** 简单 URL 反转义：%XX → 字符（小程序 JSON 里常见 %2F %3A 编码）。 */
    private fun urldecode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrDefault(s)

    /** 绑定消息单元格并显示卡片（调用方已判定 [ref] 非空）。 */
    fun bind(widget: AIOCellGroupWidget, ref: BiliRef) {
        val content = widget.getContentWidget<View>() as? TextView
        if (content == null) {
            cards[widget]?.root?.visibility = View.GONE
            return
        }
        val card = cards.getOrPut(widget) {
            val c = Card(content.context)
            val warp = content.warpOnce()
            warp.addView(c.root, LinearLayout.LayoutParams(FILL, WRAP).apply {
                topMargin = (4 * content.context.resources.displayMetrics.density).toInt()
            })
            c
        }
        (content.layoutParams as? LinearLayout.LayoutParams)?.also {
            it.width = WRAP
            it.height = WRAP
            it.weight = 0f
        } ?: run { content.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP) }
        card.root.visibility = View.VISIBLE
        card.show(ref)
    }

    fun hide(widget: AIOCellGroupWidget) {
        cards[widget]?.root?.visibility = View.GONE
    }

    /** 异步获取视频信息（后台线程回调；失败回调 null）。 */
    fun fetchInfo(ref: BiliRef, callback: (VideoInfo?) -> Unit) {
        resolveBvid(ref) { resolved ->
            if (resolved == null) { callback(null); return@resolveBvid }
            getJson(
                if (resolved.bvid != null) {
                    "https://api.bilibili.com/x/web-interface/view?bvid=${resolved.bvid}"
                } else {
                    "https://api.bilibili.com/x/web-interface/view?aid=${resolved.aid}"
                }
            ) { raw ->
                val base = raw?.let { parseView(it) }
                if (base == null) { callback(null); return@getJson }
                val bvid = base.bvid
                getJson("https://api.bilibili.com/x/tag/archive/tags?bvid=$bvid") { tagRaw ->
                    val tags = parseTags(tagRaw)
                    callback(base.copy(tags = tags))
                }
            }
        }
    }

    /** b23 短链 → 解析出真实 bvid；BV/av 直接返回。 */
    private fun resolveBvid(ref: BiliRef, callback: (BiliRef?) -> Unit) {
        if (ref.bvid != null || ref.aid != null) { callback(ref); return }
        val code = ref.short ?: return callback(null)
        thread {
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
                val bv = RE_BV.find(finalUrl)?.groupValues?.get(1)
                runOnUi { callback(if (bv != null) BiliRef(bv, null, null) else null) }
            } catch (e: Exception) {
                Utils.log("BiliCard: b23 resolve failed: ${e.message}")
                runOnUi { callback(null) }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun parseView(raw: String): VideoInfo? = try {
        val d = JSONObject(raw).getJSONObject("data")
        val stat = d.getJSONObject("stat")
        VideoInfo(
            bvid = d.getString("bvid"),
            aid = d.getLong("aid"),
            title = d.optString("title", ""),
            desc = d.optString("desc", ""),
            pic = d.optString("pic", "").replaceFirst("^http:".toRegex(), "https:"),
            pubdate = d.optLong("pubdate", 0),
            duration = d.optInt("duration", 0),
            owner = d.optJSONObject("owner")?.optString("name", "") ?: "",
            tname = d.optString("tname", ""),
            view = stat.optLong("view", 0),
            danmaku = stat.optLong("danmaku", 0),
            reply = stat.optLong("reply", 0),
            favorite = stat.optLong("favorite", 0),
            coin = stat.optLong("coin", 0),
            like = stat.optLong("like", 0),
            share = stat.optLong("share", 0),
            tags = emptyList(),
        )
    } catch (e: Exception) {
        Utils.log("BiliCard: view parse failed: ${e.message}")
        null
    }

    private fun parseTags(raw: String?): List<String> = try {
        val arr = JSONObject(raw ?: return emptyList()).optJSONArray("data") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("tag_name", "")?.takeIf { it.isNotBlank() }
        }.take(6)
    } catch (e: Exception) {
        emptyList()
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 9; Watch) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private fun getJson(url: String, callback: (String?) -> Unit) {
        thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Referer", "https://www.bilibili.com/")
                    setRequestProperty("Accept", "application/json")
                }
                val body = if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val out = ByteArrayOutputStream()
                    conn.inputStream.use { it.copyTo(out) }
                    String(out.toByteArray(), Charsets.UTF_8)
                } else {
                    Utils.log("BiliCard: HTTP ${conn.responseCode}")
                    null
                }
                runOnUi { callback(body) }
            } catch (e: Exception) {
                Utils.log("BiliCard: request failed: ${e.message}")
                runOnUi { callback(null) }
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** 打开客户端：优先「哔哩终端」，其次官方 bilibili，最后系统浏览器。 */
    fun openClient(ctx: Context, info: VideoInfo) {
        // 哔哩终端：GetIntentActivity 支持 type=video_bv + content=BV 直开（比 URL 解析更稳，
        // 且本 ROM 上 URL 入口会崩——它把 BV 当 aid 解析抛异常）。
        val biliClient = Intent().apply {
            setClassName("com.RobinNotBad.BiliClient", "com.RobinNotBad.BiliClient.activity.GetIntentActivity")
            putExtra("type", "video_bv")
            putExtra("content", info.bvid)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (biliClient.resolveActivity(ctx.packageManager) != null) {
            Utils.log("BiliCard: 打开哔哩终端 ${info.bvid}")
            runCatching { ctx.startActivity(biliClient) }
                .onFailure { Utils.log("BiliCard: client open failed: $it") }
            return
        }
        val official = Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://video/${info.bvid}")).apply {
            setPackage("tv.danmaku.bili")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (official.resolveActivity(ctx.packageManager) != null) {
            Utils.log("BiliCard: 打开官方客户端 ${info.bvid}")
            runCatching { ctx.startActivity(official) }.onFailure { Utils.log("BiliCard: official open failed: $it") }
            return
        }
        Utils.log("BiliCard: 无客户端，走浏览器 ${info.webUrl()}")
        Utils.openUrl(info.webUrl())
    }

    /** 卡片视图（紧凑：封面 + 标题 + 关键数据）。 */
    private class Card(ctx: Context) {
        val root: LinearLayout
        private val cover: ImageView
        private val title: TextView
        private val meta: TextView
        private var boundKey: String? = null
        private var boundRef: BiliRef? = null

        init {
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = GradientDrawable().apply {
                    setColor(0x33_000000)
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener {
                    val ref = boundRef ?: return@setOnClickListener
                    val intent = Intent(ctx, BiliDetailActivity::class.java).apply {
                        putExtra(BiliDetailActivity.EXTRA_BVID, ref.bvid)
                        putExtra(BiliDetailActivity.EXTRA_AID, ref.aid ?: -1L)
                        putExtra(BiliDetailActivity.EXTRA_SHORT, ref.short)
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(intent) }
                        .onFailure { Utils.log("BiliCard: detail open failed: $it") }
                }
            }
            cover = ImageView(ctx).apply {
                maxHeight = dp(150)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            root.addView(cover, LinearLayout.LayoutParams(FILL, WRAP).apply { bottomMargin = dp(4) })

            title = TextView(ctx).apply {
                textSize = 12f
                setTextColor(M3.onSurface)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            root.addView(title, LinearLayout.LayoutParams(FILL, WRAP))

            meta = TextView(ctx).apply {
                textSize = 10.5f
                setTextColor(M3.onSurfaceVariant)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.LEFT
            }
            root.addView(meta, LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = dp(2) })
        }

        fun show(ref: BiliRef) {
            if (boundKey == ref.key) return
            boundKey = ref.key
            boundRef = ref
            cover.visibility = View.GONE
            title.text = "B站视频解析中…"
            title.visibility = View.VISIBLE
            meta.visibility = View.GONE

            cache[ref.key]?.let { render(it); return }
            if (cache.containsKey(ref.key)) { render(null); return }

            BiliCard.fetchInfo(ref) { info ->
                cache[ref.key] = info
                runOnUi { if (boundKey == ref.key) render(info) }
            }
        }

        private fun render(info: VideoInfo?) {
            if (info == null) {
                root.visibility = View.GONE
                return
            }
            cover.visibility = View.VISIBLE
            cover.loadPicUrl(info.pic, cacheFileName = "bili${info.bvid}")
            title.text = info.title
            meta.text = buildString {
                append("播放 ${fmt(info.view)} · 点赞 ${fmt(info.like)}")
                append("\n投币 ${fmt(info.coin)} · 收藏 ${fmt(info.favorite)} · 评论 ${fmt(info.reply)}")
            }
            meta.visibility = View.VISIBLE
        }
    }
}
