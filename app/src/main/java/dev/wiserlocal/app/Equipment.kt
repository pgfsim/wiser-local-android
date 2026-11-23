package dev.wiserlocal.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Settings and controls for hardware beyond thermostats and valves (lights, shutters,
 * plugs, smoke alarms, EV chargers, energy meters, underfloor heating, heating
 * actuators, sensors, fans, heat pumps), plus the system and room settings that go
 * with them (summer comfort, cooling, underfloor). Each control is described once
 * here with the request the official app sends (from its ControllerService), and a
 * generic screen draws it from the hub's own data.
 *
 * Paths are relative to /data/v2/domain/ unless they start with "/" (then /data/v2).
 * Bodies are sent exactly as the official app does: some are bare words ("Off"),
 * some JSON objects.
 */
data class Req(val method: String, val path: String, val body: String?)

sealed class Ctl(val label: String) {
    /** A reading; [field] is a dotted path into the item's data. Hidden when missing. */
    class Info(label: String, val field: String, val format: (Any) -> String = Equipment::plain) : Ctl(label)

    class Toggle(label: String, val field: String, val send: (Int, Boolean) -> Req) : Ctl(label)

    /** [options] are (hub value, label). */
    class Choice(label: String, val field: String, val options: List<Pair<String, String>>, val send: (Int, String) -> Req) : Ctl(label)

    /** A number the hub keeps in [field]; [scale] 10 for tenths of a degree. */
    class Number(
        label: String,
        val field: String,
        val values: List<Int>,
        val unit: String,
        val scale: Int = 1,
        val send: (Int, Int) -> Req,
    ) : Ctl(label)

    /** A command, shown when [field] (if given) is present, or when it is missing if [whenMissing]. */
    class Button(
        label: String,
        val field: String? = null,
        val confirm: String? = null,
        val whenMissing: Boolean = false,
        val send: (Int) -> Req,
    ) : Ctl(label)

    /** Like [Number], but the request also carries the item's other current values. */
    class NumberWith(
        label: String,
        val field: String,
        val values: List<Int>,
        val unit: String,
        val scale: Int = 1,
        val send: (Int, Int, JSONObject) -> Req,
    ) : Ctl(label)

    /** Like [Choice], but the request also carries the item's other current values. */
    class ChoiceWith(label: String, val field: String, val options: List<Pair<String, String>>, val send: (Int, String, JSONObject) -> Req) : Ctl(label)

    /** Like [Toggle], but the request also carries the item's other current values. */
    class ToggleWith(label: String, val field: String, val send: (Int, Boolean, JSONObject) -> Req) : Ctl(label)
}

/** One kind of equipment: the domain list it lives in and its controls. */
data class DomainSpec(
    val domain: String,
    val title: String,
    val controls: List<Ctl>,
    /** PATCH path/body for a rename, or null if it can't be renamed here. */
    val rename: ((Int, String) -> Req)? = null,
    /** Whether it can be put in a room (PATCH {domain}/{id}/ {"RoomId"}; DELETE …/RoomId). */
    val roomable: Boolean = false,
    /** For lists whose items have no Name: build one. */
    val fallbackName: String = title,
)

object Equipment {

    fun field(o: JSONObject, path: String): Any? {
        var cur: Any? = o
        for (part in path.split('.')) {
            cur = (cur as? JSONObject)?.opt(part) ?: return null
        }
        return cur?.takeUnless { it == JSONObject.NULL }
    }

    private fun obj(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }.toString()
    private fun patch(path: String, body: String) = Req("PATCH", path, body)
    private fun bare(path: String, v: Any) = patch(path, v.toString())

    private fun opts(vararg values: String) = values.map { it to spaced(it) }
    private val AWAY = listOf("NoChange" to "No change", "Off" to "Turn off")
    private val MODE = listOf("Auto" to "Follow schedule", "Manual" to "Manual")
    private val PERCENT = (0..100 step 10).toList()
    private val MINUTES = listOf(0, 1, 2, 5, 10, 15, 20, 30, 45, 60)
    private val FLOOR_TEMPS = (50..400 step 5).toList() // tenths

