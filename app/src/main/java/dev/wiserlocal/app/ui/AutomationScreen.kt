package dev.wiserlocal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wiserlocal.app.AutomationAction
import dev.wiserlocal.app.AutomationDetail
import dev.wiserlocal.app.AutomationTime
import dev.wiserlocal.app.AutomationTrigger
import dev.wiserlocal.app.HubState
import dev.wiserlocal.app.R
import dev.wiserlocal.app.WiserHub
import org.json.JSONObject

/** The official app allows ten of each: conditions, times and actions. */
const val MAX_AUTOMATION_PARTS = 10

private val WAITS = listOf(1, 5, 10, 15, 30, 45, 60, 90, 120, 180, 240, 360)

/**
 * Create ([automationId] null) or edit an automation: name, on/off, then If / When /
 * Then. Parts this app can't describe are kept as they are and can only be removed.
 */
@Composable
fun AutomationEditorScreen(vm: HubViewModel, snackbar: SnackbarHostState, automationId: Int?) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    val state = vm.state
    val automation = automationId?.let { id -> state?.automations?.firstOrNull { it.id == id } }

    DisposableEffect(Unit) {
        vm.watchMoments(true)
        onDispose { vm.watchMoments(false) }
    }

    var name by remember { mutableStateOf(automation?.name ?: "") }
    var iconId by remember { mutableIntStateOf(automation?.iconId ?: 0) }
    var triggerMode by remember { mutableStateOf("All") }
    val triggers = remember { mutableStateListOf<AutomationTrigger>() }
    val times = remember { mutableStateListOf<AutomationTime>() }
    val actions = remember { mutableStateListOf<AutomationAction>() }
    var original by remember { mutableStateOf<AutomationDetail?>(null) }
    var loaded by remember { mutableStateOf(automationId == null) }
    var unreadable by remember { mutableStateOf(false) }

    // Fill in the automation once the hub has said what it does.
    LaunchedEffect(vm.automationDetails, automation) {
        if (loaded || automation == null || automation.id !in vm.automationDetails) return@LaunchedEffect
        if (name.isEmpty()) {
            name = automation.name
            iconId = automation.iconId
        }
        val detail = vm.automationDetails[automation.id]
        if (detail == null) unreadable = true else {
            original = detail
            triggerMode = if (detail.triggerMode.equals("Any", ignoreCase = true)) "Any" else "All"
            triggers.addAll(detail.triggers)
            times.addAll(detail.times)
            actions.addAll(detail.actions)
        }
        loaded = true
    }

    val current = AutomationDetail(triggerMode, triggers.toList(), times.toList(), actions.toList())
    val nameChanged = automation != null && (name.trim() != automation.name || iconId != automation.iconId)
    val partsChanged = automation == null && (triggers.isNotEmpty() || times.isNotEmpty() || actions.isNotEmpty()) ||
        original != null && current != original
    val dirty = if (automation == null) name.isNotBlank() || partsChanged else nameChanged || partsChanged
    // The official app treats an automation with no condition as broken, even with a time set.
    val complete = actions.isNotEmpty() && triggers.isNotEmpty()
    val canSave = loaded && !unreadable && name.isNotBlank() && complete && (automation == null || dirty)

    fun leave() {
        if (!dirty) nav.pop()
        else dialogs.confirm("Discard your changes to this automation?", "Discard") { nav.pop() }
    }
    BackHandler { leave() }

    fun save() {
        val newName = name.trim()
        vm.run(if (automation == null) "Automation “$newName” added" else "Automation saved", onSuccess = { nav.pop() }) {
            it.saveAutomation(automation?.id, newName, automation?.enabled ?: true, iconId, current)
        }
    }

    DetailScaffold(
        title = if (automationId == null) "New automation" else "Edit automation",
        snackbar = snackbar,
        onBack = ::leave,
        loading = vm.loading,
        actions = { TextButton(onClick = ::save, enabled = canSave && !vm.loading) { Text("Save") } },
        bottomBar = {
            if (automation != null) {
                OutlinedButton(
                    onClick = {
                        dialogs.confirm("Delete the automation “${automation.name}” from the hub? This cannot be undone.", "Delete") {
                            vm.run("Automation deleted", onSuccess = { nav.pop() }) { it.deleteAutomation(automation.id) }
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalAccents.current.danger),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp)
                        .height(52.dp),
                ) { Text("Delete") }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state == null || automationId != null && automation == null) {
                Text("This automation is no longer on the hub.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChooser(iconId, R.drawable.ic_automation, enabled = loaded && !unreadable) { iconId = it }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            if (automation != null) {
                Section {
                    SwitchRow("Enabled", if (automation.enabled) "Runs when its conditions are met" else "Paused", automation.enabled) { on ->
                        vm.run(if (on) "Automation on" else "Automation off") { it.setAutomationEnabled(automation.id, on) }
                    }
                }
            }
            when {
                !loaded -> {
                    Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@Column
                }
                unreadable -> {
                    NoteCard("The hub's description of this automation couldn't be read, so it can only be renamed in the official app, turned on or off, or deleted here.")
                    return@Column
                }
            }

            PartsHeader("If", "${triggers.size} of $MAX_AUTOMATION_PARTS")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("All" to "All conditions", "Any" to "Any condition").forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = triggerMode == mode,
                        onClick = { triggerMode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(label) }
                }
            }
            triggers.forEachIndexed { index, trigger ->
                val (target, text) = describeTrigger(trigger, state)
                PartCard(target, text) { triggers.removeAt(index) }
            }
            AddButton("Add condition", triggers.size < MAX_AUTOMATION_PARTS) { pickTrigger(dialogs, state) { triggers.add(it) } }

            PartsHeader("When", "${times.size} of $MAX_AUTOMATION_PARTS")
            if (times.isEmpty()) Text("At any time", color = MaterialTheme.colorScheme.onSurfaceVariant)
            times.forEachIndexed { index, time ->
                PartCard(describeTime(time), daysText(time.days)) { times.removeAt(index) }
            }
            AddButton("Add time", times.size < MAX_AUTOMATION_PARTS) { pickTime(dialogs) { times.add(it) } }

            PartsHeader("Then", "${actions.size} of $MAX_AUTOMATION_PARTS")
            Text("Actions run in this order.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions.forEachIndexed { index, action ->
                val (target, text) = describeAutomationAction(action, state)
                PartCard(target, text) { actions.removeAt(index) }
            }
            AddButton("Add action", actions.size < MAX_AUTOMATION_PARTS) { pickAutomationAction(dialogs, state) { actions.add(it) } }
            if (!complete) {
                Text(
                    "An automation needs at least one condition and at least one action. Times are optional.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PartsHeader(title: String, count: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PartCard(title: String, detail: String, onRemove: () -> Unit) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove $title") }
        }
    }
}

