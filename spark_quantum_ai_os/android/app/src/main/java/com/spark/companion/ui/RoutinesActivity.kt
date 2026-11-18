package com.spark.companion.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spark.companion.SparkApp
import com.spark.companion.automation.Routine
import com.spark.companion.automation.RoutineScheduler
import com.spark.companion.automation.RoutineStore
import com.spark.companion.automation.Trigger
import java.util.UUID

/** Create and manage automations: when something happens, Spark does a task. */
class RoutinesActivity : Activity() {

    private val store by lazy { RoutineStore(this) }
    private val scheduler by lazy { RoutineScheduler(this) }
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 20)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, Ui.dp(this@RoutinesActivity, 48))
        }
        setContentView(ScrollView(this).apply {
            background = Ui.backdrop()
            fitsSystemWindows = true
            addView(content)
        })
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(Ui.text(this, "Routines", 24f, Ui.TEXT, bold = true))
        content.addView(Ui.text(this, "Spark does a task on its own — at a time, when you plug in, or when a text arrives.", 13.5f, Ui.TEXT_DIM).apply {
            setPadding(0, Ui.dp(this@RoutinesActivity, 6), 0, Ui.dp(this@RoutinesActivity, 10))
        })
        content.addView(
            Ui.button(this, "+ New routine", Ui.ACCENT, Ui.BG) { edit(null) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = Ui.dp(this@RoutinesActivity, 12) }
        )
        val routines = store.all()
        if (routines.isEmpty()) {
            content.addView(Ui.text(this, "No routines yet. Try: “Every morning at 7, read my new notifications and tell me the time.”", 14f, Ui.TEXT_DIM))
        }
        for (r in routines) content.addView(row(r))
    }

    private fun row(r: Routine): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.SURFACE, Ui.dp(this@RoutinesActivity, 16).toFloat())
            setPadding(Ui.dp(this@RoutinesActivity, 16), Ui.dp(this@RoutinesActivity, 14), Ui.dp(this@RoutinesActivity, 16), Ui.dp(this@RoutinesActivity, 14))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, r.name, 16.5f, Ui.TEXT, bold = true))
        texts.addView(Ui.text(this, r.summary(), 13f, Ui.ACCENT))
        texts.addView(Ui.text(this, r.prompt, 13.5f, Ui.TEXT_DIM).apply { maxLines = 2 })
        top.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Switch(this).apply {
            isChecked = r.enabled
            setOnCheckedChangeListener { _, on ->
                val updated = r.copy(enabled = on)
                store.save(updated)
                scheduler.schedule(updated)
            }
        })
        card.addView(top)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, Ui.dp(this@RoutinesActivity, 8), 0, 0)
        }
        actions.addView(Ui.text(this, "Run now", 14f, Ui.OK, bold = true).apply {
            setPadding(Ui.dp(this@RoutinesActivity, 10), Ui.dp(this@RoutinesActivity, 6), Ui.dp(this@RoutinesActivity, 14), Ui.dp(this@RoutinesActivity, 6))
            setOnClickListener { SparkApp.engine.runRoutine(r.prompt) }
        })
        actions.addView(Ui.text(this, "Edit", 14f, Ui.ACCENT, bold = true).apply {
            setPadding(Ui.dp(this@RoutinesActivity, 10), Ui.dp(this@RoutinesActivity, 6), Ui.dp(this@RoutinesActivity, 10), Ui.dp(this@RoutinesActivity, 6))
            setOnClickListener { edit(r) }
        })
        actions.addView(Ui.text(this, "Delete", 14f, Ui.DANGER).apply {
            setPadding(Ui.dp(this@RoutinesActivity, 10), Ui.dp(this@RoutinesActivity, 6), 0, Ui.dp(this@RoutinesActivity, 6))
            setOnClickListener {
                store.delete(r.id)
                scheduler.cancel(r.id)
                render()
            }
        })
        card.addView(actions)
        return LinearLayout(this).apply {
            setPadding(0, 0, 0, Ui.dp(this@RoutinesActivity, 10))
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun edit(existing: Routine?) {
        val pad = Ui.dp(this, 20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val name = EditText(this).apply {
            setText(existing?.name ?: "")
            hint = "Name (e.g. Morning brief)"
            setTextColor(Ui.TEXT); setHintTextColor(Ui.TEXT_DIM); setSingleLine(true)
        }
        val prompt = EditText(this).apply {
            setText(existing?.prompt ?: "")
            hint = "What should Spark do? (say it like you'd ask)"
            setTextColor(Ui.TEXT); setHintTextColor(Ui.TEXT_DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        box.addView(Ui.text(this, "Name", 13f, Ui.TEXT_DIM))
        box.addView(name)
        box.addView(Ui.text(this, "Task", 13f, Ui.TEXT_DIM).apply { setPadding(0, Ui.dp(this@RoutinesActivity, 10), 0, 0) })
        box.addView(prompt)

        var trigger = existing?.trigger ?: Trigger.TIME
        var hour = existing?.hour ?: 8
        var minute = existing?.minute ?: 0
        val days = (existing?.days ?: emptySet()).toMutableSet()

        box.addView(Ui.text(this, "When", 13f, Ui.TEXT_DIM).apply { setPadding(0, Ui.dp(this@RoutinesActivity, 10), 0, 0) })
        val timeBtn = Ui.button(this, "", Ui.SURFACE_HI, Ui.TEXT) {}
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun refreshTime() { timeBtn.text = "At " + Routine(id = "", name = "", prompt = "", hour = hour, minute = minute).timeLabel() }
        fun renderDays() {
            dayRow.removeAllViews()
            val labels = listOf(1 to "S", 2 to "M", 3 to "T", 4 to "W", 5 to "T", 6 to "F", 7 to "S")
            for ((d, l) in labels) {
                val on = d in days
                dayRow.addView(Ui.text(this, l, 14f, if (on) Ui.BG else Ui.TEXT, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = Ui.rounded(if (on) Ui.ACCENT else Ui.SURFACE, Ui.dp(this@RoutinesActivity, 18).toFloat())
                    val sz = Ui.dp(this@RoutinesActivity, 34)
                    minWidth = sz; minHeight = sz
                    setOnClickListener { if (on) days.remove(d) else days.add(d); renderDays() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = Ui.dp(this@RoutinesActivity, 6) })
            }
        }
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> hour = h; minute = m; refreshTime() }, hour, minute, false).show()
        }
        val triggerRow = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        val timeViews = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timeViews.addView(timeBtn)
        timeViews.addView(Ui.text(this, "Days (none = every day)", 12.5f, Ui.TEXT_DIM).apply { setPadding(0, Ui.dp(this@RoutinesActivity, 8), 0, Ui.dp(this@RoutinesActivity, 4)) })
        timeViews.addView(dayRow)
        val triggers = mutableListOf(Trigger.TIME to "At a time", Trigger.CHARGER to "When I plug in the charger")
        if (com.spark.companion.BuildConfig.FULL_FEATURES) triggers += Trigger.SMS to "When a text arrives"
        for ((t, label) in triggers) {
            triggerRow.addView(android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                setTextColor(Ui.TEXT); textSize = 15f
                isChecked = trigger == t
                setOnCheckedChangeListener { _, checked -> if (checked) { trigger = t; timeViews.visibility = if (t == Trigger.TIME) View.VISIBLE else View.GONE } }
            })
        }
        box.addView(triggerRow)
        box.addView(timeViews)
        refreshTime(); renderDays()
        timeViews.visibility = if (trigger == Trigger.TIME) View.VISIBLE else View.GONE

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New routine" else "Edit routine")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val p = prompt.text.toString().trim()
                if (p.isEmpty()) return@setPositiveButton
                val routine = Routine(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name.text.toString().trim().ifEmpty { "Routine" },
                    prompt = p, trigger = trigger, hour = hour, minute = minute, days = days,
                    enabled = existing?.enabled ?: true,
                )
                store.save(routine)
                scheduler.schedule(routine)
                maybeAskExactAlarm(trigger)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** On Android 12+ exact alarms may need a one-time permission for on-time firing. */
    private fun maybeAskExactAlarm(trigger: Trigger) {
        if (trigger != Trigger.TIME || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val am = getSystemService(android.app.AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) return
        AlertDialog.Builder(this)
            .setMessage("For routines to run exactly on time, allow Spark to set alarms & reminders on the next screen.")
            .setPositiveButton("Open") { _, _ ->
                try {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    // not available
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }
}
