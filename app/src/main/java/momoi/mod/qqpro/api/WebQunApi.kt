package momoi.mod.qqpro.api

import android.text.Html
import com.tencent.qqnt.account.login.api.ITicketRuntimeService
import mqq.app.MobileQQ
import mqq.app.AppRuntime
import mqq.manager.TicketManager
import momoi.mod.qqpro.util.ThreadManager
import momoi.mod.qqpro.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * qun.qq.com 网页接口客户端，用手表自己的登录票据（p_skey/skey）直接调网页版接口。
 *
 * 背景：手表内核的群公告只给“生效中”的几条；机器人 markdown 消息在手表上被服务器按受限
 * 客户端下发占位文本。网页版接口（web.qun.qq.com / qun.qq.com）返回完整内容——含机器人
 * 发布的 markdown 公告和完整公告历史；群精华消息也只在网页版有。本机 NT 登录后持有
 * p_skey/skey（mqq TicketManager / NT ITicketRuntimeService），因此手表可以带着自己的
 * 票据直接拉取，不需要 PC 桥接。
 *
 * 已在桌面同一账号实测：
 *   GET web.qun.qq.com/cgi-bin/announce/get_t_list?bkn=&qid=&ft=23&s=-1&n=   → 完整公告历史
 *   GET qun.qq.com/cgi-bin/group_digest/digest_list?bkn=&group_code=&page_start=0&page_limit=
 *   → 精华消息（文字/图片，被加精的机器人消息也在其中）
 * 两个接口都会校验 bkn（skey 的 hash），拿不到 skey 时返回 null（失败）。
 */
object WebQunApi {

    class AnnounceItem(
        val feedId: String,
        val publisher: Long,
        val time: Long,
        val text: String,
        val imageIds: List<String>,
    )

    class EssenceItem(
        val senderUin: Long,
        val senderNick: String,
        val time: Long,
        val text: String,
        val imageUrl: String?,
    )

    private const val QUN_DOMAIN = "qun.qq.com"
    private const val UA = "Mozilla/5.0 (compatible; MSIE 10.0; Windows NT 6.2)"

    /** 拉完整公告历史；失败（无票据/网络/解析错误）回调 null，成功但无公告回调空表。 */
    fun fetchAnnouncements(groupCode: Long, callback: (List<AnnounceItem>?) -> Unit) {
        thread {
            val s = session()
            if (s == null) {
                Utils.log("WebQun: fetchAnnouncements no session")
                deliver(callback, null)
                return@thread
            }
            val url = "https://web.qun.qq.com/cgi-bin/announce/get_t_list" +
                    "?bkn=${s.bkn}&qid=$groupCode&ft=23&s=-1&n=50"
            val body = getText(url, s.cookie)
            val items = parseAnnouncements(body)
            Utils.log("WebQun: announcements gc=$groupCode count=${items?.size} head=${body.take(100)}")
            deliver(callback, items)
        }
    }

    /** 拉群精华消息；失败回调 null，成功但无精华回调空表。 */
    fun fetchEssence(groupCode: Long, callback: (List<EssenceItem>?) -> Unit) {
        thread {
            val s = session()
            if (s == null) {
                Utils.log("WebQun: fetchEssence no session")
                deliver(callback, null)
                return@thread
            }
            val url = "https://qun.qq.com/cgi-bin/group_digest/digest_list" +
                    "?bkn=${s.bkn}&group_code=$groupCode&page_start=0&page_limit=50"
            val body = getText(url, s.cookie)
            val items = parseEssence(body)
            Utils.log("WebQun: essence gc=$groupCode count=${items?.size} head=${body.take(100)}")
            deliver(callback, items)
        }
    }

    // ---- 票据 ----

    private class Session(val cookie: String, val bkn: Long)

