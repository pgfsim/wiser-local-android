package dev.wiserlocal.app.ui

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.wiserlocal.app.BuildConfigInfo
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.wiserlocal.app.Device
import dev.wiserlocal.app.HubState
import dev.wiserlocal.app.R
import dev.wiserlocal.app.WiserHub
import kotlin.math.roundToInt
import dev.wiserlocal.app.OpenThermSetpoint
import dev.wiserlocal.app.usesOpenTherm
import java.util.Locale

/** The gear menu: hub, rooms, devices and system settings, then app options. */
@Composable
fun SettingsScreen(vm: HubViewModel, snackbar: SnackbarHostState) {
    val nav = LocalNav.current
    DetailScaffold("Settings", snackbar, onBack = { nav.pop() }, loading = vm.loading) { padding ->
        SettingsColumn(padding) {
            Section {
                InfoRow("Hub connection", vm.prefs.host.ifEmpty { "Not set up" }, showChevron = true) { nav.push(Screen.HubConnection) }
                RowDivider()
                InfoRow("Rooms", "Names, schedules, window detection and devices", showChevron = true) { nav.push(Screen.Rooms) }
                RowDivider()
                InfoRow("Devices", "Thermostats and valves: battery, signal, lock, identify", showChevron = true) { nav.push(Screen.Devices) }
            }
            Section {
                if (vm.state?.features?.awayMode != false) {
                    InfoRow("Away mode", "Away temperature and hot water", showChevron = true) { nav.push(Screen.Away) }
                }
                RowDivider()
                InfoRow("Smart modes", "Eco and comfort", showChevron = true) { nav.push(Screen.SmartModes) }
                RowDivider()
                InfoRow("System settings", "Hub firmware, network, boiler, valve protection, clock", showChevron = true) {
                    nav.push(Screen.SystemSettings)
                }
            }
            Section {
                InfoRow(
                    "Wiser account",
                    if (vm.cloudSignedIn) vm.cloudAccount?.email ?: "Signed in" else "Not signed in · used for Insights",
                    showChevron = true,
                ) {
                    nav.stack.clear()
                    nav.tab = Tab.INSIGHTS
                }
                RowDivider()
                SwitchRow(
                    "Control away from home (experimental)",
                    if (vm.cloudSignedIn) {
                        "When the hub can't be reached on this network, commands go through Wiser's cloud using your Wiser account."
                    } else {
                        "Sign in to your Wiser account first. Commands then go through Wiser's cloud when the hub can't be reached."
                    },
                    vm.remoteControl && vm.cloudSignedIn,
                    enabled = vm.cloudSignedIn,
                ) { on -> vm.changeRemoteControl(on) }
            }
            Section {
                InfoRow("Home screen", "Choose and order your favourites", showChevron = true) { nav.push(Screen.HomeLayout) }
                RowDivider()
                InfoRow("App theme", themeLabel(vm.theme), showChevron = true) { nav.push(Screen.Theme) }
            }
            val updates: UpdateViewModel = viewModel()
            Section {
                InfoRow(
                    "Check for updates",
                    if (updates.checking) "Checking…" else "Looks for a newer release on GitHub. Updating is optional.",
                    showChevron = true,
                ) { updates.checkNow() }
            }
            Text(
                "Wiser Local ${BuildConfigInfo.versionName(LocalContext.current)}\nTalks to your hub directly over your home network.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

private fun themeLabel(value: String) = when (value) {
    "light" -> "Light"
    "dark" -> "Dark"
    else -> "Follow the phone"
}

@Composable
fun HubConnectionScreen(vm: HubViewModel, snackbar: SnackbarHostState) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    val context = LocalContext.current
    var host by rememberSaveable { mutableStateOf(vm.prefs.host) }
    var secret by rememberSaveable { mutableStateOf(vm.prefs.secret) }
    var minutes by rememberSaveable { mutableStateOf(vm.prefs.boostMinutes.toString()) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var search by remember { mutableStateOf<String?>(null) }
    var stopSearch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var tested by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { stopSearch?.invoke() } }

    fun findHub() {
        stopSearch?.invoke()
        search = "Searching for a hub…"
        stopSearch = discoverHub(
            context.getSystemService(NsdManager::class.java),
            onFound = { address, name ->
                host = address
                search = "Found $name. Check the secret, then save."
            },
            onFinished = { found ->
                stopSearch = null
                if (!found) search = "No hub found. Check the phone is on your home Wi-Fi, or enter the address by hand."
            },
        )
    }

    var gettingSecret by remember { mutableStateOf(false) }

    fun scanHubQr() {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code ->
                val wifi = code.rawValue?.let(dev.wiserlocal.app.HubSetupWifi::parse)
                if (wifi == null) {
                    dialogs.message("Hub QR code", "That isn't the hub's Wi-Fi code. Scan the code on the label on the hub.")
                    return@addOnSuccessListener
                }
                dialogs.confirm(
                    "Press the Setup button on the hub so its light flashes, then tap Continue. " +
                        "The phone joins the hub's ${wifi.ssid} network for a moment to read its secret; Android will ask you to allow it.",
                    "Continue",
                ) {
                    gettingSecret = true
                    search = "Getting the secret from the hub…"
                    dev.wiserlocal.app.HubSetupWifi.fetchSecret(context, wifi) { result ->
                        gettingSecret = false
                        result.onSuccess {
                            secret = it
                            search = "Got the secret. Press Setup on the hub again to finish, then find the hub and save."
                            // Back on the home Wi-Fi in a moment; look for the hub's address if it isn't set.
                            if (host.isBlank()) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(::findHub, 4_000)
                        }.onFailure { search = it.message ?: "Couldn't get the secret from the hub" }
                    }
                }
            }
            .addOnFailureListener { search = "The QR scanner isn't available: ${it.message ?: "unknown error"}" }
    }

    DetailScaffold("Hub connection", snackbar, onBack = { nav.pop() }, loading = vm.loading) { padding ->
        SettingsColumn(padding) {
            FilledTonalButton(onClick = ::scanHubQr, enabled = !gettingSecret, modifier = Modifier.fillMaxWidth()) {
                Text("Scan the QR code on the hub")
            }
            Text(
                "Reads the hub's secret for you, using the code on the label on the hub. Or enter the details below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Hub address") },
                placeholder = { Text("192.168.1.50") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(onClick = ::findHub, enabled = stopSearch == null) { Text("Find hub on this network") }
            search?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("Hub secret") },
                singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "To get the secret by hand: press the Setup button on the hub so its light flashes, join the hub's " +
                    "own Wi-Fi network (named on its label, e.g. Wiser_… or WiserHeat…) from this phone, open " +
                    "http://192.168.8.1/secret/ in a browser and copy the text shown. Press Setup again to finish.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
                label = { Text("Hot water boost length (minutes)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    tested = true
                    vm.saveConnection(host, secret, minutes.toIntOrNull() ?: 60)
                    minutes = vm.prefs.boostMinutes.toString()
                },
                enabled = host.isNotBlank() && secret.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save and test connection") }

            val state = vm.state
            val status = when {
                !vm.configured -> "Enter both the hub address and the secret."
                vm.loading -> "Connecting…"
                vm.error != null -> vm.error
                state != null -> "Connected. Found ${state.rooms.size} room${if (state.rooms.size == 1) "" else "s"}."
                else -> null
            }
            if (status != null && (tested || vm.configured)) Text(status, style = MaterialTheme.typography.bodyLarge)

            if (state != null && state.rooms.isNotEmpty()) {
                val widgetRoom = state.rooms.firstOrNull { it.id == vm.prefs.widgetRoomId } ?: state.rooms.first()
                var shown by remember { mutableStateOf(widgetRoom.name) }
                Section("Home screen widget") {
                    InfoRow("Room shown on the widget", shown, showChevron = true) {
                        dialogs.choice("Widget room", state.rooms.map { it.name }, state.rooms.indexOf(widgetRoom)) { index ->
                            vm.setWidgetRoom(state.rooms[index].id)
                            shown = state.rooms[index].name
                        }
                    }
                }
            }
        }
    }
}

/**
 * Looks for a hub announcing itself on the local network. Hubs advertise an HTTP
 * service whose name contains "WiserHeat". Returns a function that stops the search.
 */
private fun discoverHub(
    nsd: NsdManager,
    onFound: (address: String, name: String) -> Unit,
    onFinished: (found: Boolean) -> Unit,
): () -> Unit {
    val handler = Handler(Looper.getMainLooper())
    var finished = false
    lateinit var listener: NsdManager.DiscoveryListener

    fun finish(found: Boolean, report: Boolean) {
        if (finished) return
        finished = true
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (e: IllegalArgumentException) {
            // Discovery never started, so there is nothing to stop.
        }
        if (report) onFinished(found)
    }

    listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(service: NsdServiceInfo) {
            if (!service.serviceName.contains("WiserHeat", ignoreCase = true)) return
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val address = info.host?.hostAddress ?: return
                    handler.post {
                        if (finished) return@post
                        onFound(address, info.serviceName)
                        finish(found = true, report = true)
                    }
                }
            })
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            handler.post { finish(found = false, report = true) }
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onServiceLost(service: NsdServiceInfo) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    nsd.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    handler.postDelayed({ finish(found = false, report = true) }, 8_000L)
    return { finish(found = false, report = false) }
}

