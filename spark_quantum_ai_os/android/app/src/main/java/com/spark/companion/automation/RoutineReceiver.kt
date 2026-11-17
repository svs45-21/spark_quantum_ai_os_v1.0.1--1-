package com.spark.companion.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spark.companion.SparkApp

/**
 * Fires a routine: a time alarm, a charger connect, or an incoming SMS. It
 * hands the routine's instruction to Spark, which carries it out and speaks
 * the result.
 */
open class RoutineReceiver : BroadcastReceiver() {
    final override fun onReceive(context: Context, intent: Intent) {
        val scheduler = RoutineScheduler(context.applicationContext)
        when (intent.action) {
            ACTION_FIRE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                val routine = RoutineStore(context).get(id) ?: return
                if (routine.enabled) SparkApp.instance.engine.runRoutine(routine.prompt)
                // Schedule the next occurrence.
                scheduler.schedule(routine)
            }
            Intent.ACTION_POWER_CONNECTED -> fireEvent(context, Trigger.CHARGER)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> scheduler.rescheduleAll()
            "android.provider.Telephony.SMS_RECEIVED" -> fireEvent(context, Trigger.SMS)
        }
    }

    private fun fireEvent(context: Context, trigger: Trigger) {
        val engine = SparkApp.instance.engine
        RoutineStore(context).all().filter { it.enabled && it.trigger == trigger }.forEach {
            engine.runRoutine(it.prompt)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.spark.companion.ROUTINE_FIRE"
        const val EXTRA_ID = "routine_id"
    }
}