    val SPECS: List<DomainSpec> = listOf(
        DomainSpec(
            "Light", "Lights",
            listOf(
                Ctl.Info("Now", "CurrentState"),
                Ctl.Info("Brightness", "CurrentPercentage") { "$it%" },
                Ctl.Button("Turn on") { id -> patch("Light/$id/RequestOverride", obj("State" to "On")) },
                Ctl.Button("Turn off") { id -> patch("Light/$id/RequestOverride", obj("State" to "Off")) },
                Ctl.Number("Set brightness", "CurrentPercentage", PERCENT.drop(1), "%") { id, v ->
                    patch("Light/$id/RequestOverride", obj("State" to "On", "Percentage" to v))
                },
                Ctl.Choice("Mode", "Mode", MODE) { id, v -> patch("Light/$id", obj("Mode" to v)) },
                Ctl.Choice("In away mode", "AwayAction", AWAY) { id, v -> bare("Light/$id/AwayAction", v) },
                Ctl.Choice("When power returns", "PowerOnBehaviour", listOf("LastOnBrightness" to "Last brightness", "LevelPercent" to "Set level")) { id, v ->
                    patch("Light/$id", obj("PowerOnBehaviour" to v))
                },
                Ctl.Choice("Indicator light", "LedIndicator", opts("ConsistentWithLoad", "ReverseWithLoad", "AlwaysOn", "AlwaysOff", "OffAfter2s")) { id, v ->
                    patch("Light/$id", obj("LedIndicator" to v))
                },
                Ctl.Choice("Dimmer output", "OutputMode", listOf("Auto" to "Automatic", "RC" to "RC", "RL" to "RL", "RL_LED" to "RL LED")) { id, v ->
                    patch("Light/$id", obj("SetOutputMode" to v))
                },
                Ctl.Toggle("Dimming", "IsDimmable") { id, on -> patch("Light/$id/EnableDimming", on.toString()) },
                Ctl.Number("Lowest dimming level", "OutputRange.Minimum", (0..50 step 5).toList(), "%") { id, v ->
                    patch("Light/$id/SetOutputRange", obj("Minimum" to v))
                },
                Ctl.Number("Highest dimming level", "OutputRange.Maximum", (50..100 step 5).toList(), "%") { id, v ->
                    patch("Light/$id/SetOutputRange", obj("Maximum" to v))
                },
            ),
            rename = { id, name -> patch("Light/$id/", obj("Name" to name)) },
            roomable = true,
        ),
        DomainSpec(
            "Shutter", "Shutters",
            listOf(
                Ctl.Info("Position", "CurrentLift") { "$it% open" },
                Ctl.Info("Tilt", "CurrentTilt") { "$it%" },
                Ctl.Button("Open") { id -> patch("Shutter/$id/RequestAction", obj("Action" to "Open")) },
                Ctl.Button("Close") { id -> patch("Shutter/$id/RequestAction", obj("Action" to "Close")) },
                Ctl.Button("Stop") { id -> patch("Shutter/$id/RequestAction", obj("Action" to "Stop")) },
                Ctl.Number("Move to", "CurrentLift", PERCENT, "%") { id, v ->
                    patch("Shutter/$id/RequestAction", obj("Action" to "LiftTo", "Percentage" to v))
                },
                Ctl.Number("Tilt to", "CurrentTilt", PERCENT, "%") { id, v ->
                    patch("Shutter/$id/RequestAction", obj("Action" to "TiltTo", "Percentage" to v))
                },
                Ctl.Choice("Mode", "Mode", MODE) { id, v -> patch("Shutter/$id", obj("Mode" to v)) },
                Ctl.Choice("In away mode", "AwayAction", listOf("NoChange" to "No change", "Close" to "Close")) { id, v -> bare("Shutter/$id/AwayAction", v) },
                Ctl.Toggle("Summer comfort", "RespectSummerComfort") { id, on -> bare("Shutter/$id/RespectSummerComfort", on) },
                Ctl.NumberWith("Time to open fully", "DriveConfig.LiftOpenTime", (5..180 step 5).toList(), " s") { id, v, d ->
                    driveConfig(id, d, "LiftOpenTime", v)
                },
                Ctl.NumberWith("Time to close fully", "DriveConfig.LiftCloseTime", (5..180 step 5).toList(), " s") { id, v, d ->
                    driveConfig(id, d, "LiftCloseTime", v)
                },
                Ctl.ToggleWith("Tilting slats", "DriveConfig.TiltEnabled") { id, on, d -> driveConfig(id, d, "TiltEnabled", on) },
                Ctl.NumberWith("Time to tilt fully", "DriveConfig.TiltTime", (1..30).toList(), " s") { id, v, d ->
                    driveConfig(id, d, "TiltTime", v)
                },
                Ctl.NumberWith("Slat angle when closed", "DriveConfig.TiltAngleClosed", (0..90 step 5).toList(), "°") { id, v, d ->
                    driveConfig(id, d, "TiltAngleClosed", v)
                },
                Ctl.NumberWith("Slat angle when open", "DriveConfig.TiltAngleOpen", (0..90 step 5).toList(), "°") { id, v, d ->
                    driveConfig(id, d, "TiltAngleOpen", v)
                },
            ),
            rename = { id, name -> patch("Shutter/$id/", obj("Name" to name)) },
            roomable = true,
        ),
        DomainSpec(
            "SmartPlug", "Smart plugs",
            listOf(
                Ctl.Info("Now", "OutputState"),
                Ctl.Info("Power", "InstantaneousDemand") { "$it W" },
                Ctl.Button("Turn on") { id -> patch("SmartPlug/$id/", obj("RequestOutput" to "On")) },
                Ctl.Button("Turn off") { id -> patch("SmartPlug/$id/", obj("RequestOutput" to "Off")) },
                Ctl.Choice("Mode", "Mode", MODE) { id, v -> patch("SmartPlug/$id", obj("Mode" to v)) },
                Ctl.Choice("In away mode", "AwayAction", AWAY) { id, v -> patch("SmartPlug/$id", obj("AwayAction" to v)) },
                Ctl.Choice("Indicator light", "LedIndicator", opts("ConsistentWithLoad", "ReverseWithLoad", "AlwaysOn", "AlwaysOff")) { id, v ->
                    patch("SmartPlug/$id", obj("LedIndicator" to v))
                },
            ),
            rename = { id, name -> patch("SmartPlug/$id/", obj("Name" to name)) },
            roomable = true,
        ),
        DomainSpec(
            "SmokeAlarmDevice", "Smoke alarms",
            listOf(
                Ctl.Info("Smoke", "SmokeAlarm") { if (it == true) "ALARM" else "Clear" },
                Ctl.Info("Heat", "HeatAlarm") { if (it == true) "ALARM" else "Clear" },
                Ctl.Info("Alarm from another detector", "RemoteAlarm") { if (it == true) "Yes" else "No" },
                Ctl.Info("Tampered", "Tamper") { if (it == true) "Yes" else "No" },
                Ctl.Info("Battery fault", "BatteryDefect") { if (it == true) "Yes" else "No" },
                Ctl.Info("Fault", "FaultWarning") { if (it == true) "Yes" else "No" },
                Ctl.Info("Test mode", "TestMode") { if (it == true) "On" else "Off" },
                Ctl.Info("Hushed", "HushMode"),
                Ctl.Info("Temperature", "MeasuredTemperature") { formatTenths(it) },
                Ctl.Toggle("Notifications", "EnableNotification") { id, on -> patch("SmokeAlarmDevice/$id/", obj("EnableNotification" to on)) },
                Ctl.Number("Light brightness", "LEDBrightness", PERCENT, "%") { id, v -> patch("SmokeAlarmDevice/$id/", obj("SetLEDBrightness" to v)) },
            ),
            fallbackName = "Smoke alarm",
        ),
        DomainSpec(
            "EVSE", "EV chargers",
            listOf(
                Ctl.Info("Status", "Status"),
                Ctl.Info("Charging", "ChargingStatus"),
                Ctl.Info("Energy used", "EnergyUsageKwH") { "$it kWh" },
                Ctl.Button("Start charging") { id -> patch("EVSE/$id/OverrideRequest/", obj("Output" to "On")) },
                Ctl.Button("Stop charging") { id -> patch("EVSE/$id/OverrideRequest/", obj("Output" to "Off")) },
                Ctl.Choice("Charging mode", "ChargingMode", listOf("User" to "My schedule", "Green" to "Green", "Cost" to "Cheapest", "Smart" to "Smart")) { id, v ->
                    patch("EVSE/$id/", obj("ScheduleType" to v, "Mode" to "Auto"))
                },
                Ctl.Button("Charge manually", field = "Mode") { id -> patch("EVSE/$id", obj("Mode" to "Manual")) },
                Ctl.Choice("In away mode", "AwayAction", AWAY) { id, v -> patch("EVSE/$id", obj("AwayAction" to v)) },
            ),
            roomable = true,
            fallbackName = "EV charger",
        ),
        DomainSpec(
            "PTE", "Energy meters (PowerTag)",
            listOf(
                Ctl.Info("Power", "RawTotalActivePower") { "$it W" },
                Ctl.Info("Status", "OperatingStatus"),
                Ctl.Info("Phases", "NumberOfPhases"),
                Ctl.Info("Fault", "FaultStatus"),
                Ctl.Choice("Direction", "Direction", listOf("Forward" to "Forward", "Reverse" to "Reverse")) { id, v ->
                    patch("PTE/$id/", obj("Direction" to v))
                },
                Ctl.ChoiceWith("Installation", "InstallationType", listOf("Mono" to "Consumption only", "Bidirectional" to "Consumption and export")) { id, v, d ->
                    powerTag(id, d, "InstallationType", v)
                },
                Ctl.NumberWith("Grid limit", "GridLimit", (1..100).toList(), "") { id, v, d -> powerTag(id, d, "GridLimit", v) },
                Ctl.ChoiceWith("Grid limit in", "GridLimitUom", listOf("A" to "Amps (A)", "kVA" to "kVA", "W" to "Watts (W)")) { id, v, d ->
                    powerTag(id, d, "GridLimitUom", v)
                },
                Ctl.Choice("Energy export", "EnergyExport", listOf("None" to "None", "Some" to "Some", "All" to "All")) { id, v ->
                    patch("PTE/$id/", obj("EnergyExport" to v))
                },
            ),
            fallbackName = "PowerTag",
        ),
        DomainSpec(
            "PTC", "Controlled outputs (PowerTag C)",
            listOf(
                Ctl.Info("Now", "OutputState"),
                Ctl.Button("Turn on") { id -> patch("PTC/$id/OverrideRequest/", obj("Output" to "On")) },
                Ctl.Button("Turn off") { id -> patch("PTC/$id/OverrideRequest/", obj("Output" to "Off")) },
            ),
            fallbackName = "PowerTag C",
        ),
        DomainSpec(
            "Equipment", "Appliances",
            listOf(
                Ctl.Info("Power", "TotalActivePower") { "$it W" },
                Ctl.Info("Rated power", "StatedPower") { "$it W" },
                Ctl.Toggle("Power control (PCM)", "PcmMode") { id, on -> bare("Equipment/$id/PcmMode", on) },
                *powerAlarm("OverPowerNotification", "High-power alarm", "above").toTypedArray(),
                *powerAlarm("UnderPowerNotification", "Low-power alarm", "below").toTypedArray(),
            ),
            rename = { id, name -> patch("Equipment/$id/", obj("EquipmentName" to name)) },
            fallbackName = "Appliance",
        ),
        DomainSpec(
            "UnderFloorHeating", "Underfloor heating",
            listOf(
                Ctl.Info("Floor temperature", "MeasuredTemperature") { formatTenths(it) },
                Ctl.Info("Condensation", "DewDetected") { if (it == true) "Detected" else "None" },
                Ctl.Number("Minimum floor temperature", "MinHeatFloorTemperature", FLOOR_TEMPS, "°", 10) { id, v ->
                    bare("UnderFloorHeating/$id/MinHeatFloorTemperature", v)
                },
                Ctl.Number("Maximum floor temperature", "MaxHeatFloorTemperature", FLOOR_TEMPS, "°", 10) { id, v ->
                    bare("UnderFloorHeating/$id/MaxHeatFloorTemperature", v)
                },
                Ctl.Toggle("Heat/cool input", "HeatCoolInputUsed") { id, on -> bare("UnderFloorHeating/$id/HeatCoolInputUsed", on) },
            ),
            rename = { id, name -> patch("UnderFloorHeating/$id/", obj("Name" to name)) },
            fallbackName = "Underfloor controller",
        ),
        DomainSpec(
            "HeatingActuator", "Heating actuators",
            listOf(
                Ctl.Info("Temperature", "LocalTemperature") { formatTenths(it) },
                Ctl.Info("Floor temperature", "FloorTemperatureSensor.MeasuredTemperature") { formatTenths(it) },
                Ctl.Choice("Output", "OutputType", listOf("Relay" to "Relay", "FilPilote" to "Fil pilote")) { id, v ->
                    patch("HeatingActuator/$id", obj("OutputType" to v))
                },
                Ctl.Toggle("Output relay", "OutputRelayEnabled") { id, on -> bare("HeatingActuator/$id/SetOutputRelayEnabled", on) },
                Ctl.Choice("Cycle time", "CycleTime", listOf(5, 10, 20, 30).map { "CycleTime_$it" to "$it minutes" }) { id, v ->
                    bare("HeatingActuator/$id/SetCycleTime", v)
                },
                Ctl.Toggle("Valve protection", "ValveProtectionEnabled") { id, on -> bare("HeatingActuator/$id/SetValveProtectionEnabled", on) },
                Ctl.Toggle("Cooling input", "CoolingInput") { id, on -> bare("HeatingActuator/$id/SetCoolingInput", on) },
                Ctl.Choice("Universal input", "UniversalInput", listOf("Disabled" to "Off", "Presence_Detect" to "Presence", "Setback" to "Setback")) { id, v ->
                    bare("HeatingActuator/$id/SetUniversalInput", v)
                },
                Ctl.Number("Fixed demand", "FixedLoadDemand", PERCENT, "%") { id, v -> bare("HeatingActuator/$id/SetFixedLoadDemand", v) },
                Ctl.Number("Heater power", "StatedPower", (0..3000 step 100).toList(), " W") { id, v -> bare("HeatingActuator/$id/StatedPower", v) },
                Ctl.Number("Floor sensor minimum", "FloorTemperatureSensor.MinimumTemperature", FLOOR_TEMPS, "°", 10) { id, v ->
                    bare("HeatingActuator/$id/FloorTemperatureSensor/SetMinimumTemperature", v)
                },
                Ctl.Number("Floor sensor maximum", "FloorTemperatureSensor.MaximumTemperature", FLOOR_TEMPS, "°", 10) { id, v ->
                    bare("HeatingActuator/$id/FloorTemperatureSensor/SetMaximumTemperature", v)
                },
            ),
            fallbackName = "Heating actuator",
        ),
        DomainSpec(
            "FanController", "Fans",
            listOf(
                Ctl.Info("Now", "CurrentState"),
                Ctl.Info("Speed", "CurrentSpeed"),
                Ctl.Choice("Set speed", "CurrentSpeed", listOf("Off" to "Off", "Low" to "Low", "Medium" to "Medium", "High" to "High")) { id, v ->
                    patch("FanController/$id/RequestOverride", if (v == "Off") obj("State" to "Off") else obj("State" to "On", "Speed" to v))
                },
                Ctl.Choice("Mode", "Mode", MODE) { id, v -> patch("FanController/$id", obj("Mode" to v)) },
                Ctl.Choice("In away mode", "AwayAction", AWAY) { id, v -> bare("FanController/$id/AwayAction", v) },
                Ctl.Choice("Indicator light", "LedIndicatorMode", listOf("AlwaysOn" to "Always on", "DimAfter5s" to "Dim after 5 s", "OffAfter5s" to "Off after 5 s")) { id, v ->
                    patch("FanController/$id", obj("LedIndicatorMode" to v))
                },
            ),
            rename = { id, name -> patch("FanController/$id/", obj("Name" to name)) },
            roomable = true,
            fallbackName = "Fan",
        ),
        DomainSpec(
            "BinarySensor", "Motion, door and window sensors",
            listOf(
                Ctl.Info("Now", "Active") { if (it == true) "Active" else "Inactive" },
                Ctl.Info("Status", "SensorStatus"),
                Ctl.Choice("Type", "Type", opts("Motion", "Window", "Door", "WaterLeakage")) { id, v -> patch("BinarySensor/$id/", obj("Type" to v)) },
                Ctl.Choice("Notify", "EnableNotification", listOf("None" to "Never", "OnTrue" to "When active", "OnFalse" to "When inactive", "OnEither" to "Both")) { id, v ->
                    patch("BinarySensor/$id/", obj("EnableNotification" to v))
                },
                Ctl.Number("Inactive after", "DelayToInactive", MINUTES, " min") { id, v -> patch("BinarySensor/$id/", obj("DelayToInactive" to v)) },
                Ctl.Number("Sensitivity", "Sensitivity", (0..10).toList(), "") { id, v -> patch("BinarySensor/$id/", obj("Sensitivity" to v)) },
                Ctl.Toggle("Indicator light", "LedEnabled") { id, on -> patch("BinarySensor/$id/", obj("LEDEnabled" to on)) },
            ),
            fallbackName = "Sensor",
        ),
        DomainSpec(
            "ThresholdSensor", "Light and temperature sensors",
            listOf(
                Ctl.Info("Reading", "CurrentValue"),
                Ctl.Info("Level", "CurrentLevel"),
                Ctl.Info("Measures", "Quantity"),
                Ctl.Toggle("Affects room heating", "InteractsWithRoomClimate") { id, on -> patch("ThresholdSensor/$id/", obj("InteractsWithRoomClimate" to on)) },
            ),
            fallbackName = "Sensor",
        ),
        DomainSpec(
            "HeatPump", "Heat pumps",
            listOf(
                Ctl.Info("Demand", "PercentageDemand") { "$it%" },
                Ctl.Number("Minimum water temperature", "SelectedMinimumSetpoint", (200..600 step 10).toList(), "°", 10) { id, v ->
                    bare("HeatPump/$id/SelectedMinimumSetpoint", v)
                },
                Ctl.Number("Maximum water temperature", "SelectedMaximumSetpoint", (300..750 step 10).toList(), "°", 10) { id, v ->
                    bare("HeatPump/$id/SelectedMaximumSetpoint", v)
                },
            ),
            fallbackName = "Heat pump",
        ),
        DomainSpec(
            "UIConfiguration", "Thermostat displays",
            listOf(
                Ctl.Number("Brightness", "Brightness", PERCENT, "%") { id, v -> bare("UIConfiguration/$id/SetBrightness", v) },
                Ctl.Number("Brightness when idle", "InactiveBrightness", PERCENT, "%") { id, v -> bare("UIConfiguration/$id/SetInactiveBrightness", v) },
            ),
            fallbackName = "Display",
        ),
        DomainSpec("ButtonPanel", "Button panels", listOf(Ctl.Info("Buttons", "Events") { (it as? JSONArray)?.length()?.toString() ?: "" }), fallbackName = "Button panel"),
        DomainSpec("ZigbeeGroup", "Zigbee groups", listOf(Ctl.Info("Devices", "Members") { (it as? JSONArray)?.length()?.toString() ?: "" }), fallbackName = "Group"),
    )