@Composable
fun RoomsScreen(vm: HubViewModel, snackbar: SnackbarHostState) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    DetailScaffold("Rooms", snackbar, onBack = { nav.pop() }, loading = vm.loading) { padding ->
        SettingsColumn(padding) {
            val state = vm.state ?: return@SettingsColumn
            Section {
                state.rooms.forEachIndexed { index, room ->
                    if (index > 0) RowDivider()
                    InfoRow(room.name, deviceCount(state.devices.count { it.roomId == room.id }), showChevron = true) {
                        nav.push(Screen.RoomDetails(room.id))
                    }
                }
                if (state.rooms.isNotEmpty()) RowDivider()
                InfoRow("Add a room", "Creates an empty room on the hub.") {
                    dialogs.prompt("Add a room", "") { name -> vm.run("Room “$name” added") { it.createRoom(name) } }
                }
            }
        }
    }
}

private fun deviceCount(n: Int) = when (n) {
    0 -> "No devices"
    1 -> "1 device"
    else -> "$n devices"
}

@Composable
fun RoomDetailsScreen(vm: HubViewModel, snackbar: SnackbarHostState, roomId: Int) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    val room = vm.state?.rooms?.firstOrNull { it.id == roomId }
    DetailScaffold(room?.name ?: "Room", snackbar, onBack = { nav.pop() }, loading = vm.loading) { padding ->
        SettingsColumn(padding) {
            val state = vm.state ?: return@SettingsColumn
            if (room == null) {
                Text("This room is no longer on the hub.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@SettingsColumn
            }
            val heating = vm.schedules.filter { it.heating }
            val schedule = heating.firstOrNull { it.id == room.scheduleId }

            Section {
                InfoRow("Name", room.name, showChevron = true) {
                    dialogs.prompt("Rename ${room.name}", room.name) { name -> vm.run { it.renameRoom(room.id, name) } }
                }
                RowDivider()
                InfoRow("Schedule", schedule?.name?.ifEmpty { "Schedule" } ?: "No schedule", showChevron = true) {
                    val actions = buildList {
                        if (heating.isNotEmpty() && state.features.assignableSchedules) add("Change schedule" to {
                            dialogs.choice(
                                "Schedule for ${room.name}",
                                heating.map { it.name.ifEmpty { "Schedule" } },
                                heating.indexOfFirst { it.id == room.scheduleId },
                            ) { which ->
                                val chosen = heating[which]
                                if (chosen.id != room.scheduleId) {
                                    // The hub wants every room for the schedule, not just the new one.
                                    val users = state.rooms.filter { it.scheduleId == chosen.id }.map { it.id } + room.id
                                    vm.run { it.assignSchedule(chosen, users) }
                                }
                            }
                        })
                        if (room.scheduleId > 0) add("Edit schedule" to {
                            nav.push(Screen.ScheduleEditor(room.scheduleId, heating = true, title = "${room.name} schedule"))
                        })
                    }
                    when (actions.size) {
                        0 -> {}
                        1 -> actions[0].second()
                        else -> dialogs.choice("Schedule", actions.map { it.first }) { actions[it].second() }
                    }
                }
                if (state.features.openWindowDetection) room.windowDetection?.let { enabled ->
                    RowDivider()
                    SwitchRow(
                        "Open window detection",
                        if (room.windowOpen) "Window open" else "Window closed",
                        enabled,
                    ) { on -> vm.run { it.setWindowDetection(room.id, on) } }
                }
                if (state.features.roomHeatingType) room.heatingType?.let { current ->
                    RowDivider()
                    InfoRow("Heating type", heatingTypeLabel(current), showChevron = true) {
                        val types = ROOM_HEATING_TYPES.keys.toList()
                        dialogs.choice("Heating type", types.map { ROOM_HEATING_TYPES.getValue(it) }, types.indexOf(current)) { pick ->
                            if (types[pick] != current) vm.run { it.setRoomHeatingType(room.id, types[pick]) }
                        }
                    }
                }
            }

            // Hubs with more than one heating channel (relay): which one heats this room, as the
            // official app offers for radiator rooms. Hubs flagged RoomSupports2HeatChannels let a
            // room be on several channels.
            val channels = state.heatingChannels
            if (channels.size >= 2 && (room.heatingType == null || room.heatingType == "HydronicRadiator")) {
                val mine = channels.filter { room.id in it.roomIds }
                val several = state.features.flags["RoomSupports2HeatChannels"] == true
                Section("Heating channel") {
                    InfoRow(
                        "Channel",
                        mine.joinToString(", ") { it.name }.ifEmpty { "None" },
                        showChevron = true,
                    ) {
                        val choices: List<Pair<String, Set<Int>>> = channels.map { it.name to setOf(it.id) } +
                            listOfNotNull(if (several) "All channels" to channels.map { it.id }.toSet() else null) +
                            ("None" to emptySet<Int>())
                        val currentIds = mine.map { it.id }.toSet()
                        dialogs.choice("Heating channel for ${room.name}", choices.map { it.first }, choices.indexOfFirst { it.second == currentIds }) { pick ->
                            val target = choices[pick].second
                            if (target != currentIds) vm.run { it.setRoomChannels(channels, room.id, target) }
                        }
                    }
                }
            }

            room.occupancy?.let { occ ->
                Section("Occupancy") {
                    val offsets = (0..10).toList()
                    val minutes = (1..60).toList()
                    InfoRow("Lower by when empty", offsetLabel(occ.unoccupiedOffsetTenths / 10), showChevron = true) {
                        dialogs.choice("Lower by when empty", offsets.map(::offsetLabel), offsets.indexOf(occ.unoccupiedOffsetTenths / 10)) { pick ->
                            vm.run { it.setRoomOccupancy(room.id, occ.copy(unoccupiedOffsetTenths = offsets[pick] * 10)) }
                        }
                    }
                    RowDivider()
                    InfoRow("Empty after", minutesLabel(occ.delayMinutes), showChevron = true) {
                        dialogs.choice("Counts as empty after", minutes.map(::minutesLabel), minutes.indexOf(occ.delayMinutes)) { pick ->
                            vm.run { it.setRoomOccupancy(room.id, occ.copy(delayMinutes = minutes[pick])) }
                        }
                    }
                    RowDivider()
                    InfoRow("Occupied after", minutesLabel(occ.minOccupiedMinutes), showChevron = true) {
                        dialogs.choice("Counts as occupied after", minutes.map(::minutesLabel), minutes.indexOf(occ.minOccupiedMinutes)) { pick ->
                            vm.run { it.setRoomOccupancy(room.id, occ.copy(minOccupiedMinutes = minutes[pick])) }
                        }
                    }
                }
            }

            val devices = state.devices.filter { it.roomId == room.id }
            Section("Devices") {
                if (devices.isEmpty()) InfoRow("No devices")
                devices.forEachIndexed { index, device ->
                    if (index > 0) RowDivider()
                    InfoRow(
                        deviceName(device, withRoom = false),
                        deviceDetails(device).firstOrNull(),
                        showChevron = true,
                    ) { nav.push(Screen.DeviceDetails(device.id)) }
                }
            }

            Section {
                InfoRow("Delete room", "Removes the room from the hub. Its devices stay paired.") {
                    dialogs.confirm("Delete the room “${room.name}” from the hub? This cannot be undone.", "Delete") {
                        vm.run("Room “${room.name}” deleted", onSuccess = { nav.pop() }) { it.deleteRoom(room.id) }
                    }
                }
            }
        }
    }
}

