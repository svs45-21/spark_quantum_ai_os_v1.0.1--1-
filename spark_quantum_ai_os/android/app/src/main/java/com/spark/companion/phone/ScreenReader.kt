package com.spark.companion.phone

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** One thing on screen that Spark can talk about or act on. */
class ScreenElement(
    val index: Int,
    val node: AccessibilityNodeInfo,
    val kind: String,
    val label: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
)

class ScreenSnapshot(val packageName: String, val elements: List<ScreenElement>) {

    /** Compact text description for the model. */
    fun describe(max: Int = 140): String = buildString {
        append("App on screen: ").append(packageName.ifEmpty { "unknown" }).append('\n')
        if (elements.isEmpty()) {
            append("(Nothing readable on screen. It may be a game, video, or still loading.)")
            return@buildString
        }
        for (e in elements.take(max)) {
            append('[').append(e.index).append("] ").append(e.kind)
            if (e.label.isNotEmpty()) append(" \"").append(e.label).append('"')
            val flags = ArrayList<String>()
            if (e.editable) flags.add("editable")
            if (e.node.isFocused) flags.add("focused")
            if (e.node.isCheckable) flags.add(if (e.node.isChecked) "checked" else "unchecked")
            if (e.node.isSelected) flags.add("selected")
            if (e.node.isScrollable) flags.add("scrollable")
            if (!e.node.isEnabled) flags.add("disabled")
            if (flags.isNotEmpty()) append(" (").append(flags.joinToString(", ")).append(')')
            append('\n')
        }
        if (elements.size > max) append("… ").append(elements.size - max).append(" more; scroll to see them\n")
    }

    fun byIndex(index: Int): ScreenElement? = elements.firstOrNull { it.index == index }

    /** Finds an element by its visible text, preferring tappable ones. */
    fun byText(text: String): ScreenElement? {
        val scored = elements
            .map { it to NameMatch.score(text, it.label) }
            .filter { it.second > 0 }
        return scored.sortedWith(
            compareByDescending<Pair<ScreenElement, Int>> { it.second }
                .thenByDescending { it.first.clickable || it.first.editable }
        ).firstOrNull()?.first
    }
}

object ScreenReader {

    private const val MAX_NODES = 4000

    fun snapshot(root: AccessibilityNodeInfo?): ScreenSnapshot {
        if (root == null) return ScreenSnapshot("", emptyList())
        val out = ArrayList<ScreenElement>()
        val budget = intArrayOf(MAX_NODES)
        walk(root, out, budget, inheritedClick = false)
        return ScreenSnapshot(root.packageName?.toString().orEmpty(), out)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<ScreenElement>,
        budget: IntArray,
        inheritedClick: Boolean,
    ) {
        if (budget[0]-- <= 0) return
        if (!node.isVisibleToUser) return

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val editable = node.isEditable
        val clickable = node.isClickable || node.isLongClickable
        val text = if (node.isPassword) "" else node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val hint = node.hintText?.toString().orEmpty()
        var label = sequenceOf(text, desc, hint).firstOrNull { it.isNotBlank() }.orEmpty()

        // A clickable container with no text of its own: borrow its children's text.
        if (label.isEmpty() && clickable) label = childText(node, 3)
        label = label.replace(Regex("\\s+"), " ").trim().take(120)

        val interesting = !bounds.isEmpty &&
            (editable || node.isCheckable || node.isScrollable ||
                (clickable && label.isNotEmpty()) ||
                // Plain text is listed unless a clickable ancestor already carries it.
                (label.isNotEmpty() && !inheritedClick))

        if (interesting) {
            out.add(
                ScreenElement(
                    index = out.size + 1,
                    node = node,
                    kind = kindOf(node, editable, clickable),
                    label = if (node.isPassword) "password field" else label,
                    bounds = bounds,
                    clickable = clickable,
                    editable = editable,
                )
            )
        }
        val passDown = inheritedClick || (clickable && label.isNotEmpty())
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, budget, passDown)
        }
    }

    private fun childText(node: AccessibilityNodeInfo, depth: Int): String {
        if (depth == 0) return ""
        val parts = ArrayList<String>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val own = if (child.isPassword) "" else
                (child.text?.toString().orEmpty().ifBlank { child.contentDescription?.toString().orEmpty() })
            if (own.isNotBlank()) parts.add(own) else childText(child, depth - 1).let { if (it.isNotBlank()) parts.add(it) }
            if (parts.size >= 3) break
        }
        return parts.joinToString(" · ")
    }

    private fun kindOf(node: AccessibilityNodeInfo, editable: Boolean, clickable: Boolean): String {
        val cls = node.className?.toString().orEmpty().substringAfterLast('.')
        return when {
            editable -> "Field"
            cls.contains("Switch") || cls.contains("Toggle") -> "Switch"
            cls.contains("CheckBox") -> "Checkbox"
            cls.contains("RadioButton") -> "Option"
            cls.contains("Button") -> "Button"
            cls.contains("Image") && clickable -> "Button"
            cls.contains("Image") -> "Image"
            node.isCheckable -> "Toggle"
            clickable -> "Item"
            node.isScrollable -> "List"
            else -> "Text"
        }
    }
}
