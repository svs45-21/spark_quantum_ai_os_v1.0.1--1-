package com.spark.companion.automation

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** What makes a routine fire. */
enum class Trigger { TIME, CHARGER, SMS }

/**
 * A saved automation: when [trigger] happens, Spark runs [prompt] (the same
 * natural-language instruction you'd speak) and reads out the result.
 */
data class Routine(
    val id: String,
    val name: String,
    val prompt: String,
    val trigger: Trigger = Trigger.TIME,
    val hour: Int = 8,
    val minute: Int = 0,
    /** Calendar.DAY_OF_WEEK values (1=Sun..7=Sat); empty means every day. */
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("prompt", prompt)
        .put("trigger", trigger.name).put("hour", hour).put("minute", minute)
        .put("days", JSONArray(days.toList())).put("enabled", enabled)

    fun timeLabel(): String {
        val h12 = when (hour % 12) { 0 -> 12; else -> hour % 12 }
        val ampm = if (hour < 12) "AM" else "PM"
        return "%d:%02d %s".format(h12, minute, ampm)
    }

    fun daysLabel(): String {
        if (days.isEmpty() || days.size == 7) return "Every day"
        val names = mapOf(1 to "Sun", 2 to "Mon", 3 to "Tue", 4 to "Wed", 5 to "Thu", 6 to "Fri", 7 to "Sat")
        return (1..7).filter { it in days }.joinToString(", ") { names.getValue(it) }
    }

    fun summary(): String = when (trigger) {
        Trigger.TIME -> "${timeLabel()} · ${daysLabel()}"
        Trigger.CHARGER -> "When you plug in the charger"
        Trigger.SMS -> "When a text message arrives"
    }

    companion object {
        fun fromJson(j: JSONObject): Routine {
            val daysArr = j.optJSONArray("days") ?: JSONArray()
            val days = (0 until daysArr.length()).map { daysArr.getInt(it) }.toSet()
            return Routine(
                id = j.getString("id"),
                name = j.optString("name", "Routine"),
                prompt = j.optString("prompt"),
                trigger = runCatching { Trigger.valueOf(j.optString("trigger", "TIME")) }.getOrDefault(Trigger.TIME),
                hour = j.optInt("hour", 8),
                minute = j.optInt("minute", 0),
                days = days,
                enabled = j.optBoolean("enabled", true),
            )
        }

        /**
         * The next time (epoch ms) a TIME routine should fire after [nowMs].
         * Pure so it can be unit-tested.
         */
        fun nextFire(routine: Routine, nowMs: Long, calendar: Calendar = Calendar.getInstance()): Long {
            calendar.timeInMillis = nowMs
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            for (ahead in 0..7) {
                val c = calendar.clone() as Calendar
                c.add(Calendar.DAY_OF_YEAR, ahead)
                c.set(Calendar.HOUR_OF_DAY, routine.hour)
                c.set(Calendar.MINUTE, routine.minute)
                val okDay = routine.days.isEmpty() || c.get(Calendar.DAY_OF_WEEK) in routine.days
                if (okDay && c.timeInMillis > nowMs) return c.timeInMillis
            }
            // Fallback: tomorrow at the set time.
            val c = calendar.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, 1)
            c.set(Calendar.HOUR_OF_DAY, routine.hour)
            c.set(Calendar.MINUTE, routine.minute)
            return c.timeInMillis
        }
    }
}