/** The hub's room heating types, as the official app names them. */
private val ROOM_HEATING_TYPES = linkedMapOf(
    "HydronicRadiator" to "Radiators",
    "HydronicUFH" to "Underfloor (water)",
    "ElectricRadiator" to "Electric radiators",
    "ElectricUFH" to "Electric underfloor",
)

private fun heatingTypeLabel(type: String) = ROOM_HEATING_TYPES[type] ?: spaced(type)

private fun offsetLabel(degrees: Int) = if (degrees == 0) "Don't lower" else "$degrees°"

private fun minutesLabel(n: Int) = if (n == 1) "1 minute" else "$n minutes"

fun deviceName(device: Device, withRoom: Boolean = true): String {
    val type = when (device.type) {
        "RoomStat" -> "Room thermostat"
        "iTRV" -> "Radiator valve"
        "Controller" -> "Hub"
        else -> spaced(device.type)
    }
    // A name given in the official app (or here) replaces the type.
    val label = device.name ?: type
    return if (withRoom && device.roomName != null) "$label · ${device.roomName}" else label
}

/** Battery, signal, readings and firmware, one line each. */
fun deviceDetails(device: Device): List<String> = listOfNotNull(
    device.battery?.let {
        val volts = device.batteryVolts?.let { v -> String.format(Locale.getDefault(), " (%.1f V)", v) } ?: ""
        "Battery: ${spaced(it)}$volts"
    },
    device.signal?.let { "Signal: ${spaced(it)}" + (device.rssi?.let { r -> " ($r dBm)" } ?: "") },
    device.tempC?.let { temp ->
        val target = device.setPointC?.let { " · target ${WiserHub.formatSetPoint(it)}" } ?: ""
        "Reads ${WiserHub.formatTemp(temp)}$target"
    },
    device.demandPercent?.let { "Demand: $it%" },
    device.firmware?.let { "Firmware: $it" },
)

