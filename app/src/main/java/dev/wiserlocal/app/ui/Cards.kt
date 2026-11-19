package dev.wiserlocal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wiserlocal.app.HotWater
import dev.wiserlocal.app.HotWaterMode
import dev.wiserlocal.app.Room
import dev.wiserlocal.app.RoomMode
import dev.wiserlocal.app.WiserHub

private class MenuItem(val label: String, val onClick: () -> Unit)

@Composable
private fun Overflow(items: List<MenuItem>, tint: Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = tint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(item.label) }, onClick = { open = false; item.onClick() })
            }
        }
    }
}

/**
 * A room with its temperature and controls. [full] adds the next scheduled
 * change, humidity, window state and the mode buttons.
 */
@Composable
fun RoomCard(room: Room, vm: HubViewModel, full: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dialogs = LocalDialogs.current
    val nav = LocalNav.current
    val accents = LocalAccents.current
    val heating = room.heating
    val container = if (heating) accents.heatContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (heating) accents.onHeatContainer else MaterialTheme.colorScheme.onSurface
    val muted = content.copy(alpha = 0.75f)

    fun pickBoost() = dialogs.duration(
        "Boost ${room.name}",
        label = { "+${BOOST_DELTA_C.toInt()}° for ${durationText(it)}" },
    ) { minutes -> vm.run { it.boostRoom(room.id, BOOST_DELTA_C, minutes) } }

    fun pickMode() {
        val modes = RoomMode.entries
        dialogs.choice(
            "Mode for ${room.name}",
            listOf("Follow schedule", "Manual (ignore schedule)", "Off"),
            modes.indexOf(roomMode(room)),
        ) { index -> vm.run { it.setRoomMode(room.id, modes[index]) } }
    }

    val menu = buildList {
        add(MenuItem("Hold temperature…") {
            dialogs.hold("Hold ${room.name}", room.setPointC ?: 20.0) { temp, minutes ->
                vm.run { it.holdRoomTemp(room.id, temp, minutes) }
            }
        })
        add(MenuItem("Mode…") { pickMode() })
        if (room.scheduleId > 0) {
            if (room.mode == "Auto") add(MenuItem("Advance schedule") { vm.run { it.advanceRoom(room) } })
            add(MenuItem("Edit schedule") {
                nav.push(Screen.ScheduleEditor(room.scheduleId, heating = true, title = "${room.name} schedule"))
            })
        }
        if (room.overridden && !room.boosted) add(MenuItem("Resume schedule") { vm.run { it.resumeRoomSchedule(room.id) } })
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    flameIcon(),
                    contentDescription = null,
                    tint = if (heating) accents.heat else muted,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(room.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(context.roomStatus(room), style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                Overflow(menu, content)
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(WiserHub.formatTemp(room.tempC), fontSize = 40.sp, fontWeight = FontWeight.Light)
                    Text("Current", style = MaterialTheme.typography.labelMedium, color = muted)
                }
                TargetStepper(
                    label = WiserHub.formatSetPoint(vm.pendingSetPoints[room.id] ?: room.setPointC),
                    onDown = { vm.nudge(room, -0.5) },
                    onUp = { vm.nudge(room, 0.5) },
                    muted = muted,
                )
            }

            if (full) {
                val extras = listOfNotNull(
                    if (room.mode == "Auto") context.nextChange(vm.schedules, room.scheduleId, heating = true) else null,
                    room.humidity?.let { "Humidity $it%" },
                    if (room.windowOpen) "Window open" else null,
                )
                if (extras.isNotEmpty()) {
                    Text(
                        extras.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                        modifier = Modifier.padding(top = 8.dp, end = 12.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                ModeButtons(
                    labels = listOf("Schedule", "Manual", "Off"),
                    selected = RoomMode.entries.indexOf(roomMode(room)),
                    modifier = Modifier.padding(end = 12.dp),
                ) { index -> vm.run { it.setRoomMode(room.id, RoomMode.entries[index]) } }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (room.boosted) {
                    FilledTonalButton(onClick = { vm.run { it.resumeRoomSchedule(room.id) } }) { Text("Cancel boost") }
                } else {
                    FilledTonalButton(onClick = { pickBoost() }) { Text("Boost") }
                }
                if (room.overridden && !room.boosted) {
                    OutlinedButton(onClick = { vm.run { it.resumeRoomSchedule(room.id) } }) { Text("Resume schedule") }
                }
            }
        }
    }
}

fun roomMode(room: Room): RoomMode = when {
    room.mode == "Manual" && room.isOff() -> RoomMode.OFF
    room.mode == "Manual" -> RoomMode.MANUAL
    else -> RoomMode.AUTO
}

@Composable
private fun TargetStepper(label: String, onDown: () -> Unit, onUp: () -> Unit, muted: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = onDown,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                ),
            ) { Text("−", fontSize = 22.sp) }
            Text(
                label,
                modifier = Modifier.widthIn(min = 72.dp),
                textAlign = TextAlign.Center,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
            FilledTonalIconButton(
                onClick = onUp,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                ),
            ) { Text("+", fontSize = 22.sp) }
        }
        Text("Target", style = MaterialTheme.typography.labelMedium, color = muted)
    }
}

