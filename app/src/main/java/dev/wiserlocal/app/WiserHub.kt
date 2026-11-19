package dev.wiserlocal.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.abs
import kotlin.math.roundToInt

data class Room(
    val id: Int,
    val name: String,
    /** Measured temperature, or null if the room has no working sensor. */
    val tempC: Double?,
    /** Target temperature; below [WiserHub.MIN_TEMP_C] means the room is off. */
    val setPointC: Double?,
    val heating: Boolean,
    val overridden: Boolean,
    /** Schedule this room follows, or 0 if none. */
    val scheduleId: Int,
    /** "Auto" (following the schedule) or "Manual". */
    val mode: String,
    val boosted: Boolean,
    /** When the current override ends (epoch millis), or 0 if open-ended. */
    val overrideUntilMs: Long,
    /** How hard the room is calling for heat, 0 to 100. */
    val demandPercent: Int,
    /** Relative humidity from the room thermostat, if the room has one. */
    val humidity: Int?,
    val windowOpen: Boolean,
    /** Whether open window detection is switched on, or null if unsupported. */
    val windowDetection: Boolean?,
)

data class HotWater(
    val id: Int,
    val on: Boolean,
    val boosted: Boolean,
    /** When the current override ends (epoch millis), or 0 if there is none. */
    val overrideUntilMs: Long,
    /** Schedule the hot water follows, or 0 if none. */
    val scheduleId: Int,
    /** "Auto" (following the schedule) or "Manual". */
    val mode: String,
)

data class SystemInfo(
    val away: Boolean,
    /** True while the hub is calling the boiler for central heating. */
    val boilerFiring: Boolean,
    val ecoMode: Boolean?,
    val comfortMode: Boolean?,
    val cloudConnected: Boolean?,
    val firmware: String?,
    /**
     * Heat source and boiler settings as (hub field name, value) pairs, e.g.
     * "FuelType" to "Gas". Shown as reported, since the field names are undocumented.
     */
    val boiler: List<Pair<String, String>>,
    val brand: String?,
    /** OpenTherm link status, e.g. "Connected" or "Disconnected". */
    val openTherm: String?,
    /** Temperature rooms are held at in away mode. */
    val awayTempC: Double?,
    /** Whether away mode also switches hot water off. */
    val awayAffectsHotWater: Boolean?,
    /** Periodic valve exercise that stops radiator valves seizing. */
    val valveProtection: Boolean?,
    val autoDaylightSaving: Boolean?,
    /** Hub clock offset from UTC, as the hub stores it (believed to be minutes). */
    val timeZoneOffset: Int?,
)

data class Device(
    val id: Int,
    /** Hub product type, e.g. "RoomStat", "iTRV" or "Controller". */
    val type: String,
    val roomName: String?,
    /** Battery level as the hub words it, e.g. "Normal" or "Low". */
    val battery: String?,
    val batteryVolts: Double?,
    val signal: String?,
    val firmware: String?,
    /** Whether the device's own buttons are locked, or null if it has none. */
    val locked: Boolean?,
    /** The device's own temperature reading (radiator valves and room thermostats). */
    val tempC: Double?,
    /** The target the device is working to, if it reports one. */
    val setPointC: Double?,
    /** How far open a radiator valve is asking to be, 0 to 100. */
    val demandPercent: Int?,
)

/** A scene set up in the official app, which the hub can run on request. */
data class Moment(val id: Int, val name: String, val iconId: Int = 0)

/**
 * One thing a Moment does: put a room or the hot water on a manual setting.
 * [domain] is "Room" or "HotWater"; [setPoint] is in tenths of a degree
 * ([WiserHub.SCHEDULE_OFF] for off, 1100 for hot water on).
 */
data class MomentAction(val domain: String, val id: Int, val setPoint: Int, val durationMinutes: Int? = null)

data class HubState(
    val rooms: List<Room>,
    val hotWater: HotWater?,
    val system: SystemInfo,
    val devices: List<Device>,
    val moments: List<Moment>,
)

/**
 * One change point in a day. [value] is the target in tenths of a degree for a
 * heating schedule ([WiserHub.SCHEDULE_OFF] for off), or 1/0 for an on/off schedule.
 */
data class Slot(val minutes: Int, val value: Int)

