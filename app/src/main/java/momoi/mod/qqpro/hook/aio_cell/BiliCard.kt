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
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
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
 * B站卡片：消息文本/小程序分享里识别到 bilibili 链接时，在气泡下方渲染卡片。
 * 解析层（链接类型、小程序 JSON 结构、动态/专栏接口）在 [BiliParser]，这里只负责
 * 绑定/缓存/渲染：
 *  - 视频 → 封面+标题+数据卡片，点击进 [BiliDetailActivity]；
 *  - 动态/专栏 → 文本卡片（作者+内容），点击进详情页；
 *  - b23.tv 短链 → 先重定向分类，再按类型渲染。
 * 详情页可跳转「哔哩终端」客户端或官方客户端（仅视频）。
 */
object BiliCard {
    private val cards = WeakHashMap<AIOCellGroupWidget, Card>()
    private val cache = HashMap<String, VideoInfo?>()
    private val dynCache = HashMap<String, BiliParser.DynamicInfo?>()
    private val artCache = HashMap<String, BiliParser.ArticleInfo?>()
    private val resolveCache = HashMap<String, BiliParser.Target?>()
    // 按消息 msgId 缓存检测结果：同一消息反复绑定（滚动/复用）时直接命中，不再逐元素扫描。
    private val recordCache = HashMap<Long, BiliParser.Target?>()

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

    /**
     * 小程序/链接检测（主线程同步）：气泡文本 + 消息元素。元素扫描必须留在主线程——
     * 这个手表内核的消息元素在后台线程读取时 arkElement 会返回 null（v33/v34 异步扫描
     * 导致解析失效）。扫描已做硬限界（ark/struct 只取头部 16K、toString 只取 4K），
     * B 站小程序 bytesData 约 1.5K，主线程解析耗时可忽略。
     */
    fun refOf(content: TextView?, msg: MsgRecordEx?): BiliParser.Target? {
        if (!Settings.biliCard.value) return null
        BiliParser.extract(content?.text)?.let { return it }
        msg?.let { extractRecordSync(it) }?.let { return it }
        return null
    }

    /** 主线程同步扫描消息元素（带 msgId 缓存）。 */
    fun extractRecordSync(msg: MsgRecordEx): BiliParser.Target? {
        if (!Settings.biliCard.value) return null
        val msgId = runCatching { msg.msgId }.getOrDefault(0L)
        if (msgId != 0L && recordCache.containsKey(msgId)) return recordCache[msgId]
        val result = scanRecord(msg)
        if (msgId != 0L) {
            if (recordCache.size >= 1500) recordCache.clear()
            recordCache[msgId] = result
        }
        return result
    }

    /**
     * 消息转换钩子（MsgListUtilKt.c）里提前提取：那里的 [msg] 带完整 ark 元素，而单元格
     * 绑定时的 item.d 里 arkElement 会被内核剥成 null（实测 msgType=11 hasArk=false）。
     * 在这里扫描并按 msgId 缓存，绑定检测直接命中。
     */
    fun cacheMsgTarget(msg: MsgRecord) {
        try {
            if (!Settings.biliCard.value) return
            val msgId = runCatching { msg.msgId }.getOrDefault(0L)
            if (msgId == 0L || recordCache.containsKey(msgId)) return
            val target = scanRecord(msg)
            if (recordCache.size >= 1500) recordCache.clear()
            recordCache[msgId] = target
        } catch (t: Throwable) {
            // 转换钩子绝不能因 B 站检测抛异常而破坏消息渲染。
            Utils.log("BiliCard: cacheMsgTarget failed: $t")
        }
    }