@Composable
private fun AddButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

private fun pickTrigger(dialogs: DialogHost, state: HubState, onPicked: (AutomationTrigger) -> Unit) {
    val water = state.hotWater
    val options = listOf("Away mode turns on") + if (water != null) listOf("Hot water turns on", "Hot water turns off") else emptyList()
    dialogs.choice("Add condition", options) { index ->
        when (index) {
            0 -> onPicked(AutomationTrigger.awayModeOn())
            1 -> water?.let { onPicked(AutomationTrigger.hotWater(it.id, on = true)) }
            2 -> water?.let { onPicked(AutomationTrigger.hotWater(it.id, on = false)) }
        }
    }
}

private fun hhmm(minutes: Int) = minutes / 60 * 100 + minutes % 60

private fun pickTime(dialogs: DialogHost, onPicked: (AutomationTime) -> Unit) {
    fun withDays(make: (List<String>) -> AutomationTime) =
        dialogs.days("On these days", WiserHub.DAYS) { days -> onPicked(make(days)) }
    dialogs.choice(
        "Add time",
        listOf("At sunrise", "At sunset", "At a time of day…", "Daytime (sunrise to sunset)", "Night time (sunset to sunrise)", "Between two times…"),
    ) { index ->
        when (index) {
            0 -> withDays { AutomationTime.at(WiserHub.SUNRISE, it) }
            1 -> withDays { AutomationTime.at(WiserHub.SUNSET, it) }
            2 -> dialogs.time("Time of day", 7 * 60) { at -> withDays { AutomationTime.at(hhmm(at), it) } }
            3 -> withDays { AutomationTime.between(WiserHub.SUNRISE, WiserHub.SUNSET, it) }
            4 -> withDays { AutomationTime.between(WiserHub.SUNSET, WiserHub.SUNRISE, it) }
            else -> dialogs.time("From", 7 * 60) { from ->
                dialogs.time("Until", (from + 120) % (24 * 60)) { until ->
                    // A period that starts and ends at the same moment can't be opened in the official app.
                    if (until == from) dialogs.message("Same start and end", "The period needs to end at a different time from when it starts.")
                    else withDays { AutomationTime.between(hhmm(from), hhmm(until), it) }
                }
            }
        }
    }
}

