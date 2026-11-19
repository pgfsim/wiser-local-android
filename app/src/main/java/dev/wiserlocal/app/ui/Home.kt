package dev.wiserlocal.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wiserlocal.app.HubState
import dev.wiserlocal.app.Moment
import dev.wiserlocal.app.R
import dev.wiserlocal.app.SystemInfo

/** Top-level tab page: a top bar, a loading line and pull to refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScaffold(
    title: String,
    vm: HubViewModel,
    modifier: Modifier,
    subtitle: String? = null,
    showSettings: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    belowBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val nav = LocalNav.current
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(vm.loading) { if (!vm.loading) pulled = false }
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(title)
                            if (subtitle != null) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        actions()
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                        if (showSettings) {
                            IconButton(onClick = { nav.push(Screen.Settings) }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    },
                )
                belowBar()
                LoadingBar(vm.loading && !pulled)
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = pulled && vm.loading,
            onRefresh = { pulled = true; vm.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                // Keeps text fields and their buttons above the keyboard.
                Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                problems(vm, nav)
                content()
            }
        }
    }
}

/** "Not set up" or "can't reach the hub" cards, shown at the top of each tab. */
private fun LazyListScope.problems(vm: HubViewModel, nav: Navigator) {
    if (!vm.configured) {
        item { ProblemCard("Connect to your hub to get started.", "Set up") { nav.push(Screen.HubConnection) } }
    } else if (vm.error != null) {
        item {
            val text = if (vm.state == null) "${vm.error}" else "${vm.error}\nShowing the last readings."
            ProblemCard(text, "Try again") { vm.refresh() }
        }
    }
}

fun updatedText(context: android.content.Context, vm: HubViewModel): String? =
    vm.updatedAt?.let { "Updated ${context.timeText(it)}" }

/** Favourite keys in order, defaulting to every room then hot water. */
fun favouriteKeys(vm: HubViewModel, state: HubState): List<String> =
    vm.favourites ?: (state.rooms.map { "room:${it.id}" } + listOfNotNull(state.hotWater?.let { "hotwater" }))

@Composable
fun HomeTab(vm: HubViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val nav = LocalNav.current
    TabScaffold(greeting(), vm, modifier, subtitle = updatedText(context, vm)) {
        val state = vm.state ?: return@TabScaffold
        item { StatusCard(state.system, vm) }

        item {
            SectionTitle("Favourites") {
                TextButton(onClick = { nav.push(Screen.HomeLayout) }) { Text("Edit") }
            }
        }
        val keys = favouriteKeys(vm, state)
        var shown = 0
        for (key in keys) {
            if (key == "hotwater") {
                val hw = state.hotWater ?: continue
                shown++
                item(key = key) { HotWaterCard(hw, vm) }
            } else {
                val room = state.rooms.firstOrNull { "room:${it.id}" == key } ?: continue
                shown++
                item(key = key) { RoomCard(room, vm, full = false) }
            }
        }
        if (shown == 0) {
            item {
                Text(
                    "No favourites. Tap Edit to choose what shows here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        item {
            SectionTitle("Moments") {
                TextButton(onClick = { nav.automationsTab = 0; nav.tab = Tab.AUTOMATIONS }) { Text("Edit") }
            }
        }
        item {
            if (state.moments.isEmpty()) {
                Text(
                    "No Moments yet. Add one in Automations.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.moments, key = { it.id }) { moment -> MomentTile(moment, vm) }
                }
            }
        }
    }
}

/** Boiler state, away mode and whole-house actions. */
@Composable
private fun StatusCard(system: SystemInfo, vm: HubViewModel) {
    val accents = LocalAccents.current
    val dialogs = LocalDialogs.current
    val firing = system.boilerFiring
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (firing) accents.heatContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (firing) accents.onHeatContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    flameIcon(),
                    contentDescription = null,
                    tint = if (firing) accents.heat else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (firing) "Heating is on" else "Heating is off",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (firing) "The boiler is being called for heat" else "No room is calling for heat",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clickable { vm.run { it.setAway(!system.away) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_away), contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Away mode", style = MaterialTheme.typography.bodyLarge)
                    Text(if (system.away) "On: heating held low" else "Off", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = system.away, onCheckedChange = { on -> vm.run { it.setAway(on) } })
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    dialogs.duration(
                        "Boost all rooms",
                        label = { "+${BOOST_DELTA_C.toInt()}° for ${durationText(it)}" },
                    ) { minutes -> vm.run { it.boostAllRooms(BOOST_DELTA_C, minutes) } }
                }) { Text("Boost all rooms") }
                OutlinedButton(onClick = { vm.run("Overrides cancelled") { it.cancelAllOverrides() } }) {
                    Text("Cancel overrides")
                }
            }
        }
    }
}

@Composable
private fun MomentTile(moment: Moment, vm: HubViewModel) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .widthIn(min = 140.dp, max = 220.dp)
            .clickable { vm.run("Running “${moment.name}”") { it.triggerMoment(moment.id) } },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_moment),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(moment.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Placeholder content for a page that has nothing yet. */
@Composable
fun EmptyNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}