data class Schedule(
    val id: Int,
    val name: String,
    /** True for a room heating schedule, false for an on/off (hot water) schedule. */
    val heating: Boolean,
    /** Slots per day, keyed by the names in [WiserHub.DAYS], sorted by time. */
    val days: Map<String, List<Slot>>,
    /** How the hub sent on/off days, so they are sent back in the same shape. */
    val onOffWrapped: Boolean = false,
    /** Value of the next scheduled change (same units as [Slot.value]), if known. */
    val nextValue: Int? = null,
    /** Day name and minutes past midnight of the next scheduled change, if known. */
    val nextDay: String? = null,
    val nextMinutes: Int? = null,
)

enum class RoomMode { AUTO, MANUAL, OFF }

enum class HotWaterMode { AUTO, ON, OFF }

/**
 * Minimal client for the Wiser hub's unofficial local REST API.
 * All calls block, so run them off the main thread.
 *
 * Payloads follow https://github.com/msp1974/aioWiserHeatAPI. The hub works in
 * tenths of a degree, so 20.5°C is sent as 205, and schedule times are HHMM
 * integers, so 06:30 is sent as 630.
 */
class WiserHub(host: String, private val secret: String) {

    private val host = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')

    fun fetch(): HubState {
        val json = JSONObject(request("GET", "", null))
        val now = System.currentTimeMillis()

        val roomStats = mutableMapOf<Int, JSONObject>()
        json.optJSONArray("RoomStat").forEachObject { roomStats[it.optInt("id")] = it }

        // Per-device readings live in type-specific lists that share the device's id.
        val readings = HashMap(roomStats)
        json.optJSONArray("SmartValve").forEachObject { readings[it.optInt("id")] = it }

        val rooms = mutableListOf<Room>()
        val deviceRooms = mutableMapOf<Int, String>()
        json.optJSONArray("Room").forEachObject { o ->
            val id = o.getInt("id")
            val name = o.optString("Name", "Room $id")
            val statId = o.optInt("RoomStatId", 0)
            if (statId > 0) deviceRooms[statId] = name
            for (key in listOf("SmartValveIds", "HeatingActuatorIds", "UfhRelayIds")) {
                val ids = o.optJSONArray(key) ?: continue
                for (i in 0 until ids.length()) deviceRooms[ids.optInt(i)] = name
            }
            val origin = o.optString("SetpointOrigin", o.optString("SetPointOrigin"))
            val overrideType = o.optString("OverrideType", "None")
            val until = o.optLong("OverrideTimeoutUnixTime", 0) * 1000
            rooms += Room(
                id = id,
                name = name,
                tempC = fromHub(o.optInt("CalculatedTemperature", NO_READING)),
                setPointC = fromHub(o.optInt("CurrentSetPoint", NO_READING)),
                heating = o.optString("ControlOutputState") == "On" ||
                    o.optInt("PercentageDemand", 0) > 0,
                overridden = overrideType != "None",
                scheduleId = o.optInt("ScheduleId", 0),
                mode = o.optString("Mode", "Auto"),
                boosted = overrideType == "Boost" || origin.contains("Boost"),
                overrideUntilMs = if (until > now) until else 0,
                demandPercent = o.optInt("PercentageDemand", 0),
                humidity = roomStats[statId]?.takeIf { it.has("MeasuredHumidity") }
                    ?.optInt("MeasuredHumidity")?.takeIf { it in 1..100 },
                windowOpen = o.optString("WindowState") == "Open",
                windowDetection = o.optBooleanOrNull("WindowDetectionActive"),
            )
        }

        val hotWater = json.optJSONArray("HotWater")?.optJSONObject(0)?.let { o ->
            val on = o.optString("WaterHeatingState") == "On"
            val until = o.optLong("OverrideTimeoutUnixTime", 0) * 1000
            val timedOverride = o.optString("OverrideType", "None") != "None" && until > now
            HotWater(
                id = o.getInt("id"),
                on = on,
                boosted = o.optString("HotWaterDescription").contains("Boost") || (timedOverride && on),
                overrideUntilMs = if (until > now) until else 0,
                scheduleId = o.optInt("ScheduleId", 0),
                mode = o.optString("Mode", "Auto"),
            )
        }

        val sys = json.optJSONObject("System") ?: JSONObject()
        var relayOn: Boolean? = null
        json.optJSONArray("HeatingChannel").forEachObject {
            relayOn = (relayOn ?: false) || it.optString("HeatingRelayState") == "On"
        }
        val system = SystemInfo(
            away = sys.optString("OverrideType") == "Away",
            boilerFiring = relayOn ?: rooms.any { it.heating },
            ecoMode = sys.optBooleanOrNull("EcoModeEnabled"),
            comfortMode = sys.optBooleanOrNull("ComfortModeEnabled"),
            cloudConnected = sys.optStringOrNull("CloudConnectionStatus")?.let { it == "Connected" },
            firmware = sys.optStringOrNull("ActiveSystemVersion"),
            boiler = boilerSettings(sys),
            brand = sys.optStringOrNull("BrandName"),
            openTherm = sys.optStringOrNull("OpenThermConnectionStatus"),
            awayTempC = if (sys.has("AwayModeSetPointLimit")) fromHub(sys.optInt("AwayModeSetPointLimit")) else null,
            awayAffectsHotWater = sys.optBooleanOrNull("AwayModeAffectsHotWater"),
            valveProtection = sys.optBooleanOrNull("ValveProtectionEnabled"),
            autoDaylightSaving = sys.optBooleanOrNull("AutomaticDaylightSaving"),
            timeZoneOffset = if (sys.has("TimeZoneOffset")) sys.optInt("TimeZoneOffset") else null,
        )

        val devices = mutableListOf<Device>()
        json.optJSONArray("Device").forEachObject { o ->
            val id = o.optInt("id")
            devices += Device(
                id = id,
                type = o.optString("ProductType", "Device"),
                roomName = deviceRooms[id],
                battery = o.optStringOrNull("BatteryLevel"),
                batteryVolts = if (o.has("BatteryVoltage")) o.optInt("BatteryVoltage") / 10.0 else null,
                signal = o.optStringOrNull("DisplayedSignalStrength"),
                firmware = o.optStringOrNull("ActiveFirmwareVersion"),
                locked = o.optBooleanOrNull("DeviceLockEnabled"),
                tempC = readings[id]?.let { fromHub(it.optInt("MeasuredTemperature", NO_READING)) },
                setPointC = readings[id]?.let { fromHub(it.optInt("SetPoint", NO_READING)) },
                demandPercent = readings[id]?.takeIf { it.has("PercentageDemand") }?.optInt("PercentageDemand"),
            )
        }

        val moments = mutableListOf<Moment>()
        json.optJSONArray("Moment").forEachObject { o ->
            val id = o.optInt("id")
            // The official app sends the name URL-encoded in a query string and the hub
            // stores it as sent ("Test+heating", "A%2BB"), so decode it for display.
            val name = o.optStringOrNull("Name")?.let { raw ->
                try { URLDecoder.decode(raw, "UTF-8") } catch (e: IllegalArgumentException) { raw }
            } ?: "Moment $id"
            moments += Moment(id, name, o.optInt("IconId", 0))
        }

        return HubState(rooms, hotWater, system, devices, moments)
    }