private fun pickAutomationAction(dialogs: DialogHost, state: HubState, onPicked: (AutomationAction) -> Unit) {
    val water = state.hotWater
    val fixed = listOf("Send a notification", "Wait", "Run a Moment")
    val rooms = state.rooms.map { "Heating · ${it.name}" }
    dialogs.choice("Add action", fixed + rooms + listOfNotNull(water?.let { "Hot water" })) { index ->
        when {
            index == 0 -> onPicked(AutomationAction.notification())
            index == 1 -> dialogs.choice("Wait for", WAITS.map(::durationText)) { onPicked(AutomationAction.wait(WAITS[it])) }
            index == 2 -> {
                if (state.moments.isEmpty()) dialogs.message("No Moments", "Make a Moment first, then come back to run it from here.")
                else dialogs.choice("Run which Moment?", state.moments.map { it.name.trim() }) {
                    onPicked(AutomationAction.runMoment(state.moments[it].id))
                }
            }
            index - fixed.size in rooms.indices -> {
                val room = state.rooms[index - fixed.size]
                val options = listOf("Set a temperature", "Frost protection (off)") + DURATIONS.map { "Boost for ${durationText(it)}" }
                dialogs.choice(room.name, options) { pick ->
                    when (pick) {
                        0 -> dialogs.temperature(room.name, 200) { onPicked(AutomationAction.manual("Room", room.id, it)) }
                        1 -> onPicked(AutomationAction.manual("Room", room.id, WiserHub.SCHEDULE_OFF))
                        else -> onPicked(AutomationAction.boost("Room", room.id, DURATIONS[pick - 2]))
                    }
                }
            }
            water != null -> {
                val options = listOf("On", "Off") + DURATIONS.map { "On for ${durationText(it)}" }
                dialogs.choice("Hot water", options) { pick ->
                    onPicked(
                        when (pick) {
                            0 -> AutomationAction.manual("HotWater", water.id, WiserHub.HOT_WATER_ON)
                            1 -> AutomationAction.manual("HotWater", water.id, WiserHub.SCHEDULE_OFF)
                            else -> AutomationAction.manual("HotWater", water.id, WiserHub.HOT_WATER_ON, DURATIONS[pick - 2])
                        },
                    )
                }
            }
        }
    }
}

/** A one-line summary for the list: "Hot water is Off → Living Room boost for 30 min". */
fun automationSummary(detail: AutomationDetail, state: HubState): String {
    val joiner = if (detail.triggerMode.equals("Any", ignoreCase = true)) " or " else " and "
    val ifPart = detail.triggers.joinToString(joiner) { t -> describeTrigger(t, state).let { "${it.first} ${it.second.replaceFirstChar { c -> c.lowercase() }}" } }
    val thenPart = detail.actions.joinToString(", ") { a -> describeAutomationAction(a, state).let { "${it.first} ${it.second.replaceFirstChar { c -> c.lowercase() }}" } }
    return listOf(ifPart, thenPart).filter { it.isNotEmpty() }.joinToString(" → ")
}

