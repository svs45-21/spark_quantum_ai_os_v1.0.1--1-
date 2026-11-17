package com.spark.companion.engine

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.spark.companion.R
import com.spark.companion.SparkApp
import com.spark.companion.ui.MainActivity
import com.spark.companion.ui.OverlayController

/**
 * Foreground service that keeps Spark alive, and the microphone usable, while
 * a conversation continues behind another app or hands-free listening is on.
 */
class CompanionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        OverlayController.detachApp()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SparkApp.engine.stop()
            return START_NOT_STICKY
        }
        // Without phone control, draw the floating avatar from here if allowed.
        OverlayController.attachApp(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CompanionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, SparkApp.CHANNEL_COMPANION)
            .setSmallIcon(R.drawable.ic_spark)
            .setContentTitle("Spark is with you")
            .setContentText(if (SparkApp.engine.settings.wakeWordEnabled) "Say “${SparkApp.engine.settings.wakeWord.replaceFirstChar { it.uppercase() }}” to wake me up." else "Tap to open. Use Stop to end what Spark is doing.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Not allowed right now (e.g. started while in the background on
            // Android 14+). Spark still works; it just isn't a foreground service.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "com.spark.companion.STOP"

        /**
         * Uses startService (not startForegroundService) on purpose: if the
         * system refuses the foreground promotion, nothing crashes.
         */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, CompanionService::class.java))
            } catch (e: Exception) {
                // App is in the background and may not start services; fine.
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CompanionService::class.java))
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