    fun setRoomTemp(roomId: Int, tempC: Double) {
        val setPoint = (tempC.coerceIn(MIN_TEMP_C, MAX_TEMP_C) * 10).roundToInt()
        patch("Room/$roomId", """{"RequestOverride":{"Type":"Manual","SetPoint":$setPoint}}""")
    }

    /** Drops any manual override or boost so the room follows its schedule again. */
    fun resumeRoomSchedule(roomId: Int) = patch("Room/$roomId", CANCEL_OVERRIDE)

    /** Raises the room's target by [deltaC] for [minutes], then reverts. */
    fun boostRoom(roomId: Int, deltaC: Double, minutes: Int) = patch(
        "Room/$roomId",
        """{"RequestOverride":{"Type":"Boost","DurationMinutes":$minutes,"IncreaseSetPointBy":${(deltaC * 10).roundToInt()}}}""",
    )

    fun setRoomMode(roomId: Int, mode: RoomMode) {
        patch("Room/$roomId", CANCEL_OVERRIDE)
        when (mode) {
            RoomMode.AUTO -> patch("Room/$roomId", """{"Mode":"Auto"}""")
            RoomMode.MANUAL -> patch("Room/$roomId", """{"Mode":"Manual"}""")
            RoomMode.OFF -> {
                patch("Room/$roomId", """{"Mode":"Manual"}""")
                patch("Room/$roomId", """{"RequestOverride":{"Type":"Manual","SetPoint":$SCHEDULE_OFF}}""")
            }
        }
    }

