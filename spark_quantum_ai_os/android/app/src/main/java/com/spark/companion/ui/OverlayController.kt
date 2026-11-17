package com.spark.companion.ui

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.spark.companion.SparkApp
import com.spark.companion.brain.Mood
import com.spark.companion.engine.EngineState
import com.spark.companion.engine.PendingConfirm
import com.spark.companion.engine.Phase
import com.spark.companion.ui.avatar.AvatarProfile
import com.spark.companion.ui.avatar.AvatarView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Decides who draws Spark's floating avatar over other apps. The
 * accessibility service is preferred (its overlay also works on the lock
 * screen and lets Spark launch apps); otherwise the companion service draws
 * it if the user allowed "Display over other apps". Main thread only.
 */
object OverlayController {
    private var current: AvatarOverlay? = null
    private var fromAccessibility = false

    val showing: Boolean get() = current != null

    fun attachAccessibility(context: Context) {
        current?.detach()
        current = AvatarOverlay(context, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).also { it.attach() }
        fromAccessibility = true
    }

    fun detachAccessibility() {
        if (!fromAccessibility) return
        current?.detach()
        current = null
        fromAccessibility = false
    }

    fun attachApp(context: Context) {
        if (current != null || !canDrawOverlays(context)) return
        current = AvatarOverlay(context, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY).also { it.attach() }
        fromAccessibility = false
    }

    fun detachApp() {
        if (fromAccessibility) return
        current?.detach()
        current = null
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Hides the overlay while [block] runs so injected touches reach the app below. */
    suspend fun <T> hiddenFor(block: suspend () -> T): T {
        val overlay = current ?: return block()
        overlay.hidden = true
        return try {
            delay(80)
            block()
        } finally {
            overlay.hidden = false
        }
    }
}

/** The floating avatar plus a subtitle / confirmation card. */
class AvatarOverlay(private val context: Context, private val windowType: Int) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var avatar: AvatarView? = null
    private var avatarParams: WindowManager.LayoutParams? = null
    private var card: FrameLayout? = null
    private var cardText: TextView? = null
    private var cardButtons: LinearLayout? = null
    private var cardMode = ""
    private var last: Model? = null

    var hidden = false
        set(value) {
            field = value
            if (value) {
                avatar?.visibility = View.GONE
                card?.visibility = View.GONE
            } else {
                last?.let { render(it) }
            }
        }

    private data class Model(
        val state: EngineState,
        val pending: PendingConfirm?,
        val uiVisible: Boolean,
        val floating: Boolean,
        val awake: Boolean,
        val partial: String,
        val caption: String,
        val mood: Mood,
        val profile: AvatarProfile,
    )

    fun attach() {
        val engine = SparkApp.engine
        scope.launch {
            val head = combine(engine.state, engine.pendingConfirm, engine.uiVisible, engine.floatingEnabled, engine.awake) { s, p, u, f, a ->
                Model(s, p, u, f, a, "", "", Mood.CALM, AvatarProfile.DEFAULT)
            }
            val tail = combine(engine.partial, engine.caption, engine.mood, engine.avatar) { pa, c, m, st -> listOf(pa, c, m, st) }
            combine(head, tail) { h, t ->
                h.copy(partial = t[0] as String, caption = t[1] as String, mood = t[2] as Mood, profile = t[3] as AvatarProfile)
            }.collect { render(it) }
        }
        scope.launch { engine.level.collect { avatar?.level = it } }
    }

    fun detach() {
        scope.cancel()
        remove(avatar)
        remove(card)
        avatar = null
        card = null
    }

