package com.spark.companion.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.spark.companion.SparkApp
import com.spark.companion.brain.LlmException
import com.spark.companion.brain.Mood
import com.spark.companion.brain.Vision
import com.spark.companion.ui.avatar.FaceTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Spark's eyes: snap a photo and ask Gemini about it, then hear the answer. */
class VisionActivity : Activity() {

    private val engine get() = SparkApp.engine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var image: ImageView
    private lateinit var answer: TextView
    private lateinit var hint: TextView
    private lateinit var buttons: LinearLayout
    private var photo: Bitmap? = null
    private var captureUri: Uri? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (engine.settings.apiKey.isBlank()) {
            answer.text = "Camera vision uses Gemini. Add your Gemini API key in Spark settings first."
        } else {
            capture()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun capture() {
        val dir = File(cacheDir, "vision").apply { mkdirs() }
        val file = File(dir, "shot.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        captureUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CAPTURE)
        } catch (e: Exception) {
            answer.text = "No camera app was found on this phone."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK) {
            if (photo == null) finish()
            return
        }
        val uri = captureUri ?: return
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    null
                }
            }
            if (bmp == null) {
                answer.text = "I couldn't read that photo. Try again."
                return@launch
            }
            photo = bmp
            image.setImageBitmap(bmp)
            image.visibility = View.VISIBLE
            hint.text = "What would you like to know?"
            buttons.visibility = View.VISIBLE
            ask(Vision.Task.IDENTIFY)
        }
    }

    private fun ask(task: Vision.Task) = askQuestion(task.prompt, task.label)

    private fun askQuestion(prompt: String, label: String) {
        val bmp = photo ?: return
        job?.cancel()
        hint.text = label
        answer.text = "Looking…"
        job = scope.launch {
            try {
                val jpeg = withContext(Dispatchers.Default) { FaceTools.jpeg(bmp, 1280) }
                val reply = Vision.ask(prompt, jpeg, engine.settings.primaryLanguage, { engine.settings.apiKey }, { engine.settings.model })
                answer.text = reply
                if (engine.settings.speakReplies) engine.speakExternal(reply, Mood.THOUGHTFUL)
            } catch (e: LlmException) {
                answer.text = e.message ?: "That didn't work. Try again."
            }
        }
    }

    private fun buildUi(): View {
        val pad = Ui.dp(this, 16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            setPadding(pad, pad, pad, pad)
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Ui.text(this, "Spark Vision", 20f, Ui.ACCENT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Ui.button(this, "New photo") { capture() })
        root.addView(bar)

        image = ImageView(this).apply {
            adjustViewBounds = true
            visibility = View.GONE
            setPadding(0, Ui.dp(this@VisionActivity, 12), 0, Ui.dp(this@VisionActivity, 12))
        }
        root.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        hint = Ui.text(this, "Opening the camera…", 14f, Ui.TEXT_DIM, bold = true)
        root.addView(hint)
        answer = Ui.text(this, "", 16f, Ui.TEXT).apply {
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
            setPadding(0, Ui.dp(this@VisionActivity, 8), 0, Ui.dp(this@VisionActivity, 8))
        }
        root.addView(ScrollView(this).apply { addView(answer) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(buttons) }
        for (task in Vision.Task.values()) {
            buttons.addView(Ui.button(this, task.label, Ui.SURFACE_HI, Ui.TEXT) { ask(task) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = Ui.dp(this@VisionActivity, 8) })
        }
        root.addView(scroll)
        return root
    }

    companion object {
        private const val REQUEST_CAPTURE = 51
    }
}
