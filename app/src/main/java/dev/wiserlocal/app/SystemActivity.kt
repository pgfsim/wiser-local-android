package dev.wiserlocal.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

/** System-wide switches, hub status, device health and (if available) boiler data. */
class SystemActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()

    /** Device currently flashing because of Identify, if any. */
    private var identifying: Int? = null

    private lateinit var rows: Rows
    private lateinit var status: TextView

    private class Loaded(
        val state: HubState,
        val network: List<Pair<String, String>>,
        val boiler: List<Pair<String, String>>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_system)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        rows = Rows(this, findViewById(R.id.system_list))
        status = findViewById(R.id.system_status)
        load()
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        stopIdentify()
        io.shutdown()
        super.onDestroy()
    }

    /** Runs [action] against the hub (if given), then reloads everything. */
    private fun load(toast: Int? = null, action: ((WiserHub) -> Unit)? = null) {
        val prefs = Prefs(this)
        if (!prefs.configured) {
            status.setText(R.string.not_configured)
            return
        }
        val hub = prefs.hub()
        status.setText(R.string.loading)
        io.execute {
            val result = try {
                if (action != null) {
                    action(hub)
                    Thread.sleep(400) // give the hub a moment to apply the change
                }
                val state = hub.fetch()
                val network = try { hub.fetchNetwork() } catch (e: Exception) { emptyList() }
                // Boiler readings only exist when the hub is wired over OpenTherm.
                val boiler = if (state.system.openTherm == "Connected") {
                    try { hub.fetchOpenTherm() } catch (e: Exception) { emptyList() }
                } else {
                    emptyList()
                }
                Result.success(Loaded(state, network, boiler))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = {
                        show(it)
                        if (toast != null) Toast.makeText(this, toast, Toast.LENGTH_LONG).show()
                    },
                    onFailure = { status.text = getString(R.string.error, it.message ?: it.javaClass.simpleName) },
                )
            }
        }
    }

    private fun show(loaded: Loaded) {
        val state = loaded.state
        val system = state.system
        rows.clear()
        status.text = ""

        rows.header(R.string.sys_controls)
        rows.toggle(getString(R.string.away_mode), getString(R.string.away_mode_help), system.away) { on ->
            load { it.setAway(on) }
        }
        system.awayTempC?.let { temp ->
            rows.info(getString(R.string.away_temp), getString(R.string.tap_to_change, WiserHub.formatTemp(temp))) {
                pickFromWheel(
                    getString(R.string.away_temp),
                    PICKER_TEMPS.map { WiserHub.formatTemp(it / 10.0) },
                    PICKER_TEMPS.indexOfFirst { it >= (temp * 10).toInt() },
                ) { index -> load { it.setAwayTemp(PICKER_TEMPS[index] / 10.0) } }
            }
        }
        system.awayAffectsHotWater?.let { enabled ->
            rows.toggle(getString(R.string.away_hot_water), getString(R.string.away_hot_water_help), enabled) { on ->
                load { it.setAwayAffectsHotWater(on) }
            }
        }
        system.ecoMode?.let { enabled ->
            rows.toggle(getString(R.string.eco_mode), getString(R.string.eco_mode_help), enabled) { on ->
                load { it.setEcoMode(on) }
            }
        }
        system.comfortMode?.let { enabled ->
            rows.toggle(getString(R.string.comfort_mode), getString(R.string.comfort_mode_help), enabled) { on ->
                load { it.setComfortMode(on) }
            }
        }
        system.valveProtection?.let { enabled ->
            rows.toggle(getString(R.string.valve_protection), getString(R.string.valve_protection_help), enabled) { on ->
                load { it.setValveProtection(on) }
            }
        }

        val windowRooms = state.rooms.filter { it.windowDetection != null }
        if (windowRooms.isNotEmpty()) {
            rows.header(R.string.sys_window_detection)
            for (room in windowRooms) {
                val help = getString(if (room.windowOpen) R.string.window_open else R.string.window_closed)
                rows.toggle(room.name, help, room.windowDetection == true) { on ->
                    load { it.setWindowDetection(room.id, on) }
                }
            }
        }

        if (state.devices.isNotEmpty()) {
            rows.header(R.string.sys_devices)
            for (device in state.devices) {
                val name = listOfNotNull(deviceLabel(device.type), device.roomName).joinToString(" · ")
                val details = listOfNotNull(
                    device.battery?.let {
                        val volts = device.batteryVolts?.let { v -> String.format(Locale.getDefault(), " (%.1f V)", v) }
                        getString(R.string.device_battery, spaced(it) + (volts ?: ""))
                    },
                    device.signal?.let { getString(R.string.device_signal, spaced(it)) },
                    device.firmware?.let { getString(R.string.device_firmware, it) },
                    if (device.type != "Controller") getString(R.string.tap_to_identify) else null,
                )
                val onClick = if (device.type != "Controller") ({ identify(device, name) }) else null
                rows.info(name, details.joinToString("\n").ifEmpty { null }, onClick)
                device.locked?.let { locked ->
                    rows.toggle(getString(R.string.device_lock), getString(R.string.device_lock_help, name), locked) { on ->
                        load { it.setDeviceLock(device.id, on) }
                    }
                }
            }
            rows.info(getString(R.string.add_device), getString(R.string.add_device_help)) {
                confirm(getString(R.string.add_device_confirm), R.string.add_device_start) {
                    load(toast = R.string.add_device_started) { it.permitJoin(PERMIT_JOIN_SECONDS) }
                }
            }
        }

        rows.header(R.string.sys_hub)
        rows.info(getString(R.string.sys_firmware), system.firmware)
        rows.info(
            getString(R.string.sys_cloud),
            system.cloudConnected?.let { getString(if (it) R.string.connected_yes else R.string.connected_no) },
        )
        loaded.network.forEach { (label, value) -> rows.info(label, value) }
        system.boiler.forEach { (key, value) ->
            val shown = when (value) {
                "true" -> getString(R.string.yes)
                "false" -> getString(R.string.no)
                else -> spaced(value)
            }
            rows.info(spaced(key), shown)
        }
        rows.info(getString(R.string.sys_brand), system.brand)
        rows.info(getString(R.string.sys_opentherm), system.openTherm)

        if (system.autoDaylightSaving != null || system.timeZoneOffset != null) {
            rows.header(R.string.sys_clock)
            system.autoDaylightSaving?.let { enabled ->
                rows.toggle(getString(R.string.daylight_saving), getString(R.string.daylight_saving_help), enabled) { on ->
                    load { it.setAutoDaylightSaving(on) }
                }
            }
            system.timeZoneOffset?.let { offset ->
                rows.info(getString(R.string.time_zone), getString(R.string.tap_to_change, offset.toString())) {
                    promptText(getString(R.string.time_zone_prompt), offset.toString(), numeric = true) { text ->
                        val value = text.toIntOrNull()
                        if (value != null && value in -720..840) load { it.setTimeZoneOffset(value) }
                    }
                }
            }
        }

        if (loaded.boiler.isNotEmpty()) {
            rows.header(R.string.sys_boiler)
            loaded.boiler.forEach { (label, value) -> rows.info(label, value) }
        }
    }

    /**
     * Makes the device flash until the dialog is closed, like the official app.
     * The stop is also sent if the screen is left while it is still flashing.
     */
    private fun identify(device: Device, name: String) {
        val hub = Prefs(this).hub()
        io.execute {
            val error = try {
                hub.identifyDevice(device.id, true)
                null
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (error != null) {
                    status.text = getString(R.string.error, error.message ?: error.javaClass.simpleName)
                    return@runOnUiThread
                }
                identifying = device.id
                AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage(R.string.device_identifying)
                    .setPositiveButton(R.string.done, null)
                    .setOnDismissListener { stopIdentify() }
                    .show()
            }
        }
    }

    private fun stopIdentify() {
        val deviceId = identifying ?: return
        identifying = null
        if (io.isShutdown) return
        val hub = Prefs(this).hub()
        io.execute {
            try {
                hub.identifyDevice(deviceId, false)
            } catch (e: Exception) {
                // Nothing useful to do; the device stops flashing by itself after a while.
            }
        }
    }

    private fun deviceLabel(type: String): String = when (type) {
        "RoomStat" -> getString(R.string.device_roomstat)
        "iTRV" -> getString(R.string.device_trv)
        "Controller" -> getString(R.string.device_hub)
        else -> spaced(type)
    }

    /** "TwoThirds" -> "Two thirds", for the hub's run-together labels. */
    private fun spaced(value: String): String =
        value.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        private const val PERMIT_JOIN_SECONDS = 120
    }
}