    /** The official SetDriveConfig sends the whole drive set-up; [key] is the one being changed. */
    private fun driveConfig(id: Int, data: JSONObject, key: String, value: Any): Req {
        val dc = data.optJSONObject("DriveConfig") ?: JSONObject()
        val body = JSONObject()
            .put("LiftOpenTime", dc.optInt("LiftOpenTime"))
            .put("LiftCloseTime", dc.optInt("LiftCloseTime"))
        if (data.optBoolean("IsTiltSupported", false)) {
            body.put("TiltEnabled", dc.optBoolean("TiltEnabled"))
                .put("TiltTime", dc.optInt("TiltTime"))
                .put("TiltAngleClosed", dc.optInt("TiltAngleClosed"))
                .put("TiltAngleOpen", dc.optInt("TiltAngleOpen"))
        }
        body.put(key, value)
        return patch("Shutter/$id/SetDriveConfig", body.toString())
    }

    /** The official ConfigurePowerTag sends the limit, its unit and the direction together, with the installation type or export. */
    private fun powerTag(id: Int, data: JSONObject, key: String, value: Any): Req {
        val body = JSONObject()
            .put("GridLimit", data.optInt("GridLimit"))
            .put("GridLimitUom", data.optString("GridLimitUom").ifEmpty { "A" })
            .put("Direction", data.optString("Direction").ifEmpty { "Forward" })
        data.optString("InstallationType").takeIf { it.isNotEmpty() }?.let { body.put("InstallationType", it) }
        body.put(key, value)
        return patch("PTE/$id/", body.toString())
    }

