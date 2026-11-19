package dev.wiserlocal.app.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wiserlocal.app.Slot
import dev.wiserlocal.app.WiserHub

/** The official app allows up to eight changes a day; stay within that. */
private const val MAX_SLOTS_PER_DAY = 8

/**
 * Edits one schedule a day at a time. Changes stay on the phone until Save,
 * which sends all seven days to the hub in one request.
 */
@Composable
fun ScheduleEditorScreen(vm: HubViewModel, snackbar: SnackbarHostState, screen: Screen.ScheduleEditor) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    val context = LocalContext.current
    val schedule = vm.schedules.firstOrNull { it.id == screen.id && it.heating == screen.heating }

    /** Working copy of the days; filled once from the hub's copy. */
    val days = remember { mutableStateMapOf<String, List<Slot>>() }
    var loaded by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var day by remember { mutableStateOf(today()) }
    var copying by remember { mutableStateOf(false) }

    LaunchedEffect(schedule) {
        if (loaded || schedule == null) return@LaunchedEffect
        schedule.days.forEach { (name, slots) -> days[name] = slots.sortedBy { it.minutes } }
        loaded = true
    }
    LaunchedEffect(Unit) { if (schedule == null) vm.refresh() }

    fun leave() {
        if (!dirty) nav.pop()
        else dialogs.confirm("Discard your changes to this schedule?", "Discard") { nav.pop() }
    }
    BackHandler { leave() }

    val slots = days[day].orEmpty()
    fun setSlots(newSlots: List<Slot>) {
        days[day] = newSlots.sortedBy { it.minutes }
        dirty = true
    }

    /** Swaps [old] for [new]; a change already at the new time is overwritten. */
    fun replace(old: Slot?, new: Slot) =
        setSlots(slots.filter { it != old && it.minutes != new.minutes } + new)

    fun pickTime(minutes: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            context,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            minutes / 60,
            minutes % 60,
            DateFormat.is24HourFormat(context),
        ).show()
    }

    fun addSlot() {
        val suggested = slots.maxOfOrNull { it.minutes }?.let { minOf(it + 60, 23 * 60) } ?: (6 * 60)
        pickTime(suggested) { minutes ->
            val value = if (screen.heating) slots.lastOrNull()?.value?.takeIf { it > 0 } ?: 200 else 1
            replace(null, Slot(minutes, value))
        }
    }

    fun save() {
        val current = schedule ?: return
        val updated = current.copy(days = WiserHub.DAYS.associateWith { days[it].orEmpty().sortedBy { s -> s.minutes } })
        vm.run("Schedule saved", onSuccess = { dirty = false }) { it.saveSchedule(updated) }
    }

    DetailScaffold(
        title = screen.title,
        snackbar = snackbar,
        onBack = ::leave,
        loading = vm.loading,
        actions = { TextButton(onClick = ::save, enabled = loaded && dirty && !vm.loading) { Text("Save") } },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = ::addSlot,
                    enabled = loaded && slots.size < MAX_SLOTS_PER_DAY,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add change")
                }
                OutlinedButton(onClick = { copying = true }, enabled = loaded, modifier = Modifier.weight(1f)) {
                    Text("Copy day to…")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = WiserHub.DAYS.indexOf(day)) {
                WiserHub.DAYS.forEach { name ->
                    // Custom content: the default tab padding is too wide for seven tabs on a phone.
                    Tab(selected = name == day, onClick = { day = name }) {
                        Text(
                            name.take(3),
                            maxLines = 1,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(vertical = 14.dp),
                        )
                    }
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val hint = when {
                    !loaded && schedule == null && !vm.loading -> "This schedule could not be found on the hub."
                    !loaded -> "Loading…"
                    dirty -> "Unsaved changes. Tap Save to send them to the hub."
                    slots.isEmpty() -> "No changes on $day. Tap Add change."
                    screen.heating -> "Each change sets the target temperature from that time until the next change."
                    else -> "Each change turns hot water on or off from that time until the next change."
                }
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                slots.forEach { slot ->
                    SlotCard(
                        time = context.clockText(slot.minutes),
                        heating = screen.heating,
                        value = slot.value,
                        onTime = { pickTime(slot.minutes) { replace(slot, slot.copy(minutes = it)) } },
                        onTemp = {
                            dialogs.temperature("Target temperature", slot.value, allowOff = true) {
                                replace(slot, slot.copy(value = it))
                            }
                        },
                        onToggle = { on -> replace(slot, slot.copy(value = if (on) 1 else 0)) },
                        onDelete = { setSlots(slots - slot) },
                    )
                }
            }
        }
    }

    if (copying) {
        val others = WiserHub.DAYS.filter { it != day }
        val checked = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { copying = false },
            title = { Text("Copy $day to") },
            text = {
                Column {
                    others.forEach { name ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (name in checked) checked.remove(name) else checked.add(name) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = name in checked, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text(name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copying = false
                    if (checked.isNotEmpty()) {
                        checked.forEach { days[it] = slots }
                        dirty = true
                    }
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { copying = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SlotCard(
    time: String,
    heating: Boolean,
    value: Int,
    onTime: () -> Unit,
    onTemp: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val accents = LocalAccents.current
    val active = if (heating) value != WiserHub.SCHEDULE_OFF else value != 0
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = when {
                !active -> MaterialTheme.colorScheme.surfaceContainerHigh
                heating -> accents.heatContainer
                else -> accents.waterContainer
            },
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onTime) {
                Text(time, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            if (heating) {
                TextButton(onClick = onTemp) {
                    Icon(flameIcon(), contentDescription = null, tint = if (active) accents.heat else MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(WiserHub.formatSetPoint(value / 10.0), style = MaterialTheme.typography.titleLarge)
                }
            } else {
                Text(if (active) "On" else "Off", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Switch(checked = active, onCheckedChange = onToggle)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete change") }
        }
    }
}
