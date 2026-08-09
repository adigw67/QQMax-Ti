package momoi.mod.qqpro.hook.summarize

import momoi.mod.qqpro.util.Utils
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-chat persistence of past summaries. Each chat (keyed by chatType+peerUid) keeps a JSON array of
 * [Item]s in the "qqpro_summaries" SharedPreferences. Summaries are kept indefinitely (newest first);
 * the user deletes them manually from the history viewer.
 */
object SummaryStore {

    private val sp by lazy {
        Utils.application.getSharedPreferences("qqpro_summaries", android.content.Context.MODE_PRIVATE)
    }

    /** A saved summary. [time] is epoch-ms; [range] is a human label (e.g. "未读 42 条"); [content] is Markdown. */
    class Item(val time: Long, val range: String, val style: Int, val content: String)

    fun keyOf(chatType: Int, peerUid: String): String = "${chatType}_$peerUid"

    fun list(key: String): List<Item> = runCatching {
        val raw = sp.getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.optLong("time"), o.optString("range"), o.optInt("style"), o.optString("content"))
        }.sortedByDescending { it.time }
    }.getOrElse { Utils.log("SummaryStore: read failed: $it"); emptyList() }

    /** Append a new summary to [key]'s history and persist. */
    fun add(key: String, item: Item) {
        val cur = list(key).toMutableList()
        cur.add(item)
        save(key, cur)
    }

    /** Remove the summary saved at [time] from [key]'s history. */
    fun remove(key: String, time: Long) {
        save(key, list(key).filterNot { it.time == time })
    }

    private fun save(key: String, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { it ->
            arr.put(JSONObject().apply {
                put("time", it.time)
                put("range", it.range)
                put("style", it.style)
                put("content", it.content)
            })
        }
        sp.edit().putString(key, arr.toString()).apply()
    }
}