    /**
     * An appliance's high- or low-power alarm ([key] OverPowerNotification or UnderPowerNotification):
     * PATCH Equipment/{id}/ {key: {PeriodMins, Limit, Enabled}}, DELETE Equipment/{id}/{key}/ to remove.
     */
    private fun powerAlarm(key: String, title: String, word: String): List<Ctl> {
        fun send(id: Int, data: JSONObject, change: (JSONObject) -> Unit): Req {
            val cur = data.optJSONObject(key) ?: JSONObject()
            val alarm = JSONObject()
                .put("PeriodMins", cur.optInt("PeriodMins", 5))
                .put("Limit", cur.optInt("Limit", 1000))
                .put("Enabled", cur.optBoolean("Enabled", true))
            change(alarm)
            return patch("Equipment/$id/", JSONObject().put(key, alarm).toString())
        }
        return listOf(
            Ctl.Button("Add a ${title.lowercase()}", field = key, whenMissing = true) { id ->
                patch("Equipment/$id/", JSONObject().put(key, JSONObject().put("PeriodMins", 5).put("Limit", 1000).put("Enabled", true)).toString())
            },
            Ctl.ToggleWith(title, "$key.Enabled") { id, on, d -> send(id, d) { it.put("Enabled", on) } },
            Ctl.NumberWith("Alarm when $word", "$key.Limit", (100..10000 step 100).toList(), " W") { id, v, d -> send(id, d) { it.put("Limit", v) } },
            Ctl.NumberWith("For at least", "$key.PeriodMins", listOf(1, 2, 5, 10, 15, 30, 60, 120), " min") { id, v, d -> send(id, d) { it.put("PeriodMins", v) } },
            Ctl.Button("Remove the ${title.lowercase()}", field = key, confirm = "Remove the ${title.lowercase()}?") { id ->
                Req("DELETE", "Equipment/$id/$key/", null)
            },
        )
    }

