package com.spark.companion.phone

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Keeps a short in-memory list of recent notifications for read_notifications. */
class SparkNotificationListener : NotificationListenerService() {

    data class Item(val app: String, val title: String, val text: String, val time: Long)

    override fun onListenerConnected() {
        connected = true
        try {
            activeNotifications?.sortedBy { it.postTime }?.forEach { record(it) }
        } catch (e: Exception) {
            // listener not fully bound yet
        }
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) record(sbn)
    }

    private fun record(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        val item = Item(app, title.take(100), text.take(300), sbn.postTime)
        synchronized(items) {
            items.removeAll { it.app == item.app && it.title == item.title && it.text == item.text }
            items.addLast(item)
            while (items.size > 50) items.removeFirst()
        }
    }

    companion object {
        @Volatile
        var connected = false
            private set

        private val items = ArrayDeque<Item>()

        fun recent(limit: Int): List<Item> = synchronized(items) { items.toList().takeLast(limit).reversed() }
    }
}
