package momoi.mod.qqpro.util

import momoi.mod.qqpro.Settings
import org.json.JSONObject

/**
 * Serialises only the QQPro settings-page settings ([Settings.all]) to a portable JSON or XML
 * blob and restores them. Keys are matched against the live [Settings.all] registry on import, so
 * unknown / stale keys are ignored and each value is parsed back to its real type by the owning
 * [momoi.mod.qqpro.Pref]. Settings live across three SharedPreferences (qqpro / wearqq /
 * OTAManager2); writing through the Pref objects keeps each one in its correct store.
 */
object SettingsBackup {
    const val VERSION = 1

    fun exportJson(): String {
        val settings = JSONObject()
        Settings.all.forEach { settings.put(it.key, it.value) }
        return JSONObject().apply {
            put("app", "QQPro")
            put("version", VERSION)
            put("settings", settings)
        }.toString(2)
    }

    fun exportXml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<qqpro-settings version=\"$VERSION\">\n")
        Settings.all.forEach {
            append("  <pref key=\"").append(esc(it.key)).append("\">")
            append(esc(it.value.toString()))
            append("</pref>\n")
        }
        append("</qqpro-settings>\n")
    }

    /** Apply a backup. Auto-detects JSON vs XML by the first non-space char. Returns #keys applied. */
    fun import(text: String): Int {
        val t = text.trim()
        return when {
            t.startsWith("{") -> importJson(t)
            t.startsWith("<") -> importXml(t)
            else -> throw IllegalArgumentException("无法识别的格式（需 JSON 或 XML）")
        }
    }

    private fun importJson(text: String): Int {
        val root = JSONObject(text)
        // Accept either the wrapped form ({settings:{...}}) or a bare flat object of key→value.
        val s = root.optJSONObject("settings") ?: root
        var n = 0
        Settings.all.forEach { pref ->
            if (s.has(pref.key)) {
                pref.importString(s.get(pref.key).toString())
                n++
            }
        }
        return n
    }

    private fun importXml(text: String): Int {
        val re = Regex("<pref\\s+key=\"([^\"]*)\"\\s*>(.*?)</pref>", RegexOption.DOT_MATCHES_ALL)
        val byKey = Settings.all.associateBy { it.key }
        var n = 0
        re.findAll(text).forEach { m ->
            val pref = byKey[unesc(m.groupValues[1])] ?: return@forEach
            pref.importString(unesc(m.groupValues[2]))
            n++
        }
        return n
    }

    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unesc(s: String) = s
        .replace("&quot;", "\"").replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
}