    /** System-wide settings for summer comfort, cooling and underfloor heating; each shows only if the hub reports it. */
    val SYSTEM: List<Ctl> = listOf(
        Ctl.Toggle("Summer comfort", "SummerComfortEnabled") { _, on -> bare("System/SummerComfortEnabled", on) },
        Ctl.Number("Too warm indoors above", "IndoorDiscomfortTemperature", (200..300 step 5).toList(), "°", 10) { _, v ->
            bare("System/IndoorDiscomfortTemperature", v)
        },
        Ctl.Number("Too warm outdoors above", "OutdoorDiscomfortTemperature", (150..350 step 5).toList(), "°", 10) { _, v ->
            bare("System/OutdoorDiscomfortTemperature", v)
        },
        Ctl.Number("Cooling default temperature", "CoolingModeDefaultSetpoint", (160..300 step 5).toList(), "°", 10) { _, v ->
            bare("System/CoolingModeDefaultSetpoint", v)
        },
        Ctl.Number("Cooling limit in away mode", "CoolingAwayModeSetpointLimit", (200..350 step 5).toList(), "°", 10) { _, v ->
            bare("System/CoolingAwayModeSetpointLimit", v)
        },
        Ctl.Choice("Away mode for heat pumps", "AwayModeHVACMode", opts("Heat", "Cool", "Auto", "Stop")) { _, v -> bare("System/AwayModeHVACMode", v) },
        Ctl.Choice("Underfloor heating with no thermostat", "UfhOrphanModeOutput", listOf("On" to "Keep heating", "Off" to "Stop")) { _, v ->
            bare("System/UfhOrphanModeOutput", v)
        },
    )

