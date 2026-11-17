package com.spark.companion.phone

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.provider.Settings as SystemSettings
import android.telephony.SmsManager
import android.view.KeyEvent
import com.spark.companion.brain.ActionPolicy
import com.spark.companion.brain.ConfirmRequest
import com.spark.companion.brain.ToolCall
import com.spark.companion.brain.ToolHost
import com.spark.companion.core.Settings
import com.spark.companion.link.SparkLink
import com.spark.companion.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Carries out the model's tool calls on the phone. Every method returns a
 * JSON result the model can read; nothing here throws.
 */
class PhoneTools(
    private val context: Context,
    private val settings: Settings,
    private val memory: MemoryStore,
    private val link: SparkLink,
    private val uiVisible: () -> Boolean,
) : ToolHost {

    private val a11y: SparkAccessibilityService? get() = SparkAccessibilityService.instance

    private class App(val label: String, val pkg: String)
    private var appCache: List<App> = emptyList()
    private var appCacheTime = 0L

    // ── ToolHost ─────────────────────────────────────────────────────────

    override fun describe(call: ToolCall): String {
        val a = call.args
        return when (call.name) {
            "open_app" -> "Opening ${a.optString("name")}"
            "open_url", "web_search" -> "Opening the browser"
            "read_screen" -> "Reading the screen"
            "tap" -> "Tapping " + (a.optString("text").ifBlank { "item ${a.optInt("index")}" })
            "type_text" -> "Typing"
            "scroll" -> "Scrolling ${a.optString("direction")}"
            "press_button" -> "Pressing ${a.optString("button").replace('_', ' ')}"
            "wait" -> "Waiting"
            "find_contact" -> "Looking up ${a.optString("name")}"
            "make_call" -> "Calling ${a.optString("to")}"
            "send_sms" -> "Sending a text to ${a.optString("to")}"
            "open_whatsapp_chat" -> "Opening WhatsApp"
            "compose_email" -> "Drafting an email"
            "set_alarm" -> "Setting an alarm"
            "set_timer" -> "Starting a timer"
            "set_reminder" -> "Setting a reminder"
            "create_calendar_event" -> "Opening the calendar"
            "navigate_to" -> "Starting navigation"
            "play_media" -> "Playing ${a.optString("query")}"
            "media_control" -> "Controlling playback"
            "set_volume" -> "Changing the volume"
            "set_flashlight" -> "Switching the flashlight"
            "open_settings" -> "Opening settings"
            "device_status" -> "Checking the phone"
            "open_camera" -> "Opening the camera"
            "read_notifications" -> "Reading notifications"
            "remember" -> "Remembering that"
            "forget" -> "Forgetting that"
            "ask_desktop_spark" -> "Asking SPARK on your computer"
            else -> "Working"
        }
    }

    override suspend fun confirmationFor(call: ToolCall): ConfirmRequest? {
        val a = call.args
        return try {
            when (call.name) {
                "make_call" -> {
                    val r = resolveRecipient(a.optString("to")) as? Recipient.Found ?: return null
                    ConfirmRequest("Call ${r.display}")
                }
                "send_sms" -> {
                    val r = resolveRecipient(a.optString("to")) as? Recipient.Found ?: return null
                    ConfirmRequest("Send a text to ${r.display} saying: ${a.optString("message").take(300)}")
                }
                "tap" -> {
                    val service = a11y ?: return null
                    val element = findElement(service, a) ?: return null
                    val pkg = service.foregroundPackage
                    if (pkg == context.packageName) return null
                    if (!ActionPolicy.screenActionNeedsConfirm(element.label, pkg)) return null
                    ConfirmRequest("Tap “${element.label.ifBlank { element.kind }}” in ${appLabel(pkg)}")
                }
                "type_text" -> {
                    val service = a11y ?: return null
                    val pkg = service.foregroundPackage
                    if (pkg == context.packageName) return null
                    val element = if (a.has("index")) service.lastSnapshot?.byIndex(a.optInt("index")) else null
                    val label = element?.label
                        ?: service.focusedField()?.let { (it.hintText ?: it.contentDescription ?: "").toString() }.orEmpty()
                    val submits = a.optBoolean("submit", false) && !SEARCH_FIELD.containsMatchIn(label)
                    if (!submits && !ActionPolicy.isSensitivePackage(pkg)) return null
                    val what = if (submits) "Type and send" else "Type"
                    ConfirmRequest("$what “${a.optString("text").take(200)}” in ${appLabel(pkg)}")
                }
                else -> null
            }
        } catch (e: Exception) {
            // If we can't work out what this would do, ask rather than assume it's safe.
            if (ActionPolicy.alwaysConfirm(call.name)) ConfirmRequest("Run ${call.name.replace('_', ' ')}") else null
        }
    }

    override suspend fun execute(call: ToolCall): JSONObject {
        val a = call.args
        return try {
            when (call.name) {
                "open_app" -> openApp(a.optString("name"))
                "list_apps" -> ok().put("apps", JSONArray(apps().map { it.label }.sorted()))
                "open_url" -> openUrl(a.optString("url"))
                "web_search" -> openUrl(
                    "https://www.google.com/search?q=" + Uri.encode(a.optString("query"))
                )
                "read_screen" -> readScreen()
                "tap" -> tap(a)
                "type_text" -> typeText(a)
                "scroll" -> screenAction { it.scroll(a.optString("direction", "down")) }
                "press_button" -> screenAction { it.pressButton(a.optString("button")) }
                "wait" -> {
                    delay(a.optInt("seconds", 2).coerceIn(1, 10) * 1000L)
                    withScreen(ok())
                }
                "find_contact" -> findContact(a.optString("name"))
                "make_call" -> makeCall(a.optString("to"))
                "send_sms" -> sendSms(a.optString("to"), a.optString("message"))
                "open_whatsapp_chat" -> openWhatsApp(a.optString("to"), a.optString("message"))
                "compose_email" -> composeEmail(a)
                "set_alarm" -> setAlarm(a)
                "set_timer" -> setTimer(a)
                "set_reminder" -> setReminder(a.optString("text"), a.optInt("in_minutes", 0))
                "create_calendar_event" -> createEvent(a)
                "navigate_to" -> navigate(a.optString("destination"))
                "play_media" -> playMedia(a.optString("query"))
                "media_control" -> mediaControl(a.optString("action"))
                "set_volume" -> setVolume(a.optInt("percent", 50))
                "set_flashlight" -> setFlashlight(a.optBoolean("on", true))
                "open_settings" -> openSettings(a.optString("page", "main"))
                "device_status" -> deviceStatus()
                "open_camera" -> openCamera()
                "read_notifications" -> readNotifications(a.optInt("limit", 10))
                "remember" -> {
                    val saved = withContext(Dispatchers.IO) { memory.remember(a.optString("fact")) }
                    if (saved) ok() else fail("There was nothing to remember.")
                }
                "forget" -> {
                    val removed = withContext(Dispatchers.IO) { memory.forget(a.optString("about")) }
                    ok().put("forgotten", JSONArray(removed))
                }
                "ask_desktop_spark" -> link.ask(a.optString("request"))
                else -> fail("Unknown tool: ${call.name}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SecurityException) {
            fail("Android refused that for lack of a permission. It can be granted in Spark settings.")
        } catch (e: Exception) {
            fail("That didn't work: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}")
        }
    }

    // ── Result helpers ───────────────────────────────────────────────────

    private fun ok(): JSONObject = JSONObject().put("ok", true)
    private fun fail(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Adds what the screen shows now, so the model rarely needs a separate read. */
    private suspend fun withScreen(result: JSONObject, settleMs: Long = 900): JSONObject {
        val service = a11y ?: return result
        delay(settleMs)
        return result.put("screen", service.readScreen().describe())
    }

    private val noScreenControl: JSONObject
        get() = fail(
            "Screen control is off. The user needs to enable “Spark phone control” under " +
                "Accessibility (there is a button for it in Spark settings)."
        )

    private fun appLabel(pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg.ifEmpty { "this app" }
    }

    /**
     * Starts an activity. From the background Android only allows this for
     * apps with a system-bound service, so the accessibility service's context
     * is used whenever it is available.
     */
    private fun launch(intent: Intent): JSONObject {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val service = a11y
        return try {
            (service ?: context).startActivity(intent)
            val result = ok()
            if (service == null && !uiVisible()) {
                result.put(
                    "note",
                    "Spark is in the background without phone control enabled, so Android " +
                        "may have blocked this from opening."
                )
            }
            result
        } catch (e: ActivityNotFoundException) {
            fail("No app on this phone can do that.")
        }
    }

    // ── Apps & web ───────────────────────────────────────────────────────

    private fun apps(): List<App> {
        val now = System.currentTimeMillis()
        if (appCache.isNotEmpty() && now - appCacheTime < 60_000) return appCache
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        appCache = pm.queryIntentActivities(intent, 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
        appCacheTime = now
        return appCache
    }

    private suspend fun openApp(name: String): JSONObject {
        val app = NameMatch.best(name, apps()) { it.label }
            ?: return fail("No installed app matches “$name”. Use list_apps to see what is installed.")
        val intent = context.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return fail("${app.label} can't be opened directly.")
        val result = launch(intent)
        if (!result.optBoolean("ok")) return result
        return withScreen(result.put("opened", app.label), settleMs = 1500)
    }

    private suspend fun openUrl(url: String): JSONObject {
        val clean = url.trim()
        val uri = Uri.parse(if (clean.contains("://")) clean else "https://$clean")
        if (uri.scheme != "http" && uri.scheme != "https") return fail("Only web links can be opened.")
        val result = launch(Intent(Intent.ACTION_VIEW, uri))
        return if (result.optBoolean("ok")) withScreen(result, settleMs = 2000) else result
    }

    // ── Screen control ───────────────────────────────────────────────────

    private fun readScreen(): JSONObject {
        val service = a11y ?: return noScreenControl
        val snapshot = service.readScreen()
        val result = ok().put("screen", snapshot.describe())
        if (snapshot.packageName == context.packageName) {
            result.put("note", "This is Spark's own screen. Open the app you need first.")
        }
        return result
    }

    private fun findElement(service: SparkAccessibilityService, args: JSONObject): ScreenElement? {
        if (args.has("index")) {
            return (service.lastSnapshot ?: service.readScreen()).byIndex(args.optInt("index"))
        }
        val text = args.optString("text")
        if (text.isBlank()) return null
        return service.readScreen().byText(text)
    }

    private suspend fun tap(args: JSONObject): JSONObject {
        val service = a11y ?: return noScreenControl
        val element = findElement(service, args)
            ?: return withScreen(fail("I can't find that on the screen. Here is what is there now."), 0)
        val done = service.tap(element, args.optBoolean("long_press", false))
        if (!done) return withScreen(fail("The tap didn't register; the screen may have changed."), 300)
        return withScreen(ok().put("tapped", element.label.ifBlank { element.kind }))
    }

    private suspend fun typeText(args: JSONObject): JSONObject {
        val service = a11y ?: return noScreenControl
        val node = if (args.has("index")) {
            val element = (service.lastSnapshot ?: service.readScreen()).byIndex(args.optInt("index"))
                ?: return fail("There is no element with that number. Read the screen again.")
            if (!element.editable) return fail("Element ${element.index} is not a text field.")
            element.node
        } else {
            service.focusedField()
                ?: service.readScreen().elements.firstOrNull { it.editable }?.node
                ?: return fail("There is no text field on the screen to type into.")
        }
        if (node.isPassword) return fail("That is a password field. Spark does not type passwords.")
        if (!service.setText(node, args.optString("text"))) {
            return fail("The app didn't accept typed text in that field.")
        }
        val result = ok()
        if (args.optBoolean("submit", false)) {
            delay(250)
            if (!service.imeEnter(node)) {
                result.put("note", "Text entered, but the enter key couldn't be pressed. Tap the send or search button instead.")
            }
        }
        return withScreen(result)
    }

    private suspend fun screenAction(action: suspend (SparkAccessibilityService) -> Boolean): JSONObject {
        val service = a11y ?: return noScreenControl
        return if (action(service)) withScreen(ok()) else fail("The phone didn't accept that action.")
    }

    // ── Contacts, calls, messages ────────────────────────────────────────

    private sealed class Recipient {
        class Found(val name: String, val number: String) : Recipient() {
            val display: String get() = if (name.isEmpty()) number else "$name ($number)"
        }
        class Ambiguous(val options: List<Pair<String, String>>) : Recipient()
        object NotFound : Recipient()
        object NoPermission : Recipient()
    }

    private suspend fun contacts(name: String): List<Pair<String, String>>? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        return withContext(Dispatchers.IO) {
            val out = ArrayList<Pair<String, String>>()
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val contact = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    if (NameMatch.score(name, contact) > 0) out.add(contact to number)
                }
            }
            out.distinctBy { it.first.lowercase() + "|" + NameMatch.digits(it.second).takeLast(10) }
                .sortedByDescending { NameMatch.score(name, it.first) }
        }
    }

    private suspend fun resolveRecipient(to: String): Recipient {
        val target = to.trim()
        if (target.isEmpty()) return Recipient.NotFound
        if (NameMatch.looksLikeNumber(target)) return Recipient.Found("", target)
        val matches = contacts(target) ?: return Recipient.NoPermission
        if (matches.isEmpty()) return Recipient.NotFound
        val top = NameMatch.score(target, matches[0].first)
        val best = matches.filter { NameMatch.score(target, it.first) == top }
        val people = best.map { it.first.lowercase() }.distinct()
        return if (people.size > 1 || best.size > 1) Recipient.Ambiguous(best.take(6))
        else Recipient.Found(best[0].first, best[0].second)
    }

    private fun recipientProblem(r: Recipient, to: String): JSONObject = when (r) {
        is Recipient.Ambiguous -> fail("More than one match for “$to”. Ask the user which one.")
            .put("matches", JSONArray(r.options.map { JSONObject().put("name", it.first).put("number", it.second) }))
        Recipient.NoPermission -> fail("Spark doesn't have permission to read contacts. It can be granted in Spark settings, or the user can give a number.")
        else -> fail("No contact matches “$to”.")
    }

    private suspend fun findContact(name: String): JSONObject {
        val matches = contacts(name)
            ?: return fail("Spark doesn't have permission to read contacts. It can be granted in Spark settings.")
        return ok().put(
            "contacts",
            JSONArray(matches.take(8).map { JSONObject().put("name", it.first).put("number", it.second) })
        )
    }

    private suspend fun makeCall(to: String): JSONObject {
        val r = resolveRecipient(to)
        if (r !is Recipient.Found) return recipientProblem(r, to)
        val uri = Uri.parse("tel:" + Uri.encode(r.number))
        return if (granted(Manifest.permission.CALL_PHONE)) {
            launch(Intent(Intent.ACTION_CALL, uri)).put("calling", r.display)
        } else {
            launch(Intent(Intent.ACTION_DIAL, uri)).put(
                "note",
                "The dialer is open with the number filled in, but Spark has no phone permission, so the user must press call."
            )
        }
    }

    private suspend fun sendSms(to: String, message: String): JSONObject {
        if (message.isBlank()) return fail("The message is empty.")
        val r = resolveRecipient(to)
        if (r !is Recipient.Found) return recipientProblem(r, to)
        if (!granted(Manifest.permission.SEND_SMS)) {
            val draft = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(r.number)))
                .putExtra("sms_body", message)
            return launch(draft).put(
                "note",
                "Spark has no SMS permission, so the message is drafted in the messaging app but NOT sent. The user must press send."
            )
        }
        val manager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = manager.divideMessage(message)
        if (parts.size > 1) manager.sendMultipartTextMessage(r.number, null, parts, null, null)
        else manager.sendTextMessage(r.number, null, message, null, null)
        return ok().put("sent_to", r.display)
    }

    private suspend fun openWhatsApp(to: String, message: String): JSONObject {
        val r = resolveRecipient(to)
        if (r !is Recipient.Found) return recipientProblem(r, to)
        val digits = NameMatch.digits(r.number).removePrefix("+")
        val uri = Uri.parse("https://wa.me/$digits" + if (message.isBlank()) "" else "?text=" + Uri.encode(message))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        val pm = context.packageManager
        listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pm.getLaunchIntentForPackage(it) != null }
            ?.let { intent.setPackage(it) }
        val result = launch(intent)
        if (!result.optBoolean("ok")) return result
        result.put("chat_with", r.display)
        result.put("note", "The message is typed but not sent. Tap the send button to send it.")
        return withScreen(result, settleMs = 2500)
    }

    private fun composeEmail(a: JSONObject): JSONObject {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        a.optString("to").takeIf { it.isNotBlank() }?.let { intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
        intent.putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"))
        intent.putExtra(Intent.EXTRA_TEXT, a.optString("body"))
        return launch(intent).put("note", "The draft is open. It has not been sent.")
    }

    // ── Time ─────────────────────────────────────────────────────────────

    private fun setAlarm(a: JSONObject): JSONObject {
        val hour = a.optInt("hour", -1)
        val minute = a.optInt("minute", 0)
        if (hour !in 0..23 || minute !in 0..59) return fail("That isn't a valid time.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return launch(intent)
    }

    private fun setTimer(a: JSONObject): JSONObject {
        val seconds = a.optInt("seconds", 0)
        if (seconds !in 1..86_400) return fail("Timers can be between one second and 24 hours.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return launch(intent)
    }

    private fun setReminder(text: String, minutes: Int): JSONObject {
        if (text.isBlank()) return fail("The reminder has no text.")
        if (minutes !in 1..(60 * 24 * 30)) return fail("Reminders can be from one minute to 30 days ahead.")
        val at = System.currentTimeMillis() + minutes * 60_000L
        val id = (at % Int.MAX_VALUE).toInt()
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_TEXT, text)
            .putExtra(ReminderReceiver.EXTRA_ID, id)
        val pending = PendingIntent.getBroadcast(
            context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        val result = ok().put("in_minutes", minutes)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            result.put("note", "Spark has no notification permission, so the reminder won't show until it is granted in Spark settings.")
        }
        return result
    }

    private fun createEvent(a: JSONObject): JSONObject {
        val start = try {
            LocalDateTime.parse(a.optString("start")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            return fail("The start time must look like 2026-03-14T15:30.")
        }
        val minutes = a.optInt("duration_minutes", 60).coerceIn(5, 60 * 24 * 7)
        val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, a.optString("title"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + minutes * 60_000L)
        a.optString("location").takeIf { it.isNotBlank() }
            ?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        return launch(intent).put("note", "The event is filled in. The user needs to press save.")
    }

    // ── Places & media ───────────────────────────────────────────────────

    private fun navigate(destination: String): JSONObject {
        if (destination.isBlank()) return fail("No destination given.")
        val maps = "com.google.android.apps.maps"
        return if (context.packageManager.getLaunchIntentForPackage(maps) != null) {
            launch(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination)))
                    .setPackage(maps)
            )
        } else {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination))))
        }
    }

    private suspend fun playMedia(query: String): JSONObject {
        if (query.isBlank()) return fail("Nothing to play was given.")
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)
        if (intent.resolveActivity(context.packageManager) != null) {
            val result = launch(intent)
            if (result.optBoolean("ok")) return result
        }
        return openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(query))
            .put("note", "No music app took the request, so YouTube search results are open. Tap a result to play it.")
    }

    private fun mediaControl(action: String): JSONObject {
        val key = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return fail("Unknown media action.")
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return ok()
    }

    // ── Device ───────────────────────────────────────────────────────────

    private fun setVolume(percent: Int): JSONObject {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = Math.round(percent.coerceIn(0, 100) / 100f * max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
        return ok().put("percent", Math.round(level * 100f / max))
    }

    private fun setFlashlight(on: Boolean): JSONObject {
        val cameras = context.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return fail("This phone has no flashlight.")
        cameras.setTorchMode(id, on)
        return ok().put("on", on)
    }

    private fun openSettings(page: String): JSONObject {
        val action = when (page) {
            "wifi" -> SystemSettings.ACTION_WIFI_SETTINGS
            "bluetooth" -> SystemSettings.ACTION_BLUETOOTH_SETTINGS
            "display" -> SystemSettings.ACTION_DISPLAY_SETTINGS
            "sound" -> SystemSettings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "apps" -> SystemSettings.ACTION_MANAGE_APPLICATIONS_SETTINGS
            "location" -> SystemSettings.ACTION_LOCATION_SOURCE_SETTINGS
            "mobile_data" -> SystemSettings.ACTION_DATA_ROAMING_SETTINGS
            "airplane_mode" -> SystemSettings.ACTION_AIRPLANE_MODE_SETTINGS
            "notifications" -> "android.settings.NOTIFICATION_SETTINGS"
            "storage" -> SystemSettings.ACTION_INTERNAL_STORAGE_SETTINGS
            "accessibility" -> SystemSettings.ACTION_ACCESSIBILITY_SETTINGS
            "date_time" -> SystemSettings.ACTION_DATE_SETTINGS
            else -> SystemSettings.ACTION_SETTINGS
        }
        val result = launch(Intent(action))
        return if (result.optBoolean("ok")) result else launch(Intent(SystemSettings.ACTION_SETTINGS))
    }

    private fun openCamera(): JSONObject {
        return launch(
            Intent(context, com.spark.companion.ui.VisionActivity::class.java)
        ).put("note", "The camera is open. The user can point it and ask what they want to know.")
    }

    private fun deviceStatus(): JSONObject {
        val result = ok()
        val battery = context.getSystemService(BatteryManager::class.java)
        result.put("battery_percent", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        result.put("charging", battery.isCharging)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        result.put(
            "network",
            when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                else -> "connected"
            }
        )
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        result.put("media_volume_percent", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
        result.put(
            "ringer",
            when (audio.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "ring"
            }
        )
        result.put("screen_control", a11y != null)
        a11y?.foregroundPackage?.takeIf { it.isNotEmpty() }?.let { result.put("app_on_screen", appLabel(it)) }
        return result
    }

    private fun readNotifications(limit: Int): JSONObject {
        if (!SparkNotificationListener.connected) {
            return fail("Spark can't see notifications yet. The user needs to turn on notification access for Spark (there is a button in Spark settings).")
        }
        val now = System.currentTimeMillis()
        val items = SparkNotificationListener.recent(limit.coerceIn(1, 30)).map {
            JSONObject()
                .put("app", it.app)
                .put("title", it.title)
                .put("text", it.text)
                .put("minutes_ago", (now - it.time) / 60_000)
        }
        return ok().put("notifications", JSONArray(items))
    }

    companion object {
        /** Fields where pressing enter just runs a search, which is harmless. */
        private val SEARCH_FIELD = Regex("search|find|look up|url|web address|type here to search", RegexOption.IGNORE_CASE)
    }
}