@Composable
fun DevicesScreen(vm: HubViewModel, snackbar: SnackbarHostState) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    val context = LocalContext.current
    DetailScaffold("Devices", snackbar, onBack = { nav.pop() }, loading = vm.loading) { padding ->
        SettingsColumn(padding) {
            val state = vm.state ?: return@SettingsColumn
            Section {
                state.devices.forEachIndexed { index, device ->
                    if (index > 0) RowDivider()
                    InfoRow(
                        deviceName(device),
                        deviceDetails(device).filterNot { it.startsWith("Firmware") }.take(2).joinToString("\n"),
                        showChevron = true,
                    ) { nav.push(Screen.DeviceDetails(device.id)) }
                }
            }
            Section {
                InfoRow("Add a device", "Lets a new thermostat or radiator valve join the hub.", showChevron = true) {
                    dialogs.confirm(
                        "Open the hub for new devices for two minutes? Put the new device into pairing mode straight after.",
                        "Start",
                    ) { vm.run("The hub is accepting new devices for two minutes") { it.permitJoin(120) } }
                }
                RowDivider()
                InfoRow("Add a device with an install code", "Scan the QR code on devices that have one, such as newer radiator valves.", showChevron = true) {
                    fun pair(found: dev.wiserlocal.app.InstallCode) {
                        dialogs.confirm(
                            "Add the device ${found.address}? Put it into pairing mode, then tap Add. The hub waits two minutes for it.",
                            "Add",
                        ) { vm.run("The hub is waiting for that device for two minutes") { it.pairWithInstallCode(found.address, found.code) } }
                    }
                    fun byHand() {
                        dialogs.prompt("Device address (16 characters)", "") { address ->
                            dialogs.prompt("Install code (36 characters)", "") { code ->
                                if (!dev.wiserlocal.app.InstallCodeQr.isValid(address, code)) {
                                    dialogs.message(
                                        "Install code",
                                        "The address should be 16 and the install code 36 letters and numbers (0–9, A–F), as printed on the device.",
                                    )
                                } else {
                                    pair(dev.wiserlocal.app.InstallCode(dev.wiserlocal.app.InstallCodeQr.clean(address), dev.wiserlocal.app.InstallCodeQr.clean(code)))
                                }
                            }
                        }
                    }
                    dialogs.choice("Add a device with an install code", listOf("Scan the device's QR code", "Type the code by hand")) { pick ->
                        if (pick == 1) return@choice byHand()
                        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(
                                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE,
                                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_DATA_MATRIX,
                            )
                            .build()
                        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options).startScan()
                            .addOnSuccessListener { barcode ->
                                val found = barcode.rawValue?.let(dev.wiserlocal.app.InstallCodeQr::parse)
                                if (found != null) pair(found)
                                else dialogs.message("Install code", "That code doesn't hold a device address and install code. Try another code on the device or its box, or type it by hand.")
                            }
                            .addOnFailureListener { e -> vm.message = "The QR scanner isn't available: ${e.message ?: "unknown error"}" }
                    }
                }
                RowDivider()
                InfoRow("Stop adding devices", "Closes the hub to new devices straight away.") {
                    vm.run("The hub is no longer accepting new devices") { it.stopPairing() }
                }
            }
        }
    }
}