    /** Seasonal comfort (/data/v2/seasonalComfort/), on hubs that report it. */
    val SEASONAL: List<Ctl> = listOf(
        Ctl.Toggle("Seasonal comfort", "Enabled") { _, on -> bare("/seasonalComfort/Enabled", on) },
        Ctl.Number("Too warm indoors above", "IndoorDiscomfortTemperature", (200..300 step 5).toList(), "°", 10) { _, v ->
            patch("/seasonalComfort/", obj("IndoorDiscomfortTemperature" to v))
        },
        Ctl.Number("Too warm outdoors above", "OutdoorDiscomfortTemperature", (150..350 step 5).toList(), "°", 10) { _, v ->
            patch("/seasonalComfort/", obj("OutdoorDiscomfortTemperature" to v))
        },
        Ctl.Number("Act this long after sunrise", "SunriseOffsetMinutes", (-120..120 step 15).toList(), " min") { _, v ->
            patch("/seasonalComfort/", obj("SunriseOffsetMinutes" to v))
        },
        Ctl.Number("Act this long after sunset", "SunsetOffsetMinutes", (-120..120 step 15).toList(), " min") { _, v ->
            patch("/seasonalComfort/", obj("SunsetOffsetMinutes" to v))
        },
    )

    /** Matter bridge: let a Matter controller (e.g. Google Home) add the hub, as the official app does. */
    val MATTER: List<Ctl> = listOf(
        Ctl.Button("Allow Matter pairing for 3 minutes", confirm = "Open the hub to Matter pairing for 3 minutes? Then add it from your Matter app (for example Google Home).") { _ ->
            bare("/matter/Join/StartJoin", 180)
        },
        Ctl.Button("Stop Matter pairing") { _ -> bare("/matter/Join/StopJoin", 1) },
    )