    /** Holds [tempC] for [minutes], then returns to what the room was doing. */
    fun holdRoomTemp(roomId: Int, tempC: Double, minutes: Int) {
        val setPoint = (tempC.coerceIn(MIN_TEMP_C, MAX_TEMP_C) * 10).roundToInt()
        patch(
            "Room/$roomId",
            """{"RequestOverride":{"Type":"Manual","DurationMinutes":$minutes,"SetPoint":$setPoint}}""",
        )
    }

    fun renameRoom(roomId: Int, name: String) =
        patch("Room/$roomId", JSONObject().put("Name", name).toString())

    fun createRoom(name: String) {
        // The reference library sends a lower-case "name" key when creating a room.
        request("POST", "Room", JSONObject().put("name", name).toString())
    }

    /** Removes the room from the hub. Its devices stay paired but unassigned. */
    fun deleteRoom(roomId: Int) {
        request("DELETE", "Room/$roomId", null)
    }

    fun setWindowDetection(roomId: Int, enabled: Boolean) =
        patch("Room/$roomId", """{"WindowDetectionActive":$enabled}""")

    /** Jumps the room to its next scheduled target early. */
    fun advanceRoom(room: Room) {
        val next = nextScheduled(room.scheduleId, heating = true)
        patch("Room/${room.id}", CANCEL_OVERRIDE)
        patch("Room/${room.id}", """{"RequestOverride":{"Type":"Manual","SetPoint":$next}}""")
    }

    fun boostHotWater(hotWaterId: Int, minutes: Int) = patch(
        "HotWater/$hotWaterId",
        """{"RequestOverride":{"Type":"Manual","DurationMinutes":$minutes,"SetPoint":$HOT_WATER_ON}}""",
    )

    fun cancelHotWaterBoost(hotWaterId: Int) = patch("HotWater/$hotWaterId", CANCEL_OVERRIDE)

    fun setHotWaterMode(hotWaterId: Int, mode: HotWaterMode) {
        val path = "HotWater/$hotWaterId"
        if (mode == HotWaterMode.AUTO) {
            patch(path, """{"Mode":"Auto","RequestOverride":{"Type":"None"}}""")
        } else {
            patch(path, """{"Mode":"Manual","RequestOverride":{"Type":"None"}}""")
            overrideHotWater(hotWaterId, mode == HotWaterMode.ON)
        }
    }

    /** Jumps the hot water to its next scheduled state early. */
    fun advanceHotWater(hotWater: HotWater) {
        val next = nextScheduled(hotWater.scheduleId, heating = false)
        patch("HotWater/${hotWater.id}", CANCEL_OVERRIDE)
        overrideHotWater(hotWater.id, next != 0)
    }

    private fun overrideHotWater(hotWaterId: Int, on: Boolean) = patch(
        "HotWater/$hotWaterId",
        """{"RequestOverride":{"Type":"Manual","SetPoint":${if (on) HOT_WATER_ON else SCHEDULE_OFF}}}""",
    )

    private fun nextScheduled(scheduleId: Int, heating: Boolean): Int =
        fetchSchedules().firstOrNull { it.id == scheduleId && it.heating == heating }?.nextValue
            ?: throw IOException("The hub did not say what comes next in the schedule")

    /** Away mode drops every room to the hub's away temperature until switched off. */
    fun setAway(enabled: Boolean) =
        patch("System", """{"RequestOverride":{"Type":${if (enabled) 2 else 0}}}""")

    /** Raises every room's target by [deltaC] for [minutes]. */
    fun boostAllRooms(deltaC: Double, minutes: Int) = patch(
        "System",
        """{"RequestOverride":{"Type":"Boost","DurationMinutes":$minutes,"IncreaseSetPointBy":${(deltaC * 10).roundToInt()}}}""",
    )

    /** Cancels every boost and manual override in the house. */
    fun cancelAllOverrides() = patch("System", """{"RequestOverride":{"Type":"CancelUserOverrides"}}""")

    fun setAwayTemp(tempC: Double) =
        patch("System", """{"AwayModeSetPointLimit":${(tempC.coerceIn(MIN_TEMP_C, MAX_TEMP_C) * 10).roundToInt()}}""")

    // The reference library sends these two flags as the strings "true"/"false".
    fun setAwayAffectsHotWater(enabled: Boolean) =
        patch("System", """{"AwayModeAffectsHotWater":"$enabled"}""")

    fun setAutoDaylightSaving(enabled: Boolean) =
        patch("System", """{"AutomaticDaylightSaving":"$enabled"}""")

