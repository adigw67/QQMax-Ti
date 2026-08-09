package momoi.mod.qqpro.util

import org.json.JSONObject

class Json(private val json: JSONObject) {
    constructor(data: String) : this(JSONObject(data))
    val keys: Set<String> by lazy {
        val set = mutableSetOf<String>()
        json.keys().forEach { set.add(it) }
        set
    }
    fun json(key: String): Json? {
        return if (json.has(key)) {
            Json(json.getJSONObject(key))
        } else {
            null
        }
    }
    fun str(key: String): String? {
        return if (json.has(key)) {
            json.getString(key)
        } else {
            null
        }
    }
}