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
    }

    companion object {
        const val CHANNEL_COMPANION = "companion"
        const val CHANNEL_REMINDERS = "reminders"

        lateinit var instance: SparkApp
            private set

        val engine: SparkEngine get() = instance.engine
    }
}