@Composable
fun DeviceDetailsScreen(vm: HubViewModel, snackbar: SnackbarHostState, deviceId: Int) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    val device = vm.state?.devices?.firstOrNull { it.id == deviceId }
    DetailScaffold(device?.let { deviceName(it, withRoom = false) } ?: "Device", snackbar, onBack = { nav.pop() }, loading = vm.loading) { padding ->
        SettingsColumn(padding) {
            val state = vm.state ?: return@SettingsColumn
            if (device == null) {
                Text("This device is no longer on the hub.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@SettingsColumn
            }
            val name = deviceName(device, withRoom = false)
            val isHub = device.type == "Controller"

            Section {
                InfoRow(name, deviceDetails(device).filterNot { it.startsWith("Firmware") }.joinToString("\n").ifEmpty { null })
            }

            val lock = device.locked?.takeIf { state.features.deviceLock }
            if (!isHub || lock != null) Section("Options") {
                if (!isHub) {
                    InfoRow("Name", device.name ?: "Not named", showChevron = true) {
                        dialogs.prompt("Name", device.name ?: "") { newName ->
                            vm.run { it.renameDevice(device.id, newName.trim()) }
                        }
                    }
                    RowDivider()
                    InfoRow("Location", device.roomName ?: "No room", showChevron = true) {
                        val rooms = state.rooms
                        val options = rooms.map { it.name } + "No room"
                        val current = rooms.indexOfFirst { it.id == device.roomId }.takeIf { it >= 0 } ?: rooms.size
                        dialogs.choice("Location", options, current) { picked ->
                            val target = rooms.getOrNull(picked)
                            if (target?.id == device.roomId) return@choice
                            // A room has one room thermostat; don't silently push another one out.
                            val otherStat = target?.let { t ->
                                state.devices.firstOrNull { it.type == "RoomStat" && it.roomId == t.id && it.id != device.id }
                            }
                            if (device.type == "RoomStat" && otherStat != null) {
                                dialogs.message("Room thermostat", "${target.name} already has a room thermostat. Move that one out of the room first.")
                                return@choice
                            }
                            val done = target?.let { "$name moved to ${it.name}" } ?: "$name taken out of ${device.roomName}"
                            vm.run(done) { it.moveDevice(state, device, target?.id) }
                        }
                    }
                }
                lock?.let { locked ->
                    if (!isHub) RowDivider()
                    SwitchRow("Device lock", "Locks the buttons on the device.", locked) { on ->
                        vm.run { it.setDeviceLock(device.id, on) }
                    }
                }
                if (device.type == "iTRV") device.batteryType?.let { type ->
                    RowDivider()
                    val types = listOf("Alkaline", "Rechargeable (NiMH)")
                    InfoRow("Battery type", types.getOrElse(type) { "Unknown" }, showChevron = true) {
                        dialogs.choice("Battery type", types, type) { pick ->
                            if (pick != type) vm.run { it.setBatteryType(device.id, pick) }
                        }
                    }
                }
            }

            device.firmware?.let { firmware ->
                Section("About") { InfoRow("Firmware version", firmware) }
            }

            if (!isHub) Section {
                InfoRow("Identify", "Makes the device flash so you can find it.", showChevron = true) { vm.identify(device) }
                RowDivider()
                InfoRow("Delete", "Removes the device from the hub. It has to be paired again to come back.") {
                    dialogs.confirm("Remove this ${name.lowercase()} from the hub? It has to be paired again to come back.", "Delete") {
                        vm.run("$name removed", onSuccess = { nav.pop() }) { it.removeDevice(device.id) }
                    }
                }
            }
        }
    }
}

@Composable
fun AwayScreen(vm: HubViewModel, snackbar: SnackbarHostState) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    DetailScaffold("Away mode", snackbar, onBack = { nav.pop() }, loading = vm.loading) { padding ->
        SettingsColumn(padding) {
            val system = vm.state?.system ?: return@SettingsColumn
            Section {
                SwitchRow("Away mode", "Holds every room at the away temperature until switched off.", system.away) { on ->
                    vm.run { it.setAway(on) }
                }
                system.awayTempC?.let { temp ->
                    RowDivider()
                    InfoRow("Away temperature", WiserHub.formatTemp(temp), showChevron = true) {
                        dialogs.temperature("Away temperature", (temp * 10).toInt()) { tenths ->
                            vm.run { it.setAwayTemp(tenths / 10.0) }
                        }
                    }
                }
                system.awayAffectsHotWater?.let { enabled ->
                    RowDivider()
                    SwitchRow("Turn off hot water too", "When on, hot water is also switched off while away mode is on.", enabled) { on ->
                        vm.run { it.setAwayAffectsHotWater(on) }
                    }
                }
            }
        }
    }
}

