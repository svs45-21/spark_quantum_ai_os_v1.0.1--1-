package com.spark.companion.automation

/**
 * A separate manifest component for the SMS broadcast (it needs its own
 * BROADCAST_SMS permission). The firing logic is inherited from
 * [RoutineReceiver], which already handles the SMS_RECEIVED action.
 */
class SmsRoutineReceiver : RoutineReceiver()