    fun setTimeZoneOffset(offset: Int) = patch("System", """{"TimeZoneOffset":$offset}""")

    fun setValveProtection(enabled: Boolean) = patch("System", """{"ValveProtectionEnabled":$enabled}""")

    /** Lets new devices join the hub for [seconds]. */
    fun permitJoin(seconds: Int) = patch("System/RequestPermitJoin", seconds.toString())

    fun setDeviceLock(deviceId: Int, locked: Boolean) =
        patch("Device/$deviceId", """{"DeviceLockEnabled":$locked}""")

    /** Starts or stops the device flashing so it can be told apart from others. */
    fun identifyDevice(deviceId: Int, on: Boolean) = patch("Device/$deviceId", """{"Identify":$on}""")

    /** The hub's Wi-Fi details as (label, value) pairs; empty if it does not report them. */
    fun fetchNetwork(): List<Pair<String, String>> {
        val text = attempt("GET", "/data/v2/network/", null) ?: return emptyList()
        val station = JSONObject(text).optJSONObject("Station") ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        station.optStringOrNull("SSID")?.let { out += "Wi-Fi network" to it }
        station.optJSONObject("RSSI")?.let {
            if (it.has("Current")) out += "Wi-Fi signal" to "${it.optInt("Current")} dBm"
        }
        val address = station.optJSONObject("DhcpStatus")?.optStringOrNull("IPv4Address")
            ?: station.optJSONObject("NetworkInterface")?.optStringOrNull("IPv4HostAddress")
        address?.let { out += "IP address" to it }
        station.optJSONObject("NetworkInterface")?.optStringOrNull("HostName")?.let { out += "Host name" to it }
        station.optStringOrNull("MacAddress")?.let { out += "MAC address" to it }
        return out
    }

    /** Runs a Moment (scene) that was set up in the official app. */
    fun triggerMoment(momentId: Int) = patch("System", """{"TriggerMoment":$momentId}""")

    // Rename and delete are not in the reference library. They follow the same
    // pattern the hub uses for rooms, and are untested on a real hub.
    fun renameMoment(momentId: Int, name: String) =
        patch("Moment/$momentId", JSONObject().put("Name", name).toString())

    /**
     * Creates a Moment, in the format the official app uses: name and icon in the
     * query string, one action per line in the body, each override as a JSON string.
     */
    fun createMoment(name: String, actions: List<MomentAction>) {
        request("POST", "Moment?" + momentQuery(name, 0), momentBody(actions))
    }

    /**
     * What a Moment does, read with GET Moment/{id}. Null when any part of it is
     * something this app cannot describe (for example a room boost or a wait), so
     * that saving from this app never drops what the official app set up.
     */
    fun fetchMomentActions(momentId: Int): List<MomentAction>? {
        val objects = momentObjects(request("GET", "Moment/$momentId", null)) ?: return null
        if (objects.isEmpty()) return null
        return objects.map { parseMomentAction(it) ?: return null }
    }

    private fun parseMomentAction(json: JSONObject): MomentAction? {
        if (json.optString("Action") != "RequestOverride") return null
        // Extras the official app sends as null; anything set there is beyond this app.
        if (json.has("Wait") && !json.isNull("Wait")) return null
        if (json.has("EnableNotification") && !json.isNull("EnableNotification") && json.optBoolean("EnableNotification")) return null
        val value = json.optString("Value").takeIf { it.startsWith("{") }?.let { JSONObject(it) } ?: return null
        if (!value.optString("type").equals("Manual", ignoreCase = true) || !value.has("setPoint")) return null
        return MomentAction(
            domain = json.optString("Domain"),
            id = json.optInt("Id"),
            setPoint = value.optInt("setPoint"),
            durationMinutes = value.takeIf { it.has("durationMinutes") }?.optInt("durationMinutes"),
        )
    }

    /**
     * The action objects in a Moment response. The official app sends them one per
     * line, so the hub may answer the same way; a JSON array or a single object is
     * accepted too. Null when the response is not something recognisable.
     */
    private fun momentObjects(text: String): List<JSONObject>? {
        val out = mutableListOf<JSONObject>()
        val tokener = JSONTokener(text)
        try {
            while (true) {
                if (tokener.nextClean() == 0.toChar()) break
                tokener.back()
                when (val value = tokener.nextValue()) {
                    is JSONObject -> out += value
                    is JSONArray -> for (i in 0 until value.length()) out += value.optJSONObject(i) ?: return null
                    else -> return null
                }
            }
        } catch (e: JSONException) {
            return null
        }
        return out
    }