    private fun render(m: Model) {
        last = m
        if (hidden) return
        val active = m.state.phase != Phase.IDLE
        val showAvatar = !m.uiVisible && (m.floating || m.awake || active)
        val showCard = !m.uiVisible && (m.pending != null || active)

        if (showAvatar) {
            ensureAvatar()
            avatar?.let { v ->
                v.profile = m.profile
                v.awake = m.awake
                v.phase = m.state.phase
                v.mood = m.mood
                v.visibility = View.VISIBLE
                resizeAvatar(if (m.awake || active) BIG_DP else SMALL_DP)
            }
        } else {
            avatar?.visibility = View.GONE
        }

        if (showCard) {
            ensureCard()
            card?.visibility = View.VISIBLE
            if (m.pending != null) {
                cardText?.text = m.pending.summary
                setButtons("confirm")
            } else {
                cardText?.text = when (m.state.phase) {
                    Phase.LISTENING -> m.partial.ifBlank { m.state.detail.ifBlank { "I'm listening…" } }
                    Phase.THINKING -> "Thinking…"
                    Phase.ACTING -> m.state.detail.ifBlank { "Working on it…" }
                    Phase.SPEAKING -> m.caption.ifBlank { "…" }
                    Phase.IDLE -> ""
                }
                setButtons("status")
            }
        } else {
            card?.visibility = View.GONE
        }
    }

    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    private fun resizeAvatar(dp: Int) {
        val view = avatar ?: return
        val p = avatarParams ?: return
        val size = Ui.dp(context, dp)
        if (p.width == size) return
        // Keep it anchored to the screen edge it sits on.
        val metrics = context.resources.displayMetrics
        if (p.x + p.width / 2 > metrics.widthPixels / 2) p.x -= size - p.width
        p.width = size
        p.height = size
        try {
            windowManager.updateViewLayout(view, p)
        } catch (e: Exception) {
            // detached
        }
    }

    private fun ensureAvatar() {
        if (avatar != null) return
        val size = Ui.dp(context, SMALL_DP)
        val metrics = context.resources.displayMetrics
        val p = params(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - size - Ui.dp(context, 4)
            y = (metrics.heightPixels * 0.5f).toInt()
        }
        val view = AvatarView(context)
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(v, p)
                        } catch (ex: Exception) {
                            // detached
                        }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) v.performClick()
            }
            true
        }
        view.setOnClickListener { SparkApp.engine.toggleVoice() }
        view.contentDescription = "Spark"
        if (add(view, p)) {
            avatar = view
            avatarParams = p
        }
    }

    private fun ensureCard() {
        if (card != null) return
        val pad = Ui.dp(context, 16)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 20).toFloat(), Ui.ACCENT, Ui.dp(context, 1))
            setPadding(pad, pad, pad, pad)
        }
        val text = Ui.text(context, "", 15.5f).apply {
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.START
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, Ui.dp(context, 12), 0, 0)
        }
        layout.addView(text)
        layout.addView(buttons)
        val holder = FrameLayout(context).apply {
            setPadding(pad, 0, pad, Ui.dp(context, 28))
            addView(layout)
        }
        val p = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (add(holder, p)) {
            card = holder
            cardText = text
            cardButtons = buttons
            cardMode = ""
        }
    }

    private fun setButtons(mode: String) {
        if (cardMode == mode) return
        cardMode = mode
        val row = cardButtons ?: return
        row.removeAllViews()
        if (mode == "confirm") {
            row.addView(Ui.button(context, "Don't", Ui.SURFACE_HI, Ui.TEXT) { SparkApp.engine.answerConfirm(false) })
            row.addView(
                Ui.button(context, "Go ahead") { SparkApp.engine.answerConfirm(true) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = Ui.dp(context, 10) }
            )
        } else {
            row.addView(Ui.button(context, "Stop", Ui.SURFACE_HI, Ui.DANGER) { SparkApp.engine.stop() })
        }
    }

    private fun add(view: View, p: WindowManager.LayoutParams): Boolean = try {
        windowManager.addView(view, p)
        true
    } catch (e: Exception) {
        false
    }

    private fun remove(view: View?) {
        view ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // not attached
        }
    }

    companion object {
        private const val SMALL_DP = 68
        private const val BIG_DP = 112
    }
}