@Composable
fun SmartModesScreen(vm: HubViewModel, snackbar: SnackbarHostState) {
    val nav = LocalNav.current
    DetailScaffold("Smart modes", snackbar, onBack = { nav.pop() }, loading = vm.loading) { padding ->
        SettingsColumn(padding) {
            val state = vm.state ?: return@SettingsColumn
            val system = state.system
            if (system.ecoMode != null || system.comfortMode != null) {
                Section {
                    system.ecoMode?.let { enabled ->
                        SwitchRow("Eco mode", "Lets the hub stop heating early when a room will coast to its target.", enabled) { on ->
                            vm.run { it.setEcoMode(on) }
                        }
                    }
                    system.comfortMode?.let { enabled ->
                        if (system.ecoMode != null) RowDivider()
                        SwitchRow("Comfort mode", "Starts heating early so rooms reach their target at the scheduled time.", enabled) { on ->
                            vm.run { it.setComfortMode(on) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SystemSettingsScreen(vm: HubViewModel, snackbar: SnackbarHostState) {
    val nav = LocalNav.current
    val dialogs = LocalDialogs.current
    DisposableEffect(Unit) {
        vm.watchSystem(true)
        onDispose { vm.watchSystem(false) }
    }
    DetailScaffold("System settings", snackbar, onBack = { nav.pop() }, loading = vm.loading) { padding ->
        SettingsColumn(padding) {
            val state = vm.state ?: return@SettingsColumn
            val system = state.system
            Section("Hub") {
                val rows = buildList<HubRow> {
                    add(HubRow("Firmware", system.firmware))
                    add(HubRow("Cloud connection", system.cloudConnected?.let { if (it) "Connected" else "Not connected" }))
                    vm.network.forEach { (label, value) -> add(HubRow(label, value)) }
                    system.boiler.forEach { (key, value) ->
                        val (label, shown) = boilerRow(key, value)
                        val onClick: (() -> Unit)? = when (key) {
                            "FuelType" -> {
                                {
                                    // Heat pumps need firmware 3.3.3; keep the option if the hub already uses it.
                                    val sources = HEAT_SOURCES.filter { it.first != "HeatPump" || state.features.heatPump || it.first == value }
                                    dialogs.choice("Heat source type", sources.map { it.second }, sources.indexOfFirst { it.first == value }.takeIf { it >= 0 }) { i ->
                                        val picked = sources[i].first
                                        if (picked != value) vm.run { it.setFuelType(picked) }
                                    }
                                }
                            }
                            "ControlType" -> {
                                {
                                    // OpenTherm needs firmware 2.29.0; keep the option if the hub already uses it.
                                    val types = BOILER_TYPES.filter { !it.first.contains("OpenTherm") || state.features.openTherm || it.first == value }
                                    dialogs.choice("Boiler type", types.map { it.second }, types.indexOfFirst { it.first == value }.takeIf { it >= 0 }) { i ->
                                        val picked = types[i].first
                                        if (picked != value) dialogs.confirm(
                                            "Change the boiler type to ${types[i].second}? Pick OpenTherm only if the boiler is wired to the hub's " +
                                                "OpenTherm terminals, and Standard only if it is wired to the relay; the wrong one can stop the heating.",
                                            "Change",
                                        ) { vm.run { it.setBoilerControlType(picked) } }
                                    }
                                }
                            }
                            else -> null
                        }
                        // The official app flags OpenTherm when the boiler doesn't answer over it.
                        val unanswered = key == "ControlType" && system.usesOpenTherm && system.openTherm != "Connected"
                        add(HubRow(label, if (unanswered) "$shown · the boiler is not answering over OpenTherm" else shown, onClick, warning = unanswered))
                    }
                    add(HubRow("Brand", system.brand))
                    add(HubRow("OpenTherm link to boiler", system.openTherm))
                }.filter { it.value != null }
                rows.forEachIndexed { index, row ->
                    if (index > 0) RowDivider()
                    InfoRow(
                        row.label,
                        row.value,
                        icon = if (row.warning) rememberVectorPainter(Icons.Default.Warning) else null,
                        iconTint = MaterialTheme.colorScheme.error,
                        showChevron = row.onClick != null,
                        onClick = row.onClick,
                    )
                }
            }
            if (system.zigbee.isNotEmpty()) Section("Zigbee network") {
                system.zigbee.forEachIndexed { i, (label, value) ->
                    if (i > 0) RowDivider()
                    InfoRow(label, value)
                }
            }
            Section("Firmware and restart") {
                system.autoFirmwareUpdates?.let { enabled ->
                    SwitchRow("Automatic firmware updates", "The hub installs new firmware by itself when it is released.", enabled) { on ->
                        vm.run { it.setAutoFirmwareUpdates(on) }
                    }
                    RowDivider()
                }
                InfoRow("Restart the hub", "It stops answering for a few minutes while it starts up again.", showChevron = true) {
                    dialogs.confirm(
                        "Restart the hub? It stops answering for a few minutes while it starts up again.",
                        "Restart",
                    ) { vm.run("The hub is restarting") { it.restartHub() } }
                }
            }
            system.valveProtection?.let { enabled ->
                Section {
                    SwitchRow("Valve protection", "Opens and closes radiator valves now and then so they do not seize.", enabled) { on ->
                        vm.run { it.setValveProtection(on) }
                    }
                }
            }
            val legacyPowerTag = system.legacyPowerTagMode?.takeIf { state.features.legacyPowerTagMode }
            if (system.hotWaterChannel != null || legacyPowerTag != null) {
                Section("Channels") {
                    system.hotWaterChannel?.let { enabled ->
                        // The official app greys this out when an Aidoo Pro heat pump is paired.
                        val aidoo = vm.state?.devices.orEmpty().any { it.type.contains("Aidoo", ignoreCase = true) }
                        SwitchRow(
                            "Hot water channel",
                            if (aidoo) "Not available while an Aidoo Pro heat pump is paired."
                            else "Turn off if the hub doesn't control a hot water cylinder.",
                            enabled,
                            enabled = !aidoo,
                        ) { on ->
                            if (on) vm.run { it.setHotWaterChannel(true) }
                            else dialogs.confirm(
                                "Turn off the hot water channel? The hub will stop controlling hot water, delete the hot water schedule " +
                                    "and remove hot water from all automations. Turning it back on does not bring them back.",
                                "Turn off",
                            ) { vm.run { it.setHotWaterChannel(false) } }
                        }
                    }
                    legacyPowerTag?.let { enabled ->
                        if (system.hotWaterChannel != null) RowDivider()
                        SwitchRow(
                            "Legacy PowerTag energy mode",
                            "Lets older PowerTag energy meters send their readings to the hub.",
                            enabled,
                        ) { on -> vm.run { it.setLegacyPowerTagMode(on) } }
                    }
                }
            }
            if (system.timeZoneId != null || system.timeZoneOffset != null || system.hubTime != null) {
                Section("Hub clock") {
                    val rows = mutableListOf<@Composable () -> Unit>()
                    system.timeZoneId?.let { zone ->
                        rows += {
                            InfoRow("Time zone", "$zone · sets when summer time starts and ends", showChevron = true) {
                                pickTimeZone(dialogs, zone) { picked -> vm.run("Time zone set to $picked") { it.setTimeZone(picked) } }
                            }
                        }
                    }
                    system.timeZoneOffset?.let { offset ->
                        rows += {
                            InfoRow("Time zone offset", "$offset minutes from UTC", showChevron = true) {
                                dialogs.prompt("Offset from UTC in minutes (0 for the UK)", offset.toString(), numeric = true) { text ->
                                    val value = text.toIntOrNull()
                                    if (value != null && value in -720..840) vm.run { it.setTimeZoneOffset(value) }
                                }
                            }
                        }
                    }
                    rows += {
                        val context = LocalContext.current
                        val shown = system.hubTime?.let { "The hub's clock said ${context.clockText(it)} at the last refresh. " } ?: ""
                        InfoRow("Set the hub clock", "${shown}Sets it to this phone's time.", showChevron = true) {
                            dialogs.confirm("Set the hub clock to this phone's time?", "Set clock") {
                                vm.run("Hub clock set") { it.setHubClock(System.currentTimeMillis() / 1000) }
                            }
                        }
                    }
                    rows.forEachIndexed { i, row -> if (i > 0) RowDivider(); row() }
                }
            }
            if (system.usesOpenTherm) {
                val ot = vm.openTherm
                Section("OpenTherm settings") {
                    Text(
                        "Changes made on the boiler's front panel can take up to a minute to show here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    RowDivider()
                    OpenThermRow("Hot water temperature", "Domestic hot water setpoint", ot.hotWater) { tempC ->
                        vm.run { it.setOpenThermHotWater(tempC) }
                    }
                    RowDivider()
                    OpenThermRow("Maximum heating water temperature", "Max. central heating water temperature", ot.maxHeating) { tempC ->
                        vm.run { it.setOpenThermMaxHeating(tempC) }
                    }
                }
            }
            if (vm.openTherm.readings.isNotEmpty()) {
                Section("Boiler") {
                    vm.openTherm.readings.forEachIndexed { index, (label, value) ->
                        if (index > 0) RowDivider()
                        InfoRow(label, value)
                    }
                }
            }
        }
    }
}

/** One OpenTherm temperature setting; tap to change it within the boiler's range. */
@Composable
private fun OpenThermRow(title: String, dialogTitle: String, setpoint: OpenThermSetpoint, onSet: (Double) -> Unit) {
    val dialogs = LocalDialogs.current
    val min = (setpoint.minC * 10).roundToInt()
    val max = (setpoint.maxC * 10).roundToInt()
    InfoRow(title, setpoint.valueC?.let { WiserHub.formatTemp(it) } ?: "Not reported by the boiler", showChevron = true) {
        val start = ((setpoint.valueC ?: setpoint.minC) * 10).roundToInt().coerceIn(min, max)
        dialogs.temperature(dialogTitle, start, minTenths = min, maxTenths = max) { tenths ->
            if (tenths != start || setpoint.valueC == null) onSet(tenths / 10.0)
        }
    }
}

private class HubRow(val label: String, val value: String?, val onClick: (() -> Unit)? = null, val warning: Boolean = false)

private val BOILER_FUELS = setOf("Gas", "Oil", "Electric", "SolidFuel", "Biomass")

/** The heat source types the official app offers, as (hub value, label). */
private val HEAT_SOURCES = listOf(
    "Gas" to "Gas boiler",
    "Oil" to "Oil boiler",
    "Electric" to "Electric boiler",
    "HeatPump" to "Heat pump",
)

/** The boiler types the official app offers, as (hub ControlType, label). */
private val BOILER_TYPES = listOf(
    "HeatSourceType_RelayControlled" to "Standard",
    "HeatSourceType_OpenTherm" to "OpenTherm",
)

/** Labels a boiler setting the way the official app does. */
private fun boilerRow(key: String, value: String): Pair<String, String> {
    // The official app calls the fuel type "Heat Source Type".
    val fuel = key == "FuelType"
    // "Boiler Type" there is the control type: relay ("Standard") or OpenTherm.
    val control = key.contains("ControlType")
    val shown = when {
        control && value.endsWith("RelayControlled") -> "Standard"
        control && value.endsWith("OpenThermOnOff") -> "OpenTherm (on/off)"
        control && value.endsWith("OpenTherm") -> "OpenTherm"
        key == "CycleRate" && value.startsWith("CPH_") -> "${value.removePrefix("CPH_")} cycles an hour"
        value == "true" -> "Yes"
        value == "false" -> "No"
        fuel && value in BOILER_FUELS -> "${spaced(value)} boiler"
        else -> spaced(value)
    }
    val label = when {
        fuel -> "Heat source type"
        control -> "Boiler type"
        else -> spaced(key)
    }
    return label to shown
}

/** Pick which rooms and hot water show on Home, and their order. */
@Composable
fun HomeLayoutScreen(vm: HubViewModel, snackbar: SnackbarHostState) {
    val nav = LocalNav.current
    DetailScaffold("Home screen", snackbar, onBack = { nav.pop() }, loading = vm.loading) { padding ->
        SettingsColumn(padding) {
            val state = vm.state ?: return@SettingsColumn
            val all = state.rooms.map { "room:${it.id}" } + listOfNotNull(state.hotWater?.let { "hotwater" })
            val chosen = favouriteKeys(vm, state).filter { it in all }
            val ordered = chosen + all.filter { it !in chosen }
            fun label(key: String) = if (key == "hotwater") "Hot water" else state.rooms.first { "room:${it.id}" == key }.name

            Text(
                "Ticked items show on the Home tab in this order.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Section("Favourites") {
                ordered.forEachIndexed { index, key ->
                    if (index > 0) RowDivider()
                    val on = key in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.saveFavourites(if (on) chosen - key else chosen + key) }
                            .padding(start = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { vm.saveFavourites(if (on) chosen - key else chosen + key) })
                        Icon(
                            if (key == "hotwater") waterIcon() else flameIcon(),
                            contentDescription = null,
                            tint = if (key == "hotwater") LocalAccents.current.water else LocalAccents.current.heat,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label(key), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        val position = chosen.indexOf(key)
                        IconButton(
                            onClick = { vm.saveFavourites(chosen.toMutableList().apply { add(position - 1, removeAt(position)) }) },
                            enabled = on && position > 0,
                        ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up") }
                        IconButton(
                            onClick = { vm.saveFavourites(chosen.toMutableList().apply { add(position + 1, removeAt(position)) }) },
                            enabled = on && position < chosen.size - 1,
                        ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down") }
                    }
                }
            }
        }
    }
}

@Composable
fun ThemeScreen(vm: HubViewModel, snackbar: SnackbarHostState) {
    val nav = LocalNav.current
    DetailScaffold("App theme", snackbar, onBack = { nav.pop() }) { padding ->
        SettingsColumn(padding) {
            Section {
                listOf("system", "light", "dark").forEachIndexed { index, value ->
                    if (index > 0) RowDivider()
                    InfoRow(
                        themeLabel(value),
                        trailing = { RadioButton(selected = vm.theme == value, onClick = { vm.chooseTheme(value) }) },
                    ) { vm.chooseTheme(value) }
                }
            }
        }
    }
}

/** Picks an IANA time zone: this phone's zone first, then a region, then a zone in it. */
private fun pickTimeZone(dialogs: DialogHost, current: String, onPick: (String) -> Unit) {
    val zones = java.time.ZoneId.getAvailableZoneIds()
        .filter { id -> id.substringBefore('/', "") in TZ_REGIONS }
        .sorted()
    val phone = java.time.ZoneId.systemDefault().id
    val regions = TZ_REGIONS.filter { r -> zones.any { it.startsWith("$r/") } }
    val options = listOf("This phone's time zone ($phone)") + regions
    dialogs.choice("Time zone (now $current)", options) { index ->
        if (index == 0) onPick(phone)
        else {
            val inRegion = zones.filter { it.startsWith("${regions[index - 1]}/") }
            dialogs.choice(regions[index - 1], inRegion.map { it.substringAfter('/').replace('_', ' ') },
                selected = inRegion.indexOf(current).takeIf { it >= 0 }) { onPick(inRegion[it]) }
        }
    }
}

private val TZ_REGIONS = listOf("Europe", "America", "Africa", "Asia", "Atlantic", "Australia", "Indian", "Pacific", "Antarctica")