    /**
     * 取本机会话：p_skey(qun.qq.com) + skey，并算出 bkn。两条取票路径都试：
     * NT 的 ITicketRuntimeService（getLocalPskey）与经典 mqq TicketManager（MGR_TICKET=7）。
     */
    private fun session(): Session? {
        val app = MobileQQ.sMobileQQ?.peekAppRuntime() ?: run {
            Utils.log("WebQun: no app runtime")
            return null
        }
        val uin = app.currentUin?.takeIf { it.isNotBlank() }
            ?: app.account?.takeIf { it.isNotBlank() }
            ?: run {
                Utils.log("WebQun: no uin")
                return null
            }

        val tm = findTicketManager(app)
        var skey: String? = null
        // SkeyInjectManager 在手表的 banSkeyAccess 下会把 getSkey/getRealSkey 换成假 skey
        // （实测 10 字符的 MeRkm2… 即假票，bkn 必然被拒）。直接读 wlogin 的 SKEY 票（type=16）
        // 的 _sig 绕开注入。注意内部实现用 GetLocalTicket(uin, 4096, 16)（flag=4096 才从本地
        // 存储取），接口层的 getLocalTicket 只传 flag=16，读不到，所以走反射拿 helper 直调。
        runCatching {
            val svc = app.getRuntimeService(ITicketRuntimeService::class.java, "")
            if (svc != null) {
                val helper = svc.javaClass.getMethod("getMWtLoginHelper").invoke(svc)
                val ticket = helper?.javaClass
                    ?.getMethod("GetLocalTicket", String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    ?.invoke(helper, uin, 4096L, 16)
                val sig = ticket?.javaClass?.getField("_sig")?.get(ticket) as? ByteArray
                if (sig != null && sig.isNotEmpty()) {
                    skey = String(sig)
                    Utils.log("WebQun: wlogin SKEY sig len=${sig.size} head=${skey?.take(8)}")
                } else {
                    Utils.log("WebQun: wlogin SKEY ticket sig empty/null ticket=${ticket != null}")
                }
            }
        }.onFailure { Utils.log("WebQun: wlogin skey read err: ${it.message}") }
        runCatching {
            skey = skey ?: tm?.getRealSkey(uin)?.takeIf { it.isNotBlank() }
                ?: tm?.getSkey(uin)?.takeIf { it.isNotBlank() }
        }.onFailure { Utils.log("WebQun: skey err: ${it.message}") }

        // 收集所有能拿到的 p_skey 候选（不同来源/域名），逐一打印；能取到 skey 就一并打印 bkn。
        val pskeyCandidates = LinkedHashMap<String, String?>()
        runCatching {
            val svc = app.getRuntimeService(ITicketRuntimeService::class.java, "")
            if (svc != null) {
                for (domain in listOf("qun.qq.com", "web.qun.qq.com", "")) {
                    pskeyCandidates["ntService[$domain]"] =
                        svc.getLocalPskey(uin, domain)?.takeIf { it.isNotBlank() }
                }
            }
        }.onFailure { Utils.log("WebQun: ticket runtime service err: ${it.message}") }
        runCatching {
            if (tm != null) {
                for (domain in listOf("qun.qq.com", "web.qun.qq.com")) {
                    pskeyCandidates["ticketMgr[$domain]"] =
                        tm.getPskey(uin, domain)?.takeIf { it.isNotBlank() }
                }
            }
        }.onFailure { Utils.log("WebQun: ticket manager pskey err: ${it.message}") }

        val bknVal = skey?.let { bkn(it) }
        Utils.log("WebQun: uin=$uin skey=${skey ?: "null"} bkn=${bknVal}")
        pskeyCandidates.forEach { (k, v) ->
            Utils.log("WebQun: pskeyCand $k = ${v ?: "null"}")
        }

        // 选第一个非空 p_skey（qun.qq.com 优先）与 skey 组成会话。
        val pskey = pskeyCandidates.entries.firstOrNull { it.key.contains("qun.qq.com") && !it.value.isNullOrBlank() }
            ?.value
            ?: pskeyCandidates.values.firstOrNull { !it.isNullOrBlank() }
        if (pskey.isNullOrBlank() || skey.isNullOrBlank() || bknVal == null) return null
        return Session(
            cookie = "uin=o$uin; p_skey=$pskey; p_uin=o$uin",
            bkn = bknVal,
        )
    }

    /**
     * 在 mqq 的 manager 注册表里找到 TicketManager。管理器 ID 随版本/混淆变化（MGR_TICKET 不
     * 一定是 7），这里直接按接口类型扫一遍，拿到就返回。
     */
    private fun findTicketManager(app: AppRuntime): TicketManager? {
        for (id in 0..60) {
            val m = runCatching { app.getManager(id) }.getOrNull() ?: continue
            if (m is TicketManager) {
                Utils.log("WebQun: TicketManager found id=$id")
                return m
            }
        }
        Utils.log("WebQun: TicketManager not found in manager registry")
        return null
    }

    /** 标准 bkn 算法（与 QQ 网页端一致）：hash=5381，逐字符 (hash*33+code) & 0x7fffffff。 */
    private fun bkn(skey: String): Long {
        var hash = 5381L
        for (ch in skey) {
            hash = ((hash shl 5) + hash + ch.code) and 0x7fffffffL
        }
        return hash
    }

    // ---- 请求 ----

    private fun getText(url: String, cookie: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("Cookie", cookie)
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                // API 19 默认不走 TLS1.2；换用强制 TLS1.2 的 socket factory，否则 stgw 握手失败。
                if (this is HttpsURLConnection) {
                    TlsUpgrade.enableTls12(this)
                }
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                Utils.log("WebQun: http=$code url=${url.take(90)}")
                "HTTP error: $code"
            }
        } catch (e: Throwable) {
            Utils.log("WebQun: request failed: ${e.message} url=${url.take(80)}")
            "Error: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    // ---- 解析 ----

    private fun parseAnnouncements(body: String): List<AnnounceItem>? {
        if (body.isBlank() || body.startsWith("HTTP error") || body.startsWith("Error:")) {
            Utils.log("WebQun: announcements bad body: ${body.take(80)}")
            return null
        }
        return runCatching {
            val root = JSONObject(body)
            if (root.optInt("ec", -1) != 0) {
                Utils.log("WebQun: announcements ec=${root.optInt("ec")} em=${root.optString("em")}")
                return@runCatching null
            }
            val feeds = root.optJSONArray("feeds") ?: return@runCatching emptyList()
            val out = ArrayList<AnnounceItem>(feeds.length())
            for (i in 0 until feeds.length()) {
                val f = feeds.optJSONObject(i) ?: continue
                val msg = f.optJSONObject("msg") ?: continue
                val text = msg.optString("text").ifBlank { msg.optString("title") }
                val pics = msg.optJSONArray("pics") ?: JSONArray()
                val ids = ArrayList<String>(pics.length())
                for (j in 0 until pics.length()) {
                    pics.optJSONObject(j)?.optString("id")?.takeIf { it.isNotBlank() }?.let(ids::add)
                }
                out.add(
                    AnnounceItem(
                        feedId = f.optString("fid"),
                        publisher = f.optLong("u"),
                        time = f.optLong("pubt"),
                        text = htmlDecode(text),
                        imageIds = ids,
                    )
                )
            }
            out.sortedByDescending { it.time }
        }.getOrElse {
            Utils.log("WebQun: parse announcements failed: $it")
            null
        }
    }

    private fun parseEssence(body: String): List<EssenceItem>? {
        if (body.isBlank() || body.startsWith("HTTP error") || body.startsWith("Error:")) {
            Utils.log("WebQun: essence bad body: ${body.take(80)}")
            return null
        }
        return runCatching {
            val root = JSONObject(body)
            if (root.optInt("retcode", -1) != 0) {
                Utils.log("WebQun: essence retcode=${root.optInt("retcode")} ${root.optString("retmsg")}")
                return@runCatching null
            }
            val data = root.optJSONObject("data") ?: return@runCatching emptyList()
            val list = data.optJSONArray("msg_list") ?: return@runCatching emptyList()
            val out = ArrayList<EssenceItem>(list.length())
            for (i in 0 until list.length()) {
                val m = list.optJSONObject(i) ?: continue
                val content = m.optJSONArray("msg_content") ?: continue
                val sb = StringBuilder()
                var imageUrl: String? = null
                for (j in 0 until content.length()) {
                    val c = content.optJSONObject(j) ?: continue
                    when (c.optInt("msg_type")) {
                        3 -> imageUrl = c.optString("image_url").takeIf { it.isNotBlank() } ?: imageUrl
                        else -> c.optString("text").takeIf { it.isNotBlank() }?.let { sb.append(it) }
                    }
                }
                out.add(
                    EssenceItem(
                        senderUin = m.optLong("sender_uin"),
                        senderNick = m.optString("sender_nick").ifBlank { m.optLong("sender_uin").toString() },
                        time = m.optLong("sender_time"),
                        text = htmlDecode(sb.toString()),
                        imageUrl = imageUrl,
                    )
                )
            }
            out.sortedByDescending { it.time }
        }.getOrElse {
            Utils.log("WebQun: parse essence failed: $it")
            null
        }
    }

    /** 公告/精华正文里的 HTML 实体（&#10; 换行、&nbsp; 等）转成可读文本。 */
    private fun htmlDecode(s: String): String {
        if (s.isEmpty()) return s
        return runCatching { Html.fromHtml(s).toString() }.getOrDefault(s)
    }

    private fun <T> deliver(callback: (T) -> Unit, value: T) {
        ThreadManager.runOnUiThread(Runnable { runCatching { callback(value) } })
    }
}