    /** 从消息元素里提取 bilibili 链接（小程序分享：ark bytesData / struct xmlContent）。 */
    private fun scanRecord(msg: MsgRecord): BiliParser.Target? {
        runCatching { msg.elements }.getOrNull()?.forEach { el ->
            // BiliURL 结构优先：ark bytesData 是 QQ 小程序卡片的 JSON，按 meta 字段取链接。
            val arkRaw = runCatching { el.arkElement?.bytesData }.getOrNull()
            if (arkRaw != null) {
                // 巨型 ark 载荷（部分小程序卡片携带 base64 预览/大段 JSON）绝不在主线程整段解析：
                // 只取头部 16K 检测，链接几乎总在卡片 meta 字段的头部（BiliParser.extract 已内部
                // 处理 \/ 与 &amp; 转义，这里不再重复 URLDecoder 全量解码）。
                val bounded = if (arkRaw.length > 16384) arkRaw.take(16384) else arkRaw
                BiliParser.extractFromMiniApp(bounded)?.let { r -> return r }
                BiliParser.extract(bounded)?.let { r -> return r }
                // 诊断：ark 数据存在但没命中，打印头部便于定位格式。
                Utils.log("BiliCard: ark 未命中 len=${arkRaw.length} head=${bounded.take(150)}")
            } else if (runCatching { el.elementType }.getOrDefault(-1) == 10) {
                // 诊断：ARKSTRUCT 元素但 arkElement 取出来是 null（手表内核结构差异）。
                Utils.log("BiliCard: ARKSTRUCT元素但arkElement=null type=${runCatching { el.javaClass.simpleName }.getOrNull()} els=${runCatching { msg.elements?.size }.getOrNull()}")
            }
            runCatching { el.structMsgElement?.xmlContent }.getOrNull()?.let {
                val bounded = if (it.length > 16384) it.take(16384) else it
                BiliParser.extract(bounded)?.let { r -> return r }
            }
            // 全量 toString()：兜底覆盖所有元素类型的所有字段（链接可能藏在任意字段里）。
            runCatching { el.toString() }.getOrNull()?.let {
                val bounded = if (it.length > 4096) it.take(4096) else it
                BiliParser.extract(bounded)?.let { r -> return r }
            }
            runCatching { el.arkElement?.linkInfo?.desc }.getOrNull()?.let {
                BiliParser.extract(it)?.let { r -> return r }
            }
            runCatching { el.arkElement?.linkInfo?.title }.getOrNull()?.let {
                BiliParser.extract(it)?.let { r -> return r }
            }
            runCatching { el.textElement?.content }.getOrNull()?.let {
                BiliParser.extract(it)?.let { r -> return r }
            }
            // 全量 toString 兜底只构建一次；超大元素（如巨型 ark 卡片）只扫前 4K，避免拖慢渲染。
            val all = runCatching { el.toString() }.getOrNull().orEmpty()
            if (all.isNotEmpty() &&
                (all.contains("bili", true) || all.contains("b23", true) ||
                    all.contains("bvid", true) || all.contains("qqdocurl", true))
            ) {
                val limited = if (all.length > 4096) all.take(4096) else all
                BiliParser.extract(limited)?.let { r -> return r }
                // 调试：含 bili 提示但没提取出来时打印实际内容便于定位格式。
                if (all.length <= 4096) {
                    Utils.log("BiliCard: 元素含bili提示但未提取: ${all.take(300)}")
                }
            }
        }
        return null
    }