@Composable
fun ModeButtons(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selected,
                onClick = { if (index != selected) onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** Hot water with its state, mode and boost, always fully shown. */
@Composable
fun HotWaterCard(hw: HotWater, vm: HubViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dialogs = LocalDialogs.current
    val nav = LocalNav.current
    val accents = LocalAccents.current
    val container = if (hw.on) accents.waterContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (hw.on) accents.onWaterContainer else MaterialTheme.colorScheme.onSurface
    val muted = content.copy(alpha = 0.75f)
    val boostMinutes = vm.prefs.boostMinutes

    val menu = buildList {
        add(MenuItem("Boost for…") {
            dialogs.duration("Boost hot water") { minutes -> vm.run { it.boostHotWater(hw.id, minutes) } }
        })
        if (hw.scheduleId > 0) {
            if (hw.mode == "Auto") add(MenuItem("Advance schedule") { vm.run { it.advanceHotWater(hw) } })
            add(MenuItem("Edit schedule") {
                nav.push(Screen.ScheduleEditor(hw.scheduleId, heating = false, title = "Hot water schedule"))
            })
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    waterIcon(),
                    contentDescription = null,
                    tint = if (hw.on) accents.water else muted,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hot water", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(context.hotWaterStatus(hw), style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                Overflow(menu, content)
            }
            Row(Modifier.padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.Bottom) {
                Text(if (hw.on) "On" else "Off", fontSize = 40.sp, fontWeight = FontWeight.Light)
                val next = if (hw.mode == "Auto") context.nextChange(vm.schedules, hw.scheduleId, heating = false) else null
                if (next != null) {
                    Spacer(Modifier.width(16.dp))
                    Text(next, style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            val modes = HotWaterMode.entries
            val current = when {
                hw.mode != "Manual" -> HotWaterMode.AUTO
                hw.on -> HotWaterMode.ON
                else -> HotWaterMode.OFF
            }
            ModeButtons(
                labels = listOf("Schedule", "Always on", "Always off"),
                selected = modes.indexOf(current),
                modifier = Modifier.padding(end = 12.dp),
            ) { index -> vm.run { it.setHotWaterMode(hw.id, modes[index]) } }
            Row(Modifier.padding(top = 10.dp)) {
                if (hw.boosted) {
                    FilledTonalButton(onClick = { vm.run { it.cancelHotWaterBoost(hw.id) } }) { Text("Cancel boost") }
                } else {
                    FilledTonalButton(onClick = { vm.run { it.boostHotWater(hw.id, boostMinutes) } }) {
                        Text("Boost ${durationText(boostMinutes)}")
                    }
                }
            }
        }
    }
}
