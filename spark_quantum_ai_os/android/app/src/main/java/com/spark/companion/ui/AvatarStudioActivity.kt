package com.spark.companion.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.spark.companion.SparkApp
import com.spark.companion.brain.GeminiHttp
import com.spark.companion.brain.GeminiMedia
import com.spark.companion.brain.LlmException
import com.spark.companion.engine.Phase
import com.spark.companion.ui.avatar.AvatarProfile
import com.spark.companion.ui.avatar.AvatarRepository
import com.spark.companion.ui.avatar.AvatarView
import com.spark.companion.ui.avatar.FaceLandmarks
import com.spark.companion.ui.avatar.FaceTools
import com.spark.companion.ui.avatar.LandmarkEditorView
import com.spark.companion.ui.avatar.VoiceStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin

/**
 * Make your own avatars: upload a photo (used as-is or restyled by Gemini),
 * or describe a character for Gemini to create. Then place the eye and
 * mouth dots, give it a name, personality and voice, and Spark becomes it.
 */
class AvatarStudioActivity : Activity() {

    private enum class Screen { HOME, PHOTO, DESCRIBE, EDIT }

    private val engine get() = SparkApp.engine
    private val repo get() = engine.avatars
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var content: LinearLayout
    private var screen = Screen.HOME

    // Work in progress.
    private var photo: Bitmap? = null
    private var portrait: Bitmap? = null
    private var landmarks: FaceLandmarks = FaceLandmarks.CENTERED
    private var faceFound = false
    private var editingId: String? = null
    private var name = ""
    private var personality = ""
    private var accent = AvatarProfile.ACCENTS[0]
    private var geminiVoice = GeminiMedia.VOICES[0].first
    private var pitch = 1f
    private var description = ""
    private var previewJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 20)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, Ui.dp(this@AvatarStudioActivity, 48))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            addView(content)
        })
        show(Screen.HOME)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.HOME -> @Suppress("DEPRECATION") super.onBackPressed()
            Screen.EDIT -> show(if (editingId != null) Screen.HOME else if (photo != null) Screen.PHOTO else Screen.DESCRIBE)
            else -> show(Screen.HOME)
        }
    }

    private fun show(next: Screen) {
        screen = next
        previewJob?.cancel()
        content.removeAllViews()
        when (next) {
            Screen.HOME -> buildHome()
            Screen.PHOTO -> buildPhoto()
            Screen.DESCRIBE -> buildDescribe()
            Screen.EDIT -> buildEdit()
        }
        (content.parent as? ScrollView)?.scrollTo(0, 0)
    }

    // ── Building blocks ──────────────────────────────────────────────────

    private fun title(text: String) = content.addView(Ui.text(this, text, 24f, Ui.TEXT, bold = true))

    private fun heading(text: String) = content.addView(Ui.text(this, text, 13f, Ui.ACCENT, bold = true).apply {
        letterSpacing = 0.12f
        setPadding(0, Ui.dp(this@AvatarStudioActivity, 22), 0, Ui.dp(this@AvatarStudioActivity, 6))
    })

    private fun note(text: String) = content.addView(Ui.text(this, text, 13.5f, Ui.TEXT_DIM).apply {
        setLineSpacing(0f, 1.15f)
        setPadding(0, Ui.dp(this@AvatarStudioActivity, 4), 0, Ui.dp(this@AvatarStudioActivity, 6))
    })

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) {
        content.addView(
            Ui.button(this, label, if (primary) Ui.ACCENT else Ui.SURFACE_HI, if (primary) Ui.BG else Ui.TEXT, onClick),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(this@AvatarStudioActivity, 10) }
        )
    }

    private fun field(hint: String, value: String, multiline: Boolean = false, onText: (String) -> Unit): EditText {
        val edit = EditText(this).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(Ui.TEXT_DIM)
            setTextColor(Ui.TEXT)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            if (multiline) minLines = 3 else setSingleLine(true)
            background = Ui.rounded(Ui.SURFACE, Ui.dp(this@AvatarStudioActivity, 12).toFloat())
            setPadding(Ui.dp(this@AvatarStudioActivity, 14), Ui.dp(this@AvatarStudioActivity, 12), Ui.dp(this@AvatarStudioActivity, 14), Ui.dp(this@AvatarStudioActivity, 12))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = onText(s?.toString().orEmpty())
            })
        }
        content.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Ui.dp(this@AvatarStudioActivity, 6) })
        return edit
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ── Home: your avatars ───────────────────────────────────────────────

    private fun buildHome() {
        title("Avatar Studio")
        note("Pick who Spark appears as, or make your own from a photo or a description.")
        heading("YOUR AVATARS")
        val current = engine.avatar.value.id
        for (profile in repo.all()) content.addView(avatarRow(profile, profile.id == current))

        heading("CREATE A NEW ONE")
        button("Use a photo", primary = true) { pickPhoto() }
        note("Any clear, front-facing face works: a selfie, a portrait, a drawing or a painting. Use photos of yourself or of people who are happy to be an avatar.")
        button("Describe one and let AI create it") {
            photo = null
            show(Screen.DESCRIBE)
        }
        note("AI styles and AI creation send the picture or description to Google Gemini with your API key. Photos used as-is never leave the phone.")
    }

    private fun avatarRow(profile: AvatarProfile, selected: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(if (selected) Ui.SURFACE_HI else Ui.SURFACE, Ui.dp(this@AvatarStudioActivity, 16).toFloat(),
                if (selected) profile.accent else null, Ui.dp(this@AvatarStudioActivity, 1))
            setPadding(Ui.dp(this@AvatarStudioActivity, 8), Ui.dp(this@AvatarStudioActivity, 8), Ui.dp(this@AvatarStudioActivity, 12), Ui.dp(this@AvatarStudioActivity, 8))
        }
        val face = AvatarView(this).apply {
            this.profile = profile
            awake = true
        }
        row.addView(face, LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@AvatarStudioActivity, 10), 0, 0, 0)
        }
        texts.addView(Ui.text(this, profile.name, 16f, Ui.TEXT, bold = true))
        texts.addView(Ui.text(this, if (selected) "In use · ${profile.tagline}" else profile.tagline, 13f, Ui.TEXT_DIM))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (profile.custom) {
            row.addView(Ui.text(this, "Edit", 14f, Ui.ACCENT, bold = true).apply {
                setPadding(Ui.dp(this@AvatarStudioActivity, 10), Ui.dp(this@AvatarStudioActivity, 10), Ui.dp(this@AvatarStudioActivity, 10), Ui.dp(this@AvatarStudioActivity, 10))
                setOnClickListener { edit(profile) }
            })
            row.addView(Ui.text(this, "Delete", 14f, Ui.DANGER).apply {
                setPadding(Ui.dp(this@AvatarStudioActivity, 6), Ui.dp(this@AvatarStudioActivity, 10), Ui.dp(this@AvatarStudioActivity, 4), Ui.dp(this@AvatarStudioActivity, 10))
                setOnClickListener { confirmDelete(profile) }
            })
        }
        row.setOnClickListener {
            engine.settings.avatar = profile.id
            engine.refreshSettings()
            show(Screen.HOME)
        }
        return LinearLayout(this).apply {
            setPadding(0, 0, 0, Ui.dp(this@AvatarStudioActivity, 8))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun confirmDelete(profile: AvatarProfile) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${profile.name}?")
            .setMessage("The portrait and its settings are removed from this phone.")
            .setPositiveButton("Delete") { _, _ ->
                repo.delete(profile.id)
                AvatarView.clearCache()
                if (engine.settings.avatar == profile.id) engine.settings.avatar = AvatarProfile.DEFAULT.id
                engine.refreshSettings()
                show(Screen.HOME)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun edit(profile: AvatarProfile) {
        val file = profile.portrait ?: return
        val bitmap = AvatarRepository.loadPortrait(file) ?: run {
            toast("Couldn't open that portrait.")
            return
        }
        photo = null
        portrait = bitmap
        landmarks = profile.landmarks ?: FaceLandmarks.CENTERED
        faceFound = true
        editingId = profile.id
        name = profile.name
        personality = profile.personality
        accent = profile.accent
        geminiVoice = profile.voice.geminiVoice
        pitch = profile.voice.pitch
        show(Screen.EDIT)
    }

    // ── From a photo ─────────────────────────────────────────────────────

    private fun pickPhoto() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(intent, "Choose a photo"), REQUEST_PHOTO)
        } catch (e: Exception) {
            toast("No app on this phone can pick photos.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        working("Finding the face…") {
            val loaded = withContext(Dispatchers.IO) { FaceTools.load(this@AvatarStudioActivity, uri) }
                ?: throw LlmException("Couldn't open that picture.")
            val framed = withContext(Dispatchers.Default) { FaceTools.portrait(loaded) }
            photo = loaded
            startNew(framed)
            show(Screen.PHOTO)
        }
    }

    private fun startNew(framed: FaceTools.Portrait) {
        portrait = framed.bitmap
        landmarks = framed.landmarks
        faceFound = framed.faceFound
        editingId = null
        if (name.isBlank()) name = "My avatar"
    }

    private fun buildPhoto() {
        title("Your photo")
        content.addView(ImageView(this).apply {
            setImageBitmap(portrait)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Ui.dp(this@AvatarStudioActivity, 10) })
        note(if (faceFound) "Face found, straightened and centred. On the next screen the eye and mouth dots are already placed — just check them."
        else "I couldn't find a face in this picture, so on the next screen you'll place the eye and mouth dots yourself. A clear, front-facing face works best.")
        button("Use this photo as it is", primary = true) { show(Screen.EDIT) }
        heading("OR RESTYLE IT WITH AI")
        note("Gemini redraws the picture in the style you pick, keeping the likeness. Takes 10–30 seconds. On the free plan this has a small quota — if it says the limit was hit, wait a minute, or just use the photo as it is above.")
        for (style in GeminiMedia.ImageStyle.values()) {
            button(style.label) { generate(GeminiMedia.convertPrompt(style), photo) }
        }
        button("Choose a different photo") { pickPhoto() }
    }

    // ── Describe with AI ─────────────────────────────────────────────────

    private fun buildDescribe() {
        title("Describe your avatar")
        note("Who should Spark look like? Describe the face, age, hair, clothes and vibe.")
        field("e.g. a cheerful woman in her twenties with curly black hair, a yellow kurta and a warm smile", description, multiline = true) {
            description = it
        }
        heading("PICK A LOOK")
        for (style in GeminiMedia.ImageStyle.values()) {
            button(style.label, primary = style == GeminiMedia.ImageStyle.REALISTIC) {
                if (description.isBlank()) toast("Describe your avatar first.")
                else generate(GeminiMedia.describePrompt(description, style), null)
            }
        }
    }

    /** Asks Gemini for a portrait, then moves to the editor. */
    private fun generate(prompt: String, source: Bitmap?) {
        if (engine.settings.apiKey.isBlank()) {
            toast("Add your Gemini API key in Spark settings first.")
            return
        }
        working("Gemini is drawing your avatar…") {
            val bytes = withContext(Dispatchers.Default) { source?.let { FaceTools.jpeg(it) } }
            val body = GeminiMedia.imageRequest(prompt, bytes).toString()
            val url = GeminiHttp.endpoint(engine.settings.imageModel, GeminiMedia.DEFAULT_IMAGE_MODEL)
            val reply = GeminiHttp.post(url, engine.settings.apiKey, body, 180_000)
            val image = GeminiMedia.parseImage(reply)
            val bitmap = withContext(Dispatchers.Default) { FaceTools.decode(image) }
                ?: throw LlmException("Gemini's image couldn't be opened.")
            val framed = withContext(Dispatchers.Default) { FaceTools.portrait(bitmap, tight = false) }
            startNew(framed)
            show(Screen.EDIT)
        }
    }

    // ── Edit: dots, preview, name, personality, voice ────────────────────

    private fun buildEdit() {
        val bitmap = portrait ?: return show(Screen.HOME)
        title(if (editingId != null) "Edit ${name}" else "Bring it to life")
        note("The dots are placed for you. Drag any that are off: blue on the centre of each eye, pink on the corners of the mouth. The preview updates as you move them.")
        val preview = AvatarView(this).apply { awake = true }
        val editor = LandmarkEditorView(this).apply {
            this.bitmap = bitmap
            this.landmarks = this@AvatarStudioActivity.landmarks
            onChange = { lm ->
                this@AvatarStudioActivity.landmarks = lm
                preview.preview(bitmap, lm, accent)
            }
        }
        content.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Ui.dp(this@AvatarStudioActivity, 8) })

        heading("PREVIEW")
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        preview.preview(bitmap, landmarks, accent)
        previewRow.addView(preview, LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, 170)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun control(label: String, action: () -> Unit) = controls.addView(
            Ui.button(this, label, Ui.SURFACE_HI, Ui.TEXT, action),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = Ui.dp(this@AvatarStudioActivity, 8) }
        )
        control("Talk") { animate(preview) }
        control("Sleep / wake") {
            previewJob?.cancel()
            preview.phase = Phase.IDLE
            preview.awake = !preview.awake
        }
        control("Smile") {
            previewJob?.cancel()
            preview.awake = true
            preview.phase = Phase.IDLE
            preview.mood = if (preview.mood == com.spark.companion.brain.Mood.HAPPY) com.spark.companion.brain.Mood.CALM else com.spark.companion.brain.Mood.HAPPY
        }
        previewRow.addView(controls, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = Ui.dp(this@AvatarStudioActivity, 12) })
        content.addView(previewRow)

        heading("NAME")
        field("What should this avatar be called?", name) { name = it }
        heading("PERSONALITY (OPTIONAL)")
        field("e.g. a calm, wise mentor who loves cricket and old Hindi songs", personality, multiline = true) { personality = it }

        heading("COLOUR")
        val colours = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun renderColours() {
            colours.removeAllViews()
            for (c in AvatarProfile.ACCENTS) {
                colours.addView(View(this).apply {
                    background = Ui.rounded(c, Ui.dp(this@AvatarStudioActivity, 16).toFloat(),
                        if (c == accent) Ui.TEXT else null, Ui.dp(this@AvatarStudioActivity, 3))
                    setOnClickListener {
                        accent = c
                        preview.preview(bitmap, landmarks, accent)
                        renderColours()
                    }
                }, LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)).apply { marginEnd = Ui.dp(this@AvatarStudioActivity, 10) })
            }
        }
        renderColours()
        content.addView(colours)

        heading("VOICE")
        note("Used with Gemini natural voices (Spark settings → Voice). The pitch also shapes the phone's own voice.")
        val voices = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for ((voice, about) in GeminiMedia.VOICES) {
            voices.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = "$voice · $about"
                setTextColor(Ui.TEXT)
                textSize = 15f
                isChecked = voice == geminiVoice
                setOnCheckedChangeListener { _, checked -> if (checked) geminiVoice = voice }
            })
        }
        content.addView(voices)
        val pitchLabel = Ui.text(this, "", 14f, Ui.TEXT_DIM)
        fun renderPitch() {
            pitchLabel.text = "Phone voice pitch: " + when {
                pitch < 0.85f -> "deep"
                pitch < 0.97f -> "a little low"
                pitch <= 1.05f -> "normal"
                pitch <= 1.2f -> "a little high"
                else -> "high"
            }
        }
        renderPitch()
        content.addView(pitchLabel)
        content.addView(SeekBar(this).apply {
            max = 100
            progress = ((pitch - 0.6f) / 1.0f * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    pitch = 0.6f + value / 100f
                    renderPitch()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })

        button(if (editingId != null) "Save changes" else "Save and use this avatar", primary = true) { save() }
        if (editingId == null && photo != null) button("Back to styles") { show(Screen.PHOTO) }
    }

    /** A few seconds of pretend speech so you can check the mouth. */
    private fun animate(preview: AvatarView) {
        previewJob?.cancel()
        preview.awake = true
        previewJob = scope.launch {
            preview.phase = Phase.SPEAKING
            val start = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - start < 3500) {
                val t = (System.currentTimeMillis() - start) / 1000f
                preview.level = 0.15f + 0.8f * abs(sin(t * 11f) * sin(t * 2.9f))
                delay(33)
            }
            preview.level = 0f
            preview.phase = Phase.IDLE
        }
    }

    private fun save() {
        val bitmap = portrait ?: return
        working("Saving…") {
            val saved = withContext(Dispatchers.IO) {
                repo.save(
                    existingId = editingId,
                    name = name.trim(),
                    personality = personality.trim(),
                    accent = accent,
                    voice = VoiceStyle(pitch, 1f, geminiVoice),
                    portrait = bitmap,
                    landmarks = landmarks,
                    tagline = if (photo != null || editingId != null) "Your avatar" else "Made with AI",
                )
            }
            AvatarView.clearCache()
            engine.settings.avatar = saved.id
            engine.refreshSettings()
            toast("${saved.name} is ready. Say “Spark” to wake them up!")
            photo = null
            show(Screen.HOME)
        }
    }

    /** Runs [block] behind a "working" dialog; errors become a friendly message. */
    private fun working(message: String, block: suspend () -> Unit) {
        var job: Job? = null
        val dialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }
            .show()
        job = scope.launch {
            try {
                block()
            } catch (e: LlmException) {
                AlertDialog.Builder(this@AvatarStudioActivity)
                    .setMessage(e.message ?: "Something went wrong.")
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: OutOfMemoryError) {
                toast("That picture is too large. Try a smaller one.")
            } finally {
                dialog.dismiss()
            }
        }
    }

    companion object {
        private const val REQUEST_PHOTO = 31
    }
}
