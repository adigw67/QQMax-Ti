package momoi.mod.qqpro.hook.aio_cell

import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.msg.KernelServiceUtil
import momoi.mod.qqpro.enums.ElementType
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.lib.Markdown
import momoi.mod.qqpro.util.Json
import momoi.mod.qqpro.util.ThreadManager
import momoi.mod.qqpro.util.Utils

/**
 * 机器人消息补完：手表内核把部分机器人消息以 msgType=NULL、elements 为空下发，转成
 * “请在手机QQ查看”占位。这里按 msgId 从内核库重新拉完整记录（`IKernelMsgService.getMsgsByMsgId`），
 * 若完整记录带 markdown/文本/ark 内容，就缓存起来供气泡渲染替换占位符。
 *
 * 只拉一次并缓存；拉取结果里仍无内容（内核库也是精简记录）则保持占位，日志可见。
 */
object RobotMsgFetcher {

    private val cacheText = HashMap<Long, String>()
    private val cacheMarkdown = HashMap<Long, String>()
    private val inflight = HashSet<Long>()

    /** 该 msgId 是否已有可渲染内容（markdown 优先，其次纯文本）。 */
    fun renderableFor(msgId: Long): Pair<String?, String?> {
        val md = cacheMarkdown[msgId]
        if (md != null) return null to md
        return cacheText[msgId] to null
    }

    private fun isPlaceholder(s: String?): Boolean =
        s != null && (s.contains("暂不支持") || s.contains("请在手机QQ查看") || s.contains("手机QQ查看"))

    /** 对一条“无可渲染元素”的消息发起按 msgId 补拉（幂等，只在首次触发）。 */
    fun request(msg: MsgRecord) {
        val msgId = msg.msgId
        if (msgId == 0L || msgId in cacheText || msgId in cacheMarkdown) return
        synchronized(inflight) {
            if (!inflight.add(msgId)) return
        }
        val raw = runCatching { KernelServiceUtil.g()?.getMsgService() }.getOrNull()
        if (raw == null) {
            synchronized(inflight) { inflight.remove(msgId) }
            return
        }
        val contact = runCatching { Contact(msg.chatType, msg.peerUid, msg.guildId) }.getOrNull()
        if (contact == null) {
            synchronized(inflight) { inflight.remove(msgId) }
            return
        }
        Utils.log("RobotMsgFetcher: fetch by msgId=$msgId peer=${msg.peerUid} type=${msg.chatType}")
        runCatching {
            raw.getMsgsByMsgId(contact, arrayListOf(msgId)) { code, err, msgs ->
                val rec = msgs?.firstOrNull { it.msgId == msgId }
                Utils.log("RobotMsgFetcher: result code=$code err=$err found=${rec != null} elems=${rec?.elements?.size}")
                if (rec != null) {
                    val textEl = rec.elements?.firstOrNull {
                        it.elementType == ElementType.TEXT && it.textElement?.content?.isNotBlank() == true
                    }
                    val mdEl = rec.elements?.firstOrNull {
                        it.elementType == ElementType.MARKDOWN && it.markdownElement?.content?.isNotBlank() == true
                    }
                    val arkBody = arkCardText(rec)
                    val localText = textEl?.textElement?.content
                    Utils.log("RobotMsgFetcher: local text='${localText?.take(40)}' md=${mdEl != null} ark=${arkBody?.take(40)}")
                    when {
                        mdEl != null -> cacheMarkdown[msgId] = mdEl.markdownElement!!.content
                        // 本地库里的“文本”可能仍是占位符（服务器对受限客户端下发占位文本）——
                        // 这种情况不缓存，改走服务端漫游拉取真实内容。
                        textEl != null && !isPlaceholder(localText) && localText != null ->
                            cacheText[msgId] = localText
                        arkBody != null -> cacheText[msgId] = arkBody
                        else -> {}
                    }
                    if (cacheMarkdown.containsKey(msgId) || cacheText.containsKey(msgId)) {
                        Utils.log("RobotMsgFetcher: cached msgId=$msgId md=${cacheMarkdown.containsKey(msgId)} text=${cacheText.containsKey(msgId)}")
                        synchronized(inflight) { inflight.remove(msgId) }
                        patchVisible(msgId)
                        return@getMsgsByMsgId
                    }
                }
                // 本地库没有真实内容（占位文本）→ 从服务端漫游拉取（getMsgsBySeqAndCount, isRoamMsg=true）。
                fetchRoam(msg, msgId)
            }
        }.onFailure {
            Utils.log("RobotMsgFetcher: fetch threw: ${it.message}")
            synchronized(inflight) { inflight.remove(msgId) }
        }
    }