    /** 兜底：从已渲染的小程序卡片视图里收集所有 TextView 文本再匹配一次。 */
    fun extractFromViews(root: View?): BiliParser.Target? {
        // 与 refOf 一致：总开关关闭时整条 B 站链路（含兜底扫描）都不应触发。
        if (!Settings.biliCard.value) return null
        if (root == null) return null
        if (root is TextView) {
            BiliParser.extract(root.text)?.let { return it }
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                extractFromViews(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** 绑定消息单元格并显示卡片（调用方已判定 [target] 非空）。 */
    fun bind(widget: AIOCellGroupWidget, target: BiliParser.Target) {
        val content = widget.getContentWidget<View>() as? TextView
        val card = cards.getOrPut(widget) {
            // 文本气泡把卡片挂在正文下方；小程序/占位等非文本单元格（contentWidget 不是
            // TextView）直接挂到单元格根视图上，保证卡片一定可见。
            val ctx = content?.context ?: widget.context
            val c = Card(ctx)
            val warp: android.view.ViewGroup = content?.warpOnce() ?: run {
                val w = widget as? android.view.ViewGroup
                if (w == null) {
                    cards[widget]?.root?.visibility = View.GONE
                    return
                }
                w
            }
            warp.addView(c.root, LinearLayout.LayoutParams(FILL, WRAP).apply {
                topMargin = (4 * ctx.resources.displayMetrics.density).toInt()
            })
            c
        }
        if (content != null) {
            (content.layoutParams as? LinearLayout.LayoutParams)?.also {
                it.width = WRAP
                it.height = WRAP
                it.weight = 0f
            } ?: run { content.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP) }
        }
        card.root.visibility = View.VISIBLE
        card.show(target)
    }

    fun hide(widget: AIOCellGroupWidget) {
        cards[widget]?.root?.visibility = View.GONE
    }

    /** 异步获取视频信息（后台线程回调；失败回调 null）。 */
    fun fetchInfo(ref: BiliParser.Target.Video, callback: (VideoInfo?) -> Unit) {
        val bvid = ref.bvid
        if (bvid != null) {
            getJson(
                "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
            ) { raw -> finishVideo(raw, bvid, callback) }
            return
        }
        val aid = ref.aid
        if (aid != null) {
            getJson(
                "https://api.bilibili.com/x/web-interface/view?aid=$aid"
            ) { raw -> finishVideo(raw, null, callback) }
            return
        }
        val short = ref.short ?: return callback(null)
        BiliParser.resolveShort(short) { resolved ->
            if (resolved is BiliParser.Target.Video) {
                fetchInfo(resolved, callback)
            } else {
                callback(null)
            }
        }
    }

    private fun finishVideo(raw: String?, fallbackBvid: String?, callback: (VideoInfo?) -> Unit) {
        val base = raw?.let { parseView(it) }
        if (base == null) {
            callback(null)
            return
        }
        val bvid = base.bvid.ifBlank { fallbackBvid }
        if (bvid == null) {
            callback(base)
            return
        }
        getJson("https://api.bilibili.com/x/tag/archive/tags?bvid=$bvid") { tagRaw ->
            val tags = parseTags(tagRaw)
            callback(base.copy(tags = tags))
        }
    }

    private fun parseView(raw: String): VideoInfo? = try {
        val d = JSONObject(raw).getJSONObject("data")
        val stat = d.getJSONObject("stat")
        VideoInfo(
            bvid = d.optString("bvid", ""),
            aid = d.optLong("aid", 0),
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
            arr.optJSONObject(i)?.optString("tag_name", "")?.takeIf { it.isNotBlank() && it != "null" }
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

    /** 按 BV 号直开客户端（不依赖 API）：优先「哔哩终端」，其次官方 bilibili，最后系统浏览器。 */
    fun openClientByBvid(ctx: Context, bvid: String) {
        // 哔哩终端：GetIntentActivity 支持 type=video_bv + content=BV 直开（比 URL 解析更稳，
        // 且本 ROM 上 URL 入口会崩——它把 BV 当 aid 解析抛异常）。
        val biliClient = Intent().apply {
            setClassName("com.RobinNotBad.BiliClient", "com.RobinNotBad.BiliClient.activity.GetIntentActivity")
            putExtra("type", "video_bv")
            putExtra("content", bvid)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (biliClient.resolveActivity(ctx.packageManager) != null) {
            Utils.log("BiliCard: 打开哔哩终端 $bvid")
            runCatching { ctx.startActivity(biliClient) }
                .onFailure { Utils.log("BiliCard: client open failed: $it") }
            return
        }
        val official = Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://video/$bvid")).apply {
            setPackage("tv.danmaku.bili")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (official.resolveActivity(ctx.packageManager) != null) {
            Utils.log("BiliCard: 打开官方客户端 $bvid")
            runCatching { ctx.startActivity(official) }.onFailure { Utils.log("BiliCard: official open failed: $it") }
            return
        }
        Utils.log("BiliCard: 无客户端，走浏览器 https://www.bilibili.com/video/$bvid")
        Utils.openUrl("https://www.bilibili.com/video/$bvid")
    }

    /** 打开客户端：优先「哔哩终端」，其次官方 bilibili，最后系统浏览器。 */
    fun openClient(ctx: Context, info: VideoInfo) {
        openClientByBvid(ctx, info.bvid)
    }

    /** 卡片视图：视频（封面+标题+数据）或动态/专栏（标题+内容文本）。 */
    private class Card(val ctx: Context) {
        val root: LinearLayout
        private val cover: ImageView
        private val title: TextView
        private val meta: TextView
        private var boundKey: String? = null
        private var boundTarget: BiliParser.Target? = null
        // 原始短链（含小程序标题/描述）：解析/拉取失败时回退显示，保证卡片永不死路。
        private var boundShort: BiliParser.Target.ShortLink? = null

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
                setOnClickListener { openDetail() }
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
                maxLines = 6
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.LEFT
            }
            root.addView(meta, LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = dp(2) })
        }

        fun show(target: BiliParser.Target) {
            when (target) {
                is BiliParser.Target.Video -> showVideo(target)
                is BiliParser.Target.ShortLink -> showShort(target)
                is BiliParser.Target.Dynamic -> showDynamic(target)
                is BiliParser.Target.Article -> showArticle(target)
            }
        }

        private fun openDetail() {
            val t = boundTarget ?: return
            val intent = when (t) {
                is BiliParser.Target.Video -> Intent(ctx, BiliDetailActivity::class.java).apply {
                    putExtra(BiliDetailActivity.EXTRA_BVID, t.bvid)
                    putExtra(BiliDetailActivity.EXTRA_AID, t.aid ?: -1L)
                    putExtra(BiliDetailActivity.EXTRA_SHORT, t.short)
                }
                is BiliParser.Target.Dynamic -> Intent(ctx, BiliDetailActivity::class.java).apply {
                    putExtra(BiliDetailActivity.EXTRA_DYNAMIC_ID, t.id)
                }
                is BiliParser.Target.Article -> Intent(ctx, BiliDetailActivity::class.java).apply {
                    putExtra(BiliDetailActivity.EXTRA_ARTICLE_ID, t.id)
                }
                else -> return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
                .onFailure { Utils.log("BiliCard: detail open failed: $it") }
        }

        private fun showLoading(text: String) {
            cover.visibility = View.GONE
            title.text = text
            title.visibility = View.VISIBLE
            meta.visibility = View.GONE
        }

        private fun showFail(text: String) {
            cover.visibility = View.GONE
            title.text = text
            title.visibility = View.VISIBLE
            meta.visibility = View.GONE
        }

        private fun showShort(ref: BiliParser.Target.ShortLink) {
            val key = ref.key
            if (boundKey == key) return
            Utils.log("BiliCard: showShort code=${ref.code} title=${ref.title?.take(20)} desc=${ref.desc?.take(20)}")
            boundKey = key
            boundTarget = ref
            boundShort = ref
            // 小程序卡片自带标题/描述：先直接显示（不依赖网络），后台再解析真实视频信息。
            if (!ref.title.isNullOrBlank() || !ref.desc.isNullOrBlank()) {
                cover.visibility = View.GONE
                title.text = ref.title?.ifBlank { null } ?: "B站视频"
                title.visibility = View.VISIBLE
                meta.text = buildString {
                    if (!ref.title.isNullOrBlank() && !ref.desc.isNullOrBlank() && ref.desc != ref.title) {
                        append(ref.desc)
                    } else if (!ref.desc.isNullOrBlank()) {
                        append(ref.desc)
                    }
                }
                meta.maxLines = 4
                meta.visibility = View.VISIBLE
            } else {
                // 无标题/描述（纯文本链接）：直接显示链接本身，解析失败也保留。
                cover.visibility = View.GONE
                title.text = "B站链接"
                title.visibility = View.VISIBLE
                meta.text = ref.webUrl()
                meta.maxLines = 2
                meta.visibility = View.VISIBLE
            }
            resolveCache[key]?.let { renderResolved(it); return }
            if (resolveCache.containsKey(key)) {
                renderResolved(null)
                return
            }
            BiliParser.resolveShort(ref.code) { resolved ->
                resolveCache[key] = resolved
                runOnUi { if (boundKey == key) renderResolved(resolved) }
            }
        }

        private fun renderResolved(resolved: BiliParser.Target?) {
            when (resolved) {
                is BiliParser.Target.Video -> showVideo(resolved)
                is BiliParser.Target.Dynamic -> showDynamic(resolved)
                is BiliParser.Target.Article -> showArticle(resolved)
                else -> {
                    // 解析失败（无网络/短链失效）：保留标题/描述或链接本身，点击仍可打开。
                    val ref = boundShort
                    if (ref != null) {
                        if (!ref.title.isNullOrBlank() || !ref.desc.isNullOrBlank()) {
                            title.text = ref.title?.ifBlank { null } ?: "B站视频"
                            meta.text = buildString {
                                if (!ref.desc.isNullOrBlank()) append(ref.desc)
                                if (isNotEmpty()) append("\n")
                                append(ref.webUrl())
                            }
                        } else {
                            title.text = "B站链接"
                            meta.text = ref.webUrl()
                        }
                        meta.maxLines = 3
                        meta.visibility = View.VISIBLE
                    } else {
                        showFail("B站链接解析失败")
                    }
                }
            }
        }

        private fun showVideo(ref: BiliParser.Target.Video) {
            if (boundKey == ref.key) return
            boundKey = ref.key
            boundTarget = ref
            showLoading("B站视频解析中…")

            cache[ref.key]?.let { renderVideo(it); return }
            if (cache.containsKey(ref.key)) {
                renderVideo(null)
                return
            }
            BiliCard.fetchInfo(ref) { info ->
                cache[ref.key] = info
                runOnUi { if (boundKey == ref.key) renderVideo(info) }
            }
        }

        private fun renderVideo(info: VideoInfo?) {
            if (info == null) {
                // 视频信息拉取失败（无网络/接口风控）：回退显示链接本身，点击可打开，绝不隐藏。
                val short = boundShort
                val video = boundTarget as? BiliParser.Target.Video
                if (short != null) {
                    cover.visibility = View.GONE
                    title.text = if (!short.title.isNullOrBlank()) short.title else "B站视频"
                    meta.text = buildString {
                        if (!short.desc.isNullOrBlank()) append(short.desc).append("\n")
                        append(short.webUrl())
                    }
                    meta.maxLines = 3
                    meta.visibility = View.VISIBLE
                } else if (video != null) {
                    cover.visibility = View.GONE
                    title.text = "B站视频"
                    title.visibility = View.VISIBLE
                    meta.text = video.webUrl()
                    meta.maxLines = 3
                    meta.visibility = View.VISIBLE
                } else {
                    root.visibility = View.GONE
                }
                return
            }
            cover.visibility = View.VISIBLE
            cover.loadPicUrl(info.pic, cacheFileName = "bili${info.bvid}")
            title.text = info.title
            meta.text = buildString {
                append("播放 ${fmt(info.view)} · 点赞 ${fmt(info.like)}")
                append("\n投币 ${fmt(info.coin)} · 收藏 ${fmt(info.favorite)} · 评论 ${fmt(info.reply)}")
            }
            meta.maxLines = 2
            meta.visibility = View.VISIBLE
        }

        private fun showDynamic(ref: BiliParser.Target.Dynamic) {
            if (boundKey == ref.key) return
            boundKey = ref.key
            boundTarget = ref
            showLoading("B站动态解析中…")

            dynCache[ref.key]?.let { renderDynamic(it); return }
            if (dynCache.containsKey(ref.key)) {
                renderDynamic(null)
                return
            }
            BiliParser.fetchDynamic(ref.id) { info ->
                dynCache[ref.key] = info
                runOnUi { if (boundKey == ref.key) renderDynamic(info) }
            }
        }

        private fun renderDynamic(info: BiliParser.DynamicInfo?) {
            if (info == null) {
                showFail("B站动态解析失败")
                return
            }
            cover.visibility = View.GONE
            title.text = "【B站动态】${info.author.ifBlank { "未知作者" }}"
            meta.text = info.content.ifBlank { "（无文字内容）" }
            meta.maxLines = 6
            meta.visibility = View.VISIBLE
        }

        private fun showArticle(ref: BiliParser.Target.Article) {
            if (boundKey == ref.key) return
            boundKey = ref.key
            boundTarget = ref
            showLoading("B站专栏解析中…")

            artCache[ref.key]?.let { renderArticle(it); return }
            if (artCache.containsKey(ref.key)) {
                renderArticle(null)
                return
            }
            BiliParser.fetchArticle(ref.id) { info ->
                artCache[ref.key] = info
                runOnUi { if (boundKey == ref.key) renderArticle(info) }
            }
        }

        private fun renderArticle(info: BiliParser.ArticleInfo?) {
            if (info == null) {
                showFail("B站专栏解析失败")
                return
            }
            cover.visibility = View.GONE
            title.text = "【B站专栏】${info.author.ifBlank { "未知作者" }}"
            meta.text = buildString {
                if (info.title.isNotBlank()) append("标题：").append(info.title)
                if (info.summary.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("内容：").append(info.summary)
                }
                if (isEmpty()) append("（无内容）")
            }
            meta.maxLines = 6
            meta.visibility = View.VISIBLE
        }
    }
}
