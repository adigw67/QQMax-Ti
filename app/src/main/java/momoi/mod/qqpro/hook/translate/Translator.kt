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
 *  - baidu：百度翻译开放平台（需 APP ID + 密钥）。
 *  - mymemory：MyMemory 免费接口（无需 Key，每日有限额）。
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
    private const val BAIDU = "https://fanyi-api.baidu.com/api/trans/vip/translate"
    private const val MYMEMORY = "https://api.mymemory.translated.net/get"
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
                "baidu" -> if (Settings.translateBaiduAppId.value.isNotBlank() &&
                    Settings.translateBaiduKey.value.isNotBlank()
                ) {
                    baiduTranslate(trimmed, target)
                } else {
                    Utils.log("Translator: 百度翻译未填 APP ID/密钥，回退免费接口")
                    freeTranslate(trimmed, target)
                }
                "ms" -> if (Settings.translateMsKey.value.isNotBlank()) {
                    microsoftTranslate(trimmed, target)
                } else {
                    Utils.log("Translator: 微软翻译未填 Key，回退免费接口")
                    freeTranslate(trimmed, target)
                }
                "custom" -> if (Settings.summarizeApiKey.value.isNotBlank()) {
                    customTranslate(trimmed, target)
                } else {
                    Utils.log("Translator: 自定义AI未填 Key，回退免费接口")
                    freeTranslate(trimmed, target)
                }
                "mymemory" -> mymemoryTranslate(trimmed, target)
                else -> googleTranslate(trimmed, target)
            }
            callback(result)
        }
    }

    /** 免 Key 回退链：MyMemory（无需 Key）→ 谷歌（海外）。 */
    private fun freeTranslate(text: String, target: String): String? =
        mymemoryTranslate(text, target) ?: googleTranslate(text, target)

    /** MyMemory 免费接口（无需 Key，自动检测源语言，每日 5000 字符额度）。 */
    private fun mymemoryTranslate(text: String, target: String): String? {
        val to = if (target == "zh") "zh-CN" else target
        val q = runCatching { URLEncoder.encode(text, "UTF-8") }.getOrNull() ?: return null
        val raw = get("$MYMEMORY?q=$q&langpair=Autodetect%7C$to") ?: return null
        return runCatching {
            val root = JSONObject(raw)
            if (root.optInt("responseStatus", 0) != 200) return@runCatching null
            root.optJSONObject("responseData")?.optString("translatedText", "")
                ?.trim()?.takeIf { it.isNotBlank() }
        }.onFailure { Utils.log("Translator: mymemory parse failed: $it") }.getOrNull()
    }

    /** 百度翻译开放平台：GET + md5(appid+q+salt+密钥) 签名。 */
    private fun baiduTranslate(text: String, target: String): String? {
        val appid = Settings.translateBaiduAppId.value.trim()
        val key = Settings.translateBaiduKey.value.trim()
        val to = baiduCode(target)
        val salt = System.currentTimeMillis().toString()
        val sign = md5("$appid$text$salt$key") ?: return null
        val params = buildString {
            append("q=").append(URLEncoder.encode(text, "UTF-8"))
            append("&from=auto&to=").append(to)
            append("&appid=").append(appid)
            append("&salt=").append(salt)
            append("&sign=").append(sign)
        }
        val raw = get("$BAIDU?$params") ?: return null
        val err = runCatching { JSONObject(raw).optString("error_code", "") }.getOrDefault("")
        if (err.isNotEmpty()) {
            Utils.log("Translator: baidu error $err: ${JSONObject(raw).optString("error_msg", "")}")
            return null
        }
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("trans_result") ?: return@runCatching null
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                sb.append(arr.optJSONObject(i)?.optString("dst", ""))
            }
            sb.toString().trim().takeIf { it.isNotBlank() }
        }.onFailure { Utils.log("Translator: baidu parse failed: $it") }.getOrNull()
    }

    private fun baiduCode(code: String): String = when (code) {
        "ja" -> "jp"
        "ko" -> "kor"
        "fr" -> "fra"
        "es" -> "spa"
        "ar" -> "ara"
        else -> code
    }

    private fun md5(s: String): String? = try {
        val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        d.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Utils.log("Translator: md5 failed: ${e.message}")
        null
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
