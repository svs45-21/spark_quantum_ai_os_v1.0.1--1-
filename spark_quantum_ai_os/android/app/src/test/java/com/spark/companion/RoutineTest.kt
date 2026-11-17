package com.spark.companion

import com.spark.companion.automation.Routine
import com.spark.companion.automation.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RoutineTest {
    private fun cal(y: Int, mo: Int, d: Int, h: Int, mi: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(y, mo, d, h, mi, 0)
        }

    private fun utcRoutine(hour: Int, minute: Int, days: Set<Int> = emptySet()) =
        Routine(id = "1", name = "r", prompt = "p", trigger = Trigger.TIME, hour = hour, minute = minute, days = days)

    @Test fun firesLaterToday() {
        val now = cal(2026, 0, 5, 6, 0) // Mon 06:00 UTC
        val next = Routine.nextFire(utcRoutine(7, 0), now.timeInMillis, Calendar.getInstance(TimeZone.getTimeZone("UTC")))
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = next }
        assertEquals(7, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(5, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun rollsToTomorrowWhenPassed() {
        val now = cal(2026, 0, 5, 8, 0)
        val next = Routine.nextFire(utcRoutine(7, 0), now.timeInMillis, Calendar.getInstance(TimeZone.getTimeZone("UTC")))
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = next }
        assertEquals(6, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun honoursSelectedDays() {
        // Only Wednesday (Calendar.WEDNESDAY = 4). Now is Monday.
        val now = cal(2026, 0, 5, 9, 0)
        val next = Routine.nextFire(utcRoutine(7, 0, setOf(Calendar.WEDNESDAY)), now.timeInMillis, Calendar.getInstance(TimeZone.getTimeZone("UTC")))
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = next }
        assertEquals(Calendar.WEDNESDAY, c.get(Calendar.DAY_OF_WEEK))
        assertEquals(7, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun roundTripsJson() {
        val r = utcRoutine(6, 30, setOf(2, 4, 6)).copy(name = "Brief", prompt = "read news", enabled = false)
        assertEquals(r, Routine.fromJson(r.toJson()))
    }

    @Test fun labels() {
        assertEquals("7:05 AM", utcRoutine(7, 5).timeLabel())
        assertEquals("12:00 PM", utcRoutine(12, 0).timeLabel())
        assertEquals("Every day", utcRoutine(7, 0).daysLabel())
        assertTrue(utcRoutine(7, 0, setOf(2)).daysLabel().contains("Mon"))
    }
}
