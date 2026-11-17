package com.spark.companion.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.spark.companion.SparkApp
import com.spark.companion.ui.OverlayController
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Spark's hands and eyes inside other apps. It only acts when the engine asks
 * it to during one of the user's requests; it does not watch the screen in
 * the background. While it is enabled it also hosts the floating avatar.
 */
class SparkAccessibilityService : AccessibilityService() {

    /** The elements from the most recent read_screen; tap-by-number refers to these. */
    @Volatile
    var lastSnapshot: ScreenSnapshot? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Touch the engine so it exists, then take over drawing the floating avatar.
        SparkApp.engine
        OverlayController.attachAccessibility(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        OverlayController.detachAccessibility()
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

    /** Injected touches land on the top window, so Spark's own overlay steps aside. */
    private suspend fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        OverlayController.hiddenFor { dispatch(x1, y1, x2, y2, durationMs) }

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

    companion object {
        /** Non-null while the user has the service enabled. */
        @Volatile
        var instance: SparkAccessibilityService? = null
            private set
    }
}