    /** Per-room settings for summer comfort, cooling and underfloor heating. */
    val ROOM: List<Ctl> = listOf(
        Ctl.Toggle("Summer comfort", "IncludeInSummerComfort") { id, on -> bare("Room/$id/IncludeInSummerComfort", on) },
        Ctl.Toggle("Leave out of cooling", "ExcludeFromCooling") { id, on -> bare("Room/$id/ExcludeFromCooling", on) },
        Ctl.Choice("Heat pump mode", "HvacMode", opts("Heat", "Cool", "Fan", "Dry", "Auto", "Stop")) { id, v -> bare("Room/$id/HVACMode", v) },
        Ctl.Toggle("Floor temperature sensor", "FloorSensorAttached") { id, on -> bare("Room/$id/FloorSensorAttached", on) },
    )

    /** The items of [spec] on the hub: (id, name, data). */
    fun items(domain: JSONObject?, spec: DomainSpec): List<Triple<Int, String, JSONObject>> {
        val list = domain?.optJSONArray(spec.domain) ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val id = if (o.has("id")) o.optInt("id") else o.optInt("Id")
            val name = o.optString("Name").ifEmpty { o.optString("EquipmentName") }.ifEmpty { "${spec.fallbackName} $id" }
            Triple(id, name, o)
        }
    }

    fun plain(v: Any): String = when (v) {
        is Boolean -> if (v) "Yes" else "No"
        is String -> spaced(v)
        else -> v.toString()
    }

    fun formatTenths(v: Any): String {
        val n = (v as? Number)?.toInt() ?: return v.toString()
        if (n < -400 || n > 1999) return "--"
        return String.format(Locale.getDefault(), "%.1f°", n / 10.0)
    }

    fun spaced(s: String): String =
        s.replace('_', ' ').replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }
}
