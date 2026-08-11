package momoi.mod.qqpro.hook.translate

import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * 消息翻译客户端。三种服务可选（[Settings.translateProvider]）：
 *
 *  - custom（默认）：自定义 OpenAI 兼容接口，复用「聊天总结」的 API Key / 接口地址 / 模型
 *    （国内推荐 DeepSeek）。未填 Key 时回退谷歌。
 *  - ms：微软翻译（Azure Translator v3，需 Ocp-Apim-Subscription-Key，区域可选）。
 *  - google：谷歌免费接口（海外可用，大陆直连不通）。
 *
 * [translate] runs on a background thread (Http.get spawns one) and calls back there; callers that
 * touch views must hop to the UI thread themselves (runOnUi).
 */
object Translator {
    /**
     * Target languages offered in the 查看语言 / 发送语言 pickers, as (api-code, display-name). The API
     * source is always `auto`, so these are output languages only (no "auto" entry).
     */
    val TARGETS: List<Pair<String, String>> = listOf(
        "zh" to "中文",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "ru" to "Русский",
        "pt" to "Português",
        "ar" to "العربية",
    )

    /** Display name for a stored language code (falls back to the raw code). */
    fun nameOf(code: String): String = TARGETS.firstOrNull { it.first == code }?.second ?: code

    private const val GOOGLE = "https://translate.googleapis.com/translate_a/single"
    private const val MS = "https://api.cognitive.microsofttranslator.com/translate"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 9; Watch) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /**
     * Translate [text] into [target]. [callback] receives the translated string, or null on any
     * failure (network error / blank input / unparsable response). Invoked on a background thread.
     */
    fun translate(text: String, target: String, callback: (String?) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || target.isBlank()) { callback(null); return }
        thread {
            val result = when (Settings.translateProvider.value) {
                "ms" -> if (Settings.translateMsKey.value.isNotBlank()) {
                    microsoftTranslate(trimmed, target)
                } else {
                    Utils.log("Translator: 微软翻译未填 Key，回退谷歌")
                    googleTranslate(trimmed, target)
                }
                "custom" -> if (Settings.summarizeApiKey.value.isNotBlank()) {
                    customTranslate(trimmed, target)
                } else {
                    Utils.log("Translator: 自定义AI未填 Key，回退谷歌")
                    googleTranslate(trimmed, target)
                }
                else -> googleTranslate(trimmed, target)
            }
            callback(result)
        }
    }

    /** 谷歌免费接口（无需 Key）：GET translate_a/single?client=gtx … */
    private fun googleTranslate(text: String, target: String): String? {
        val encoded = runCatching { URLEncoder.encode(text, "UTF-8") }.getOrNull() ?: return null
        val tl = if (target == "zh") "zh-CN" else target
        val url = "$GOOGLE?client=gtx&sl=auto&tl=$tl&dt=t&q=$encoded"
        val raw = get(url) ?: return null
        return runCatching {
            val root = JSONArray(raw)
            val segs = root.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONArray(i) ?: continue
                sb.append(seg.optString(0))
            }
            sb.toString().trim().takeIf { it.isNotBlank() }
        }.onFailure { Utils.log("Translator: google parse failed: $it") }.getOrNull()
    }

    /** 微软翻译（Azure Translator v3，需 Ocp-Apim-Subscription-Key，区域可选）。 */
    private fun microsoftTranslate(text: String, target: String): String? {
        val to = if (target == "zh") "zh-Hans" else target
        val url = "$MS?api-version=3.0&from=auto&to=$to"
        val body = JSONArray().put(JSONObject().put("text", text)).toString()
        return post(url, body) { conn ->
            conn.setRequestProperty("Ocp-Apim-Subscription-Key", Settings.translateMsKey.value.trim())
            val region = Settings.translateMsRegion.value.trim()
            if (region.isNotEmpty()) conn.setRequestProperty("Ocp-Apim-Subscription-Region", region)
        }?.let { raw ->
            runCatching {
                JSONArray(raw).optJSONObject(0)
                    ?.optJSONArray("translations")?.optJSONObject(0)?.optString("text", "")
                    ?.trim()?.takeIf { it.isNotBlank() }
            }.onFailure { Utils.log("Translator: ms parse failed: $it") }.getOrNull()
        }
    }

    /** 自定义 OpenAI 兼容接口（复用「聊天总结」的 Key/接口/模型）。 */
    private fun customTranslate(text: String, target: String): String? {
        val base = Settings.summarizeApiBase.value.ifBlank { "https://api.deepseek.com" }.trim()
        val url = if (base.endsWith("/chat/completions")) base else base.trimEnd('/') + "/chat/completions"
        val model = Settings.summarizeApiModel.value.ifBlank { "deepseek-v4-flash" }
        val langName = nameOf(target)
        val body = JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是翻译助手，只输出译文，不加解释。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "把下面的文本翻译成$langName，只输出译文：\n$text")
                })
            })
        }.toString()
        return post(url, body) { conn ->
            conn.setRequestProperty("Authorization", "Bearer ${Settings.summarizeApiKey.value.trim()}")
        }?.let { raw ->
            runCatching {
                JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content", "")
                    ?.trim()?.takeIf { it.isNotBlank() }
            }.onFailure { Utils.log("Translator: custom parse failed: $it") }.getOrNull()
        }
    }

    /** GET 请求（统一带浏览器 UA，避免部分接口拦截默认 UA）。 */
    private fun get(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", BROWSER_UA)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val out = ByteArrayOutputStream()
                conn.inputStream.use { it.copyTo(out) }
                String(out.toByteArray(), Charsets.UTF_8)
            } else {
                Utils.log("Translator: HTTP ${conn.responseCode}")
                null
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Utils.log("Translator: request failed: ${e.message}")
        null
    }

    /** POST JSON 请求；[headers] 额外设置请求头（Key 等）。 */
    private fun post(url: String, body: String, headers: (HttpURLConnection) -> Unit): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("User-Agent", BROWSER_UA)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            headers(this)
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val out = ByteArrayOutputStream()
                conn.inputStream.use { it.copyTo(out) }
                String(out.toByteArray(), Charsets.UTF_8)
            } else {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                Utils.log("Translator: POST HTTP ${conn.responseCode} body=${err?.take(160)}")
                null
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Utils.log("Translator: POST failed: ${e.message}")
        null
    }
}