    /** Replaces what a Moment does, keeping its name and icon, as the official app does. */
    fun updateMoment(moment: Moment, actions: List<MomentAction>) {
        request("PATCH", "Moment/${moment.id}?" + momentQuery(moment.name, moment.iconId), momentBody(actions))
    }

    private fun momentQuery(name: String, iconId: Int) =
        // Spaces go as "+", exactly as the official app sends them, so both apps read the name back the same way.
        "Name=" + URLEncoder.encode(name, "UTF-8") + "&IconId=$iconId"

    /**
     * The actions one per line (CRLF, which matches the length the official app sent),
     * each with its override as a JSON string inside "Value".
     */
    private fun momentBody(actions: List<MomentAction>): String = actions.joinToString("\r\n") { action ->
        val override = JSONObject().put("type", "Manual").put("setPoint", action.setPoint)
        // A timed hot water boost; the official app adds the duration to the same override.
        action.durationMinutes?.let { override.put("durationMinutes", it) }
        JSONObject()
            .put("Domain", action.domain)
            .put("Id", action.id)
            .put("Action", "RequestOverride")
            .put("Value", override.toString())
            .put("Wait", JSONObject.NULL)
            .put("EnableNotification", JSONObject.NULL)
            .toString()
    }

    fun deleteMoment(momentId: Int) {
        request("DELETE", "Moment/$momentId", null)
    }

    fun setEcoMode(enabled: Boolean) = setSystemFlag("EcoModeEnabled", enabled)

    fun setComfortMode(enabled: Boolean) = setSystemFlag("ComfortModeEnabled", enabled)

    /**
     * Same request as the official app (the bare true/false sent to the property's own
     * path). Older firmware may not serve that path, so fall back to patching System.
     */
    private fun setSystemFlag(name: String, enabled: Boolean) {
        try {
            patch("System/$name", enabled.toString())
        } catch (e: IOException) {
            if (e.message == SECRET_REJECTED) throw e
            patch("System", """{"$name":$enabled}""")
        }
    }

    /**
     * Boiler readings as (label, value) pairs. Only has content when the hub is
     * wired to the boiler over OpenTherm; otherwise the list is empty.
     */
    fun fetchOpenTherm(): List<Pair<String, String>> {
        val text = attempt("GET", "/data/v2/opentherm/", null)
            ?: attempt("GET", "/data/v2/openTherm/", null)
            ?: return emptyList()
        val json = JSONObject(text)
        val live = json.optJSONObject("operationalData") ?: json
        val counters = json.optJSONObject("extendedDiagnostics") ?: json
        val out = mutableListOf<Pair<String, String>>()
        fun temp(label: String, key: String) {
            fromHub(live.optInt(key, NO_READING))?.let { out += label to formatTemp(it) }
        }
        temp("Flow temperature", "Ch1FlowTemperature")
        temp("Return temperature", "ChReturnTemperature")
        temp("Hot water temperature", "Dhw1Temperature")
        if (live.has("ChPressureBar")) {
            out += "Pressure" to String.format(Locale.getDefault(), "%.1f bar", live.optInt("ChPressureBar") / 10.0)
        }
        if (live.has("RelativeModulationLevel")) {
            out += "Modulation" to "${live.optInt("RelativeModulationLevel")}%"
        }
        if (counters.has("burnerHours")) out += "Burner hours" to counters.optInt("burnerHours").toString()
        if (counters.has("successfulBurnerStarts")) {
            out += "Burner starts" to counters.optInt("successfulBurnerStarts").toString()
        }
        return out
    }

