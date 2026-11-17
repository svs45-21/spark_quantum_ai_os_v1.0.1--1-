package com.spark.companion.brain

import org.json.JSONArray
import org.json.JSONObject

/** What the user is asked before a risky action runs. */
data class ConfirmRequest(val summary: String)

/** Executes tool calls against the phone. Implemented by PhoneTools. */
interface ToolHost {
    /**
     * Returns a confirmation request if this call must be approved by the user
     * first, or null if it can run straight away.
     */
    suspend fun confirmationFor(call: ToolCall): ConfirmRequest?

    /** Runs the call and returns a JSON result for the model. Never throws. */
    suspend fun execute(call: ToolCall): JSONObject

    /** Short present-tense description for the status line, e.g. "Opening Maps". */
    fun describe(call: ToolCall): String
}

/** Function declarations sent to the model. */
object ToolCatalog {

    private fun str(desc: String) = JSONObject().put("type", "STRING").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "INTEGER").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "BOOLEAN").put("description", desc)
    private fun enum(desc: String, vararg values: String) =
        str(desc).put("enum", JSONArray(values.toList()))

    private fun tool(
        name: String,
        description: String,
        props: List<Pair<String, JSONObject>> = emptyList(),
        required: List<String> = emptyList(),
    ): JSONObject {
        val decl = JSONObject().put("name", name).put("description", description)
        if (props.isNotEmpty()) {
            val properties = JSONObject()
            props.forEach { (key, schema) -> properties.put(key, schema) }
            val params = JSONObject().put("type", "OBJECT").put("properties", properties)
            if (required.isNotEmpty()) params.put("required", JSONArray(required))
            decl.put("parameters", params)
        }
        return decl
    }

    val names: List<String> by lazy {
        val all = declarations(includeDesktopLink = true, includeWorkflow = true)
        (0 until all.length()).map { all.getJSONObject(it).getString("name") }
    }

    fun declarations(includeDesktopLink: Boolean, includeWorkflow: Boolean = false): JSONArray {
        val list = mutableListOf(
            // ── Apps & web ─────────────────────────────────────────────
            tool(
                "open_app", "Open an installed app by its name (e.g. 'WhatsApp', 'Camera').",
                listOf("name" to str("App name as the user said it")), listOf("name")
            ),
            tool("list_apps", "List the names of installed apps. Use when open_app can't find a match."),
            tool(
                "open_url", "Open a web page in the browser.",
                listOf("url" to str("Full https URL")), listOf("url")
            ),
            tool(
                "web_search", "Search the web in the browser and show the results to the user.",
                listOf("query" to str("Search terms")), listOf("query")
            ),
            // ── Screen control (accessibility) ─────────────────────────
            tool(
                "read_screen",
                "Read what is currently on the phone screen. Returns numbered elements like " +
                    "[7] Button \"Send\". Use the numbers with tap and type_text. Call this before " +
                    "interacting with an app, and again whenever the screen may have changed."
            ),
            tool(
                "tap",
                "Tap an element on screen. Give either the element number from the latest " +
                    "read_screen, or the visible text of the element.",
                listOf(
                    "index" to int("Element number from read_screen"),
                    "text" to str("Visible text or label of the element"),
                    "long_press" to bool("Press and hold instead of a normal tap"),
                )
            ),
            tool(
                "type_text",
                "Type text into a text field. Without index, types into the focused field.",
                listOf(
                    "text" to str("Text to enter (replaces what is in the field)"),
                    "index" to int("Element number of the field from read_screen"),
                    "submit" to bool("Press enter/search/send on the keyboard afterwards"),
                ),
                listOf("text")
            ),
            tool(
                "scroll", "Scroll the current screen.",
                listOf("direction" to enum("Which way to move through the content", "down", "up", "left", "right")),
                listOf("direction")
            ),
            tool(
                "press_button", "Press a system button.",
                listOf(
                    "button" to enum(
                        "Which button", "back", "home", "recents", "notifications",
                        "quick_settings", "lock_screen", "screenshot"
                    )
                ),
                listOf("button")
            ),
            tool(
                "wait", "Pause briefly to let an app finish loading, then read the screen.",
                listOf("seconds" to int("1 to 10")), listOf("seconds")
            ),
            // ── People & messaging ─────────────────────────────────────
            tool(
                "find_contact", "Look up phone numbers in the user's contacts by name.",
                listOf("name" to str("Full or partial contact name")), listOf("name")
            ),
            tool(
                "make_call", "Place a phone call. The user is asked to confirm first.",
                listOf("to" to str("Phone number, or a contact name")), listOf("to")
            ),
            tool(
                "send_sms", "Send an SMS text message. The user is asked to confirm first.",
                listOf(
                    "to" to str("Phone number, or a contact name"),
                    "message" to str("Message text"),
                ),
                listOf("to", "message")
            ),
            tool(
                "open_whatsapp_chat",
                "Open a WhatsApp chat with the message typed in but NOT sent. To send it, " +
                    "read_screen and tap the send button (the user will be asked to confirm).",
                listOf(
                    "to" to str("Phone number with country code, or a contact name"),
                    "message" to str("Message to pre-fill"),
                ),
                listOf("to")
            ),
            tool(
                "compose_email", "Open an email draft in the user's mail app. Does not send it.",
                listOf(
                    "to" to str("Recipient email address"),
                    "subject" to str("Subject line"),
                    "body" to str("Email body"),
                )
            ),
            // ── Time ───────────────────────────────────────────────────
            tool(
                "set_alarm", "Set an alarm in the clock app.",
                listOf(
                    "hour" to int("Hour, 0-23"),
                    "minute" to int("Minute, 0-59"),
                    "label" to str("Optional alarm label"),
                ),
                listOf("hour", "minute")
            ),
            tool(
                "set_timer", "Start a countdown timer in the clock app.",
                listOf("seconds" to int("Length in seconds"), "label" to str("Optional label")),
                listOf("seconds")
            ),
            tool(
                "set_reminder",
                "Remind the user about something later with a notification from Spark.",
                listOf(
                    "text" to str("What to remind them about"),
                    "in_minutes" to int("Minutes from now"),
                ),
                listOf("text", "in_minutes")
            ),
            tool(
                "create_calendar_event",
                "Open the calendar with a new event filled in for the user to save.",
                listOf(
                    "title" to str("Event title"),
                    "start" to str("Local start time as yyyy-MM-ddTHH:mm"),
                    "duration_minutes" to int("Length in minutes (default 60)"),
                    "location" to str("Optional location"),
                ),
                listOf("title", "start")
            ),
            // ── Places & media ─────────────────────────────────────────
            tool(
                "navigate_to", "Start navigation to a place in the maps app.",
                listOf("destination" to str("Place name or address")), listOf("destination")
            ),
            tool(
                "play_media", "Play music or a video by searching for it.",
                listOf("query" to str("Song, artist, album, or video to play")), listOf("query")
            ),
            tool(
                "media_control", "Control whatever is playing.",
                listOf("action" to enum("What to do", "play", "pause", "toggle", "next", "previous")),
                listOf("action")
            ),
            // ── Device ─────────────────────────────────────────────────
            tool(
                "set_volume", "Set the media volume.",
                listOf("percent" to int("0 to 100")), listOf("percent")
            ),
            tool(
                "set_flashlight", "Turn the flashlight on or off.",
                listOf("on" to bool("true for on")), listOf("on")
            ),
            tool(
                "open_settings", "Open a page of the phone's system settings.",
                listOf(
                    "page" to enum(
                        "Which settings page", "main", "wifi", "bluetooth", "display", "sound",
                        "battery", "apps", "location", "mobile_data", "airplane_mode",
                        "notifications", "storage", "accessibility", "date_time"
                    )
                ),
                listOf("page")
            ),
            tool(
                "device_status",
                "Get battery level, charging state, network, volume and which app is on screen."
            ),
            tool(
                "open_camera",
                "Open Spark's camera so the user can show you something and you can see it — for 'what is this', reading or translating text, or describing what's in front of them."
            ),
            tool(
                "read_notifications",
                "Read the user's recent notifications (who messaged, what arrived).",
                listOf("limit" to int("How many, newest first (default 10)"))
            ),
            // ── Memory & conversation ──────────────────────────────────
            tool(
                "remember",
                "Save a lasting fact about the user (preferences, people, routines, plans) so " +
                    "you know it in future conversations. Use it whenever you learn something " +
                    "worth keeping; don't ask permission for ordinary facts.",
                listOf("fact" to str("One self-contained sentence")), listOf("fact")
            ),
            tool(
                "forget", "Delete remembered facts that mention the given words.",
                listOf("about" to str("Words identifying what to forget")), listOf("about")
            ),
            tool(
                "end_conversation",
                "Stop listening after your reply. Use when the user says goodbye, thanks you " +
                    "and is clearly done, or asks you to stop listening."
            ),
        )
        if (includeDesktopLink) {
            list += tool(
                "ask_desktop_spark",
                "Hand a heavy job to the SPARK system on the user's computer: research with " +
                    "sources, writing documents (Word, PDF, PowerPoint, Excel), writing or running " +
                    "code, or controlling the PC. Returns what SPARK replied.",
                listOf("request" to str("The full request in plain language")), listOf("request")
            )
        }
        if (includeWorkflow) {
            list += tool(
                "trigger_workflow",
                "Run one of the user's own n8n automation workflows by its webhook name, passing " +
                    "the request to it, and return what it replies. Use for anything the user has " +
                    "set up in n8n (email, calendar, home automation, external services).",
                listOf(
                    "workflow" to str("The workflow's webhook name/path, e.g. 'send-email' or 'daily-report'"),
                    "input" to str("What to send to the workflow, in plain language"),
                ),
                listOf("workflow")
            )
        }
        return JSONArray(list)
    }
}
