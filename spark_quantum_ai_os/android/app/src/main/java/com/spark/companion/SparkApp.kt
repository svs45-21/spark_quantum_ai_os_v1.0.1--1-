package com.spark.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.spark.companion.engine.SparkEngine

class SparkApp : Application() {

    /** The one companion engine for the whole process. */
    val engine: SparkEngine by lazy { SparkEngine(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPANION, "Spark is active", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        registerChargerTrigger()
    }

    /**
     * ACTION_POWER_CONNECTED is not delivered to manifest receivers (Android 8+),
     * so charger routines listen at runtime while Spark is running.
     */
    private fun registerChargerTrigger() {
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED)
        val receiver = com.spark.companion.automation.RoutineReceiver()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    companion object {
        const val CHANNEL_COMPANION = "companion"
        const val CHANNEL_REMINDERS = "reminders"

        lateinit var instance: SparkApp
            private set

        val engine: SparkEngine get() = instance.engine
    }
}
