package com.spark.companion.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.spark.companion.brain.ActionPolicy
import com.spark.companion.brain.Agent
import com.spark.companion.brain.ConfirmRequest
import com.spark.companion.brain.Msg
import com.spark.companion.brain.Greetings
import com.spark.companion.brain.ClaudeClient
import com.spark.companion.brain.LlmException
import com.spark.companion.brain.Languages
import com.spark.companion.brain.Mood
import com.spark.companion.brain.ReplyTags
import com.spark.companion.brain.Persona
import com.spark.companion.brain.SpeechText
import com.spark.companion.brain.ToolCatalog
import com.spark.companion.core.Settings
import com.spark.companion.link.SparkLink
import com.spark.companion.memory.ChatMessage
import com.spark.companion.memory.MemoryStore
import com.spark.companion.phone.PhoneTools
import com.spark.companion.phone.SparkAccessibilityService
import com.spark.companion.ui.OverlayController
import com.spark.companion.ui.avatar.AvatarRig
import com.spark.companion.ui.avatar.AvatarRepository
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
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    private val n8n = com.spark.companion.brain.N8n({ settings.n8nUrl }, { settings.n8nAuth })
    private val tools = PhoneTools(context, settings, memory, link, n8n) { uiVisible.value }
    /** Claude is Spark's only brain (Anthropic's official SDK). */
    val claude = ClaudeClient({ settings.claudeApiKey }, { settings.claudeModel }) { settings.claudeModel = it }
    private val llm: com.spark.companion.brain.Llm = claude
    private val agent = Agent(llm, tools)

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

    /** Mic level while listening, voice level while speaking (0..1), for the avatar. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    /** Mouth shape of what Spark is saying right now (for lip-sync), or null. */
    private val _mouth = MutableStateFlow<com.spark.companion.brain.MouthShape?>(null)
    val mouth: StateFlow<com.spark.companion.brain.MouthShape?> = _mouth

    /** Whether the avatar is awake. It wakes for a conversation and dozes off after. */
    private val _awake = MutableStateFlow(false)
    val awake: StateFlow<Boolean> = _awake

    /** The feeling of the latest reply, shown on the avatar's face. */
    private val _mood = MutableStateFlow(Mood.CALM)
    val mood: StateFlow<Mood> = _mood

    /** What Spark is saying right now (subtitles). */
    private val _caption = MutableStateFlow("")
    val caption: StateFlow<String> = _caption

    val avatars = AvatarRepository(context)

    /** The avatar Spark currently appears as. */
    val avatar = MutableStateFlow(avatars.get(settings.avatar))


    /** The language the conversation is in right now; listening follows it. */
    private var talkLanguage: String = settings.primaryLanguage
    private val missingVoiceNoted = HashSet<String>()

    private val _pending = MutableStateFlow<PendingConfirm?>(null)
    val pendingConfirm: StateFlow<PendingConfirm?> = _pending

    /** True while Spark's own screen is in front. */
    val uiVisible = MutableStateFlow(false)

    /** Mirrors the floating-avatar setting so the overlay can react to changes. */
    val floatingEnabled = MutableStateFlow(settings.floatingAvatar)

    private var sleepJob: Job? = null
    /** Falls back to the online recogniser if on-device recognition keeps failing. */
    private var wakeOnDevice = true

    /** Use one long recognition session for the wake word; off if the phone can't. */
    private var continuousWake = true

    val busy: Boolean get() = job?.isActive == true

    init {
        speaker.onMissingVoice = { tag ->
            if (missingVoiceNoted.add(tag)) {
                postAssistant(
                    "This phone has no ${Languages.name(tag)} voice installed, so I'll read it with the default voice. " +
                        "Add it under Settings → Text-to-speech."
                )
            }
        }
        scope.launch {
            val recent = withContext(Dispatchers.IO) { memory.recentMessages() }
            _messages.value = recent
            agent.seed(recent.takeLast(12).map { it.fromUser to it.text })
            refreshSettings()
            // Keeps model drop-downs current, so newly released models show up.
            refreshModelListsIfStale()
        }
    }

    // ── Public controls ──────────────────────────────────────────────────

    /** A typed message: answer in text only. */
    fun submitText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        startSession { runTurn(clean, voice = false) }
    }

    /** Tap on the avatar: wake it and talk, or stop whatever is going on. */
    fun toggleVoice() {
        if (busy) stop() else startVoice(greet = !_awake.value)
    }

    /**
     * Starts a voice conversation. [firstUtterance] is what was already said
     * after the wake word; [greet] makes the avatar say hello first.
     */
    fun startVoice(firstUtterance: String? = null, greet: Boolean = false) {
        if (!micGranted()) {
            wakeUp()
            postAssistant("I need microphone permission to hear you. You can grant it in Spark settings.")
            scheduleSleep()
            return
        }
        startSession { voiceSession(firstUtterance, greet) }
    }

    fun stop() {
        job?.cancel()
        speaker.stop()
    }

    /** Opens the avatar's eyes (the wake-up animation plays on the transition). */
    fun wakeUp() {
        sleepJob?.cancel()
        _awake.value = true
    }

    /** Lets the avatar doze off after a quiet spell. */
    private fun scheduleSleep(afterMs: Long = SLEEP_AFTER_MS) {
        sleepJob?.cancel()
        sleepJob = scope.launch {
            delay(afterMs)
            if (!busy) {
                _awake.value = false
                _mood.value = Mood.CALM
                _caption.value = ""
            }
        }
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
        floatingEnabled.value = settings.floatingAvatar
        avatar.value = avatars.get(settings.avatar)
        ensureRealisticLook()
        if (talkLanguage !in settings.languages) talkLanguage = settings.primaryLanguage
        if (settings.wakeWordEnabled && micGranted()) {
            CompanionService.start(context)
            if (wakeJob?.isActive != true && !busy) wakeJob = scope.launch { wakeLoop() }
        } else {
            wakeJob?.cancel()
            if (!busy) CompanionService.stop(context)
        }
    }

