package dev.wiserlocal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wiserlocal.app.HubState
import dev.wiserlocal.app.MomentAction
import dev.wiserlocal.app.R
import dev.wiserlocal.app.WiserHub

/** Create or edit a Moment: its name at the top, its actions below. */
@Composable
fun MomentEditorScreen(vm: HubViewModel, snackbar: SnackbarHostState, momentId: Int?) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    val state = vm.state
    val moment = momentId?.let { id -> state?.moments?.firstOrNull { it.id == id } }

    DisposableEffect(Unit) {
        vm.watchMoments(true)
        onDispose { vm.watchMoments(false) }
    }

    var name by remember { mutableStateOf(moment?.name ?: "") }
    var iconId by remember { mutableIntStateOf(moment?.iconId ?: 0) }
    val actions = remember { mutableStateListOf<MomentAction>() }
    var loaded by remember { mutableStateOf(momentId == null) }
    var unknown by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<List<MomentAction>>(emptyList()) }

    // Fill in the Moment's actions once the hub has told us what they are.
    LaunchedEffect(vm.momentActions, moment) {
        if (loaded || moment == null || moment.id !in vm.momentActions) return@LaunchedEffect
        if (name.isEmpty()) {
            name = moment.name
            iconId = moment.iconId
        }
        val loadedActions = vm.momentActions[moment.id]
        if (loadedActions == null) unknown = true else {
            original = loadedActions
            actions.addAll(loadedActions)
        }
        loaded = true
    }

    val nameChanged = moment != null && (name.trim() != moment.name || iconId != moment.iconId)
    val actionChanged = actions.toList() != original
    val dirty = if (moment == null) name.isNotBlank() || actions.isNotEmpty() else nameChanged || actionChanged
    val canSave = loaded && name.isNotBlank() && when {
        unknown -> nameChanged
        else -> actions.isNotEmpty() && (moment == null || dirty)
    }

    fun leave() {
        if (!dirty) nav.pop()
        else dialogs.confirm("Discard your changes to this Moment?", "Discard") { nav.pop() }
    }
    BackHandler { leave() }

    fun save() {
        val newName = name.trim()
        when {
            moment == null -> vm.run("Moment “$newName” added", onSuccess = { nav.pop() }) {
                it.createMoment(newName, iconId, actions.toList())
            }
            unknown -> vm.run("Moment saved", onSuccess = { nav.pop() }) { it.rewriteMoment(moment.id, newName, iconId) }
            else -> vm.run("Moment saved", onSuccess = { nav.pop() }) {
                it.updateMoment(moment.copy(name = newName, iconId = iconId), actions.toList())
            }
        }
    }

    DetailScaffold(
        title = if (momentId == null) "New Moment" else "Edit Moment",
        snackbar = snackbar,
        onBack = ::leave,
        loading = vm.loading,
        actions = { TextButton(onClick = ::save, enabled = canSave && !vm.loading) { Text("Save") } },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        if (state != null) pickMomentAction(dialogs, state, null) { actions.add(it) }
                    },
                    enabled = loaded && !unknown && actions.size < MAX_MOMENT_ACTIONS && state != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add action")
                }
                if (moment != null) {
                    OutlinedButton(
                        onClick = {
                            dialogs.confirm("Delete the Moment “${moment.name}” from the hub? This cannot be undone.", "Delete") {
                                vm.run("Moment deleted", onSuccess = { nav.pop() }) { it.deleteMoment(moment.id) }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalAccents.current.danger),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Delete") }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChooser(iconId, R.drawable.ic_moment, enabled = loaded) { iconId = it }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Actions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "${if (unknown) "?" else actions.size} of $MAX_MOMENT_ACTIONS",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                !loaded -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                unknown -> NoteCard(
                    "This Moment was set up in the official app and does something this app can't show " +
                        "or change yet, such as controlling a light or waiting before an action. You can still rename it, change its icon, run it or delete it here.",
                )
                actions.isEmpty() -> Text(
                    "No actions yet. Tap Add action to choose what this Moment does.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            actions.forEachIndexed { index, action ->
                if (state != null) {
                    ActionCard(
                        action,
                        state,
                        onClick = { pickMomentAction(dialogs, state, action) { actions[index] = it } },
                        onRemove = { actions.removeAt(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionCard(action: MomentAction, state: HubState, onClick: () -> Unit, onRemove: () -> Unit) {
    val accents = LocalAccents.current
    val water = action.domain == "HotWater"
    val (target, value) = describeAction(action, state)
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (water) accents.waterContainer else accents.heatContainer,
            contentColor = if (water) accents.onWaterContainer else accents.onHeatContainer,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (water) waterIcon() else flameIcon(),
                contentDescription = null,
                tint = if (water) accents.water else accents.heat,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (water) "Hot water" else "Heating", style = MaterialTheme.typography.titleMedium)
                if (!water) Text(target, style = MaterialTheme.typography.bodyMedium)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove action") }
        }
    }
}

@Composable
fun NoteCard(text: String) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Asks what an action should do: a room's temperature or off, or hot water on, off or boosted. */
fun pickMomentAction(dialogs: DialogHost, state: HubState, current: MomentAction?, onPicked: (MomentAction) -> Unit) {
    val targets = state.rooms.map { "Heating · ${it.name}" } + listOfNotNull(state.hotWater?.let { "Hot water" })
    dialogs.choice("What should it change?", targets) { index ->
        val room = state.rooms.getOrNull(index)
        if (room != null) {
            // The same choices as the official app: a temperature, a boost, or cancel a boost.
            val options = listOf("Set a temperature") + DURATIONS.map { "Boost for ${durationText(it)}" } + "Back to schedule"
            dialogs.choice(room.name, options) { pick ->
                when {
                    pick == 0 -> {
                        val initial = current?.takeIf { it.domain == "Room" && it.id == room.id && it.type == MomentAction.TYPE_MANUAL }?.setPoint ?: 200
                        dialogs.temperature(room.name, initial, allowOff = true) { tenths ->
                            onPicked(MomentAction("Room", room.id, tenths))
                        }
                    }
                    pick <= DURATIONS.size -> onPicked(MomentAction("Room", room.id, 0, DURATIONS[pick - 1], MomentAction.TYPE_BOOST))
                    else -> onPicked(MomentAction("Room", room.id, 0, null, MomentAction.TYPE_NONE))
                }
            }
        } else {
            val water = state.hotWater ?: return@choice
            val options = listOf("On", "Off") + DURATIONS.map { "Boost for ${durationText(it)}" } + "Back to schedule"
            dialogs.choice("Hot water", options) { pick ->
                onPicked(
                    when {
                        pick == 0 -> MomentAction("HotWater", water.id, WiserHub.HOT_WATER_ON)
                        pick == 1 -> MomentAction("HotWater", water.id, WiserHub.SCHEDULE_OFF)
                        pick < 2 + DURATIONS.size -> MomentAction("HotWater", water.id, WiserHub.HOT_WATER_ON, DURATIONS[pick - 2])
                        else -> MomentAction("HotWater", water.id, 0, null, MomentAction.TYPE_NONE)
                    },
                )
            }
        }
    }
}
