package com.spark.companion.phone

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.spark.companion.R
import com.spark.companion.SparkApp
import com.spark.companion.ui.MainActivity

/** Shows a reminder notification when its alarm fires. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(context, SparkApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_spark)
            .setContentTitle("Reminder from Spark")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(intent.getIntExtra(EXTRA_ID, text.hashCode()), notification)
        } catch (e: SecurityException) {
            // notification permission not granted
        }
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
    }
}
