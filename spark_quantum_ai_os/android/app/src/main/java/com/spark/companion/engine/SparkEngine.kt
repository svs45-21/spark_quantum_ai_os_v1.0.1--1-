package com.spark.companion.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.spark.companion.brain.ActionPolicy
import com.spark.companion.brain.Agent
import com.spark.companion.brain.ConfirmRequest
import com.spark.companion.brain.GeminiClient
import com.spark.companion.brain.LlmException
import com.spark.companion.brain.Persona
import com.spark.companion.brain.SpeechText
import com.spark.companion.brain.ToolCatalog
import com.spark.companion.core.Settings
import com.spark.companion.link.SparkLink
import com.spark.companion.memory.ChatMessage
import com.spark.companion.memory.MemoryStore
import com.spark.companion.phone.PhoneTools
import com.spark.companion.phone.SparkAccessibilityService
import com.spark.companion.voice.Speaker
import com.spark.companion.voice.SpeechInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { IDLE, LISTENING, THINKING, ACTING, SPEAKING }

data class EngineState(val phase: Phase = Phase.IDLE, val detail: String = "")

class PendingConfirm(val summary: String, val answer: CompletableDeferred<Boolean>)

/**
 * The companion itself: owns the conversation, the voice loop and the agent.
 * One instance lives for the whole process so a conversation carries on while
 * Spark works inside other apps. All state changes happen on the main thread.
 */
class SparkEngine(private val context: Context) {

    val settings = Settings(context)
    val memory = MemoryStore(context)
    private val speech = SpeechInput(context)
    private val speaker = Speaker(context)
    private val link = SparkLink({ settings.serverUrl }, { settings.serverToken })
    private val tools = PhoneTools(context, settings, memory, link) { uiVisible.value }
    private val agent = Agent(GeminiClient({ settings.apiKey }, { settings.model }), tools)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeJob: Job? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    /** Live partial transcript while the user is speaking. */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial

    /** Microphone level, 0..1, for the orb animation. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private val _pending = MutableStateFlow<PendingConfirm?>(null)
    val pendingConfirm: StateFlow<PendingConfirm?> = _pending

    /** True while Spark's own screen is in front. */
    val uiVisible = MutableStateFlow(false)

    /** Mirrors the floating-orb setting so the overlay can react to changes. */
    val orbEnabled = MutableStateFlow(settings.floatingOrb)

    val busy: Boolean get() = job?.isActive == true

    init {
        scope.launch {
            val recent = withContext(Dispatchers.IO) { memory.recentMessages() }
            _messages.value = recent
            agent.seed(recent.takeLast(12).map { it.fromUser to it.text })
            refreshSettings()
        }
    }

    // ── Public controls ──────────────────────────────────────────────────