suspend fun testBrain(): String = try {
        val reply = llm.generate(
            "Reply with one short friendly sentence.",
            listOf(Msg.User("Say hello to confirm you are working.")),
            org.json.JSONArray()
        )
        "Connected: " + reply.text
    } catch (e: LlmException) {
        e.message ?: "Connection test failed."
    }

    val modelLists = com.spark.companion.core.ModelListStore(context)

    /** Result of a model-list refresh, for the settings screen. */
    class ModelRefresh(val summary: String, val added: List<String>)

    @Volatile private var refreshingModels = false

    /**
     * Asks every provider that has a key which models it offers right now and
     * updates the drop-downs. New models are remembered so they can be marked
     * NEW. A saved model the provider no longer offers is swapped for the best
     * available one; with [pickBest], every provider gets its best model.
     */
    suspend fun refreshModelLists(pickBest: Boolean = false): ModelRefresh {
        val now = System.currentTimeMillis()
        if (settings.claudeApiKey.isBlank()) return ModelRefresh("Add your Claude API key first." + refreshImageModels(now), emptyList())
        return try {
            val fresh = claude.listModels()
            if (fresh.isEmpty()) return ModelRefresh("Claude didn't list any models for this key.", emptyList())
            val merge = com.spark.companion.brain.ModelListing.merge(modelLists.get(com.spark.companion.core.ModelListStore.CLAUDE), fresh.map { it to "" }, now)
            modelLists.put(com.spark.companion.core.ModelListStore.CLAUDE, merge.list)
            modelLists.lastRefresh = now
            val current = settings.claudeModel
            val note = when {
                pickBest && current != fresh.first() -> { settings.claudeModel = fresh.first(); " → using ${fresh.first()}" }
                current !in fresh -> { settings.claudeModel = fresh.first(); " → $current isn't available, using ${fresh.first()}" }
                else -> ""
            }
            val added = merge.added.map { "Claude: $it" }
            val pictures = refreshImageModels(now)
            val head = if (added.isEmpty()) "" else "New models: ${added.joinToString(", ")}\n"
            ModelRefresh(head + "Claude: ${fresh.size} models$note" + pictures, added)
        } catch (e: LlmException) {
            ModelRefresh("Claude: ${e.message}", emptyList())
        }
    }

    /** Updates the OpenAI picture-model list when an OpenAI key is set; returns a summary line. */
    private suspend fun refreshImageModels(now: Long): String {
        if (settings.openAiApiKey.isBlank()) return ""
        return try {
            val ids = com.spark.companion.brain.OpenAiImageGen({ settings.openAiApiKey }, { settings.openAiImageModel }).availableModels()
            if (ids.isEmpty()) return "\nOpenAI: no image models for this key"
            val store = com.spark.companion.core.ModelListStore.OPENAI_IMAGES
            modelLists.put(store, com.spark.companion.brain.ModelListing.merge(modelLists.get(store), ids.map { it to "" }, now).list)
            if (settings.openAiImageModel !in ids) settings.openAiImageModel = ids.first()
            "\nOpenAI pictures: ${ids.size} models"
        } catch (e: LlmException) {
            "\nOpenAI: ${e.message}"
        }
    }

    /** Refreshes the model lists in the background when they are older than [maxAgeMs]. */
    fun refreshModelListsIfStale(maxAgeMs: Long = 12L * 60 * 60 * 1000, onDone: (ModelRefresh) -> Unit = {}) {
        if (refreshingModels) return
        if (System.currentTimeMillis() - modelLists.lastRefresh < maxAgeMs) return
        refreshingModels = true
        scope.launch {
            try {
                onDone(refreshModelLists())
            } finally {
                refreshingModels = false
            }
        }
    }

    suspend fun testDesktopLink(): Boolean = link.ping()

    @Volatile private var lastProactiveAt = 0L

    /**
     * A new notification arrived. If proactive mode is on and Spark is free,
     * it gives a short spoken heads-up. Plain template, so it costs no tokens.
     */
    fun onIncomingNotification(app: String, title: String, text: String) {
        if (!settings.proactiveEnabled || !settings.speakReplies || busy) return
        if (!micOrSpeakerReady()) return
        val now = System.currentTimeMillis()
        if (now - lastProactiveAt < 8000) return
        lastProactiveAt = now
        val who = title.ifBlank { app }
        val body = text.take(160)
        val line = if (body.isBlank()) "New notification from $app." else "$who on $app says: $body"
        startSession {
            _mood.value = Mood.CALM
            say(line)
        }
    }

    private fun micOrSpeakerReady(): Boolean = true

    /** Runs a saved routine's instruction and reads out the result. */
    fun runRoutine(prompt: String) {
        if (prompt.isBlank()) return
        startSession {
            talkLanguage = settings.primaryLanguage
            val reply = runTurn(prompt, voice = true)
            if (reply != null && settings.speakReplies) say(reply)
        }
    }

    /** Speaks [text] aloud in the current language and avatar voice (used by the camera screen). */
    fun speakExternal(text: String, mood: Mood = Mood.CALM) {
        startSession {
            _mood.value = mood
            say(text)
        }
    }

    /** Says a short sample with the current avatar's voice, for the settings screen. */
    fun testVoice() {
        startSession {
            talkLanguage = settings.primaryLanguage
            _mood.value = Mood.HAPPY
            say(Greetings.sample(settings.userName, talkLanguage))
        }
    }

    // ── Sessions ─────────────────────────────────────────────────────────

    private fun startSession(block: suspend () -> Unit) {
        refreshModelListsIfStale()
        wakeJob?.cancel()
        wakeUp()
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
                scheduleSleep()
                if (!settings.wakeWordEnabled) CompanionService.stop(context)
            }
        }.also { session ->
            session.invokeOnCompletion {
                // Back to hands-free listening once this session is over.
                scope.launch { if (job === session) refreshSettings() }
            }
        }
    }

    private suspend fun voiceSession(first: String?, greet: Boolean) {
        var text = first
        agent.endRequested = false
        if (greet && text == null) {
            // Give the wake-up animation a moment, then say hello.
            delay(AvatarRig.WAKE_MS - 300)
            _mood.value = Mood.HAPPY
            talkLanguage = settings.primaryLanguage
            say(Greetings.pick(settings.userName, Calendar.getInstance().get(Calendar.HOUR_OF_DAY), talkLanguage))
        }
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
            if (reply != null && settings.speakReplies) say(reply)
            if (agent.endRequested || !settings.continuous) return
        }
    }

    private suspend fun listen(prompt: String = ""): String? {
        _partial.value = ""
        _state.value = EngineState(Phase.LISTENING, prompt)
        return try {
            speech.listen(
                talkLanguage,
                onPartial = { _partial.value = it },
                onLevel = { _level.value = it },
                alsoAllowed = settings.languages,
            ).also {
                speech.detectedLanguage?.let { tag -> Languages.resolve(tag, settings.languages)?.let { l -> talkLanguage = l } }
            }
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
                tools = availableTools(),
                confirm = { request -> confirm(request, voice) },
                listener = listener,
            )
            val tags = ReplyTags.parse(reply)
            val clean = tags.text
            _mood.value = tags.mood ?: Mood.CALM
            _caption.value = clean
            // Speak (and next listen) in the language of this reply.
            val langs = settings.languages
            (Languages.resolve(tags.language, langs) ?: Languages.guessFromScript(clean, langs))
                ?.let { talkLanguage = it }
            post(false, clean)
            clean
        } catch (e: LlmException) {
            val message = e.message ?: "Something went wrong talking to the model."
            _mood.value = Mood.CONCERNED
            post(false, message)
            if (voice && settings.speakReplies) say(message)
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
                screenControlAvailable = com.spark.companion.BuildConfig.FULL_FEATURES,
                desktopLink = settings.desktopLinked,
                workflows = settings.n8nConfigured,
                voice = voice,
                avatar = avatar.value.prompt,
                languages = settings.languages.map { "${Languages.name(it)} ($it)" },
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
        val canShow = uiVisible.value || OverlayController.showing
        if (!canShow && !voice) return false
        val answer = CompletableDeferred<Boolean>()
        _pending.value = PendingConfirm(request.summary, answer)
        val voiceJob = if (voice) scope.launch {
            _mood.value = Mood.THOUGHTFUL
            say(request.summary + ". Shall I go ahead?")
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

    // ── Hands-free wake word ─────────────────────────────────────────────

    /**
     * Listens over and over for the wake word. As soon as it shows up in the
     * partial transcript the avatar starts waking, so it reacts while you are
     * still talking; the full sentence then becomes the first request.
     */
    private suspend fun wakeLoop() {
        var silentRounds = 0
        while (scope.isActive && settings.wakeWordEnabled) {
            if (busy) {
                delay(1000)
                continue
            }
            val onDevice = wakeOnDevice && speech.onDeviceAvailable
            var stirred = false
            val stir = {
                if (!stirred) {
                    stirred = true
                    if (!_awake.value) wakeUp()
                }
            }

            // Android 13+: one long session keeps the mic on steadily instead of
            // switching it off and on after every pause.
            if (continuousWake && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                when (val r = speech.listenForWakeWord(settings.primaryLanguage, settings.wakeWord, onDevice, SpeechText.WAKE_SESSION_MS, stir)) {
                    is SpeechInput.WakeResult.Heard -> {
                        val wasAwake = _awake.value && !stirred
                        startVoice(r.command.ifBlank { null }, greet = r.command.isBlank() && !wasAwake)
                        return
                    }
                    is SpeechInput.WakeResult.Ended -> {
                        if (stirred) scheduleSleep(4_000)
                        if (r.unsupported) continuousWake = false
                        if (onDevice && r.error != 0 && r.error !in QUIET_ERRORS) wakeOnDevice = false
                        delay(if (r.error == 0 || r.error in QUIET_ERRORS) 300 else 2500)
                        continue
                    }
                }
            }

            // Fallback for recognisers without long sessions: listen, restart,
            // and back off while nobody is talking so the mic blinks less.
            val heard = speech.listen(
                settings.primaryLanguage,
                alsoAllowed = settings.languages,
                onPartial = { partial ->
                    if (SpeechText.afterWakeWord(partial, settings.wakeWord) != null) stir()
                },
                onDevice = onDevice,
            )
            if (heard == null) {
                val error = speech.lastError
                if (stirred) scheduleSleep(4_000)
                if (onDevice && error !in QUIET_ERRORS) wakeOnDevice = false
                if (error in QUIET_ERRORS) {
                    silentRounds++
                    delay(SpeechText.wakeRestartDelayMs(silentRounds))
                } else {
                    delay(2500)
                }
                continue
            }
            silentRounds = 0
            val command = SpeechText.afterWakeWord(heard, settings.wakeWord)
            if (command == null) {
                if (stirred) scheduleSleep(4_000)
                continue
            }
            val wasAwake = _awake.value && !stirred
            startVoice(command.ifBlank { null }, greet = command.isBlank() && !wasAwake)
            return
        }
    }

    /**
     * Speaks [text] in the conversation's language with the avatar's voice,
     * showing subtitles and moving the avatar's mouth.
     */
    private suspend fun say(text: String) {
        val clean = ReplyTags.parse(text).text
        _caption.value = clean
        _state.value = EngineState(Phase.SPEAKING)
        val voice = avatar.value.voice
        val onLevel: (Float) -> Unit = { _level.value = it }
        val onMouth: (com.spark.companion.brain.MouthShape?) -> Unit = { _mouth.value = it }
        try {
            speaker.speak(clean, talkLanguage, onLevel, voice.pitch, voice.rate, onMouth)
        } finally {
            _level.value = 0f
            _mouth.value = null
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** The tools this build offers the model. The Play build has no screen control. */
    private fun availableTools(): org.json.JSONArray {
        val all = ToolCatalog.declarations(settings.desktopLinked, settings.n8nConfigured)
        if (com.spark.companion.BuildConfig.FULL_FEATURES) return all
        val out = org.json.JSONArray()
        for (i in 0 until all.length()) {
            val decl = all.getJSONObject(i)
            if (decl.optString("name") !in ToolCatalog.SCREEN_TOOLS) out.put(decl)
        }
        return out
    }

    companion object {
        const val SLEEP_AFTER_MS = 15_000L
        private val QUIET_ERRORS = setOf(
            android.speech.SpeechRecognizer.ERROR_NO_MATCH,
            android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        )
    }

    private fun micGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private suspend fun postUser(text: String) = post(true, text)

    // ── Realistic look ───────────────────────────────────────────────────

    @Volatile private var realJob: Job? = null
    private val realFailedAt = HashMap<String, Long>()

    /** What's happening with the avatar's realistic look, for the main screen ("" when nothing). */
    private val _lookStatus = MutableStateFlow("")
    val lookStatus: StateFlow<String> = _lookStatus

    /**
     * Full-body avatars look like real people: if the current one has no
     * realistic picture of its outfit yet, one is made in the background
     * (free image service; a face photo is swapped on afterwards).
     */
    fun ensureRealisticLook(force: Boolean = false) {
        val profile = avatar.value
        if (!avatars.needsRealBody(profile)) return
        if (realJob?.isActive == true) return
        val key = profile.id + "/" + profile.body?.outfitId
        val failed = realFailedAt[key]
        if (!force && failed != null && System.currentTimeMillis() - failed < 10 * 60_000) return
        realJob = scope.launch {
            val spec = profile.body ?: return@launch
            _lookStatus.value = "Creating ${profile.name}'s realistic look…"
            try {
                val portrait = withContext(Dispatchers.IO) { profile.portrait?.let { AvatarRepository.loadPortrait(it) } }
                // OpenAI (with a key) draws the person with the user's own face;
                // the free service draws someone else, and the face is swapped on.
                val maker = settings.imageMaker(com.spark.companion.brain.OpenAiImageGen.TALL)
                val likeness = maker.canUsePhoto && portrait != null
                val result = com.spark.companion.ui.avatar.RealBody.create(maker, spec, if (likeness) portrait else null)
                val swapped = portrait != null && !likeness
                withContext(Dispatchers.IO) { avatars.saveRealBody(profile, result, swapped, spec.outfitId) }
                com.spark.companion.ui.avatar.AvatarView.clearCache()
                avatar.value = avatars.get(settings.avatar)
                _lookStatus.value = ""
            } catch (e: kotlinx.coroutines.CancellationException) {
                _lookStatus.value = ""
                throw e
            } catch (e: Exception) {
                realFailedAt[key] = System.currentTimeMillis()
                _lookStatus.value = "Couldn't create the realistic look yet (${(e as? LlmException)?.message ?: "no connection"}). Tap to try again."
            }
        }
    }

    private fun postAssistant(text: String) {
        scope.launch { post(false, text) }
    }

    private suspend fun post(fromUser: Boolean, text: String) {
        if (text.isBlank()) return
        val message = withContext(Dispatchers.IO) { memory.addMessage(fromUser, text) }
        _messages.value = _messages.value + message
    }
}
