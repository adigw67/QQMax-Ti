package momoi.mod.qqpro.hook.style

import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ext.MsgListUtilKt
import com.tencent.watch.aio_impl.ui.cell.UnSupportWatchAIOMsgItem
import com.tencent.watch.aio_impl.ui.cell.unsupport.WatchToQQViewMsgItem
import momoi.anno.mixin.StaticHook
import momoi.mod.qqpro.hook.aio_cell.BiliCard
import momoi.mod.qqpro.enums.ElementType
import momoi.mod.qqpro.util.Json
import momoi.mod.qqpro.util.Utils

@StaticHook(MsgListUtilKt::class)
fun c(msg: MsgRecord): WatchAIOMsgItem {
    // 此处的 msg 带完整 ark 元素（绑定时的 item.d 里 arkElement 会被剥掉），提前提取
    // B 站链接按 msgId 缓存，供 HookCell 绑定检测直接命中。
    // 双保险：转换钩子绝不能因 B 站检测的任何异常而破坏消息渲染。
    runCatching { BiliCard.cacheMsgTarget(msg) }
        .onFailure { Utils.log("BiliCard: c() cache failed: ${it.message}") }
    val reply = msg.elements.firstOrNull { it.replyElement != null }
    if (reply == null) {
        val result = MsgListUtilKt.c(msg)
        dumpUnsupported(msg, result)
        // Official-bot / markdown / ark messages fall into the watch's "unsupported" cell
        // ("[暂不支持该消息类型，请用手机QQ查看]"). When the record carries renderable text (a plain
        // text element or a markdown body), rebuild it as a normal text message so bot replies are
        // actually viewable on the watch.
        return renderBotText(msg, result)
    }
    msg.elements.remove(reply)
    val rawType = msg.msgType
    msg.msgType = 2
    val result = MsgListUtilKt.c(msg)
    msg.elements.add(0, reply)
    msg.msgType = rawType
    return result
}

/**
 * Re-render a message that the watch routed to the unsupported cell as plain text.
 * 机器人消息补完：取第一个可渲染正文 —— 文本元素优先，其次 markdown 正文（保留元素，
 * 由 AIOCell 的 markdown 渲染路径做格式显示），再退到 ark 卡片的 JSON 文本（prompt/标题/描述），
 * 最后只剩按钮时也给出可读文本。inline keyboard 的按钮标签会附加在正文末尾
 * （markdown 消息的按钮由 AIOCell 渲染时附加）。No-op when the record carries nothing
 * renderable, or when [result] is already a supported item.
 */
private fun renderBotText(msg: MsgRecord, result: WatchAIOMsgItem): WatchAIOMsgItem {
    // 两种“请在手机QQ查看”占位都要接管：UnSupportWatchAIOMsgItem（无元素）和
    // WatchToQQViewMsgItem（DirtyMsgApi 判定需手机查看，通常是 ark 卡片）。
    if (result !is UnSupportWatchAIOMsgItem && result !is WatchToQQViewMsgItem) return result
    val textEl = msg.elements.firstOrNull {
        it.elementType == ElementType.TEXT && it.textElement?.content?.isNotBlank() == true
    }
    val mdEl = msg.elements.firstOrNull {
        it.elementType == ElementType.MARKDOWN && it.markdownElement?.content?.isNotBlank() == true
    }
    val kb = keyboardLabels(msg)
    val keep: MsgElement? = when {
        // 纯文本正文：把按钮标签直接附在文本后（新建元素，不改原记录）。
        textEl != null -> MsgElement().apply {
            elementType = ElementType.TEXT
            textElement = TextElement().apply {
                content = textEl.textElement!!.content + (kb?.let { "\n$it" } ?: "")
            }
        }
        // markdown 正文：保留原元素（AIOCell 渲染格式），按钮由渲染路径附加。
        mdEl != null -> mdEl
        // ark 卡片：从 JSON 里重建可读文本，附按钮。
        else -> {
            val arkBody = arkCardText(msg)
            if (arkBody != null || kb != null) {
                MsgElement().apply {
                    elementType = ElementType.TEXT
                    textElement = TextElement().apply {
                        content = listOfNotNull(arkBody, kb).joinToString("\n")
                    }
                }
            } else null
        }
    }
    if (keep == null) {
        // 记录里没有任何可渲染内容（手表内核把机器人消息精简成 msgType=NULL/空 elements）：
        // 按 msgId 从内核库补拉完整记录，拉到后由 AIOCell 渲染替换“请在手机QQ查看”占位。
        momoi.mod.qqpro.hook.aio_cell.RobotMsgFetcher.request(msg)
        return result
    }

    val savedType = msg.msgType
    val savedElements = msg.elements
    return try {
        msg.elements.clear()
        msg.elements.add(keep)
        msg.msgType = 2 // text message → native text cell
        MsgListUtilKt.c(msg)
    } catch (t: Throwable) {
        Utils.log("renderBotText failed: $t")
        result
    } finally {
        msg.elements = savedElements
        msg.msgType = savedType
    }
}

