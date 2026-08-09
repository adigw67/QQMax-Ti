package momoi.mod.qqpro.hook.translate

import momoi.mod.qqpro.util.Json
import momoi.mod.qqpro.util.Utils
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Thin client over the ai-life translate endpoint:
 *   GET https://translate.ai-life.xyz/api/v1/auto/<target>/<url-encoded text>
 * `auto` is the source language (auto-detected); <target> is the 2-letter output code. The JSON
 * response is `{"translation":"…","info":{"detectedSource":"zh", …}}` — we only need `translation`.
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

    private const val BASE = "https://translate.ai-life.xyz/api/v1/auto"

    /**
     * Translate [text] into [target]. [callback] receives the translated string, or null on any
     * failure (network error / blank input / unparsable response). Invoked on a background thread.
     *
     * Uses its own request (not api.Http) because that helper only accepts text bodies and this
     * endpoint returns application/json.
     */
    fun translate(text: String, target: String, callback: (String?) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || target.isBlank()) { callback(null); return }
        // Percent-encode the text as a path segment. URLEncoder emits '+' for spaces (which this
        // server takes literally — verified), so swap those for %20; %2F etc. are correct as-is.
        val encoded = runCatching { URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20") }
            .getOrElse { callback(null); return }
        val url = "$BASE/$target/$encoded"
        thread {
            var conn: HttpURLConnection? = null
            val resp = try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    val out = ByteArrayOutputStream()
                    conn.inputStream.use { it.copyTo(out) }
                    String(out.toByteArray(), Charsets.UTF_8)
                } else {
                    Utils.log("Translator: HTTP $code for $target")
                    null
                }
            } catch (e: Exception) {
                Utils.log("Translator: request failed: ${e.message}")
                null
            } finally {
                conn?.disconnect()
            }
            val result = resp?.let { runCatching { Json(it).str("translation") }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            if (resp != null && result == null) Utils.log("Translator: no translation in: ${resp.take(120)}")
            callback(result)
        }
    }
}
