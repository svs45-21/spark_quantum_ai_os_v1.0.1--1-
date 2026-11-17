package com.spark.companion.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.spark.companion.SparkApp
import com.spark.companion.engine.EngineState
import com.spark.companion.engine.PendingConfirm
import com.spark.companion.engine.Phase
import com.spark.companion.ui.OrbView
import com.spark.companion.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Spark's hands and eyes inside other apps. It only acts when the engine asks
 * it to during one of the user's requests; it does not watch the screen in
 * the background. It also draws the floating orb and the confirmation card.
 */
class SparkAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var orb: OrbView? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var card: FrameLayout? = null
    private var cardText: TextView? = null
    private var cardButtons: LinearLayout? = null
    private var cardMode = ""

    /** The elements from the most recent read_screen; tap-by-number refers to these. */
    @Volatile
    var lastSnapshot: ScreenSnapshot? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        val engine = SparkApp.engine
        scope.launch {
            combine(
                engine.state, engine.pendingConfirm, engine.uiVisible, engine.orbEnabled, engine.partial
            ) { state, pending, visible, orbOn, partial ->
                OverlayModel(state, pending, visible, orbOn, partial)
            }.collect { render(it) }
        }
        scope.launch { engine.level.collect { orb?.level = it } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        scope.cancel()
        removeView(orb)
        removeView(card)
        orb = null
        card = null
        super.onDestroy()
    }

    // ── Reading ──────────────────────────────────────────────────────────

    fun readScreen(): ScreenSnapshot {
        val snapshot = ScreenReader.snapshot(rootInActiveWindow)
        lastSnapshot = snapshot
        return snapshot
    }

    val foregroundPackage: String
        get() = try {
            rootInActiveWindow?.packageName?.toString().orEmpty()
        } catch (e: Exception) {
            ""
        }

    // ── Acting ───────────────────────────────────────────────────────────

    suspend fun tap(element: ScreenElement, longPress: Boolean): Boolean {
        if (!element.node.refresh()) return false
        var node: AccessibilityNodeInfo? = element.node
        val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        while (node != null) {
            val can = if (longPress) node.isLongClickable else node.isClickable
            if (can && node.performAction(action)) return true
            node = node.parent
        }
        // Nothing in the tree accepts the click: touch the screen at its centre.
        val bounds = Rect().also { element.node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return false
        return gesture(
            bounds.exactCenterX(), bounds.exactCenterY(),
            bounds.exactCenterX(), bounds.exactCenterY(),
            if (longPress) 700L else 60L
        )
    }

    fun focusedField(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.refresh()) return false
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Presses the keyboard's enter/search/send key on [node]. Android 11+. */
    fun imeEnter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    suspend fun scroll(direction: String): Boolean {
        val forward = direction == "down" || direction == "right"
        val target = largestScrollable(rootInActiveWindow)
        if (target != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (target.performAction(action)) return true
        }
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        return when (direction) {
            "down" -> gesture(w / 2, h * 0.72f, w / 2, h * 0.28f, 350)
            "up" -> gesture(w / 2, h * 0.28f, w / 2, h * 0.72f, 350)
            "right" -> gesture(w * 0.8f, h / 2, w * 0.2f, h / 2, 350)
            else -> gesture(w * 0.2f, h / 2, w * 0.8f, h / 2, 350)
        }
    }

    private fun largestScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val rect = Rect()
        while (queue.isNotEmpty() && visited++ < 3000) {
            val node = queue.removeFirst()
            if (node.isScrollable && node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                val area = rect.width().toLong() * rect.height()
                if (area > bestArea) {
                    bestArea = area
                    best = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return best
    }

    fun pressButton(button: String): Boolean {
        val action = when (button) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return false
            "screenshot" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            else -> return false
        }
        return performGlobalAction(action)
    }

    /**
     * Injected touches land on whatever window is on top, which could be
     * Spark's own orb or card, so those are hidden while a gesture runs.
     */
    private suspend fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        hideForGesture = true
        orb?.visibility = View.GONE
        card?.visibility = View.GONE
        return try {
            delay(80)
            dispatch(x1, y1, x2, y2, durationMs)
        } finally {
            hideForGesture = false
            lastModel?.let { render(it) }
        }
    }

    private suspend fun dispatch(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(x1, y1)
                if (x1 != x2 || y1 != y2) lineTo(x2, y2)
            }
            val description = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val accepted = dispatchGesture(description, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!accepted && cont.isActive) cont.resume(false)
        }

    // ── Overlay: floating orb + status / confirmation card ───────────────

    private class OverlayModel(
        val state: EngineState,
        val pending: PendingConfirm?,
        val uiVisible: Boolean,
        val orbEnabled: Boolean,
        val partial: String,
    )

    private var lastModel: OverlayModel? = null
    private var hideForGesture = false

    private fun render(m: OverlayModel) {
        lastModel = m
        if (hideForGesture) return
        val active = m.state.phase != Phase.IDLE
        val showOrb = !m.uiVisible && (m.orbEnabled || active)
        val showCard = !m.uiVisible && (m.pending != null || active)

        if (showOrb) {
            ensureOrb()
            orb?.phase = m.state.phase
            orb?.visibility = View.VISIBLE
        } else {
            orb?.visibility = View.GONE
        }

        if (showCard) {
            ensureCard()
            card?.visibility = View.VISIBLE
            if (m.pending != null) {
                cardText?.text = m.pending.summary
                setCardButtons("confirm")
            } else {
                cardText?.text = when (m.state.phase) {
                    Phase.LISTENING -> m.partial.ifBlank { m.state.detail.ifBlank { "Listening…" } }
                    Phase.THINKING -> "Thinking…"
                    Phase.ACTING -> m.state.detail.ifBlank { "Working…" }
                    Phase.SPEAKING -> "Speaking…"
                    Phase.IDLE -> ""
                }
                setCardButtons("status")
            }
        } else {
            card?.visibility = View.GONE
        }
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    private fun ensureOrb() {
        if (orb != null) return
        val size = Ui.dp(this, 64)
        val metrics = resources.displayMetrics
        val params = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - size - Ui.dp(this@SparkAccessibilityService, 8)
            y = (metrics.heightPixels * 0.55f).toInt()
        }
        val view = OrbView(this)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try {
                            windowManager?.updateViewLayout(v, params)
                        } catch (e: Exception) {
                            // view detached
                        }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) {
                    v.performClick()
                }
            }
            true
        }
        view.setOnClickListener { SparkApp.engine.toggleVoice() }
        view.contentDescription = "Spark"
        if (addView(view, params)) {
            orb = view
            orbParams = params
        }
    }

    private fun ensureCard() {
        if (card != null) return
        val pad = Ui.dp(this, 16)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.SURFACE, Ui.dp(this@SparkAccessibilityService, 20).toFloat(), Ui.ACCENT, Ui.dp(this@SparkAccessibilityService, 1))
            setPadding(pad, pad, pad, pad)
        }
        val text = Ui.text(this, "", 15f).apply { maxLines = 6 }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, Ui.dp(this@SparkAccessibilityService, 12), 0, 0)
        }
        layout.addView(text)
        layout.addView(buttons)
        val holder = FrameLayout(this).apply {
            setPadding(pad, 0, pad, Ui.dp(this@SparkAccessibilityService, 28))
            addView(layout)
        }
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (addView(holder, params)) {
            card = holder
            cardText = text
            cardButtons = buttons
            cardMode = ""
        }
    }

    private fun setCardButtons(mode: String) {
        if (cardMode == mode) return
        cardMode = mode
        val row = cardButtons ?: return
        row.removeAllViews()
        val gap = Ui.dp(this, 10)
        if (mode == "confirm") {
            val deny = Ui.button(this, "Don't", Ui.SURFACE_HI, Ui.TEXT) { SparkApp.engine.answerConfirm(false) }
            val allow = Ui.button(this, "Go ahead") { SparkApp.engine.answerConfirm(true) }
            row.addView(deny)
            row.addView(allow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = gap })
        } else {
            row.addView(Ui.button(this, "Stop", Ui.SURFACE_HI, Ui.DANGER) { SparkApp.engine.stop() })
        }
    }

    private fun addView(view: View, params: WindowManager.LayoutParams): Boolean = try {
        windowManager?.addView(view, params)
        true
    } catch (e: Exception) {
        false
    }

    private fun removeView(view: View?) {
        view ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            // not attached
        }
    }

    companion object {
        /** Non-null while the user has the service enabled. */
        @Volatile
        var instance: SparkAccessibilityService? = null
            private set
    }
}