    /** All heating and on/off schedules on the hub. Needs firmware with the v2 API. */
    fun fetchSchedules(): List<Schedule> {
        val text = attempt("GET", SCHEDULES_PATH, null)
            ?: throw IOException("This hub's firmware does not offer schedules over the local API")
        val json = JSONObject(text)
        val schedules = mutableListOf<Schedule>()
        for ((key, heating) in listOf("Heating" to true, "OnOff" to false)) {
            json.optJSONArray(key).forEachObject { o ->
                var wrapped = false
                val days = DAYS.associateWith { day ->
                    val slots = mutableListOf<Slot>()
                    if (heating) {
                        // {"Time": [630, 830], "DegreesC": [200, 160]}
                        val d = o.optJSONObject(day)
                        val times = d?.optJSONArray("Time")
                        val temps = d?.optJSONArray("DegreesC")
                        if (times != null && temps != null) {
                            for (j in 0 until minOf(times.length(), temps.length())) {
                                slots += Slot(fromHhmm(times.getInt(j)), temps.getInt(j))
                            }
                        }
                    } else {
                        // [630, -830]: positive turns on, negative turns off.
                        val list = when (val raw = o.opt(day)) {
                            is JSONArray -> raw
                            is JSONObject -> {
                                wrapped = true
                                raw.optJSONArray("Time")
                            }
                            else -> null
                        }
                        if (list != null) {
                            for (j in 0 until list.length()) {
                                val v = list.getInt(j)
                                val time = abs(v)
                                // "Off at midnight" cannot be -0, so the hub uses -2400.
                                slots += Slot(fromHhmm(if (time >= 2400) 0 else time), if (v < 0) 0 else 1)
                            }
                        }
                    }
                    slots.sortedBy { it.minutes }
                }
                val next = o.optJSONObject("Next")
                val nextValue = when {
                    next == null -> null
                    heating -> if (next.has("DegreesC")) next.optInt("DegreesC") else null
                    else -> next.optStringOrNull("State")?.let { if (it == "On") 1 else 0 }
                }
                schedules += Schedule(
                    id = o.getInt("id"),
                    name = o.optString("Name"),
                    heating = heating,
                    days = days,
                    onOffWrapped = wrapped,
                    nextValue = nextValue,
                    nextDay = next?.optStringOrNull("Day"),
                    nextMinutes = if (next != null && next.has("Time")) fromHhmm(next.optInt("Time")) else null,
                )
            }
        }
        return schedules
    }

    /** Replaces all seven days of [schedule] on the hub. */
    fun saveSchedule(schedule: Schedule) {
        val body = JSONObject()
        for (day in DAYS) {
            val slots = schedule.days[day].orEmpty().sortedBy { it.minutes }
            if (schedule.heating) {
                body.put(
                    day,
                    JSONObject()
                        .put("Time", JSONArray(slots.map { toHhmm(it.minutes) }))
                        .put("DegreesC", JSONArray(slots.map { it.value })),
                )
            } else {
                val times = JSONArray(slots.map {
                    val time = toHhmm(it.minutes)
                    when {
                        it.value != 0 -> time
                        time == 0 -> -2400
                        else -> -time
                    }
                })
                body.put(day, if (schedule.onOffWrapped) JSONObject().put("Time", times) else times)
            }
        }
        val type = if (schedule.heating) "Heating" else "OnOff"
        attempt("PATCH", "$SCHEDULES_PATH$type/${schedule.id}", body.toString())
            ?: throw IOException("The hub did not recognise that schedule")
    }

    fun renameSchedule(schedule: Schedule, name: String) {
        attempt("PATCH", schedulePath(schedule), JSONObject().put("Name", name).toString())
            ?: throw IOException("The hub did not recognise that schedule")
    }

    /** Creates an empty heating schedule that is not yet used by any room. */
    fun createHeatingSchedule(name: String) {
        val body = JSONObject()
            .put("Assignments", JSONArray())
            .put("Heating", JSONObject().put("Name", name))
        attempt("POST", "${SCHEDULES_PATH}Assign", body.toString())
            ?: throw IOException("This hub's firmware cannot create schedules over the local API")
    }

    fun deleteSchedule(schedule: Schedule) {
        attempt("DELETE", schedulePath(schedule), null)
            ?: throw IOException("The hub did not recognise that schedule")
    }

    /** Overwrites the days of schedule [toId] with those of [from]. */
    fun copySchedule(from: Schedule, toId: Int) = saveSchedule(from.copy(id = toId))

    /** Makes [roomIds] (all of them, not just new ones) follow [schedule]. */
    fun assignSchedule(schedule: Schedule, roomIds: List<Int>) {
        val body = JSONObject()
            .put("Assignments", JSONArray(roomIds.distinct()))
            .put("Heating", JSONObject().put("id", schedule.id).put("Name", schedule.name))
        attempt("PATCH", "${SCHEDULES_PATH}Assign", body.toString())
            ?: throw IOException("This hub's firmware cannot assign schedules over the local API")
    }