private fun targetName(domain: String, id: Int, state: HubState): String = when (domain) {
    "HotWater" -> "Hot water"
    "Room" -> state.rooms.firstOrNull { it.id == id }?.name ?: "Room $id"
    "System" -> "System"
    else -> "${spaced(domain)} $id"
}

/** ("Hot water", "State is Off"), ("Away mode", "Is on"), and so on. */
fun describeTrigger(t: AutomationTrigger, state: HubState): Pair<String, String> {
    // Away mode is the system's override type being "Away".
    if (t.domain == "System" && t.property == "OverrideType" && t.compareTo == "Away") {
        when (t.comparator) {
            "Equals" -> return "Away mode" to "Is on"
            "NotEquals" -> return "Away mode" to "Is off"
        }
    }
    val property = when (t.property) {
        "WaterHeatingState" -> "State"
        else -> spaced(t.property)
    }
    val comparator = when (t.comparator) {
        "Equals" -> "is"
        "NotEquals" -> "is not"
        "GreaterThan" -> "is above"
        "LessThan" -> "is below"
        "GreaterThanOrEqual" -> "is at least"
        "LessThanOrEqual" -> "is at most"
        else -> spaced(t.comparator).lowercase()
    }
    return targetName(t.domain, t.id, state) to "$property $comparator ${t.compareTo}"
}

/** ("Living Room", "Boost for 30 min"), ("Hot water", "Off"), and so on. */
fun describeAutomationAction(a: AutomationAction, state: HubState): Pair<String, String> {
    if (a.notify != null) return "Notification" to if (a.notify) "Send to phone" else "Off"
    if (a.waitHhmm != null) return "Wait" to durationText(a.waitHhmm / 100 * 60 + a.waitHhmm % 100)
    if (a.domain == "System" && a.action == "TriggerMoment") {
        val moment = a.value.toIntOrNull()?.let { id -> state.moments.firstOrNull { it.id == id } }
        return "Moment" to "Run “${moment?.name?.trim() ?: "Moment ${a.value}"}”"
    }
    val target = targetName(a.domain, a.id, state)
    if (a.action != "RequestOverride") return target to listOf(spaced(a.action), a.value).filter { it.isNotEmpty() }.joinToString(": ")
    val value = runCatching { JSONObject(a.value) }.getOrNull() ?: return target to a.value
    val type = value.optString("type")
    val minutes = value.takeIf { it.has("durationMinutes") }?.optInt("durationMinutes")
    val setPoint = value.takeIf { it.has("setPoint") }?.optInt("setPoint")
    val text = when {
        type.equals("Boost", ignoreCase = true) -> "Boost" + (minutes?.let { " for ${durationText(it)}" } ?: "")
        type.equals("Manual", ignoreCase = true) && setPoint != null -> when {
            // Wiser calls a room set to off "frost protection"; the hot water is just off.
            setPoint == WiserHub.SCHEDULE_OFF -> if (a.domain == "Room") "Frost protection" else "Off"
            a.domain == "HotWater" -> "On" + (minutes?.let { " for ${durationText(it)}" } ?: "")
            else -> WiserHub.formatSetPoint(setPoint / 10.0)
        }
        else -> a.value
    }
    return target to text
}

/** "07:05", "sunrise" or "sunset" for one of the hub's automation times. */
fun automationTimeText(code: Int): String = when (code) {
    WiserHub.SUNRISE -> "sunrise"
    WiserHub.SUNSET -> "sunset"
    else -> "%02d:%02d".format(code / 100, code % 100)
}

fun describeTime(t: AutomationTime): String = when {
    t.start != null && t.end != null ->
        "From ${automationTimeText(t.start)} to ${automationTimeText(t.end)}".replace("From s", "From S")
    t.start != null -> "At ${automationTimeText(t.start)}"
    else -> "At any time"
}

fun daysText(days: List<String>): String = when {
    days.size == 7 -> "Every day"
    days == WiserHub.DAYS.take(5) -> "Weekdays"
    days == WiserHub.DAYS.drop(5) -> "Weekends"
    days.isEmpty() -> "No days"
    else -> days.joinToString(", ") { it.take(3) }
}