    /** A typed message: answer in text only. */
    fun submitText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        startSession { runTurn(clean, voice = false) }
    }

    /** Tap on the orb: start talking, or stop whatever is going on. */
    fun toggleVoice() {
        if (busy) stop() else startVoice()
    }

    fun startVoice(firstUtterance: String? = null) {
        if (!micGranted()) {
            postAssistant("I need microphone permission to hear you. You can grant it in Spark settings.")
            return
        }
        startSession { voiceSession(firstUtterance) }
    }

    fun stop() {
        job?.cancel()
        speaker.stop()
    }

    fun answerConfirm(approved: Boolean) {
        _pending.value?.answer?.complete(approved)
    }

    fun clearConversation() {
        stop()
        agent.clear()
        _messages.value = emptyList()
        scope.launch(Dispatchers.IO) { memory.clearMessages() }
    }

    fun forgetEverything() {
        stop()
        agent.clear()
        _messages.value = emptyList()
        scope.launch(Dispatchers.IO) { memory.clearEverything() }
    }

    /** Call after settings change so background features follow them. */
    fun refreshSettings() {
        orbEnabled.value = settings.floatingOrb
        if (settings.wakeWordEnabled && micGranted()) {
            CompanionService.start(context)
            if (wakeJob?.isActive != true && !busy) wakeJob = scope.launch { wakeLoop() }
        } else {
            wakeJob?.cancel()
            if (!busy) CompanionService.stop(context)
        }
    }

    suspend fun testGemini(): String = try {
        val reply = GeminiClient({ settings.apiKey }, { settings.model }).generate(
            "Reply with one short friendly sentence.",
            listOf(com.spark.companion.brain.Content.userText("Say hello to confirm you are working.")),
            org.json.JSONArray()
        )
        "Connected: " + reply.text
    } catch (e: LlmException) {
        e.message ?: "Gemini test failed."
    }

    suspend fun testDesktopLink(): Boolean = link.ping()

    // ── Sessions ─────────────────────────────────────────────────────────

    private fun startSession(block: suspend () -> Unit) {
        wakeJob?.cancel()
        val previous = job
        previous?.cancel()
        speaker.stop()
        job = scope.launch {
            previous?.join()
            CompanionService.start(context)
            try {
                block()
            } finally {
                _partial.value = ""
                _level.value = 0f
                _state.value = EngineState()
                if (!settings.wakeWordEnabled) CompanionService.stop(context)
            }
        }.also { session ->
            session.invokeOnCompletion {
                // Back to hands-free listening once this session is over.
                scope.launch { if (job === session) refreshSettings() }
            }
        }
    }

    private suspend fun voiceSession(first: String?) {
        var text = first
        agent.endRequested = false
        while (true) {
            if (text == null) {
                text = listen()
                if (text.isNullOrBlank()) {
                    val problem = SpeechInput.describeError(speech.lastError)
                    if (problem.isNotEmpty()) post(false, problem)
                    return
                }
            }
            val reply = runTurn(text, voice = true)
            text = null
            if (reply != null && settings.speakReplies) {
                _state.value = EngineState(Phase.SPEAKING)
                speaker.speak(reply, settings.language)
            }
            if (agent.endRequested || !settings.continuous) return
        }
    }

    private suspend fun listen(prompt: String = ""): String? {
        _partial.value = ""
        _state.value = EngineState(Phase.LISTENING, prompt)
        return try {
            speech.listen(
                settings.language,
                onPartial = { _partial.value = it },
                onLevel = { _level.value = it },
            )
        } finally {
            _level.value = 0f
            _partial.value = ""
        }
    }

    /** One user message through the agent. Returns the reply, or null on failure. */
    private suspend fun runTurn(text: String, voice: Boolean): String? {
        postUser(text)
        _state.value = EngineState(Phase.THINKING)
        val listener = object : Agent.Listener {
            override fun onAction(status: String) {
                _state.value = EngineState(Phase.ACTING, status)
            }
        }
        return try {
            val reply = agent.respond(
                userText = text,
                system = { systemPrompt(voice) },
                tools = ToolCatalog.declarations(settings.desktopLinked),
                confirm = { request -> confirm(request, voice) },
                listener = listener,
            )
            post(false, reply)
            reply
        } catch (e: LlmException) {
            val message = e.message ?: "Something went wrong talking to the model."
            post(false, message)
            if (voice && settings.speakReplies) {
                _state.value = EngineState(Phase.SPEAKING)
                speaker.speak(message, settings.language)
            }
            // A failed turn ends a voice session rather than looping on errors.
            agent.endRequested = true
            null
        }
    }

    private suspend fun systemPrompt(voice: Boolean): String {
        val facts = withContext(Dispatchers.IO) { memory.facts() }
        return Persona.systemPrompt(
            Persona.Context(
                userName = settings.userName,
                now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date()),
                memories = facts,
                screenControl = SparkAccessibilityService.instance != null,
                desktopLink = settings.desktopLinked,
                voice = voice,
            )
        )
    }

    // ── Confirmation ─────────────────────────────────────────────────────

    /**
     * Asks the user to approve a risky action: on screen (in the app or the
     * overlay) and, in a voice conversation, by saying yes or no. No answer
     * within 45 seconds counts as no.
     */
    private suspend fun confirm(request: ConfirmRequest, voice: Boolean): Boolean {
        val canShow = uiVisible.value || SparkAccessibilityService.instance != null
        if (!canShow && !voice) return false
        val answer = CompletableDeferred<Boolean>()
        _pending.value = PendingConfirm(request.summary, answer)
        val voiceJob = if (voice) scope.launch {
            speaker.speak(request.summary + ". Shall I go ahead?", settings.language)
            repeat(2) {
                val heard = listen("Say yes or no")
                _state.value = EngineState(Phase.ACTING, "Waiting for your OK")
                val parsed = ActionPolicy.parseYesNo(heard)
                if (parsed != null) {
                    answer.complete(parsed)
                    return@launch
                }
            }
        } else null
        return try {
            withTimeoutOrNull(45_000) { answer.await() } ?: false
        } finally {
            voiceJob?.cancel()
            _pending.value = null
        }
    }

    // ── Hands-free wake word (experimental) ──────────────────────────────

    private suspend fun wakeLoop() {
        while (scope.isActive && settings.wakeWordEnabled) {
            if (busy) {
                delay(1000)
                continue
            }
            val heard = speech.listen(settings.language)
            if (heard == null) {
                // Silence is normal; real errors get a longer pause so we don't spin.
                delay(if (speech.lastError == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                    speech.lastError == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 250 else 2500)
                continue
            }
            val command = SpeechText.afterWakeWord(heard, settings.wakeWord) ?: continue
            startVoice(command.ifBlank { null })
            return
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun micGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private suspend fun postUser(text: String) = post(true, text)

    private fun postAssistant(text: String) {
        scope.launch { post(false, text) }
    }

    private suspend fun post(fromUser: Boolean, text: String) {
        if (text.isBlank()) return
        val message = withContext(Dispatchers.IO) { memory.addMessage(fromUser, text) }
        _messages.value = _messages.value + message
    }
}
