package dev.wiserlocal.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wiserlocal.app.HubState
import dev.wiserlocal.app.MomentAction
import dev.wiserlocal.app.R
import dev.wiserlocal.app.Schedule
import dev.wiserlocal.app.WiserHub

private val AUTOMATION_TABS = listOf("Moments", "Automations", "Schedules")

@Composable
fun AutomationsTab(vm: HubViewModel, modifier: Modifier) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    val selected = nav.automationsTab

    DisposableEffect(Unit) {
        vm.watchMoments(true)
        onDispose { vm.watchMoments(false) }
    }

    TabScaffold(
        "Automations",
        vm,
        modifier,
        actions = {
            val features = vm.state?.features
            when (selected) {
                0 -> if (features?.moments != false) IconButton(onClick = { nav.push(Screen.MomentEditor(null)) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add a Moment")
                }
                1 -> if (features?.automations != false) IconButton(onClick = { nav.push(Screen.AutomationEditor(null)) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add an automation")
                }
                2 -> IconButton(onClick = {
                    dialogs.prompt("New heating schedule", "") { name ->
                        vm.run("Schedule “$name” added") { it.createHeatingSchedule(name) }
                    }
                }) { Icon(Icons.Default.Add, contentDescription = "Add a schedule") }
            }
        },
        belowBar = {
            TabRow(selectedTabIndex = selected) {
                AUTOMATION_TABS.forEachIndexed { index, label ->
                    Tab(selected = selected == index, onClick = { nav.automationsTab = index }, text = { Text(label) })
                }
            }
        },
    ) {
        val state = vm.state ?: return@TabScaffold
        when (selected) {
            0 -> if (!state.features.moments) {
                item { EmptyNote(needsFirmware("Moments", "3.7.14", state)) }
            } else {
                if (state.moments.isEmpty()) item { EmptyNote("No Moments yet. Tap + to add one.") }
                state.moments.forEach { moment ->
                    item(key = "m${moment.id}") {
                        val described = vm.momentActions[moment.id]?.joinToString(" · ") { action ->
                            describeAction(action, state).let { (target, value) -> "$target: $value" }
                        }
                        ListCard(
                            icon = painterResource(momentIcon(moment.iconId, R.drawable.ic_moment)),
                            title = moment.name,
                            detail = when {
                                described != null -> described
                                moment.id in vm.momentActions -> "Set up in the official app"
                                else -> null
                            },
                            onClick = { nav.push(Screen.MomentEditor(moment.id)) },
                        ) {
                            FilledTonalIconButton(onClick = {
                                vm.run("Running “${moment.name}”") { it.triggerMoment(moment.id) }
                            }) { Icon(Icons.Default.PlayArrow, contentDescription = "Run ${moment.name}") }
                        }
                    }
                }
            }
            1 -> if (!state.features.automations) {
                item { EmptyNote(needsFirmware("Automations", "4.6.0", state)) }
            } else {
                if (state.automations.isEmpty()) {
                    item { EmptyNote("No automations yet. Tap + to add one.") }
                }
                state.automations.forEach { automation ->
                    item(key = "a${automation.id}") {
                        val detail = vm.automationDetails[automation.id]
                        ListCard(
                            icon = painterResource(momentIcon(automation.iconId, R.drawable.ic_automation)),
                            title = automation.name,
                            detail = when {
                                detail != null -> automationSummary(detail, state)
                                automation.id in vm.automationDetails -> "Set up in the official app"
                                else -> null
                            },
                            onClick = { nav.push(Screen.AutomationEditor(automation.id)) },
                        ) {
                            Switch(
                                checked = automation.enabled,
                                onCheckedChange = { on ->
                                    vm.run(if (on) "Automation on" else "Automation off") { it.setAutomationEnabled(automation.id, on) }
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
            else -> {
                val heating = vm.schedules.filter { it.heating }
                heating.forEach { schedule ->
                    item(key = "s${schedule.id}") { HeatingScheduleCard(schedule, heating, state, vm) }
                }
                vm.schedules.filter { !it.heating }.forEach { schedule ->
                    item(key = "w${schedule.id}") {
                        ListCard(
                            icon = waterIcon(),
                            title = "Hot water",
                            detail = schedule.name.takeIf { it.isNotEmpty() && it != "Hot Water" },
                            onClick = {
                                nav.push(Screen.ScheduleEditor(schedule.id, heating = false, title = "Hot water schedule"))
                            },
                        )
                    }
                }
                if (vm.schedules.isEmpty()) item { EmptyNote("No schedules found on the hub.") }
            }
        }
    }
}

@Composable
private fun HeatingScheduleCard(schedule: Schedule, all: List<Schedule>, state: HubState, vm: HubViewModel) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    val name = schedule.name.ifEmpty { "Schedule" }
    val users = state.rooms.filter { it.scheduleId == schedule.id }.map { it.name }
    var menu by remember { mutableStateOf(false) }
    ListCard(
        icon = flameIcon(),
        title = name,
        detail = if (users.isEmpty()) "Not used by any room" else "Used by ${users.joinToString(", ")}",
        onClick = { nav.push(Screen.ScheduleEditor(schedule.id, heating = true, title = name)) },
    ) {
        Column {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = {
                    menu = false
                    dialogs.prompt("Rename $name", schedule.name) { newName -> vm.run { it.renameSchedule(schedule, newName) } }
                })
                val others = all.filter { it.id != schedule.id }
                if (others.isNotEmpty()) {
                    DropdownMenuItem(text = { Text("Copy over another schedule…") }, onClick = {
                        menu = false
                        val labels = others.map { it.name.ifEmpty { "Schedule" } }
                        dialogs.choice("Copy $name over", labels) { index ->
                            dialogs.confirm("Replace “${labels[index]}” with a copy of “$name”?", "Replace") {
                                vm.run("Schedule copied") { it.copySchedule(schedule, others[index].id) }
                            }
                        }
                    })
                }
                DropdownMenuItem(text = { Text("Delete") }, onClick = {
                    menu = false
                    val message = if (users.isEmpty()) "Delete the schedule “$name”? This cannot be undone."
                    else "“$name” is used by ${users.joinToString(", ")}. Delete it anyway? This cannot be undone."
                    dialogs.confirm(message, "Delete") { vm.run { it.deleteSchedule(schedule) } }
                })
            }
        }
    }
}

/** A tappable card with an icon, a title and a detail line. */
@Composable
fun ListCard(
    icon: Painter,
    title: String,
    detail: String?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!detail.isNullOrEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing()
        }
    }
}

/** ("Living Room", "17.0°"), ("Hot water", "On for 1 hour"), and so on. */
fun describeAction(action: MomentAction, state: HubState): Pair<String, String> {
    when (action.type) {
        MomentAction.TYPE_LIGHT -> return (state.lights.firstOrNull { it.id == action.id }?.name ?: "Light ${action.id}") to when {
            action.setPoint <= 0 -> "Off"
            action.setPoint >= 100 -> "On"
            else -> "${action.setPoint}%"
        }
        MomentAction.TYPE_PLUG -> return (state.plugs.firstOrNull { it.id == action.id }?.name ?: "Plug ${action.id}") to
            (if (action.setPoint != 0) "On" else "Off")
        MomentAction.TYPE_SHUTTER -> return (state.shutters.firstOrNull { it.id == action.id }?.name ?: "Shutter ${action.id}") to
            "${if (action.text == "TiltTo") "Tilt" else "Lift"} to ${action.setPoint}%"
    }
    val roomName = state.rooms.firstOrNull { it.id == action.id }?.name ?: "Room ${action.id}"
    when (action.type) {
        MomentAction.TYPE_PERCENT -> return roomName to "Heating ${action.setPoint}%"
        MomentAction.TYPE_HVAC -> return roomName to "Mode: ${action.text ?: "Auto"}"
        MomentAction.TYPE_CLIMATE -> return roomName to (if (action.text.equals("On", true)) "Climate on" else "Climate off")
    }
    if (action.type == MomentAction.TYPE_WAIT) return "Wait" to durationText(action.durationMinutes ?: 0)
    if (action.type == MomentAction.TYPE_NOTIFY) return "Notification" to "Send"
    val value = when {
        action.type == MomentAction.TYPE_BOOST -> "Boost for ${durationText(action.durationMinutes ?: 60)}"
        action.type == MomentAction.TYPE_NONE -> "Back to schedule"
        action.setPoint == WiserHub.SCHEDULE_OFF -> "Off"
        action.domain == "HotWater" && action.durationMinutes != null -> "On for ${durationText(action.durationMinutes)}"
        action.domain == "HotWater" -> "On"
        else -> WiserHub.formatSetPoint(action.setPoint / 10.0)
    }
    val target = if (action.domain == "HotWater") "Hot water"
    else state.rooms.firstOrNull { it.id == action.id }?.name ?: "Room ${action.id}"
    return target to value
}

/** Says a feature needs newer hub firmware, naming the firmware the hub has. */
fun needsFirmware(feature: String, minimum: String, state: dev.wiserlocal.app.HubState): String {
    val have = state.features.firmwareText?.let { " This hub has $it." } ?: ""
    return "$feature need hub firmware $minimum or newer.$have"
}
