package com.spark.companion.automation

import android.content.Context
import org.json.JSONArray
import org.json.JSONException

/** Stores the user's routines as JSON in private preferences. */
class RoutineStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("spark_routines", Context.MODE_PRIVATE)

    fun all(): List<Routine> {
        val raw = prefs.getString("routines", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { runCatching { Routine.fromJson(arr.getJSONObject(it)) }.getOrNull() }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun get(id: String): Routine? = all().firstOrNull { it.id == id }

    fun save(routine: Routine) {
        val list = all().filter { it.id != routine.id } + routine
        write(list)
    }

    fun delete(id: String) = write(all().filter { it.id != id })

    private fun write(list: List<Routine>) {
        val arr = JSONArray()
        list.sortedBy { it.name.lowercase() }.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("routines", arr.toString()).apply()
    }
}
