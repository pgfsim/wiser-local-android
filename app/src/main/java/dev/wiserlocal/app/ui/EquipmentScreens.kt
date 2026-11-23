package dev.wiserlocal.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wiserlocal.app.Ctl
import dev.wiserlocal.app.Equipment
import org.json.JSONObject

/** True if any of [controls] has something to show for [data]. */
fun hasControls(data: JSONObject?, controls: List<Ctl>): Boolean =
    data != null && controls.any { it.visibleFor(data) }

private fun Ctl.visibleFor(data: JSONObject): Boolean = when (this) {
    is Ctl.Info -> Equipment.field(data, field) != null
    is Ctl.Toggle -> Equipment.field(data, field) != null
    is Ctl.Choice -> Equipment.field(data, field) != null
    is Ctl.Number -> Equipment.field(data, field) != null
    is Ctl.Button -> when {
        field == null -> true
        whenMissing -> Equipment.field(data, field) == null
        else -> Equipment.field(data, field) != null
    }
    is Ctl.NumberWith -> Equipment.field(data, field) != null
    is Ctl.ChoiceWith -> Equipment.field(data, field) != null
    is Ctl.ToggleWith -> Equipment.field(data, field) != null
}

private fun isOn(v: Any?): Boolean = when (v) {
    is Boolean -> v
    is String -> v.equals("true", true) || v.equals("On", true) || v.equals("Enabled", true)
    is Number -> v.toInt() != 0
    else -> false
}

/**
 * Draws [controls] for one item (or the system, or a room) from its data [data], sending
 * changes with [id]. Rows whose field the hub doesn't report are left out.
 */
@Composable
fun ControlRows(vm: HubViewModel, data: JSONObject, id: Int, controls: List<Ctl>) {
    val dialogs = LocalDialogs.current
    var first = true
    controls.filter { it.visibleFor(data) }.forEach { c ->
        if (!first) RowDivider()
        first = false
        when (c) {
            is Ctl.Info -> InfoRow(c.label, Equipment.field(data, c.field)?.let(c.format))
            is Ctl.Toggle -> SwitchRow(c.label, null, isOn(Equipment.field(data, c.field))) { now -> vm.run { it.send(c.send(id, now)) } }
            is Ctl.ToggleWith -> SwitchRow(c.label, null, isOn(Equipment.field(data, c.field))) { now -> vm.run { it.send(c.send(id, now, data)) } }
            is Ctl.ChoiceWith -> {
                val current = Equipment.field(data, c.field)?.toString()
                val index = c.options.indexOfFirst { it.first.equals(current, true) }
                InfoRow(c.label, c.options.getOrNull(index)?.second ?: current?.let(Equipment::spaced), showChevron = true) {
                    dialogs.choice(c.label, c.options.map { it.second }, index.takeIf { it >= 0 }) { pick ->
                        vm.run { it.send(c.send(id, c.options[pick].first, data)) }
                    }
                }
            }
            is Ctl.NumberWith -> {
                val raw = (Equipment.field(data, c.field) as? Number)?.toInt()
                fun label(v: Int) = if (c.scale == 10) Equipment.formatTenths(v) else "$v${c.unit}"
                InfoRow(c.label, raw?.let(::label), showChevron = true) {
                    val nearest = raw?.let { r -> c.values.minByOrNull { kotlin.math.abs(it - r) } }
                    dialogs.choice(c.label, c.values.map(::label), nearest?.let { c.values.indexOf(it) }) { pick ->
                        vm.run { it.send(c.send(id, c.values[pick], data)) }
                    }
                }
            }
            is Ctl.Choice -> {
                val current = Equipment.field(data, c.field)?.toString()
                val index = c.options.indexOfFirst { it.first.equals(current, true) }
                InfoRow(c.label, c.options.getOrNull(index)?.second ?: current?.let(Equipment::spaced), showChevron = true) {
                    dialogs.choice(c.label, c.options.map { it.second }, index.takeIf { it >= 0 }) { pick ->
                        vm.run { it.send(c.send(id, c.options[pick].first)) }
                    }
                }
            }
            is Ctl.Number -> {
                val raw = (Equipment.field(data, c.field) as? Number)?.toInt()
                fun label(v: Int) = if (c.scale == 10) Equipment.formatTenths(v) else "$v${c.unit}"
                InfoRow(c.label, raw?.let(::label), showChevron = true) {
                    val nearest = raw?.let { r -> c.values.minByOrNull { kotlin.math.abs(it - r) } }
                    dialogs.choice(c.label, c.values.map(::label), nearest?.let { c.values.indexOf(it) }) { pick ->
                        vm.run { it.send(c.send(id, c.values[pick])) }
                    }
                }
            }
            is Ctl.Button -> InfoRow(c.label, null, showChevron = true) {
                val go = { vm.run("${c.label}: sent") { it.send(c.send(id)) } }
                if (c.confirm != null) dialogs.confirm(c.confirm, c.label) { go() } else go()
            }
        }
    }
}

