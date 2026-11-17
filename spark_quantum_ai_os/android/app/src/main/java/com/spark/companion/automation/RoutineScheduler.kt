package com.spark.companion.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Books time-based routines with AlarmManager. Event routines use receivers. */
class RoutineScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(routine: Routine) {
        if (routine.trigger != Trigger.TIME) return
        val pending = pendingFor(routine.id)
        if (!routine.enabled) {
            alarms.cancel(pending)
            return
        }
        val at = Routine.nextFire(routine, System.currentTimeMillis())
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (e: SecurityException) {
            // exact-alarm permission not granted; fall back to an inexact window
            alarms.set(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(id: String) = alarms.cancel(pendingFor(id))

    fun rescheduleAll() = RoutineStore(context).all().forEach { schedule(it) }

    private fun pendingFor(id: String): PendingIntent {
        val intent = Intent(context, RoutineReceiver::class.java)
            .setAction(RoutineReceiver.ACTION_FIRE)
            .putExtra(RoutineReceiver.EXTRA_ID, id)
            .setData(android.net.Uri.parse("spark://routine/$id"))
        return PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
