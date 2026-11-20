package dev.wiserlocal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wiserlocal.app.AutomationAction
import dev.wiserlocal.app.AutomationDetail
import dev.wiserlocal.app.AutomationTime
import dev.wiserlocal.app.AutomationTrigger
import dev.wiserlocal.app.HubState
import dev.wiserlocal.app.WiserHub
import org.json.JSONObject

/**
 * One automation: on/off, then its If / When / Then, read from the hub.
 * Editing comes once the official app's save format for changes is known.
 */
@Composable
fun AutomationDetailScreen(vm: HubViewModel, snackbar: SnackbarHostState, automationId: Int) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    val state = vm.state
    val automation = state?.automations?.firstOrNull { it.id == automationId }

    DisposableEffect(Unit) {
        vm.watchMoments(true)
        onDispose { vm.watchMoments(false) }
    }

    DetailScaffold(
        title = automation?.name ?: "Automation",
        snackbar = snackbar,
        onBack = { nav.pop() },
        loading = vm.loading,
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
            if (state == null || automation == null) {
                Text("This automation is no longer on the hub.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Section {
                SwitchRow("Enabled", if (automation.enabled) "Runs when its conditions are met" else "Paused", automation.enabled) { on ->
                    vm.run(if (on) "Automation on" else "Automation off") { it.setAutomationEnabled(automation.id, on) }
                }
            }
            val detail = vm.automationDetails[automation.id]
            when {
                automation.id !in vm.automationDetails -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                detail == null -> NoteCard("The hub's description of this automation couldn't be read.")
                else -> AutomationParts(detail, state)
            }
            NoteCard("Automations can be turned on or off and deleted here. Making and changing them is still done in the official app.")
        }
    }
}

@Composable
private fun AutomationParts(detail: AutomationDetail, state: HubState) {
    Section(if (detail.triggerMode.equals("Any", ignoreCase = true)) "If any of these" else "If all of these") {
        if (detail.triggers.isEmpty()) InfoRow("No conditions")
        detail.triggers.forEachIndexed { index, trigger ->
            if (index > 0) RowDivider()
            val (target, text) = describeTrigger(trigger, state)
            InfoRow(target, text)
        }
    }
    Section("When") {
        if (detail.times.isEmpty()) InfoRow("At any time")
        detail.times.forEachIndexed { index, time ->
            if (index > 0) RowDivider()
            InfoRow(describeTime(time), daysText(time.days))
        }
    }
    Section("Then") {
        if (detail.actions.isEmpty()) InfoRow("No actions")
        detail.actions.forEachIndexed { index, action ->
            if (index > 0) RowDivider()
            val (target, text) = describeAutomationAction(action, state)
            InfoRow(target, text)
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

/** ("Hot water", "Heating state is Off"). Hot water's own state drops the repeated "water". */
fun describeTrigger(t: AutomationTrigger, state: HubState): Pair<String, String> {
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
    val target = targetName(a.domain, a.id, state)
    if (a.action != "RequestOverride") return target to listOf(spaced(a.action), a.value).filter { it.isNotEmpty() }.joinToString(": ")
    val value = runCatching { JSONObject(a.value) }.getOrNull() ?: return target to a.value
    val type = value.optString("type")
    val minutes = value.takeIf { it.has("durationMinutes") }?.optInt("durationMinutes")
    val setPoint = value.takeIf { it.has("setPoint") }?.optInt("setPoint")
    val text = when {
        type.equals("Boost", ignoreCase = true) -> "Boost" + (minutes?.let { " for ${durationText(it)}" } ?: "")
        type.equals("Manual", ignoreCase = true) && setPoint != null -> when {
            setPoint == WiserHub.SCHEDULE_OFF -> "Off"
            a.domain == "HotWater" -> "On" + (minutes?.let { " for ${durationText(it)}" } ?: "")
            else -> WiserHub.formatSetPoint(setPoint / 10.0)
        }
        else -> a.value
    }
    return target to text
}

/**
 * The hub sends times as plain numbers (3000, 4000 seen so far) whose meaning isn't
 * known yet, so they are shown as sent until a capture with a known clock time pins it down.
 */
fun describeTime(t: AutomationTime): String = when {
    t.start != null && t.end != null -> "From time ${t.start} to ${t.end}"
    t.start != null -> "At time ${t.start}"
    else -> "At any time"
}

fun daysText(days: List<String>): String = when {
    days.size == 7 -> "Every day"
    days == WiserHub.DAYS.take(5) -> "Weekdays"
    days == WiserHub.DAYS.drop(5) -> "Weekends"
    days.isEmpty() -> "No days"
    else -> days.joinToString(", ") { it.take(3) }
}
