package dev.wiserlocal.app

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

/** System-wide switches, hub status, device health and (if available) boiler data. */
class SystemActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_system)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        list = findViewById(R.id.system_list)
        status = findViewById(R.id.system_status)
        load()
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** Runs [action] against the hub (if given), then reloads everything. */
    private fun load(action: ((WiserHub) -> Unit)? = null) {
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
                // Boiler readings only exist when the hub is wired over OpenTherm.
                val boiler = if (state.system.openTherm == "Connected") {
                    try { hub.fetchOpenTherm() } catch (e: Exception) { emptyList() }
                } else {
                    emptyList()
                }
                Result.success(state to boiler)
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { (state, boiler) -> show(state, boiler) },
                    onFailure = { status.text = getString(R.string.error, it.message ?: it.javaClass.simpleName) },
                )
            }
        }
    }

    private fun show(state: HubState, boiler: List<Pair<String, String>>) {
        list.removeAllViews()
        status.text = ""
        val system = state.system

        header(R.string.sys_controls)
        toggle(getString(R.string.away_mode), getString(R.string.away_mode_help), system.away) { hub, on ->
            hub.setAway(on)
        }
        system.ecoMode?.let {
            toggle(getString(R.string.eco_mode), getString(R.string.eco_mode_help), it) { hub, on -> hub.setEcoMode(on) }
        }
        system.comfortMode?.let {
            toggle(getString(R.string.comfort_mode), getString(R.string.comfort_mode_help), it) { hub, on ->
                hub.setComfortMode(on)
            }
        }

        val windowRooms = state.rooms.filter { it.windowDetection != null }
        if (windowRooms.isNotEmpty()) {
            header(R.string.sys_window_detection)
            for (room in windowRooms) {
                val help = getString(if (room.windowOpen) R.string.window_open else R.string.window_closed)
                toggle(room.name, help, room.windowDetection == true) { hub, on ->
                    hub.setWindowDetection(room.id, on)
                }
            }
        }

        header(R.string.sys_hub)
        info(getString(R.string.sys_firmware), system.firmware)
        info(
            getString(R.string.sys_cloud),
            system.cloudConnected?.let { getString(if (it) R.string.connected_yes else R.string.connected_no) },
        )
        info(getString(R.string.sys_fuel), system.fuelType)
        info(getString(R.string.sys_brand), system.brand)
        info(getString(R.string.sys_opentherm), system.openTherm)

        if (state.devices.isNotEmpty()) {
            header(R.string.sys_devices)
            for (device in state.devices) {
                val name = listOfNotNull(deviceLabel(device.type), device.roomName).joinToString(" · ")
                val details = listOfNotNull(
                    device.battery?.let {
                        val volts = device.batteryVolts?.let { v -> String.format(Locale.getDefault(), " (%.1f V)", v) }
                        getString(R.string.device_battery, spaced(it) + (volts ?: ""))
                    },
                    device.signal?.let { getString(R.string.device_signal, spaced(it)) },
                    device.firmware?.let { getString(R.string.device_firmware, it) },
                )
                info(name, details.joinToString("\n").ifEmpty { null })
            }
        }

        if (boiler.isNotEmpty()) {
            header(R.string.sys_boiler)
            boiler.forEach { (label, value) -> info(label, value) }
        }
    }

    private fun header(textRes: Int) {
        val view = layoutInflater.inflate(R.layout.row_header, list, false) as TextView
        view.setText(textRes)
        list.addView(view)
    }

    private fun info(label: String, value: String?) {
        if (value == null) return
        val row = layoutInflater.inflate(R.layout.row_info, list, false)
        row.findViewById<TextView>(R.id.info_label).text = label
        row.findViewById<TextView>(R.id.info_value).text = value
        list.addView(row)
    }

    private fun toggle(label: String, help: String, checked: Boolean, onChange: (WiserHub, Boolean) -> Unit) {
        val row = layoutInflater.inflate(R.layout.row_switch, list, false)
        row.findViewById<TextView>(R.id.switch_help).text = help
        row.findViewById<Switch>(R.id.switch_toggle).apply {
            text = label
            isChecked = checked
            setOnCheckedChangeListener { _, on -> load { onChange(it, on) } }
        }
        list.addView(row)
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
}
