package dev.wiserlocal.app

import android.app.Activity
import android.app.AlertDialog
import android.text.format.DateFormat
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Switch
import android.widget.TextView
import java.util.Calendar

/** Builds the simple card rows used by the System and Rooms & schedules screens. */
internal class Rows(private val activity: Activity, private val list: LinearLayout) {

    fun clear() = list.removeAllViews()

    fun header(textRes: Int) {
        val view = activity.layoutInflater.inflate(R.layout.row_header, list, false) as TextView
        view.setText(textRes)
        list.addView(view)
    }

    /** A label with a value under it. Skipped when [value] is null; tappable when [onClick] is set. */
    fun info(label: String, value: String?, onClick: (() -> Unit)? = null) {
        if (value == null) return
        val row = activity.layoutInflater.inflate(R.layout.row_info, list, false)
        row.findViewById<TextView>(R.id.info_label).text = label
        row.findViewById<TextView>(R.id.info_value).text = value
        if (onClick != null) row.setOnClickListener { onClick() }
        list.addView(row)
    }

    fun toggle(label: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = activity.layoutInflater.inflate(R.layout.row_switch, list, false)
        row.findViewById<TextView>(R.id.switch_help).text = help
        row.findViewById<Switch>(R.id.switch_toggle).apply {
            text = label
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        list.addView(row)
    }
}

/** Target temperatures offered in pickers, in tenths of a degree (5.0° to 30.0°). */
internal val PICKER_TEMPS: List<Int> = (50..300 step 5).toList()

internal fun Activity.promptText(title: String, initial: String, numeric: Boolean = false, onOk: (String) -> Unit) {
    val input = EditText(this).apply {
        setText(initial)
        setSingleLine()
        setSelection(text.length)
        if (numeric) {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
    }
    val pad = (20 * resources.displayMetrics.density).toInt()
    val frame = FrameLayout(this).apply {
        setPadding(pad, pad / 2, pad, 0)
        addView(input)
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(frame)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) onOk(text)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

internal fun Activity.confirm(message: String, actionRes: Int, onOk: () -> Unit) {
    AlertDialog.Builder(this)
        .setMessage(message)
        .setPositiveButton(actionRes) { _, _ -> onOk() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** A scroll wheel of [labels]; calls [onOk] with the chosen index. */
internal fun Activity.pickFromWheel(title: String, labels: List<String>, selected: Int, onOk: (Int) -> Unit) {
    val picker = wheel(labels, selected)
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(picker)
        .setPositiveButton(android.R.string.ok) { _, _ -> onOk(picker.value) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

internal fun Activity.wheel(labels: List<String>, selected: Int): NumberPicker = NumberPicker(this).apply {
    minValue = 0
    maxValue = labels.size - 1
    displayedValues = labels.toTypedArray()
    value = selected.coerceIn(0, labels.size - 1)
    wrapSelectorWheel = false
    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
}

internal fun Activity.durationLabel(minutes: Int): String =
    if (minutes < 60) getString(R.string.minutes, minutes)
    else resources.getQuantityString(R.plurals.hours, minutes / 60, minutes / 60)

/** Formats minutes past midnight in the phone's 12 or 24 hour style. */
internal fun Activity.clock(minutes: Int): String {
    val time = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minutes / 60)
        set(Calendar.MINUTE, minutes % 60)
    }
    return DateFormat.getTimeFormat(this).format(time.time)
}