    private fun schedulePath(schedule: Schedule) =
        "$SCHEDULES_PATH${if (schedule.heating) "Heating" else "OnOff"}/${schedule.id}"

    private fun patch(path: String, body: String) {
        request("PATCH", path, body)
    }

    private fun request(method: String, path: String, body: String?): String {
        // Newer firmware serves /data/v2/domain/, older firmware only /data/domain/.
        for (base in BASE_PATHS) {
            attempt(method, "$base$path", body)?.let { return it }
        }
        throw IOException("Hub returned HTTP 404 for $path")
    }

    /** One request, switching to HTTPS if the hub asks for it. Returns null on 404. */
    private fun attempt(method: String, fullPath: String, body: String?): String? {
        repeat(2) {
            val https = host in httpsHosts
            val conn = open(URL((if (https) "https" else "http") + "://$host$fullPath"))
            try {
                conn.requestMethod = method
                conn.connectTimeout = 5_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("SECRET", secret)
                conn.setRequestProperty("Content-Type", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                when {
                    // Newer firmware redirects plain HTTP to HTTPS. Retry on HTTPS at the
                    // same address rather than trusting the Location header.
                    code in 300..399 && !https -> {
                        httpsHosts += host
                        return@repeat
                    }
                    code == 404 -> return null
                    code == 401 || code == 403 -> throw IOException(SECRET_REJECTED)
                    code !in 200..299 -> throw IOException("Hub returned HTTP $code")
                    else -> return conn.inputStream.bufferedReader().use { it.readText() }
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Hub kept redirecting")
    }

    private fun open(url: URL): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = hubSocketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        return conn
    }

    companion object {
        const val MIN_TEMP_C = 5.0
        const val MAX_TEMP_C = 30.0

        /** Setpoint meaning "off" (-20°C in tenths), for rooms, schedules and hot water. */
        const val SCHEDULE_OFF = -200

        /** Day names exactly as the hub's schedule API spells them. */
        val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        private val BASE_PATHS = listOf("/data/v2/domain/", "/data/domain/")
        private const val SECRET_REJECTED = "The hub rejected the secret"
        private const val SCHEDULES_PATH = "/data/v2/schedules/"
        private const val CANCEL_OVERRIDE = """{"RequestOverride":{"Type":"None"}}"""
        const val HOT_WATER_ON = 1100
        private const val NO_READING = -32768

        /** Hubs that have told us to use HTTPS, so later calls skip the redirect. */
        private val httpsHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * The hub's HTTPS certificate is self-signed and issued for no particular
         * address, so it cannot be verified. This factory accepts it, and is only
         * ever attached to connections to the hub address the user entered.
         */
        @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
        private val hubSocketFactory: SSLSocketFactory by lazy {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }.socketFactory
        }

        /** Hub values are tenths of a degree; out-of-range values mean "no reading". */
        private fun fromHub(raw: Int): Double? = if (raw in -400..1999) raw / 10.0 else null

        private val BOILER_KEY = Regex("HeatSource|ControlType|Boiler|Fuel|HydronicChannel|HotWaterChannel")

        /**
         * Everything in the hub's "BoilerSettings" block, plus any other simple
         * system setting whose name looks boiler related.
         */
        private fun boilerSettings(sys: JSONObject): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            fun collect(source: JSONObject, filter: Boolean) {
                for (key in source.keys()) {
                    if (filter && !BOILER_KEY.containsMatchIn(key)) continue
                    val value = source.opt(key)
                    if (value is String || value is Number || value is Boolean) out += key to value.toString()
                }
            }
            sys.optJSONObject("BoilerSettings")?.let { collect(it, filter = false) }
            collect(sys, filter = true)
            return out
        }

        private fun fromHhmm(hhmm: Int): Int = (hhmm / 100) * 60 + hhmm % 100
        private fun toHhmm(minutes: Int): Int = (minutes / 60) * 100 + minutes % 60

        private inline fun JSONArray?.forEachObject(block: (JSONObject) -> Unit) {
            if (this == null) return
            for (i in 0 until length()) optJSONObject(i)?.let(block)
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

        private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
            if (has(key) && !isNull(key)) optBoolean(key) else null

        fun formatTemp(c: Double?): String =
            if (c == null) "--" else String.format(Locale.getDefault(), "%.1f°", c)

        fun formatSetPoint(c: Double?): String = when {
            c == null -> "--"
            c < MIN_TEMP_C -> "Off"
            else -> formatTemp(c)
        }
    }
}
