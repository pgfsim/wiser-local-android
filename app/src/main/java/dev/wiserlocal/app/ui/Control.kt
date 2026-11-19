package dev.wiserlocal.app.ui

import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Every room and the hot water with full controls; one tab per room. */
@Composable
fun ControlTab(vm: HubViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val rooms = vm.state?.rooms.orEmpty()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    if (selected > rooms.size) selected = 0

    TabScaffold(
        "Control",
        vm,
        modifier,
        subtitle = updatedText(context, vm),
        belowBar = {
            if (rooms.isNotEmpty()) {
                ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                    Tab(selected = selected == 0, onClick = { selected = 0 }, text = { Text("All") })
                    rooms.forEachIndexed { index, room ->
                        Tab(selected = selected == index + 1, onClick = { selected = index + 1 }, text = { Text(room.name) })
                    }
                }
            }
        },
    ) {
        val state = vm.state ?: return@TabScaffold
        if (selected == 0) {
            if (state.rooms.isNotEmpty()) item { SectionTitle("Heating") }
            state.rooms.forEach { room -> item(key = "room${room.id}") { RoomCard(room, vm, full = true) } }
            state.hotWater?.let { hw ->
                item { SectionTitle("Hot water") }
                item(key = "hw") { HotWaterCard(hw, vm) }
            }
        } else {
            val room = state.rooms.getOrNull(selected - 1) ?: return@TabScaffold
            item(key = "room${room.id}") { RoomCard(room, vm, full = true) }
            val devices = state.devices.filter { it.roomName == room.name }
            if (devices.isNotEmpty()) {
                item {
                    Section("Devices in ${room.name}") {
                        devices.forEachIndexed { index, device ->
                            if (index > 0) RowDivider()
                            InfoRow(deviceName(device, withRoom = false), deviceDetails(device).joinToString("\n"))
                        }
                    }
                }
            }
        }
    }
}
