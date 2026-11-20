package com.spark.companion.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import com.spark.companion.SparkApp
import com.spark.companion.assist.SparkVoiceInteractionService
import com.spark.companion.phone.SparkAccessibilityService
import com.spark.companion.phone.SparkNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Setup and preferences: keys, voice options, and the permissions Spark needs. */
class SettingsActivity : Activity() {

    private val engine get() = SparkApp.engine
    private val settings get() = engine.settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var modelRefresh: () -> Unit = {}

    private lateinit var content: LinearLayout
    private lateinit var permissionRows: LinearLayout
    private val savers = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 20)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, Ui.dp(this@SettingsActivity, 48))
        }
        setContentView(ScrollView(this).apply {
            background = Ui.backdrop()
            fitsSystemWindows = true
            addView(content)
        })
        build()
    }

    override fun onResume() {
        super.onResume()
        renderPermissions()
        // Pull in newly released models when the lists are over an hour old.
        engine.refreshModelListsIfStale(60L * 60 * 1000) {
            if (!isDestroyed) {
                modelRefresh()
                dropdowns.forEach { d -> d.render() }
            }
        }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun save() {
        savers.forEach { it() }
        engine.refreshSettings()
    }

    // ── Building blocks ──────────────────────────────────────────────────

    private fun section(title: String, blurb: String? = null) {
        content.addView(Ui.text(this, title, 13f, Ui.ACCENT, bold = true).apply {
            letterSpacing = 0.12f
            setPadding(0, Ui.dp(this@SettingsActivity, 26), 0, Ui.dp(this@SettingsActivity, 6))
        })
        if (blurb != null) note(blurb)
    }

    private fun note(text: String) {
        content.addView(Ui.text(this, text, 13.5f, Ui.TEXT_DIM).apply {
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, Ui.dp(this@SettingsActivity, 8))
        })
    }

    private fun field(
        label: String,
        value: String,
        hint: String = "",
        secret: Boolean = false,
        onSave: (String) -> Unit,
    ): EditText {
        content.addView(Ui.text(this, label, 13.5f, Ui.TEXT_DIM).apply {
            setPadding(0, Ui.dp(this@SettingsActivity, 10), 0, Ui.dp(this@SettingsActivity, 4))
        })
        val edit = EditText(this).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(Ui.TEXT_DIM)
            setTextColor(Ui.TEXT)
            textSize = 15f
            setSingleLine(true)
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            background = Ui.rounded(Ui.SURFACE, Ui.dp(this@SettingsActivity, 12).toFloat())
            setPadding(Ui.dp(this@SettingsActivity, 14), Ui.dp(this@SettingsActivity, 12), Ui.dp(this@SettingsActivity, 14), Ui.dp(this@SettingsActivity, 12))
        }
        content.addView(edit)
        savers.add { onSave(edit.text.toString()) }
        return edit
    }

    private fun toggle(label: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(this@SettingsActivity, 10), 0, Ui.dp(this@SettingsActivity, 10))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, label, 15.5f))
        texts.addView(Ui.text(this, detail, 13f, Ui.TEXT_DIM))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked ->
                onChange(checked)
                engine.refreshSettings()
            }
        })
        content.addView(row)
    }

    private fun action(label: String, fill: Int = Ui.SURFACE_HI, textColor: Int = Ui.TEXT, onClick: () -> Unit) {
        content.addView(
            Ui.button(this, label, fill, textColor, onClick),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(this@SettingsActivity, 10) }
        )
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private val dropdowns = ArrayList<ModelDropdown>()

    /** A drop-down of the provider's current models; picking one saves it at once. */
    private fun modelField(label: String, listKey: String, value: String, onSave: (String) -> Unit): ModelDropdown {
        content.addView(Ui.text(this, label, 13.5f, Ui.TEXT_DIM).apply {
            setPadding(0, Ui.dp(this@SettingsActivity, 10), 0, Ui.dp(this@SettingsActivity, 4))
        })
        val dropdown = ModelDropdown(this, label, listKey, value, engine.modelLists, { refreshModels(false) }) {
            onSave(it)
            engine.refreshSettings()
        }
        content.addView(dropdown.view)
        savers.add { onSave(dropdown.value) }
        dropdowns.add(dropdown)
        return dropdown
    }

    private fun refreshModels(pickBest: Boolean) {
        save()
        toast("Checking what each provider offers…")
        scope.launch {
            val result = engine.refreshModelLists(pickBest)
            modelRefresh()
            dropdowns.forEach { it.render() }
            engine.refreshSettings()
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(if (result.added.isEmpty()) "Model lists updated" else "New models found")
                .setMessage(result.summary)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ── Screen ───────────────────────────────────────────────────────────

    private fun build() {
        content.addView(Ui.text(this, "Spark settings", 24f, Ui.TEXT, bold = true))

        section("YOU")
        field("What should Spark call you?", settings.userName, "Your name") { settings.userName = it }

        section(
            "CLAUDE",
            "Spark thinks with Claude, by Anthropic: conversation, phone control and camera vision. " +
                "Create a key at console.anthropic.com → API keys (usage is billed to your Anthropic account). " +
                "The key is stored encrypted on this phone."
        )
        field("Claude API key", settings.claudeApiKey, "sk-ant-…", secret = true) { settings.claudeApiKey = it }
        val claudeModel = modelField("Claude model", com.spark.companion.core.ModelListStore.CLAUDE, settings.claudeModel) { settings.claudeModel = it }
        modelRefresh = { claudeModel.set(settings.claudeModel) }
        note("Claude Opus 5.5 is the default and Anthropic's recommended model. The list comes from your key and updates by itself, so new Claude models appear (marked NEW). If Claude is busy, Spark briefly uses another Claude model so you still get an answer.")
        action("Refresh model list") { refreshModels(pickBest = false) }
        action("Test Claude") {
            save()
            toast("Testing…")
            scope.launch {
                val result = engine.testBrain()
                modelRefresh()
                toast(result)
            }
        }

        section(
            "PICTURES",
            "Claude doesn't make pictures. For realistic avatars and AI-styled portraits, add an OpenAI API key " +
                "(platform.openai.com → API keys; pictures are billed to your OpenAI account). With it, your realistic " +
                "avatar keeps your real face. Without it, Spark uses the free Pollinations service, which is slower, " +
                "limited and can't keep your likeness."
        )
        field("OpenAI API key (optional)", settings.openAiApiKey, "sk-…", secret = true) { settings.openAiApiKey = it }
        val imageModel = modelField("OpenAI picture model", com.spark.companion.core.ModelListStore.OPENAI_IMAGES, settings.openAiImageModel) { settings.openAiImageModel = it }
        val refreshClaude = modelRefresh
        modelRefresh = {
            refreshClaude()
            imageModel.set(settings.openAiImageModel)
        }
        note("Flare is the fast model; Sunburst draws more detail. The list comes from your key and updates when you tap Refresh model list.")

        section("AVATAR", "Pick who Spark appears as, or create your own from a photo or a description.")
        action("Open Avatar Studio", Ui.ACCENT, Ui.BG) {
            startActivity(Intent(this, AvatarStudioActivity::class.java))
        }

        section(
            "LANGUAGES",
            "Tick every language you speak; put a tick on your main one first. Spark answers in whichever you use, " +
                "with a matching voice, and switches when you do."
        )
        val chosen = settings.languages.toMutableList()
        val boxes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (lang in com.spark.companion.brain.Languages.ALL) {
            boxes.addView(android.widget.CheckBox(this).apply {
                text = if (lang.native == lang.english || lang.native == "English") lang.english else "${lang.english} · ${lang.native}"
                setTextColor(Ui.TEXT)
                textSize = 15f
                isChecked = lang.tag in chosen
                setOnCheckedChangeListener { _, checked ->
                    if (checked) { if (lang.tag !in chosen) chosen.add(lang.tag) } else chosen.remove(lang.tag)
                    if (chosen.isNotEmpty()) settings.languages = chosen
                }
            })
        }
        content.addView(boxes)
        note("Main language now: the first one you ticked. For the best accent, install voices for your languages in the phone's Settings → Text-to-speech.")
        action("Phone text-to-speech settings") {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e: Exception) {
                open(SystemSettings.ACTION_SETTINGS)
            }
        }

        section("VOICE")
        toggle("Speak replies aloud", "Turn off for silent, text-only answers", settings.speakReplies) {
            settings.speakReplies = it
        }
        toggle(
            "Keep the conversation going", "Listen again after each reply until you stop talking",
            settings.continuous
        ) { settings.continuous = it }
        action("Hear my avatar") {
            save()
            engine.testVoice()
            toast("Listen…")
        }
        toggle(
            "Floating avatar",
            "Keep the sleeping avatar on screen over other apps. It always appears when it wakes up (needs phone control or “Show over other apps”).",
            settings.floatingAvatar
        ) { settings.floatingAvatar = it }
        toggle(
            "Proactive heads-up",
            "When a new message or notification arrives, Spark speaks a short summary (only when it's free and not already talking).",
            settings.proactiveEnabled
        ) { settings.proactiveEnabled = it }
        toggle(
            "Wake word",
            "Always listen for the wake word, so saying it wakes the avatar from any screen. Android shows its green mic dot the whole time this is on; that's its privacy indicator and can't be hidden. On Android 13+ the mic stays on steadily; on older phones it restarts between phrases and may blink or beep. Uses some battery.",
            settings.wakeWordEnabled
        ) { on ->
            settings.wakeWordEnabled = on
            if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        field("Wake word", settings.wakeWord, "spark") { settings.wakeWord = it }

        section(
            "PERMISSIONS",
            "Each of these unlocks something Spark can do for you. Spark always asks before it sends a " +
                "message, places a call, pays, deletes, or touches a banking app."
        )
        permissionRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(permissionRows)
        note(
            "If Android says a setting is “restricted” when you enable phone control or notification access: " +
                "open App info for Spark, tap the ⋮ menu at the top right, choose “Allow restricted settings”, then try again."
        )
        action("Open App info for Spark") {
            startActivity(
                Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        section(
            "DESKTOP SPARK (OPTIONAL)",
            "Link to the SPARK system on your computer for research, documents and code. Start it with " +
                "“python main.py --api” and enter its address. Use this on your home network only."
        )
        field("Server address", settings.serverUrl, "http://192.168.1.20:8765") { settings.serverUrl = it }
        field("Access token (SPARK_API_TOKEN)", settings.serverToken, "optional", secret = true) {
            settings.serverToken = it
        }
        action("Test desktop link") {
            save()
            scope.launch {
                toast(if (engine.testDesktopLink()) "Connected to SPARK on your computer." else "Couldn't reach that address.")
            }
        }

        section(
            "n8n WORKFLOWS",
            "Let Spark run your n8n automations. Enter your n8n webhook address; Spark can then trigger workflows by name (and routines can too). Use on your own network."
        )
        field("n8n webhook URL", settings.n8nUrl, "http://192.168.1.20:5678") { settings.n8nUrl = it }
        field("Auth header (optional)", settings.n8nAuth, "Header-Name: value, or a token", secret = true) { settings.n8nAuth = it }

        section("MORE", "Spark's camera, and automations that run on their own.")
        action("Open Spark Vision (camera)") { startActivity(Intent(this, VisionActivity::class.java)) }
        action("Open Routines") { startActivity(Intent(this, RoutinesActivity::class.java)) }
        action("Add the quick tile / widget") {
            toast("Add Spark's tile from the quick-settings edit screen, and its widget by long-pressing the home screen.")
        }

        section("MEMORY", "Everything Spark remembers stays in a private database on this phone.")
        action("Forget everything Spark knows about me", Ui.SURFACE_HI, Ui.DANGER) {
            AlertDialog.Builder(this)
                .setTitle("Forget everything?")
                .setMessage("This deletes the conversation and every fact Spark has remembered. It cannot be undone.")
                .setPositiveButton("Forget") { _, _ ->
                    engine.forgetEverything()
                    toast("Memory cleared.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Permissions ──────────────────────────────────────────────────────

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun isDefaultAssistant(): Boolean {
        val current = SystemSettings.Secure.getString(contentResolver, "voice_interaction_service") ?: return false
        return ComponentName.unflattenFromString(current)?.packageName == packageName &&
            current.contains(SparkVoiceInteractionService::class.java.simpleName)
    }

    private fun renderPermissions() {
        permissionRows.removeAllViews()
        permissionRow("Microphone", "So Spark can hear you", has(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (com.spark.companion.BuildConfig.FULL_FEATURES) permissionRow(
            "Phone control", "Read the screen and tap, type and scroll in other apps (Accessibility)",
            SparkAccessibilityService.instance != null
        ) { open(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS) }
        permissionRow(
            "Show over other apps", "Let the avatar pop up over any app when it wakes",
            SystemSettings.canDrawOverlays(this) || SparkAccessibilityService.instance != null
        ) {
            try {
                startActivity(Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                open(SystemSettings.ACTION_SETTINGS)
            }
        }
        permissionRow("Contacts", "Call and message people by name", has(Manifest.permission.READ_CONTACTS)) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1)
        }
        permissionRow("Phone calls", "Place calls for you (after you confirm)", has(Manifest.permission.CALL_PHONE)) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 1)
        }
        if (com.spark.companion.BuildConfig.FULL_FEATURES) permissionRow("SMS", "Send texts for you (after you confirm)", has(Manifest.permission.SEND_SMS)) {
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permissionRow("Notifications", "Show reminders", has(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        permissionRow(
            "Notification access", "Tell you who messaged and what arrived", SparkNotificationListener.connected
        ) { open(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        permissionRow(
            "Default assistant", "Summon Spark with the assistant gesture or button", isDefaultAssistant()
        ) { open(SystemSettings.ACTION_VOICE_INPUT_SETTINGS) }
    }

    private fun permissionRow(title: String, detail: String, granted: Boolean, onGrant: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.SURFACE, Ui.dp(this@SettingsActivity, 14).toFloat())
            setPadding(Ui.dp(this@SettingsActivity, 14), Ui.dp(this@SettingsActivity, 12), Ui.dp(this@SettingsActivity, 12), Ui.dp(this@SettingsActivity, 12))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, title, 15.5f))
        texts.addView(Ui.text(this, detail, 13f, Ui.TEXT_DIM))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val badge: View = if (granted) {
            Ui.text(this, "On", 14f, Ui.OK, bold = true).apply {
                setPadding(Ui.dp(this@SettingsActivity, 12), 0, Ui.dp(this@SettingsActivity, 6), 0)
            }
        } else {
            Ui.button(this, "Turn on", onClick = onGrant)
        }
        row.addView(badge)
        permissionRows.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Ui.dp(this@SettingsActivity, 8) })
    }

    private fun open(action: String) {
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            try {
                startActivity(Intent(SystemSettings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                toast("Couldn't open system settings.")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderPermissions()
        engine.refreshSettings()
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            toast("If Android didn't ask, turn it on under App info → Permissions.")
        }
    }

}
