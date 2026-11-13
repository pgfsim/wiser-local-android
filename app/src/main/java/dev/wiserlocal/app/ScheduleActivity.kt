package dev.wiserlocal.app

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Edits one schedule a day at a time. Changes stay on the phone until Save is
 * tapped, which sends all seven days to the hub in one request.
 */
class ScheduleActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()

    /** Working copy of the schedule's days; [schedule] holds what the hub has. */
    private val days = mutableMapOf<String, MutableList<Slot>>()
    private var schedule: Schedule? = null
    private var dirty = false
    private var busy = false

    // Calendar counts Sunday as 1; DAYS starts on Monday.
    private var day = WiserHub.DAYS[(Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7]

    private lateinit var dayBar: LinearLayout
    private lateinit var slotList: LinearLayout
    private lateinit var status: TextView
    private lateinit var addButton: Button
    private lateinit var copyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.schedule)

        dayBar = findViewById(R.id.day_bar)
        slotList = findViewById(R.id.slot_list)
        status = findViewById(R.id.schedule_status)
        addButton = findViewById(R.id.add_slot)
        copyButton = findViewById(R.id.copy_day)

        val pad = (8 * resources.displayMetrics.density).toInt()
        for (name in WiserHub.DAYS) {
            dayBar.addView(
                TextView(this).apply {
                    text = name.take(3)
                    contentDescription = name
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setPadding(0, pad, 0, pad)
                    setOnClickListener {
                        day = name
                        render()
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        addButton.setOnClickListener { addSlot() }
        copyButton.setOnClickListener { copyDay() }
        load()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.schedule, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_save -> { save(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onNavigateUp(): Boolean {
        leave()
        return true
    }

    @Deprecated("Deprecated in Java")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = leave()

    private fun leave() {
        if (!dirty) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.discard_changes)
            .setPositiveButton(R.string.discard) { _, _ -> finish() }
            .setNegativeButton(R.string.keep_editing, null)
            .show()
    }

    private fun load() {
        val hub = Prefs(this).hub()
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val heating = intent.getBooleanExtra(EXTRA_HEATING, true)
        busy = true
        render()
        io.execute {
            val result = try {
                Result.success(
                    hub.fetchSchedules().firstOrNull { it.id == id && it.heating == heating }
                        ?: throw IOException(getString(R.string.schedule_missing))
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                result.fold(
                    onSuccess = { loaded ->
                        schedule = loaded
                        days.clear()
                        loaded.days.forEach { (name, slots) -> days[name] = slots.toMutableList() }
                        render()
                    },
                    onFailure = { render(getString(R.string.error, it.message ?: it.javaClass.simpleName)) },
                )
            }
        }
    }

    private fun save() {
        val current = schedule ?: return
        if (busy) return
        val updated = current.copy(days = days.mapValues { it.value.sortedBy { slot -> slot.minutes } })
        val hub = Prefs(this).hub()
        busy = true
        render()
        io.execute {
            val error = try {
                hub.saveSchedule(updated)
                null
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = false
                if (error == null) {
                    schedule = updated
                    dirty = false
                    render()
                    Toast.makeText(this, R.string.schedule_saved, Toast.LENGTH_SHORT).show()
                } else {
                    render(getString(R.string.error, error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    private fun render(message: String? = null) {
        for (i in 0 until dayBar.childCount) {
            val tab = dayBar.getChildAt(i) as TextView
            val selected = WiserHub.DAYS[i] == day
            tab.setBackgroundResource(if (selected) R.drawable.card_bg else 0)
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }

        slotList.removeAllViews()
        val current = schedule
        val slots = days.getOrPut(day) { mutableListOf() }
        slots.sortBy { it.minutes }

        if (current != null) {
            for (slot in slots) {
                val row = layoutInflater.inflate(R.layout.row_slot, slotList, false)
                row.findViewById<Button>(R.id.slot_time).apply {
                    text = formatTime(slot.minutes)
                    setOnClickListener { pickTime(slot.minutes) { replace(slot, slot.copy(minutes = it)) } }
                }
                row.findViewById<Button>(R.id.slot_value).apply {
                    if (current.heating) {
                        text = WiserHub.formatSetPoint(slot.value / 10.0)
                        setOnClickListener { pickTemp(slot) }
                    } else {
                        setText(if (slot.value != 0) R.string.on else R.string.off)
                        setOnClickListener { replace(slot, slot.copy(value = if (slot.value != 0) 0 else 1)) }
                    }
                }
                row.findViewById<Button>(R.id.slot_delete).setOnClickListener {
                    slots.remove(slot)
                    dirty = true
                    render()
                }
                slotList.addView(row)
            }
        }

        addButton.isEnabled = current != null && !busy && slots.size < MAX_SLOTS_PER_DAY
        copyButton.isEnabled = current != null && !busy
        status.text = message ?: when {
            busy -> getString(R.string.loading)
            current == null -> ""
            dirty -> getString(R.string.unsaved_changes)
            slots.isEmpty() -> getString(R.string.schedule_empty)
            current.heating -> getString(R.string.schedule_hint_heating)
            else -> getString(R.string.schedule_hint_onoff)
        }
    }

    /** Swaps [old] for [new]; a slot already at the new time is overwritten. */
    private fun replace(old: Slot?, new: Slot) {
        val slots = days.getOrPut(day) { mutableListOf() }
        slots.remove(old)
        slots.removeAll { it.minutes == new.minutes }
        slots += new
        dirty = true
        render()
    }

    private fun addSlot() {
        val current = schedule ?: return
        val slots = days[day].orEmpty()
        val suggested = slots.maxOfOrNull { it.minutes }?.let { minOf(it + 60, 23 * 60) } ?: (6 * 60)
        pickTime(suggested) { minutes ->
            val value = if (current.heating) slots.lastOrNull()?.value?.takeIf { it > 0 } ?: 200 else 1
            replace(null, Slot(minutes, value))
        }
    }

    private fun pickTime(minutes: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            minutes / 60,
            minutes % 60,
            DateFormat.is24HourFormat(this),
        ).show()
    }

    private fun pickTemp(slot: Slot) {
        val temps = listOf(WiserHub.SCHEDULE_OFF) + (50..300 step 5)
        val picker = NumberPicker(this).apply {
            minValue = 0
            maxValue = temps.size - 1
            displayedValues = temps.map { WiserHub.formatSetPoint(it / 10.0) }.toTypedArray()
            value = temps.indexOfFirst { it >= slot.value }.coerceAtLeast(0)
            wrapSelectorWheel = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.target_temperature)
            .setView(picker)
            .setPositiveButton(android.R.string.ok) { _, _ -> replace(slot, slot.copy(value = temps[picker.value])) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyDay() {
        val others = WiserHub.DAYS.filter { it != day }
        val checked = BooleanArray(others.size)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.copy_day_title, day))
            .setMultiChoiceItems(others.toTypedArray(), checked) { _, index, isChecked -> checked[index] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val targets = others.filterIndexed { index, _ -> checked[index] }
                if (targets.isNotEmpty()) {
                    targets.forEach { days[it] = days[day].orEmpty().toMutableList() }
                    dirty = true
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatTime(minutes: Int): String {
        val time = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
        }
        return DateFormat.getTimeFormat(this).format(time.time)
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_HEATING = "heating"
        const val EXTRA_TITLE = "title"

        /** The Wiser app allows up to eight changes a day; stay within that. */
        private const val MAX_SLOTS_PER_DAY = 8
    }
}
