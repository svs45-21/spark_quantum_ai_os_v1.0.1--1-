package com.spark.companion.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import com.spark.companion.brain.ModelEntry
import com.spark.companion.brain.ModelListing
import com.spark.companion.core.ModelListStore

/**
 * A drop-down of the models a provider offers right now. The list comes from
 * [ModelListStore], which Spark refreshes in the background, so newly released
 * models appear by themselves (marked NEW for a week). Any name can still be
 * typed by hand.
 */
class ModelDropdown(
    private val activity: Activity,
    private val title: String,
    private val listKey: String,
    initial: String,
    private val lists: ModelListStore,
    private val onRefresh: () -> Unit,
    private val onChoose: (String) -> Unit,
) {
    var value: String = initial
        private set

    private val valueText = Ui.text(activity, "", 15f, Ui.TEXT)
    private val infoText = Ui.text(activity, "", 12f, Ui.TEXT_DIM)

    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.SURFACE, Ui.dp(activity, 12).toFloat())
        val p = Ui.dp(activity, 14)
        setPadding(p, Ui.dp(activity, 10), p, Ui.dp(activity, 10))
        isClickable = true
        isFocusable = true
        contentDescription = "$title, choose a model"
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(valueText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(activity, "▾", 18f, Ui.ACCENT, bold = true))
        addView(row)
        addView(infoText)
        setOnClickListener { open() }
    }

    init {
        render()
    }

    fun set(model: String) {
        value = model
        render()
    }

    /** Re-reads the saved list, e.g. after a background refresh. */
    fun render() {
        valueText.text = value.ifBlank { "Choose a model" }
        val list = lists.get(listKey)
        val now = System.currentTimeMillis()
        infoText.text = if (list == null || list.entries.isEmpty()) {
            "Tap to choose · list appears after “Refresh model lists”"
        } else {
            val fresh = list.entries.count { ModelListing.isNew(it, now) }
            val current = list.entries.firstOrNull { it.id == value }
            listOfNotNull(
                "${list.entries.size} models",
                if (fresh > 0) "$fresh NEW" else null,
                "updated ${ago(now - list.fetchedAt)}",
                current?.tags?.takeIf { it.isNotBlank() },
                if (value.isNotBlank() && current == null) "not in the provider's list" else null,
            ).joinToString(" · ")
        }
    }

    private fun ago(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "${min}m ago"
            min < 48 * 60 -> "${min / 60}h ago"
            else -> "${min / (24 * 60)}d ago"
        }
    }

    private fun label(e: ModelEntry, now: Long, recommended: Boolean): CharSequence {
        val b = SpannableStringBuilder()
        val start = b.length
        b.append(e.id)
        b.setSpan(StyleSpan(Typeface.BOLD), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val badges = listOfNotNull(
            if (e.id == value) "✓ in use" else null,
            if (recommended) "★ recommended" else null,
            if (ModelListing.isNew(e, now)) "NEW" else null,
        )
        if (badges.isNotEmpty()) {
            val s = b.length
            b.append("  ").append(badges.joinToString("  "))
            b.setSpan(ForegroundColorSpan(Ui.ACCENT), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (e.tags.isNotBlank()) {
            val s = b.length
            b.append("\n").append(e.tags)
            b.setSpan(RelativeSizeSpan(0.82f), s, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return b
    }

    private fun open() {
        val now = System.currentTimeMillis()
        val list = lists.get(listKey)
        val entries = list?.entries.orEmpty()
        // Newly released models float to the top so they're easy to spot.
        val ordered = entries.withIndex().sortedWith(
            compareBy<IndexedValue<ModelEntry>> { if (ModelListing.isNew(it.value, now)) 0 else 1 }.thenBy { it.index }
        ).map { it.value }
        val recommended = entries.firstOrNull()?.id

        val search = EditText(activity).apply {
            hint = if (entries.isEmpty()) "No list yet" else "Search ${entries.size} models"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        var shown = ordered
        val adapter = ArrayAdapter<CharSequence>(activity, android.R.layout.simple_list_item_1, ArrayList())
        fun filter(q: String) {
            shown = if (q.isBlank()) ordered else ordered.filter { it.id.contains(q.trim(), ignoreCase = true) || it.tags.contains(q.trim(), ignoreCase = true) }
            adapter.clear()
            adapter.addAll(shown.map { label(it, now, it.id == recommended) })
        }
        filter("")
        val listView = ListView(activity).apply { this.adapter = adapter }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(activity, 16)
            setPadding(p, Ui.dp(activity, 8), p, 0)
            addView(search)
            addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 360)))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(
                if (entries.isEmpty()) "Add the key above, then tap Refresh. You can also type a model name."
                else "Updated ${ago(now - (list?.fetchedAt ?: now))}. New models appear here by themselves."
            )
            .setView(box)
            .setPositiveButton("Refresh") { _, _ -> onRefresh() }
            .setNeutralButton("Type a name") { _, _ -> typeName() }
            .setNegativeButton("Cancel", null)
            .create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
        listView.setOnItemClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { choose(it.id) }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun typeName() {
        val edit = EditText(activity).apply {
            setText(value)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(LinearLayout(activity).apply {
                val p = Ui.dp(activity, 20)
                setPadding(p, Ui.dp(activity, 8), p, 0)
                addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton("Use") { _, _ -> edit.text.toString().trim().let { if (it.isNotEmpty()) choose(it) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun choose(model: String) {
        set(model)
        onChoose(model)
    }
}
