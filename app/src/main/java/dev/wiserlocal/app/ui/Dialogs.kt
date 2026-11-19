package dev.wiserlocal.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wiserlocal.app.WiserHub

/** Shows one dialog at a time on top of the app. */
class DialogHost {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    fun show(dialog: @Composable () -> Unit) {
        content = dialog
    }

    fun close() {
        content = null
    }
}

val LocalDialogs = staticCompositionLocalOf<DialogHost> { error("No DialogHost") }

/** A list to pick from; with [selected], a single-choice list with radio buttons. */
fun DialogHost.choice(title: String, options: List<String>, selected: Int? = null, onPick: (Int) -> Unit) = show {
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { index, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { close(); onPick(index) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selected != null) {
                            RadioButton(selected = index == selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } },
    )
}

fun DialogHost.confirm(message: String, action: String, onOk: () -> Unit) = show {
    AlertDialog(
        onDismissRequest = ::close,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { close(); onOk() }) { Text(action) } },
        dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } },
    )
}

fun DialogHost.message(title: String, message: String, button: String = "OK", onClose: () -> Unit = {}) = show {
    AlertDialog(
        onDismissRequest = { close(); onClose() },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { close(); onClose() }) { Text(button) } },
    )
}

fun DialogHost.prompt(title: String, initial: String, numeric: Boolean = false, onOk: (String) -> Unit) = show {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { close(); onOk(text.trim()) }, enabled = text.isNotBlank()) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } },
    )
}

/**
 * Picks a temperature in tenths of a degree, in 0.5° steps. With [allowOff],
 * stepping below the minimum gives [WiserHub.SCHEDULE_OFF].
 */
fun DialogHost.temperature(title: String, initial: Int, allowOff: Boolean = false, onOk: (Int) -> Unit) = show {
    var value by remember { mutableIntStateOf(initial) }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(title) },
        text = { TempStepper(value, allowOff) { value = it } },
        confirmButton = { TextButton(onClick = { close(); onOk(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } },
    )
}

/** Asks for a temperature and how long to hold it. */
fun DialogHost.hold(title: String, initialC: Double, onOk: (Double, Int) -> Unit) = show {
    var value by remember { mutableIntStateOf((initialC * 10).toInt().coerceIn(MIN_TENTHS, MAX_TENTHS)) }
    var minutes by remember { mutableIntStateOf(60) }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TempStepper(value, allowOff = false) { value = it }
                Spacer(Modifier.height(16.dp))
                Text("For", style = MaterialTheme.typography.labelLarge)
                DurationChips(minutes) { minutes = it }
            }
        },
        confirmButton = { TextButton(onClick = { close(); onOk(value / 10.0, minutes) }) { Text("Hold") } },
        dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } },
    )
}

/** Picks one of [DURATIONS]. */
fun DialogHost.duration(title: String, label: (Int) -> String = ::durationText, onOk: (Int) -> Unit) =
    choice(title, DURATIONS.map(label)) { onOk(DURATIONS[it]) }

@Composable
fun DurationChips(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DURATIONS.forEach { minutes ->
            FilterChip(
                selected = minutes == selected,
                onClick = { onSelect(minutes) },
                label = { Text(durationText(minutes)) },
            )
        }
    }
}

private const val MIN_TENTHS = 50
private const val MAX_TENTHS = 300

@Composable
fun TempStepper(value: Int, allowOff: Boolean, onChange: (Int) -> Unit) {
    val off = value == WiserHub.SCHEDULE_OFF
    fun step(delta: Int) {
        val next = when {
            off && delta > 0 -> MIN_TENTHS
            off -> WiserHub.SCHEDULE_OFF
            allowOff && value + delta < MIN_TENTHS -> WiserHub.SCHEDULE_OFF
            else -> (value + delta).coerceIn(MIN_TENTHS, MAX_TENTHS)
        }
        onChange(next)
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = { step(-5) }) { Text("−", fontSize = 22.sp) }
        Text(
            if (off) "Off" else WiserHub.formatSetPoint(value / 10.0),
            modifier = Modifier.widthIn(min = 120.dp),
            textAlign = TextAlign.Center,
            fontSize = 36.sp,
            fontWeight = FontWeight.Medium,
        )
        FilledTonalIconButton(onClick = { step(5) }) { Text("+", fontSize = 22.sp) }
    }
}
