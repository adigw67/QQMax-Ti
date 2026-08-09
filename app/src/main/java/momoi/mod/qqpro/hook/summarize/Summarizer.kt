package momoi.mod.qqpro.hook.summarize

import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Streaming client over the chat-summarization Cloudflare Worker:
 *   POST https://onyxai.ai-life.xyz/chat/summarize  (Server-Sent Events response)
 *
 * The body is `{messages:[{sender,text}], style, language}`; the response is a token stream of
 * `data: {"response":"<fragment>"}` lines terminated by `data: [DONE]`. [summarize] runs on a
 * background thread and delivers fragments to [Listener] there — callers that touch views must hop to
 * the UI thread themselves (runOnUi).
 */
object Summarizer {

    private const val URL = "https://onyxai.ai-life.xyz/chat/summarize"
    private const val APP_ID = "qqmax"

    /** True when the user configured their own OpenAI-compatible endpoint (API Key non-blank). */
    private val customEnabled: Boolean get() = Settings.summarizeApiKey.value.isNotBlank()

    /** One message to summarize (only sender + text are read by the server). */
    class Msg(val sender: String, val text: String)

    interface Listener {
        /** A streamed text fragment arrived; append it to the accumulating summary. */
        fun onChunk(fragment: String)
        /** The stream finished. [used]/[limit] are today's quota (−1 when the header was absent). */
        fun onDone(used: Int, limit: Int)
        /** The call failed. [retryable] = the same request may be retried (overloaded / server fault). */
        fun onError(message: String, retryable: Boolean)
    }

    /** Style code (Settings.summarizeStyle) → API style string. */
    private fun styleString(code: Int): String = when (code) {
        1 -> "tldr"
        2 -> "detailed"
        else -> "bullets"
    }

    /** Style code → Chinese instruction fragment used in the custom-endpoint prompt. */
    private fun styleName(code: Int): String = when (code) {
        1 -> "一句话（TL;DR）"
        2 -> "详细版"
        else -> "要点（Markdown 列表）"
    }

    /** Stable per-install id for the daily quota; generated and persisted on first use. */
    fun userId(): String {
        var id = Settings.installUuid.value
        if (id.isBlank()) {
            id = UUID.randomUUID().toString()
            Settings.installUuid.value = id
        }
        return id
    }

    /**
     * Summarize [messages] (ordered oldest→newest). [style] is a Settings.summarizeStyle code and
     * [language] is a 2-letter code or "auto" (omitted from the request so the server matches the
     * conversation's own language).
     */
    fun summarize(
        messages: List<Msg>,
        style: Int,
        language: String,
        listener: Listener,
    ) {
        if (messages.isEmpty()) { listener.onError("没有可总结的消息", false); return }
        thread {
            if (customEnabled) customSummarize(messages, style, language, listener)
            else workerSummarize(messages, style, language, listener)
        }
    }