/**
 * ark 卡片 → 可读文本：优先取 JSON `prompt`（卡片显示的一行摘要），再按已知 app 类型
 * 重建正文（公告/名片/地图等），最后 fallback 到 meta 第一项的 title/desc。
 */
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

/** inline keyboard 按钮标签：每行 "🔘 标签1 · 标签2"，多行用换行分隔；无按钮返回 null。 */
private fun keyboardLabels(msg: MsgRecord): String? = runCatching {
    val kb = msg.elements.firstOrNull { it.inlineKeyboardElement != null }?.inlineKeyboardElement ?: return null
    val rows = kb.rows?.mapNotNull { row ->
        val labels = row.buttons?.mapNotNull { it.label?.takeIf { l -> l.isNotBlank() } }.orEmpty()
        labels.joinToString(" · ").takeIf { it.isNotBlank() }
    }.orEmpty()
    rows.joinToString("\n").takeIf { it.isNotBlank() }?.let { "🔘 $it" }
}.getOrNull()

/**
 * Diagnostic: dump the full structure of any message that the watch routes to
 * the "unsupported message" cell, so we can identify what a group-invite (and
 * other unsupported) message actually carries (ark/struct/longmsg/file) and
 * build a precise renderer. Read via qqpro_debug.log.
 */
private fun dumpUnsupported(msg: MsgRecord, result: WatchAIOMsgItem) {
    try {
        // Skip the common, already-rendered cases (plain text / face only) to keep
        // the log light; dump everything else along with the resulting item class.
        val onlyTextFace = msg.msgType == 2 && msg.elements?.all {
            it.elementType == 1 || it.elementType == 2 || it.elementType == 6
        } == true
        if (onlyTextFace) return
        val sb = StringBuilder("MSGDUMP result=${result.javaClass.simpleName} msgType=${msg.msgType} subType=${msg.subMsgType} elems=${msg.elements?.size}")
        msg.elements?.forEachIndexed { i, e ->
            sb.append("\n  [$i] elementType=${e.elementType}")
            e.arkElement?.let { sb.append(" ark.sub=${it.subElementType} ark.bytesData=${it.bytesData}") }
            e.structMsgElement?.let { sb.append(" struct.xml=${it.xmlContent}") }
            e.structLongMsgElement?.let { sb.append(" longmsg.resId=${it.resId} longmsg.xml=${it.xmlContent}") }
            e.grayTipElement?.let { sb.append(" grayTip.subType=${it.subElementType}") }
            e.fileElement?.let { sb.append(" file.name=${it.fileName} file.size=${it.fileSize}") }
            e.walletElement?.let { sb.append(" wallet=present") }
        }
        Utils.log(sb.toString())
    } catch (e: Exception) {
        Utils.log("dumpUnsupported error: ${e.message}")
    }
}
