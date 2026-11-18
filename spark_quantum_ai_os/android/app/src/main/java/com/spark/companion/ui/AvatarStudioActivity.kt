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
import com.spark.companion.brain.GeminiImageGen
import com.spark.companion.brain.GeminiMedia
import com.spark.companion.brain.ImageGen
import com.spark.companion.brain.PollinationsImageGen
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

    private enum class Screen { HOME, PHOTO, DESCRIBE, EDIT, OUTFIT }

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

    // Full-body avatars.
    private var body: com.spark.companion.ui.avatar.BodySpec? = null
    private var pendingGender: com.spark.companion.ui.avatar.Gender? = null
    /** The saved avatar whose look is being changed (null while creating one). */
    private var outfitTarget: AvatarProfile? = null
    private var outfitFromEdit = false
    private var thumbJob: Job? = null

    // A realistic body made in this session, not saved yet.
    private var pendingReal: com.spark.companion.ui.avatar.RealBody.Result? = null
    private var pendingRealSwap = false
    private var pendingRealOutfit: String? = null
    /** Making the realistic picture in the background. */
    private var realJob: Job? = null
    private var realError: String? = null
    /** The next photo picked is a full-body photo to use as the body. */
    private var bodyPhotoMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 20)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, Ui.dp(this@AvatarStudioActivity, 48))
        }
        setContentView(ScrollView(this).apply {
            background = Ui.backdrop()
            fitsSystemWindows = true
            addView(content)
        })
        show(Screen.HOME)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Back steps through the studio's screens before leaving it. */
    private fun goBack(): Boolean {
        when (screen) {
            Screen.HOME -> return false
            Screen.EDIT -> show(if (editingId != null) Screen.HOME else if (photo != null) Screen.PHOTO else Screen.DESCRIBE)
            Screen.OUTFIT -> show(if (outfitFromEdit) Screen.EDIT else Screen.HOME)
            else -> show(Screen.HOME)
        }
        return true
    }

    // Android 13+ (and always on Android 16) delivers back through
    // OnBackInvokedCallback instead of onBackPressed. The callback is only
    // registered while there is a studio screen to go back to, so on the home
    // screen back leaves the activity normally (and the system animation shows).
    private val backCallback: Any? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            android.window.OnBackInvokedCallback { goBack() } else null
    private var backRegistered = false

    private fun syncBackCallback() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val cb = backCallback as android.window.OnBackInvokedCallback
        val want = screen != Screen.HOME
        if (want && !backRegistered) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backRegistered = true
        } else if (!want && backRegistered) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
            backRegistered = false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Android 12 and older.
        if (!goBack()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun show(next: Screen) {
        screen = next
        syncBackCallback()
        previewJob?.cancel()
        thumbJob?.cancel()
        content.removeAllViews()
        when (next) {
            Screen.HOME -> buildHome()
            Screen.PHOTO -> buildPhoto()
            Screen.DESCRIBE -> buildDescribe()
            Screen.EDIT -> buildEdit()
            Screen.OUTFIT -> buildOutfit()
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
        val all = repo.all()
        for (profile in all) content.addView(avatarRow(profile, profile.id == current))

        val inUse = all.firstOrNull { it.id == current }
        if (inUse?.body != null) {
            heading("OUTFIT & LOOK")
            note("Dress ${inUse.name} in any of 50 styles, and change hair, skin and eyes.")
            button("Change ${inUse.name}'s outfit & look", primary = true) {
                body = inUse.body
                outfitTarget = inUse
                outfitFromEdit = false
                pendingReal = null
                show(Screen.OUTFIT)
            }
        }

        heading("CREATE A NEW ONE")
        button("Use a full-body photo (real person)", primary = true) {
            bodyPhotoMode = true
            askGender()
        }
        note("Pick a photo of a person standing, head to toe, on a plain background (like a studio or wall photo). That real person becomes the avatar: they talk, blink, show feelings and breathe.")
        button("Full-body avatar with my face") {
            bodyPhotoMode = false
            askGender()
        }
        note("Upload a photo: your face goes onto a full female or male body, with matching skin and hair. Then pick from 50 outfits.")
        button("Use a photo (face only)") {
            pendingGender = null
            bodyPhotoMode = false
            pickPhoto()
        }
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
        body = profile.body
        pendingReal = null
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
        if (bodyPhotoMode) {
            bodyPhotoMode = false
            working("Getting the person ready…") {
                val loaded = withContext(Dispatchers.IO) { FaceTools.load(this@AvatarStudioActivity, uri) }
                    ?: throw LlmException("Couldn't open that picture.")
                val result = com.spark.companion.ui.avatar.RealBody.fromPhoto(loaded)
                val framed = withContext(Dispatchers.Default) { FaceTools.portrait(loaded) }
                photo = loaded
                if (pendingGender == null) pendingGender = com.spark.companion.ui.avatar.Gender.FEMALE
                startNew(framed)
                body = body?.copy(outfitId = com.spark.companion.ui.avatar.RealBody.PHOTO_OUTFIT, realistic = true)
                pendingReal = result
                pendingRealSwap = false
                pendingRealOutfit = com.spark.companion.ui.avatar.RealBody.PHOTO_OUTFIT
                show(Screen.EDIT)
            }
            return
        }
        working("Finding the face…") {
            val loaded = withContext(Dispatchers.IO) { FaceTools.load(this@AvatarStudioActivity, uri) }
                ?: throw LlmException("Couldn't open that picture.")
            val framed = withContext(Dispatchers.Default) { FaceTools.portrait(loaded) }
            photo = loaded
            startNew(framed)
            show(Screen.PHOTO)
        }
    }

    private fun askGender() {
        AlertDialog.Builder(this)
            .setTitle("Which body?")
            .setItems(arrayOf("Female", "Male")) { _, which ->
                pendingGender = if (which == 0) com.spark.companion.ui.avatar.Gender.FEMALE else com.spark.companion.ui.avatar.Gender.MALE
                pickPhoto()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startNew(framed: FaceTools.Portrait) {
        portrait = framed.bitmap
        landmarks = framed.landmarks
        faceFound = framed.faceFound
        editingId = null
        body = pendingGender?.let { com.spark.companion.ui.avatar.BodySpec.defaultFor(it).copy(skinFromFace = true, realistic = true) }
        pendingGender = null
        pendingReal = null
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
        val free = engine.settings.imageProvider == "pollinations"
        note(if (free) "Using the free image maker (Pollinations) — no key, no quota." else "Using Gemini (free-tier quota). You can switch to the free image maker in Spark settings.")
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
    /** Picks the image generator: Pollinations (free) unless restyling a photo. */
    private fun imageGen(needsPhoto: Boolean): ImageGen {
        val gemini = GeminiImageGen({ engine.settings.apiKey }, { engine.settings.imageModel }, onModelSwitch = { engine.settings.imageModel = it })
        if (needsPhoto) return gemini
        return if (engine.settings.imageProvider == "pollinations") PollinationsImageGen() else gemini
    }

    private fun generate(prompt: String, source: Bitmap?) {
        val gen = imageGen(needsPhoto = source != null)
        if (gen is GeminiImageGen && engine.settings.apiKey.isBlank()) {
            toast("Add your Gemini API key in Spark settings, or switch to the free image maker in Settings.")
            return
        }
        val label = if (gen is PollinationsImageGen) "Drawing your avatar (free)…" else "Gemini is drawing your avatar…"
        working(label) {
            val bytes = withContext(Dispatchers.Default) { source?.let { FaceTools.jpeg(it) } }
            val image = gen.generate(prompt, bytes)
            val bitmap = withContext(Dispatchers.Default) { FaceTools.decode(image) }
                ?: throw LlmException("The generated image couldn't be opened.")
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
        fun refreshPreview() {
            val b = body
            val real = pendingReal
            if (b != null && b.realistic && real != null && pendingRealOutfit == b.outfitId) {
                preview.fullBody = true
                if (pendingRealSwap) preview.previewReal(real.bitmap, real.face, accent, bitmap, landmarks)
                else preview.previewReal(real.bitmap, real.face, accent)
            } else if (b != null) {
                preview.fullBody = true
                preview.previewBody(b, accent, bitmap, landmarks)
            } else {
                preview.fullBody = false
                preview.preview(bitmap, landmarks, accent)
            }
        }
        val editor = LandmarkEditorView(this).apply {
            this.bitmap = bitmap
            this.landmarks = this@AvatarStudioActivity.landmarks
            onChange = { lm ->
                this@AvatarStudioActivity.landmarks = lm
                refreshPreview()
            }
        }
        content.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = Ui.dp(this@AvatarStudioActivity, 8) })

        heading("PREVIEW")
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        refreshPreview()
        previewRow.addView(preview, LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, if (body != null) 300 else 170)))
        val waitingReal = body?.let { b -> b.realistic && !(pendingReal != null && pendingRealOutfit == b.outfitId) } == true
        preview.alpha = if (waitingReal) 0.3f else 1f
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
        control("Next feeling") {
            previewJob?.cancel()
            preview.awake = true
            preview.phase = Phase.IDLE
            val all = com.spark.companion.brain.Mood.values()
            preview.mood = all[(preview.mood.ordinal + 1) % all.size]
            toast(preview.mood.tag.replaceFirstChar { it.uppercase() })
        }
        previewRow.addView(controls, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = Ui.dp(this@AvatarStudioActivity, 12) })
        content.addView(previewRow)
        realStatus(waitingReal, bitmap)

        heading("BODY")
        note("Show just the face, or put it on a full body that can move, gesture and wear 50 outfits.")
        val bodies = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val choices = listOf(null, com.spark.companion.ui.avatar.Gender.FEMALE, com.spark.companion.ui.avatar.Gender.MALE)
        for (g in choices) {
            bodies.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = when (g) { null -> "Face only (portrait)"; com.spark.companion.ui.avatar.Gender.FEMALE -> "Female full body"; else -> "Male full body" }
                setTextColor(Ui.TEXT)
                textSize = 15f
                isChecked = body?.gender == g
                setOnCheckedChangeListener { _, checked ->
                    if (!checked || body?.gender == g) return@setOnCheckedChangeListener
                    body = g?.let { com.spark.companion.ui.avatar.BodySpec.defaultFor(it).copy(skinFromFace = true, realistic = true) }
                    pendingReal = null
                    show(Screen.EDIT)
                }
            })
        }
        content.addView(bodies)
        if (body != null) {
            button("Choose outfit, hair & skin") {
                outfitTarget = null
                outfitFromEdit = true
                show(Screen.OUTFIT)
            }
        }

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

    // ── Outfit & look (full-body avatars) ────────────────────────────────

    private fun buildOutfit() {
        val start = body ?: return show(Screen.HOME)
        val target = outfitTarget
        // The face being worn: the one in the editor, or the saved avatar's portrait.
        val faceBitmap = if (outfitFromEdit) portrait else target?.portrait?.let { AvatarRepository.loadPortrait(it) }
        val faceMarks = if (outfitFromEdit) landmarks else target?.landmarks
        val tint = if (outfitFromEdit) accent else target?.accent ?: accent
        title("Outfit & look")
        note("Tap an outfit to try it on. Tap the avatar to see its feelings.")

        val preview = AvatarView(this).apply {
            awake = true
            fullBody = true
        }
        // The realistic picture for the chosen outfit: made just now, or saved earlier.
        fun realFor(spec: com.spark.companion.ui.avatar.BodySpec): Triple<Bitmap, FaceLandmarks, Boolean>? {
            val made = pendingReal
            if (made != null && pendingRealOutfit == spec.outfitId) return Triple(made.bitmap, made.face, pendingRealSwap)
            val saved = target?.takeIf { !outfitFromEdit && it.realOutfit == spec.outfitId && it.realFace != null } ?: return null
            val bmp = saved.realBody?.let { AvatarRepository.loadPortrait(it) } ?: return null
            return Triple(bmp, saved.realFace!!, saved.realSwap)
        }
        val currentReal = if (start.realistic) realFor(start) else null
        fun refresh() {
            val spec = body ?: return
            val real = if (spec.realistic) currentReal else null
            if (real != null) {
                if (real.third && faceBitmap != null && faceMarks != null) preview.previewReal(real.first, real.second, tint, faceBitmap, faceMarks)
                else preview.previewReal(real.first, real.second, tint)
            } else {
                preview.previewBody(spec, tint, faceBitmap, faceMarks)
            }
        }
        refresh()
        preview.setOnClickListener {
            val all = com.spark.companion.brain.Mood.values()
            preview.mood = all[(preview.mood.ordinal + 1) % all.size]
            toast(preview.mood.tag.replaceFirstChar { it.uppercase() })
        }
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 340)))
        if (start.realistic) {
            preview.alpha = if (currentReal == null) 0.3f else 1f
            if (!realStatus(currentReal == null, faceBitmap)) {
                note("Not quite right? Each try gives a new picture.")
                button("Try another picture") { makeReal(faceBitmap) }
            }
        }

        // Hair, colours and beard.
        fun chips(label: String, items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
            heading(label)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEachIndexed { i, (text, on) ->
                row.addView(Ui.text(this, text, 14f, if (on) Ui.BG else Ui.TEXT, bold = on).apply {
                    background = Ui.rounded(if (on) Ui.ACCENT else Ui.SURFACE_HI, Ui.dp(this@AvatarStudioActivity, 16).toFloat())
                    setPadding(Ui.dp(this@AvatarStudioActivity, 14), Ui.dp(this@AvatarStudioActivity, 8), Ui.dp(this@AvatarStudioActivity, 14), Ui.dp(this@AvatarStudioActivity, 8))
                    setOnClickListener { onPick(i) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = Ui.dp(this@AvatarStudioActivity, 8) })
            }
            content.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
        }
        fun swatches(label: String, colours: List<Int>, selected: Int, onPick: (Int) -> Unit) {
            heading(label)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in colours) {
                row.addView(View(this).apply {
                    background = Ui.rounded(c, Ui.dp(this@AvatarStudioActivity, 16).toFloat(),
                        if (c == selected) Ui.TEXT else null, Ui.dp(this@AvatarStudioActivity, 3))
                    setOnClickListener { onPick(c) }
                }, LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)).apply { marginEnd = Ui.dp(this@AvatarStudioActivity, 10) })
            }
            content.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
        }
        val b = start
        fun update(next: com.spark.companion.ui.avatar.BodySpec, rebuild: Boolean) {
            if (next.outfitId != body?.outfitId || next.realistic != body?.realistic) {
                // A different outfit needs its own picture.
                realJob?.cancel()
                realError = null
            }
            body = next
            if (rebuild) {
                val y = (content.parent as? ScrollView)?.scrollY ?: 0
                show(Screen.OUTFIT)
                (content.parent as? ScrollView)?.post { (content.parent as? ScrollView)?.scrollTo(0, y) }
            } else {
                refresh()
            }
        }
        chips("STYLE", listOf("Realistic (photo)" to b.realistic, "Illustrated (full gestures)" to !b.realistic)) {
            update(b.copy(realistic = it == 0), true)
        }
        if (b.realistic) note("Realistic: a real-looking person whose face talks, blinks and shows feelings, and whose body breathes and sways. Illustrated: drawn, but waves and gestures with the arms.")
        val hairs = com.spark.companion.ui.avatar.HairStyle.values().filter { it.gender == null || it.gender == b.gender }
        chips("HAIR", hairs.map { it.label to (it == b.hair) }) { update(b.copy(hair = hairs[it]), true) }
        if (faceBitmap != null) {
            chips("SKIN & HAIR COLOUR", listOf("Match my photo" to b.skinFromFace, "Choose myself" to !b.skinFromFace)) {
                update(b.copy(skinFromFace = it == 0), true)
            }
        }
        if (!b.skinFromFace || faceBitmap == null) {
            swatches("HAIR COLOUR", com.spark.companion.ui.avatar.BodySpec.HAIR_COLORS, b.hairColor) { update(b.copy(hairColor = it), true) }
            swatches("SKIN", com.spark.companion.ui.avatar.BodySpec.SKINS, b.skin) { update(b.copy(skin = it), true) }
        }
        if (faceBitmap == null) {
            swatches("EYES", com.spark.companion.ui.avatar.BodySpec.EYE_COLORS, b.eyeColor) { update(b.copy(eyeColor = it), true) }
        }
        if (b.gender == com.spark.companion.ui.avatar.Gender.MALE && faceBitmap == null) {
            val beards = com.spark.companion.ui.avatar.Beard.values().toList()
            chips("BEARD", beards.map { it.label to (it == b.beard) }) { update(b.copy(beard = beards[it]), true) }
        }

        // The outfit catalogue.
        val outfits = com.spark.companion.ui.avatar.Outfits.forGender(b.gender)
        heading("OUTFITS (${outfits.size})")
        val grid = android.widget.GridLayout(this).apply { columnCount = 3 }
        val cellW = (resources.displayMetrics.widthPixels - Ui.dp(this, 40)) / 3
        val cellH = (cellW * 1.75f).toInt()
        val images = ArrayList<ImageView>()
        for (o in outfits) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val on = o.id == b.outfitId
                background = Ui.rounded(if (on) Ui.SURFACE_HI else Ui.SURFACE, Ui.dp(this@AvatarStudioActivity, 12).toFloat(),
                    if (on) Ui.ACCENT else null, Ui.dp(this@AvatarStudioActivity, 2))
                setPadding(4, 6, 4, 8)
                setOnClickListener {
                    update(b.copy(outfitId = o.id), true)
                    (content.parent as? ScrollView)?.post { (content.parent as? ScrollView)?.scrollTo(0, 0) }
                }
            }
            val img = ImageView(this)
            images += img
            cell.addView(img, LinearLayout.LayoutParams(cellW - 12, cellH - Ui.dp(this, 26)))
            cell.addView(Ui.text(this, o.name, 11.5f, Ui.TEXT_DIM).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            grid.addView(cell, android.widget.GridLayout.LayoutParams().apply {
                width = cellW - Ui.dp(this@AvatarStudioActivity, 4)
                height = cellH
                setMargins(Ui.dp(this@AvatarStudioActivity, 2), Ui.dp(this@AvatarStudioActivity, 2), Ui.dp(this@AvatarStudioActivity, 2), Ui.dp(this@AvatarStudioActivity, 2))
            })
        }
        content.addView(grid)
        // Draw the thumbnails in the background, a few at a time.
        thumbJob = scope.launch {
            // Thumbnails use a drawn face, in the photo's skin and hair colours when matching it.
            val look = withContext(Dispatchers.Default) {
                if (b.skinFromFace && faceBitmap != null && faceMarks != null) {
                    val face = com.spark.companion.ui.avatar.PhotoFace(faceBitmap, faceMarks)
                    b.copy(skinFromFace = false, skin = face.skinTone, hairColor = face.hairTone)
                } else b
            }
            for ((i, o) in outfits.withIndex()) {
                val bmp = withContext(Dispatchers.Default) {
                    com.spark.companion.ui.avatar.BodyThumbs.render(
                        look.copy(outfitId = o.id), tint, cellW - 12, cellH - Ui.dp(this@AvatarStudioActivity, 26),
                    )
                }
                images[i].setImageBitmap(bmp)
            }
        }

        button(if (outfitFromEdit) "Done" else "Save this look", primary = true) {
            val chosen = body ?: return@button
            if (outfitFromEdit) {
                show(Screen.EDIT)
                return@button
            }
            val profile = outfitTarget ?: return@button
            working("Saving…") {
                var saved = withContext(Dispatchers.IO) { repo.saveBody(profile, chosen) }
                val real = pendingReal
                if (chosen.realistic && real != null && pendingRealOutfit == chosen.outfitId) {
                    saved = withContext(Dispatchers.IO) { repo.saveRealBody(saved, real, pendingRealSwap, chosen.outfitId) }
                    pendingReal = null
                }
                AvatarView.clearCache()
                engine.refreshSettings()
                toast(if (chosen.realistic && realFor(chosen) == null) "${saved.name} is getting dressed — the realistic photo is being made now."
                    else "${saved.name} is wearing ${chosen.outfit.name}.")
                show(Screen.HOME)
            }
        }
    }

    /**
     * Makes a photo-realistic picture of the current body and outfit. With your
     * photo and a Gemini key, Gemini keeps your likeness; otherwise the free
     * image service draws the person and your face is swapped on.
     */
    private fun makeReal(facePhoto: Bitmap?) {
        realJob?.cancel()
        realError = null
        startReal(facePhoto)
        rebuildKeepingScroll()
    }

    /** Starts making the realistic picture in the background; the screen updates when it's ready. */
    private fun startReal(facePhoto: Bitmap?) {
        val spec = body ?: return
        if (realJob?.isActive == true) return
        val settings = engine.settings
        val gemini = com.spark.companion.brain.GeminiImageGen({ settings.apiKey }, { settings.imageModel }, { settings.imageModel = it }, aspectRatio = "9:16")
        val free = PollinationsImageGen(PollinationsImageGen.TALL_WIDTH, PollinationsImageGen.TALL_HEIGHT)
        val tryGeminiFirst = settings.apiKey.isNotBlank() && (facePhoto != null || settings.imageProvider == "gemini")
        realError = null
        realJob = scope.launch {
            try {
                val (result, swapped) = try {
                    if (!tryGeminiFirst) throw LlmException("skip")
                    com.spark.companion.ui.avatar.RealBody.create(gemini, spec, facePhoto) to false
                } catch (e: LlmException) {
                    // Free fallback (also when Gemini's image quota is used up).
                    com.spark.companion.ui.avatar.RealBody.create(free, spec, null) to (facePhoto != null)
                }
                pendingReal = result
                pendingRealSwap = swapped
                pendingRealOutfit = spec.outfitId
            } catch (e: LlmException) {
                realError = e.message ?: "Something went wrong."
            }
            if (screen == Screen.EDIT || screen == Screen.OUTFIT) rebuildKeepingScroll()
        }
    }

    private fun rebuildKeepingScroll() {
        val scroll = content.parent as? ScrollView
        val y = scroll?.scrollY ?: 0
        show(screen)
        scroll?.post { scroll.scrollTo(0, y) }
    }

    /** A status line and retry button for the realistic picture; true while it isn't ready. */
    private fun realStatus(needed: Boolean, facePhoto: Bitmap?): Boolean {
        if (!needed) return false
        val error = realError
        when {
            realJob?.isActive == true -> note("Creating the realistic person… (about 20–40 seconds). The faint figure is only a placeholder.")
            error != null -> {
                note("Couldn't create the realistic person: $error")
                button("Try again", primary = true) { makeReal(facePhoto) }
            }
            else -> startReal(facePhoto).also { note("Creating the realistic person… (about 20–40 seconds).") }
        }
        return true
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
                    tagline = when {
                        body != null -> "Full body · your face"
                        photo != null || editingId != null -> "Your avatar"
                        else -> "Made with AI"
                    },
                    body = body,
                )
            }
            val real = pendingReal
            val b = body
            if (b != null && b.realistic && real != null && pendingRealOutfit == b.outfitId) {
                withContext(Dispatchers.IO) { repo.saveRealBody(saved, real, pendingRealSwap, b.outfitId) }
            }
            pendingReal = null
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