    /** Built-in Onyx worker (SSE token stream, daily quota via X-* headers). */
    private fun workerSummarize(
        messages: List<Msg>,
        style: Int,
        language: String,
        listener: Listener,
    ) {
        var conn: HttpURLConnection? = null
        try {
            val body = JSONObject().apply {
                put("messages", JSONArray().apply {
                    messages.forEach { m ->
                        put(JSONObject().apply { put("sender", m.sender); put("text", m.text) })
                    }
                })
                put("style", styleString(style))
                if (language.isNotBlank() && language != "auto") put("language", langName(language))
            }.toString()

            conn = (URL(URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("X-App-ID", APP_ID)
                setRequestProperty("X-User-ID", userId())
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                handleError(conn, code, listener)
                return
            }

            val limit = conn.getHeaderField("X-Quota-Limit")?.toIntOrNull() ?: -1
            val used = conn.getHeaderField("X-Quota-Used")?.toIntOrNull() ?: -1

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    if (payload.isEmpty()) continue
                    val frag = runCatching { JSONObject(payload).optString("response", "") }.getOrNull()
                    if (!frag.isNullOrEmpty()) listener.onChunk(frag)
                }
            }
            listener.onDone(used, limit)
        } catch (e: Exception) {
            Utils.log("Summarizer: request failed: ${e.message}")
            listener.onError("网络错误，请重试", true)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Direct non-streaming call to the user's own OpenAI-compatible /chat/completions endpoint.
     * Request is capped (last 100 messages, 500 chars each) so a watch on 4G doesn't blow the
     * token budget; the whole response is delivered as one chunk.
     */
    private fun customSummarize(
        messages: List<Msg>,
        style: Int,
        language: String,
        listener: Listener,
    ) {
        var conn: HttpURLConnection? = null
        try {
            val transcript = StringBuilder()
            messages.takeLast(100).forEach { m ->
                val text = m.text.trim().take(500)
                if (text.isNotEmpty()) transcript.append(m.sender).append(": ").append(text).append('\n')
            }
            val prompt = buildString {
                append("请把下面的聊天记录总结成").append(styleName(style))
                if (language.isNotBlank() && language != "auto") append("，使用").append(langName(language))
                append("。只基于原文，不要编造：\n\n").append(transcript)
            }
            val body = JSONObject().apply {
                put("model", Settings.summarizeApiModel.value.ifBlank { "deepseek-chat" })
                put("stream", false)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "你是聊天记录总结助手，输出简洁准确。")
                    })
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
            }.toString()

            conn = (URL(Settings.summarizeApiBase.value.ifBlank { "https://api.deepseek.com/v1/chat/completions" })
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 120000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${Settings.summarizeApiKey.value.trim()}")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val raw = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                Utils.log("Summarizer(custom): HTTP $code body=${raw?.take(160)}")
                when {
                    code == 401 || code == 403 -> listener.onError("API Key 无效或已过期", false)
                    code == 404 -> listener.onError("接口地址错误（404），请检查“接口地址”", false)
                    code == 429 -> listener.onError("API 配额或余额不足（429）", false)
                    code >= 500 -> listener.onError("服务商暂时不可用（$code），请稍后重试", true)
                    else -> listener.onError("请求失败（$code）", true)
                }
                return
            }

            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val content = runCatching {
                JSONObject(text).getJSONArray("choices").optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content", "")
            }.getOrNull()
            if (content.isNullOrBlank()) {
                listener.onError("服务商返回内容为空，请重试", true)
                return
            }
            listener.onChunk(content.trim())
            listener.onDone(-1, -1)
        } catch (e: Exception) {
            Utils.log("Summarizer(custom): request failed: ${e.message}")
            listener.onError("网络错误，请重试", true)
        } finally {
            conn?.disconnect()
        }
    }

    private fun handleError(conn: HttpURLConnection, code: Int, listener: Listener) {
        val raw = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        Utils.log("Summarizer: HTTP $code body=${raw?.take(160)}")
        when (code) {
            429 -> {
                val limit = runCatching { JSONObject(raw ?: "").optInt("limit", -1) }.getOrDefault(-1)
                listener.onError(
                    if (limit > 0) "今日总结次数已用完（上限 $limit），UTC 0 点重置" else "今日总结次数已用完，UTC 0 点重置",
                    false,
                )
            }
            502 -> listener.onError("总结服务暂不可用（502），可在 设置›聊天总结 配置自己的 API Key 绕过", true)
            503 -> listener.onError("服务器繁忙，请稍后重试", true)
            403 -> listener.onError("应用鉴权失败", false)
            400 -> listener.onError("请求无效（消息为空）", false)
            500 -> listener.onError("服务器错误，请重试", true)
            else -> listener.onError("请求失败（$code）", true)
        }
    }

    /** Map a 2-letter code to a language name the server understands (falls back to the raw code). */
    private fun langName(code: String): String = when (code) {
        "zh" -> "中文"
        "en" -> "English"
        "ja" -> "日本語"
        "ko" -> "한국어"
        "fr" -> "Français"
        "de" -> "Deutsch"
        "es" -> "Español"
        "ru" -> "Русский"
        "pt" -> "Português"
        "ar" -> "العربية"
        else -> code
    }
}