    /** 按 msgSeq 从服务端漫游拉消息，绕开本地库里的占位文本。 */
    private fun fetchRoam(msg: MsgRecord, msgId: Long) {
        val raw = runCatching { KernelServiceUtil.g()?.getMsgService() }.getOrNull() ?: return
        val contact = runCatching { Contact(msg.chatType, msg.peerUid, msg.guildId) }.getOrNull() ?: return
        val seq = msg.msgSeq
        Utils.log("RobotMsgFetcher: roam fetch seq=$seq msgId=$msgId")
        runCatching {
            raw.getMsgsBySeqAndCount(contact, seq, 1, true, false) { code, err, msgs ->
                val rec = msgs?.firstOrNull()
                Utils.log("RobotMsgFetcher: roam result code=$code err=$err found=${rec != null} elems=${rec?.elements?.size}")
                if (rec != null) {
                    val textEl = rec.elements?.firstOrNull {
                        it.elementType == ElementType.TEXT && it.textElement?.content?.isNotBlank() == true
                    }
                    val mdEl = rec.elements?.firstOrNull {
                        it.elementType == ElementType.MARKDOWN && it.markdownElement?.content?.isNotBlank() == true
                    }
                    val arkBody = arkCardText(rec)
                    val txt = textEl?.textElement?.content
                    Utils.log("RobotMsgFetcher: roam text='${txt?.take(50)}' md=${mdEl != null} ark=${arkBody?.take(50)}")
                    val cached = when {
                        mdEl != null -> { cacheMarkdown[msgId] = mdEl.markdownElement!!.content; true }
                        textEl != null && !isPlaceholder(txt) && txt != null -> { cacheText[msgId] = txt; true }
                        arkBody != null -> { cacheText[msgId] = arkBody; true }
                        else -> false
                    }
                    if (cached) {
                        Utils.log("RobotMsgFetcher: roam cached msgId=$msgId")
                        patchVisible(msgId)
                    }
                }
                synchronized(inflight) { inflight.remove(msgId) }
            }
        }.onFailure {
            Utils.log("RobotMsgFetcher: roam fetch threw: ${it.message}")
            synchronized(inflight) { inflight.remove(msgId) }
        }
    }

    /** ark 卡片 → 可读文本（与 renderBotText 的 arkCardText 同一套抽取）。 */
    private fun arkCardText(msg: MsgRecord): String? = runCatching {
        val ark = msg.elements.firstOrNull { it.arkElement != null }?.arkElement ?: return null
        val json = Json(ark.bytesData)
        val prompt = json.str("prompt")?.takeIf { it.isNotBlank() }
        val meta = json.json("meta") ?: return prompt
        fun decodeB64(s: String?): String? = s?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrNull() }
        fun join(vararg parts: String?): String? =
            parts.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                .takeIf { it.isNotBlank() }
        val rich = when (json.str("app")) {
            "com.tencent.mannounce" -> {
                val a = meta.json("mannounce") ?: meta.keys.firstOrNull()?.let { meta.json(it) }
                join(decodeB64(a?.str("title")), decodeB64(a?.str("text")))
            }
            "com.tencent.contact.lua" ->
                meta.json("contact")?.let { join(it.str("nickname"), it.str("contact")) }
            "com.tencent.map" ->
                meta.json("Location.Search")?.let { join(it.str("name"), it.str("address")) }
            else -> meta.keys.firstOrNull()?.let { meta.json(it) }?.let { join(it.str("title"), it.str("desc")) }
        }
        rich ?: prompt
    }.getOrNull()

    /**
     * 拉取成功后，若该消息当前可见，直接把占位符文本替换成渲染结果（等下一次 bind 也来得及，
     * 但主动补一次体验更好，思路同 CurrentMsgList.markRecalledVisible）。
     */
    private fun patchVisible(msgId: Long) {
        ThreadManager.runOnUiThread({
            runCatching {
                val rv = CurrentMsgList.vb.H
                val n = rv.childCount
                val live = CurrentMsgList.uiOp?.m() ?: return@runCatching
                for (i in 0 until n) {
                    val child = rv.getChildAt(i) ?: continue
                    val pos = rv.getChildAdapterPosition(child)
                    if (pos < 0) continue
                    val item = live.getOrNull(pos) as? com.tencent.watch.aio_impl.data.WatchAIOMsgItem ?: continue
                    if (item.d.msgId != msgId) continue
                    val (text, md) = renderableFor(msgId)
                    val tv = child.findTextView() ?: continue
                    val rendered = when {
                        md != null -> Markdown.toSpannable(md).takeIf { it.isNotEmpty() }
                        text != null -> text
                        else -> null
                    }
                    if (rendered != null) tv.text = rendered
                }
            }.onFailure { Utils.log("RobotMsgFetcher patchVisible failed: $it") }
        })
    }

    private fun android.view.View.findTextView(): TextView? {
        if (this is TextView) return this
        if (this is android.view.ViewGroup) {
            for (i in 0 until childCount) {
                getChildAt(i).findTextView()?.let { return it }
            }
        }
        return null
    }
}