/** Every kind of equipment the hub reports, each with its items. */
@Composable
fun EquipmentScreen(vm: HubViewModel, snackbar: SnackbarHostState) {
    val nav = LocalNav.current
    DetailScaffold("Equipment", snackbar, onBack = { nav.pop() }, loading = vm.loading) { padding ->
        SettingsColumn(padding) {
            val domain = vm.state?.domain ?: return@SettingsColumn
            var any = false
            Equipment.SPECS.forEach { spec ->
                val items = Equipment.items(domain, spec)
                if (items.isEmpty()) return@forEach
                any = true
                Section(spec.title) {
                    items.forEachIndexed { i, (id, name, data) ->
                        if (i > 0) RowDivider()
                        val summary = spec.controls.filterIsInstance<Ctl.Info>().take(2)
                            .mapNotNull { c -> Equipment.field(data, c.field)?.let { "${c.label}: ${c.format(it)}" } }
                            .joinToString(" · ").ifEmpty { null }
                        InfoRow(name, summary, showChevron = true) { nav.push(Screen.EquipmentItem(spec.domain, id)) }
                    }
                }
            }
            val groupable = Equipment.items(domain, Equipment.SPECS.first { it.domain == "Light" }) +
                Equipment.items(domain, Equipment.SPECS.first { it.domain == "Shutter" })
            if (groupable.isNotEmpty()) Section {
                val dialogs = LocalDialogs.current
                InfoRow("New Zigbee group", "Lights or shutters that switch together, for example from one button.", showChevron = true) {
                    dialogs.prompt("Group name", "") { name -> vm.run("Group created") { it.createZigbeeGroup(name.trim(), emptyList()) } }
                }
            }
            if (!any) {
                Text(
                    "This hub has no lights, shutters, plugs, sensors or other equipment besides its heating devices.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One item: its readings, controls, name and room. */
@Composable
fun EquipmentItemScreen(vm: HubViewModel, snackbar: SnackbarHostState, domainName: String, id: Int) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    val spec = Equipment.SPECS.firstOrNull { it.domain == domainName }
    val item = spec?.let { s -> Equipment.items(vm.state?.domain, s).firstOrNull { it.first == id } }
    DetailScaffold(item?.second ?: spec?.title ?: "Equipment", snackbar, onBack = { nav.pop() }, loading = vm.loading) { padding ->
        SettingsColumn(padding) {
            val state = vm.state ?: return@SettingsColumn
            if (spec == null || item == null) {
                Text("This is no longer on the hub.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@SettingsColumn
            }
            val (_, name, data) = item
            Section { ControlRows(vm, data, id, spec.controls) }

            if (spec.domain == "ButtonPanel") ButtonPanelEvents(vm, state, id, data)
            if (spec.domain == "ZigbeeGroup") ZigbeeGroupMembers(vm, id, name, data)

            // Lights, shutters and fans follow a level schedule; plugs and EV chargers an on/off one.
            val levelKind = spec.domain in setOf("Light", "Shutter", "FanController")
            if (levelKind || spec.domain in setOf("SmartPlug", "EVSE")) {
                val candidates = vm.schedules.filter { if (levelKind) it.level else !it.heating && !it.level }
                val current = data.optInt("ScheduleId", 0)
                val mine = candidates.firstOrNull { it.id == current }
                Section("Schedule") {
                    InfoRow("Schedule", mine?.name?.ifEmpty { "Schedule ${mine.id}" } ?: "None", showChevron = true) {
                        if (candidates.isEmpty()) {
                            dialogs.message("Schedule", "The hub has no ${if (levelKind) "level" else "on/off"} schedules yet. Create one in the official app.")
                            return@InfoRow
                        }
                        dialogs.choice("Schedule for $name", candidates.map { it.name.ifEmpty { "Schedule ${it.id}" } }, candidates.indexOf(mine).takeIf { it >= 0 }) { pick ->
                            // The official app's SetXFollowSchedule: the schedule, and back to following it.
                            val body = JSONObject().put("ScheduleId", candidates[pick].id).put("Mode", "Auto").toString()
                            vm.run { it.send(dev.wiserlocal.app.Req("PATCH", "${spec.domain}/$id/", body)) }
                        }
                    }
                    if (mine != null) {
                        RowDivider()
                        InfoRow("Edit schedule", null, showChevron = true) {
                            nav.push(Screen.ScheduleEditor(mine.id, heating = false, title = "$name schedule", level = mine.level))
                        }
                    }
                }
            }
            if (spec.rename != null || spec.roomable) Section("Options") {
                spec.rename?.let { rename ->
                    InfoRow("Name", name, showChevron = true) {
                        dialogs.prompt("Name", name) { newName -> vm.run { it.send(rename(id, newName.trim())) } }
                    }
                }
                if (spec.roomable) {
                    if (spec.rename != null) RowDivider()
                    val roomId = data.optInt("RoomId", 0).takeIf { it > 0 }
                    val rooms = state.rooms
                    InfoRow("Location", rooms.firstOrNull { it.id == roomId }?.name ?: "No room", showChevron = true) {
                        val options = rooms.map { it.name } + "No room"
                        val current = rooms.indexOfFirst { it.id == roomId }.takeIf { it >= 0 } ?: rooms.size
                        dialogs.choice("Location", options, current) { pick ->
                            val target = rooms.getOrNull(pick)
                            vm.run {
                                // The official app's AssignXToRoom / RemoveXFromRoom.
                                if (target != null) it.send(dev.wiserlocal.app.Req("PATCH", "${spec.domain}/$id/", JSONObject().put("RoomId", target.id).toString()))
                                else it.send(dev.wiserlocal.app.Req("DELETE", "${spec.domain}/$id/RoomId", null))
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * What each button on a panel does, set with the same actions as a Moment
 * (PATCH ButtonPanel/{id}/Events/{event}/Action), or cleared.
 */
@Composable
private fun ButtonPanelEvents(vm: HubViewModel, state: dev.wiserlocal.app.HubState, panelId: Int, data: JSONObject) {
    val dialogs = LocalDialogs.current
    val events = data.optJSONArray("Events") ?: return
    val reader = remember { dev.wiserlocal.app.WiserHub("localhost", "") } // only reads action JSON, never connects
    Section("Buttons") {
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val eventId = ev.optInt("Id", ev.optInt("id", i))
            val label = ev.optString("Name").ifEmpty { "Button ${i + 1}" } + (ev.optString("Type").takeIf { it.isNotEmpty() }?.let { " · ${Equipment.spaced(it)}" } ?: "")
            val action = ev.optJSONObject("Action")?.let(reader::readAction)
            val summary = when {
                ev.optJSONObject("Action") == null -> "Does nothing"
                action == null -> "Set up in the official app"
                else -> describeAction(action, state).let { (t, v) -> "$t: $v" }
            }
            if (i > 0) RowDivider()
            InfoRow(label, summary, showChevron = true) {
                dialogs.choice(label, listOf("Choose what it does", "Make it do nothing")) { pick ->
                    if (pick == 0) pickMomentAction(dialogs, state, action) { chosen ->
                        vm.run("Button set") { it.setButtonAction(panelId, eventId, chosen) }
                    } else vm.run("Button cleared") { it.clearButtonAction(panelId, eventId) }
                }
            }
        }
    }
}

/** The lights and shutters in a Zigbee group: add, remove, rename or delete it. */
@Composable
private fun ZigbeeGroupMembers(vm: HubViewModel, groupId: Int, name: String, data: JSONObject) {
    val dialogs = LocalDialogs.current
    val nav = LocalNav.current
    val state = vm.state ?: return
    val domain = state.domain
    val targets = data.optJSONArray("TargetDevices")
    val members: List<String> = when {
        targets != null -> List(targets.length()) { targets.optString(it) }
        else -> data.optJSONArray("Members")?.let { m -> List(m.length()) { m.optJSONObject(it)?.optString("DevicePath").orEmpty() } }.orEmpty()
    }.filter { it.isNotEmpty() }
    // Every light and shutter, by the device path the hub uses in groups.
    val all = listOf("Light", "Shutter").flatMap { d ->
        Equipment.items(domain, Equipment.SPECS.first { it.domain == d }).map { (id, n, _) -> "/$d/$id" to n }
    }
    fun label(path: String) = all.firstOrNull { it.first == path }?.second ?: path
    val iconId = data.optInt("IconId", 0)
    Section("In this group") {
        if (members.isEmpty()) InfoRow("No devices yet")
        members.forEachIndexed { i, p ->
            if (i > 0) RowDivider()
            InfoRow(label(p), "Tap to take it out of the group", showChevron = true) {
                vm.run { it.updateZigbeeGroup(groupId, name, iconId, members - p) }
            }
        }
    }
    Section {
        val outside = all.filter { it.first !in members }
        InfoRow("Add a light or shutter", null, showChevron = true) {
            if (outside.isEmpty()) {
                dialogs.message("Zigbee group", "Every light and shutter is already in this group.")
                return@InfoRow
            }
            dialogs.choice("Add to $name", outside.map { it.second }) { pick ->
                vm.run { it.updateZigbeeGroup(groupId, name, iconId, members + outside[pick].first) }
            }
        }
        RowDivider()
        InfoRow("Rename group", name, showChevron = true) {
            dialogs.prompt("Group name", name) { n -> vm.run { it.updateZigbeeGroup(groupId, n.trim(), iconId, members) } }
        }
        RowDivider()
        InfoRow("Delete group") {
            dialogs.confirm("Delete the group “$name”? Its lights and shutters stay on the hub.", "Delete") {
                vm.run("Group deleted", onSuccess = { nav.pop() }) { it.deleteZigbeeGroup(groupId) }
            }
        }
    }
}
