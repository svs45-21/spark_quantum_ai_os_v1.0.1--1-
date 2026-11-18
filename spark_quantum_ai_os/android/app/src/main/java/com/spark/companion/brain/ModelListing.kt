package com.spark.companion.brain

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One row in a model drop-down. [firstSeen] is 0 for models present at the first check. */
data class ModelEntry(val id: String, val tags: String = "", val firstSeen: Long = 0L)

/** A provider's model list as last fetched, best first. */
data class ModelList(val fetchedAt: Long, val entries: List<ModelEntry>) {
    val ids: List<String> get() = entries.map { it.id }
}

/**
 * Keeps drop-downs current: each refresh replaces the list with what the
 * provider offers now, remembers when every model first appeared, and reports
 * the ones that are new since last time.
 */
object ModelListing {
    const val NEW_FOR_MS = 7L * 24 * 60 * 60 * 1000

    class Merge(val list: ModelList, val added: List<String>, val removed: List<String>)

    fun merge(previous: ModelList?, fresh: List<Pair<String, String>>, now: Long): Merge {
        val seen = previous?.entries?.associate { it.id to it.firstSeen }.orEmpty()
        val added = ArrayList<String>()
        val entries = fresh.distinctBy { it.first }.map { (id, tags) ->
            val first = when {
                previous == null -> 0L
                id in seen -> seen.getValue(id)
                else -> now.also { added += id }
            }
            ModelEntry(id, tags, first)
        }
        val freshIds = entries.map { it.id }.toSet()
        val removed = previous?.ids?.filter { it !in freshIds }.orEmpty()
        return Merge(ModelList(now, entries), added, removed)
    }

    fun isNew(e: ModelEntry, now: Long): Boolean = e.firstSeen > 0 && now - e.firstSeen < NEW_FOR_MS

    fun toJson(list: ModelList): String {
        val arr = JSONArray()
        for (e in list.entries) arr.put(JSONObject().put("id", e.id).put("t", e.tags).put("f", e.firstSeen))
        return JSONObject().put("at", list.fetchedAt).put("m", arr).toString()
    }

    fun fromJson(raw: String?): ModelList? {
        if (raw.isNullOrBlank()) return null
        return try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("m") ?: JSONArray()
            val entries = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { ModelEntry(it.optString("id"), it.optString("t"), it.optLong("f")) }
            }.filter { it.id.isNotEmpty() }
            ModelList(o.optLong("at"), entries)
        } catch (e: JSONException) {
            null
        }
    }
}
