package com.spark.companion.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spark.companion.SparkApp
import com.spark.companion.engine.EngineState
import com.spark.companion.engine.Phase
import com.spark.companion.memory.ChatMessage
import com.spark.companion.ui.avatar.AvatarStyle
import com.spark.companion.ui.avatar.AvatarView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The companion screen: the avatar, the conversation, and a text box. */
class MainActivity : Activity() {

    private val engine get() = SparkApp.engine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var avatar: AvatarView
    private lateinit var status: TextView
    private lateinit var wakeChip: TextView
    private lateinit var styleRow: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private lateinit var setupBanner: TextView
    private lateinit var confirmCard: LinearLayout
    private lateinit var confirmText: TextView
    private var rendered = 0
    private var firstRenderedId = -1L
    private var listenAfterPermission = false
    private var askedForMic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        observe()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        engine.uiVisible.value = true
        setupBanner.visibility = if (engine.settings.apiKey.isEmpty()) View.VISIBLE else View.GONE
        renderWakeChip()
        // The wake word is on by default and needs the microphone: ask once.
        if (engine.settings.wakeWordEnabled && !micGranted() && !askedForMic) {
            askedForMic = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        } else {
            engine.refreshSettings()
        }
    }

    override fun onPause() {
        engine.uiVisible.value = false
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val wantsVoice = intent.getBooleanExtra(EXTRA_LISTEN, false) ||
            intent.action == Intent.ACTION_ASSIST ||
            intent.action == Intent.ACTION_VOICE_COMMAND
        if (wantsVoice) {
            intent.removeExtra(EXTRA_LISTEN)
            if (!engine.busy) startVoice()
        }
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ── Voice ────────────────────────────────────────────────────────────

    private fun startVoice() {
        if (!micGranted()) {
            listenAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        engine.startVoice(greet = !engine.awake.value)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        engine.refreshSettings()
        renderWakeChip()
        if (listenAfterPermission) {
            listenAfterPermission = false
            if (granted) engine.startVoice(greet = true)
        }
    }

    // ── State → UI ───────────────────────────────────────────────────────

    private fun observe() {
        scope.launch { engine.messages.collect { renderMessages(it) } }
        scope.launch { engine.level.collect { avatar.level = it } }
        scope.launch { engine.mood.collect { avatar.mood = it } }
        scope.launch {
            engine.avatarStyle.collect { style ->
                avatar.style = style
                renderStyleRow(style)
            }
        }
        scope.launch {
            combine(engine.state, engine.partial, engine.caption, engine.awake) { state, partial, caption, awake ->
                Quad(state, partial, caption, awake)
            }.collect { (state, partial, caption, awake) -> renderState(state, partial, caption, awake) }
        }
        scope.launch {
            engine.pendingConfirm.collect { pending ->
                if (pending == null) {
                    confirmCard.visibility = View.GONE
                } else {
                    confirmText.text = pending.summary
                    confirmCard.visibility = View.VISIBLE
                }
            }
        }
    }

    private data class Quad(val state: EngineState, val partial: String, val caption: String, val awake: Boolean)

    private fun renderState(state: EngineState, partial: String, caption: String, awake: Boolean) {
        avatar.awake = awake
        avatar.phase = state.phase
        val name = engine.avatarStyle.value.displayName
        status.text = when (state.phase) {
            Phase.IDLE -> when {
                awake -> "Tap me to talk"
                engine.settings.wakeWordEnabled && micGranted() ->
                    "$name is asleep. Say “${engine.settings.wakeWord.replaceFirstChar { it.uppercase() }}” to wake me up"
                else -> "$name is asleep. Tap to wake me up"
            }
            Phase.LISTENING -> partial.ifBlank { state.detail.ifBlank { "I'm listening…" } }
            Phase.THINKING -> "Thinking…"
            Phase.ACTING -> state.detail.ifBlank { "Working on it…" }
            Phase.SPEAKING -> caption.ifBlank { "…" }
        }
    }

    private fun renderWakeChip() {
        val on = engine.settings.wakeWordEnabled
        val word = engine.settings.wakeWord.replaceFirstChar { it.uppercase() }
        wakeChip.text = if (on) "Wake word “$word”: on" else "Wake word: off"
        wakeChip.setTextColor(if (on) Ui.BG else Ui.TEXT_DIM)
        wakeChip.background = Ui.rounded(if (on) Ui.OK else Ui.SURFACE, Ui.dp(this, 14).toFloat())
    }

    private fun renderStyleRow(selected: AvatarStyle) {
        styleRow.removeAllViews()
        for (style in AvatarStyle.values()) {
            val chosen = style == selected
            val chip = Ui.text(this, style.displayName, 14f, if (chosen) Ui.BG else Ui.TEXT, bold = chosen).apply {
                gravity = Gravity.CENTER
                background = Ui.rounded(if (chosen) style.accent else Ui.SURFACE, Ui.dp(this@MainActivity, 16).toFloat())
                setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 7), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 7))
                contentDescription = "${style.displayName}, ${style.tagline}"
                setOnClickListener {
                    engine.settings.avatar = style.name
                    engine.refreshSettings()
                }
            }
            styleRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(this@MainActivity, 4); marginEnd = Ui.dp(this@MainActivity, 4) })
        }
    }

    private fun renderMessages(messages: List<ChatMessage>) {
        val firstId = messages.firstOrNull()?.id ?: -1L
        if (messages.size < rendered || firstId != firstRenderedId) {
            list.removeAllViews()
            rendered = 0
            firstRenderedId = firstId
        }
        for (i in rendered until messages.size) list.addView(bubble(messages[i]))
        rendered = messages.size
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(message: ChatMessage): View {
        val text = Ui.text(this, message.text, 15.5f, if (message.fromUser) Ui.BG else Ui.TEXT).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
            background = Ui.rounded(if (message.fromUser) Ui.ACCENT else Ui.SURFACE, Ui.dp(this@MainActivity, 18).toFloat())
            setPadding(Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 10), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 10))
        }
        return FrameLayout(this).apply {
            val side = Ui.dp(this@MainActivity, 48)
            setPadding(
                if (message.fromUser) side else 0, Ui.dp(this@MainActivity, 4),
                if (message.fromUser) 0 else side, Ui.dp(this@MainActivity, 4)
            )
            addView(
                text,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (message.fromUser) Gravity.END else Gravity.START
                )
            )
        }
    }

    // ── Layout ───────────────────────────────────────────────────────────

    private fun buildUi(): View {
        val pad = Ui.dp(this, 16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.BG_TOP, Ui.BG))
            fitsSystemWindows = true
        }

        // Top bar.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, Ui.dp(this@MainActivity, 10), pad, 0)
        }
        bar.addView(
            Ui.text(this, "SPARK", 18f, Ui.ACCENT, bold = true).apply { letterSpacing = 0.25f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(Ui.text(this, "New chat", 14f, Ui.TEXT_DIM).apply {
            setPadding(pad, Ui.dp(this@MainActivity, 8), pad, Ui.dp(this@MainActivity, 8))
            setOnClickListener { engine.clearConversation() }
        })
        bar.addView(Ui.text(this, "Settings", 14f, Ui.TEXT_DIM).apply {
            setPadding(pad, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 8))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        root.addView(bar)

        // First-run banner.
        setupBanner = Ui.text(this, "Add your Gemini API key to wake me up  →", 14.5f, Ui.BG, bold = true).apply {
            background = Ui.rounded(Ui.ACCENT, Ui.dp(this@MainActivity, 14).toFloat())
            setPadding(pad, Ui.dp(this@MainActivity, 12), pad, Ui.dp(this@MainActivity, 12))
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        root.addView(setupBanner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(pad, Ui.dp(this@MainActivity, 10), pad, 0) })

        // The avatar.
        avatar = AvatarView(this).apply {
            contentDescription = "Talk to Spark"
            setOnClickListener { if (engine.busy) engine.stop() else startVoice() }
        }
        root.addView(avatar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 250)))

        // Character picker and wake-word switch.
        styleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        root.addView(styleRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        wakeChip = Ui.text(this, "", 13f, Ui.TEXT_DIM, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 6))
            setOnClickListener {
                engine.settings.wakeWordEnabled = !engine.settings.wakeWordEnabled
                if (engine.settings.wakeWordEnabled && !micGranted()) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                }
                engine.refreshSettings()
                renderWakeChip()
                renderState(engine.state.value, engine.partial.value, engine.caption.value, engine.awake.value)
            }
        }
        root.addView(wakeChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dp(this@MainActivity, 8) })

        // Status / subtitles.
        status = Ui.text(this, "", 15f, Ui.TEXT).apply {
            gravity = Gravity.CENTER
            maxLines = 3
            setPadding(pad, Ui.dp(this@MainActivity, 10), pad, Ui.dp(this@MainActivity, 6))
        }
        root.addView(status)

        // Conversation.
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(this@MainActivity, 4), pad, Ui.dp(this@MainActivity, 8))
        }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Confirmation card.
        confirmText = Ui.text(this, "", 15.5f)
        confirmCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.SURFACE_HI, Ui.dp(this@MainActivity, 18).toFloat(), Ui.ACCENT, Ui.dp(this@MainActivity, 1))
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
            addView(Ui.text(this@MainActivity, "Shall I go ahead?", 13f, Ui.ACCENT, bold = true))
            addView(confirmText)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, Ui.dp(this@MainActivity, 12), 0, 0)
                addView(Ui.button(this@MainActivity, "Don't", Ui.SURFACE, Ui.TEXT) { engine.answerConfirm(false) })
                addView(
                    Ui.button(this@MainActivity, "Go ahead") { engine.answerConfirm(true) },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = Ui.dp(this@MainActivity, 10) }
                )
            })
        }
        root.addView(confirmCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(pad, 0, pad, Ui.dp(this@MainActivity, 8)) })

        // Text input.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, Ui.dp(this@MainActivity, 12))
        }
        input = EditText(this).apply {
            hint = "Message Spark"
            setHintTextColor(Ui.TEXT_DIM)
            setTextColor(Ui.TEXT)
            textSize = 15.5f
            background = Ui.rounded(Ui.SURFACE, Ui.dp(this@MainActivity, 24).toFloat())
            setPadding(Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            Ui.button(this, "Send") { send() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(this@MainActivity, 8) }
        )
        root.addView(row)
        return root
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        engine.submitText(text)
    }

    companion object {
        const val EXTRA_LISTEN = "listen"
        private const val REQUEST_MIC = 7
    }
}
