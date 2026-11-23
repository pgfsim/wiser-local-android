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
    /** HydronicRadiator, HydronicUFH, ElectricRadiator or ElectricUFH, if the hub reports it. */
    val heatingType: String? = null,
    /** Occupancy settings, only for rooms the hub marks OccupancyCapable. */
    val occupancy: RoomOccupancy? = null,
    /** The room can cool as well as heat (the hub reports HvacMode for it). */
    val cooling: Boolean = false,
)

/**
 * How a room reacts to nobody being in it: it drops by [unoccupiedOffsetTenths] (tenths of a
 * degree) once it has been empty for [delayMinutes], and counts as occupied after
 * [minOccupiedMinutes] of presence.
 */
data class RoomOccupancy(val unoccupiedOffsetTenths: Int, val delayMinutes: Int, val minOccupiedMinutes: Int)

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
    /** Whether the hub installs firmware updates by itself, or null if it doesn't say. */
    val autoFirmwareUpdates: Boolean? = null,
    /** IANA time zone the hub follows, such as Europe/London, which sets its summer time. */
    val timeZoneId: String?,
    /** Hub clock offset from UTC in minutes. */
    val timeZoneOffset: Int?,
    /** The hub's local time of day in minutes after midnight, as of the last refresh. */
    val hubTime: Int? = null,
    /** Whether the hub's hot water channel is in use, or null if this hub can't switch it. */
    val hotWaterChannel: Boolean? = null,
    /**
     * "Legacy PowerTag energy mode" (the hub's Zigbee LegacyLongPacketSupport), or null
     * if the hub doesn't report it.
     */
    val legacyPowerTagMode: Boolean? = null,
    /** The hub's Zigbee network: channel, network id, address and so on, as label/value pairs. */
    val zigbee: List<Pair<String, String>> = emptyList(),
)

data class Device(
    val id: Int,
    /** Hub product type, e.g. "RoomStat", "iTRV" or "Controller". */
    val type: String,
    /** The room this device is in, or null if it isn't in one. */
    val roomId: Int? = null,
    val roomName: String?,
    /** Battery level as the hub words it, e.g. "Normal" or "Low". */
    val battery: String?,
    val batteryVolts: Double?,
    val signal: String?,
    /** Signal strength at the hub in dBm (ReceptionOfController.Rssi), if reported. */
    val rssi: Int? = null,
    val firmware: String?,
    /** Whether the device's own buttons are locked, or null if it has none. */
    val locked: Boolean?,
    /** The device's own temperature reading (radiator valves and room thermostats). */
    val tempC: Double?,
    /** The target the device is working to, if it reports one. */
    val setPointC: Double?,
    /** How far open a radiator valve is asking to be, 0 to 100. */
    val demandPercent: Int?,
    /** A name given in the official app or here, if any. */
    val name: String? = null,
    /** Radiator valves: 0 alkaline, 1 rechargeable (NiCd/NiMH), if reported. */
    val batteryType: Int? = null,
)

/** A scene set up in the official app, which the hub can run on request. */
/** True when the boiler type is OpenTherm rather than Standard (relay). */
val SystemInfo.usesOpenTherm: Boolean
    get() = boiler.any { (key, value) -> key == "ControlType" && value.contains("OpenTherm") }

/** A boiler setting sent over OpenTherm, in °C, with the range the boiler accepts. */
data class OpenThermSetpoint(val valueC: Double?, val minC: Double, val maxC: Double)

/** What the hub's OpenTherm block reports; only filled in once OpenTherm is chosen as the boiler type. */
data class OpenThermInfo(
    /** Readings from the boiler as (label, value) pairs; empty unless it answers over OpenTherm. */
    val readings: List<Pair<String, String>>,
    /** "Domestic Hot Water Setpoint" in the official app. */
    val hotWater: OpenThermSetpoint,
    /** "Max. Central Heating Water Temperature" in the official app. */
    val maxHeating: OpenThermSetpoint,
) {
    companion object {
        val NONE = OpenThermInfo(emptyList(), OpenThermSetpoint(null, 35.0, 55.0), OpenThermSetpoint(null, 35.0, 80.0))
    }
}

data class Moment(val id: Int, val name: String, val iconId: Int = 0)

/**
 * One thing a Moment does to a room or the hot water. [domain] is "Room" or "HotWater".
 * [type] is the override the official app sends: "Manual" holds [setPoint] (tenths of a
 * degree; [WiserHub.SCHEDULE_OFF] for off, 1100 for hot water on), for [durationMinutes]
 * if set; "Boost" boosts a room for [durationMinutes]; "None" cancels a boost or
 * override, so it goes back to its schedule.
 */
data class MomentAction(
    val domain: String,
    val id: Int,
    val setPoint: Int,
    val durationMinutes: Int? = null,
    val type: String = TYPE_MANUAL,
    /** Cooling mode ("Cool", "Heat", ...), "On"/"Off", or a shutter's "LiftTo"/"TiltTo". */
    val text: String? = null,
) {
    companion object {
        const val TYPE_MANUAL = "Manual"
        const val TYPE_BOOST = "Boost"
        const val TYPE_NONE = "None"
        /** A pause before the next action, [durationMinutes] long (stored as HHMM). */
        const val TYPE_WAIT = "Wait"
        /** Sends a notification to the phone (official app only). */
        const val TYPE_NOTIFY = "Notify"
        /** A room without a temperature input: heating level [setPoint] percent. */
        const val TYPE_PERCENT = "Percent"
        /** A cooling room's mode, in [text] (Cool, Heat, Fan, Dry, Auto, Stop). */
        const val TYPE_HVAC = "HvacMode"
        /** A cooling room switched on or off, in [text]. */
        const val TYPE_CLIMATE = "ClimateOnOff"
        /** A light: [setPoint] percent (0 off; 100 on for lights that don't dim). */
        const val TYPE_LIGHT = "Light"
        /** A smart plug: [setPoint] 1 on, 0 off. */
        const val TYPE_PLUG = "Plug"
        /** A shutter: [text] LiftTo or TiltTo, [setPoint] percent. */
        const val TYPE_SHUTTER = "Shutter"
        /** An EV charger or socket switched on/off ([setPoint] 1/0): OverrideRequest {"Output"}. */
        const val TYPE_EV_OUTPUT = "EvOutput"
        /** An EV charger's charging started or paused ([text] Start/Pause): RequestAction. */
        const val TYPE_EV_ACTION = "EvAction"
        /** A fan at [text] speed (Off, Low, Medium, High). */
        const val TYPE_FAN = "Fan"
        /** A PowerTag C output switched on/off ([setPoint] 1/0). */
        const val TYPE_PTC = "PowerTagC"
    }
}

/** An automation as listed in the hub's domain data: name, icon and whether it is on. */
data class Automation(val id: Int, val name: String, val iconId: Int, val enabled: Boolean, val notify: Boolean)

data class HubState(
    val rooms: List<Room>,
    val hotWater: HotWater?,
    val system: SystemInfo,
    val devices: List<Device>,
    val moments: List<Moment>,
    val automations: List<Automation> = emptyList(),
    /** What this hub's firmware supports. */
    val features: HubFeatures = HubFeatures(),
    /** Lights, smart plugs and shutters, for Moments. Empty on hubs without them. */
    val lights: List<SimpleDevice> = emptyList(),
    val plugs: List<SimpleDevice> = emptyList(),
    val shutters: List<SimpleDevice> = emptyList(),
    val evChargers: List<SimpleDevice> = emptyList(),
    val fans: List<SimpleDevice> = emptyList(),
    val powerTagCs: List<SimpleDevice> = emptyList(),
    /** The hub's heating channels (relays), with the rooms each one heats. */
    val heatingChannels: List<HeatingChannel> = emptyList(),
    /** The hub's whole domain data, for the generic equipment pages ([Equipment]). */
    val domain: org.json.JSONObject? = null,
    /** Seasonal comfort settings (/data/v2/seasonalComfort/), on hubs that have it. */
    val seasonalComfort: org.json.JSONObject? = null,
)

/** A light, smart plug or shutter: [extra] is "dims" for a dimmable light, "tilts" for a tilting shutter. */
data class SimpleDevice(val id: Int, val name: String, val extra: Boolean = false)

/** One heating channel (relay) and the rooms it heats. */
data class HeatingChannel(val id: Int, val name: String, val roomIds: List<Int>)

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
    /** A "Level" schedule (lights, shutters, fans): slot values are percentages. */
    val level: Boolean = false,
    /** For level schedules: Lighting, Shutters or Fan3Speed. */
    val levelType: String? = null,
    /** Value of the next scheduled change (same units as [Slot.value]), if known. */
    val nextValue: Int? = null,
    /** What the schedule asks for right now (heating: tenths of a degree), if the hub says. */
    val currentValue: Int? = null,
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
/** The hub answered with a server error (5xx): it is reachable but couldn't handle the request. */
class HubServerError(val code: Int) : IOException("Hub returned HTTP $code")

class WiserHub(
    host: String,
    private val secret: String,
    /** Experimental: used when the hub can't be reached on this network. */
    private val relay: CloudRelay? = null,
) {

    /** True once a request from this instance went through Wiser's cloud. */
    @Volatile var usedCloud = false
        private set

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
        val deviceRooms = mutableMapOf<Int, Int>()
        val roomNames = mutableMapOf<Int, String>()
        json.optJSONArray("Room").forEachObject { o ->
            val id = o.getInt("id")
            val name = o.optString("Name", "Room $id")
            roomNames[id] = name
            val statId = o.optInt("RoomStatId", 0)
            if (statId > 0) deviceRooms[statId] = id
            for (key in listOf("SmartValveIds", "HeatingActuatorIds", "UfhRelayIds")) {
                val ids = o.optJSONArray(key) ?: continue
                for (i in 0 until ids.length()) deviceRooms[ids.optInt(i)] = id
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
                heatingType = o.optStringOrNull("HeatingType"),
                cooling = o.has("HvacMode"),
                occupancy = if (o.optBoolean("OccupancyCapable", false)) RoomOccupancy(
                    o.optInt("UnoccupiedOffset", 0),
                    o.optInt("OccupiedToUnoccupiedDelay", 1),
                    o.optInt("MinimumOccupiedTime", 1),
                ) else null,
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
            autoFirmwareUpdates = sys.optBooleanOrNull("FotaEnabled"),
            timeZoneId = sys.optStringOrNull("TimeZoneIdentifier"),
            timeZoneOffset = if (sys.has("TimeZoneOffset")) sys.optInt("TimeZoneOffset") else null,
            hubTime = sys.optJSONObject("LocalDateAndTime")?.takeIf { it.has("Time") }
                ?.optInt("Time")?.let { it / 100 * 60 + it % 100 },
            // The official app only offers the switch when the hub says the channel can be switched.
            hotWaterChannel = if (sys.optBoolean("HotWaterChannelSupported")) sys.optBoolean("HotWaterEnabled", true) else null,
            legacyPowerTagMode = json.optJSONObject("Zigbee")?.optStringOrNull("LegacyLongPacketSupport")
                ?.takeUnless { it.contains("NotSupported") }
                // The official app counts both "enabled" states (automatically once, or by the user) as on.
                ?.let { it.startsWith("Enabled") },
            zigbee = json.optJSONObject("Zigbee")?.let { z ->
                listOfNotNull(
                    z.takeIf { it.has("NetworkChannel") }?.let { "Channel" to it.optInt("NetworkChannel").toString() },
                    z.takeIf { it.has("PanId") }?.let { "Network ID (PAN)" to "0x%04X".format(it.optInt("PanId")) },
                    z.optStringOrNull("ZigbeeEui")?.let { "Hub's Zigbee address" to it },
                    z.optStringOrNull("ZigbeeModuleVersion")?.let { "Zigbee radio version" to it },
                    z.optStringOrNull("ZigbeeAntenna")?.let { "Antenna" to spacedWords(it) },
                )
            }.orEmpty(),
        )

        val devices = mutableListOf<Device>()
        json.optJSONArray("Device").forEachObject { o ->
            val id = o.optInt("id")
            // Heating devices are listed on the room; others (plugs, lights) carry a RoomId.
            val roomId = deviceRooms[id] ?: o.optInt("RoomId", 0).takeIf { it > 0 && it in roomNames }
            devices += Device(
                id = id,
                type = o.optString("ProductType", "Device"),
                roomId = roomId,
                roomName = roomId?.let { roomNames[it] },
                battery = o.optStringOrNull("BatteryLevel"),
                batteryVolts = if (o.has("BatteryVoltage")) o.optInt("BatteryVoltage") / 10.0 else null,
                signal = o.optStringOrNull("DisplayedSignalStrength"),
                rssi = o.optJSONObject("ReceptionOfController")?.takeIf { it.has("Rssi") }?.optInt("Rssi"),
                firmware = o.optStringOrNull("ActiveFirmwareVersion"),
                locked = o.optBooleanOrNull("DeviceLockEnabled"),
                tempC = readings[id]?.let { fromHub(it.optInt("MeasuredTemperature", NO_READING)) },
                setPointC = readings[id]?.let { fromHub(it.optInt("SetPoint", NO_READING)) },
                demandPercent = readings[id]?.takeIf { it.has("PercentageDemand") }?.optInt("PercentageDemand"),
                name = o.optStringOrNull("Name")?.takeIf { it.isNotBlank() },
                batteryType = o.takeIf { it.has("BatteryType") }?.let { b ->
                    // The hub may report the number or the enum name.
                    b.optString("BatteryType").let { t -> t.toIntOrNull() ?: if (t.startsWith("NiCd", true)) 1 else 0 }
                },
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

        val automations = mutableListOf<Automation>()
        json.optJSONArray("Automation").forEachObject { o ->
            val id = o.optInt("id")
            val name = o.optStringOrNull("Name")?.let { raw ->
                try { URLDecoder.decode(raw, "UTF-8") } catch (e: IllegalArgumentException) { raw }
            } ?: "Automation $id"
            automations += Automation(id, name, o.optInt("IconId", 0), o.optBoolean("Enabled", true), o.optBoolean("EnableNotification", false))
        }

        fun simple(key: String, extraKey: String?, label: String = key): List<SimpleDevice> {
            val out = mutableListOf<SimpleDevice>()
            json.optJSONArray(key).forEachObject { o ->
                val id = o.optInt("id")
                out += SimpleDevice(id, o.optStringOrNull("Name") ?: "$label $id", extraKey?.let { o.optBoolean(it, false) } ?: false)
            }
            return out
        }
        val channels = mutableListOf<HeatingChannel>()
        json.optJSONArray("HeatingChannel").forEachObject { o ->
            val ids = o.optJSONArray("RoomIds")
            val id = o.optInt("id")
            channels += HeatingChannel(
                id,
                o.optStringOrNull("Name")?.replace('-', ' ') ?: "Channel $id",
                if (ids == null) emptyList() else List(ids.length()) { ids.optInt(it) },
            )
        }
        return HubState(
            rooms, hotWater, system, devices, moments, automations, HubFeatures.parse(json),
            lights = simple("Light", "IsDimmable", "Light"),
            plugs = simple("SmartPlug", null, "Smart plug"),
            shutters = simple("Shutter", "IsTiltSupported"),
            evChargers = simple("EVSE", null, "EV charger"),
            fans = simple("FanController", null, "Fan"),
            powerTagCs = simple("PTC", null, "PowerTag C"),
            heatingChannels = channels,
            domain = json,
            seasonalComfort = if (json.optJSONObject("FeatureCapability")?.optBoolean("SeasonalComfortAvailable") == true) {
                runCatching { attempt("GET", "/data/v2/seasonalComfort/", null)?.let { JSONObject(it) } }.getOrNull()
            } else null,
        )
    }

    fun setRoomTemp(roomId: Int, tempC: Double) {
        val setPoint = (tempC.coerceIn(MIN_TEMP_C, MAX_TEMP_C) * 10).roundToInt()
        patch("Room/$roomId", """{"RequestOverride":{"Type":"Manual","SetPoint":$setPoint}}""")
    }

    /** Drops any manual override or boost so the room follows its schedule again. */
    fun resumeRoomSchedule(roomId: Int) = patch("Room/$roomId", CANCEL_OVERRIDE)

    /** Boosts the room for [minutes], then reverts. The hub decides how much, as with the official app. */
    fun boostRoom(roomId: Int, minutes: Int) =
        patch("Room/$roomId/RequestOverride/", """{"Type":"Boost","DurationMinutes":$minutes}""")

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
        request("POST", "Room/", JSONObject().put("Name", name).toString())
    }

    /** Removes the room from the hub. Its devices stay paired but unassigned. */
    fun deleteRoom(roomId: Int) {
        request("DELETE", "Room/$roomId", null)
    }

    /** The room's heating type, as the official app sets it on firmware 2.55.0 and later. */
    fun setRoomHeatingType(roomId: Int, type: String) =
        patch("Room/$roomId", JSONObject().put("HeatingType", type).toString())

    /** All three occupancy settings at once, as the official app sends them. */
    fun setRoomOccupancy(roomId: Int, occupancy: RoomOccupancy) = patch(
        "Room/$roomId",
        JSONObject()
            .put("UnoccupiedOffset", occupancy.unoccupiedOffsetTenths)
            .put("OccupiedToUnoccupiedDelay", occupancy.delayMinutes)
            .put("MinimumOccupiedTime", occupancy.minOccupiedMinutes)
            .toString(),
    )

    /**
     * Puts the room in exactly the channels [channelIds], as the official app does: the
     * hub's whole channel list goes back with the room's id moved between channels.
     */
    fun setRoomChannels(channels: List<HeatingChannel>, roomId: Int, channelIds: Set<Int>) {
        val list = JSONArray()
        channels.forEach { c ->
            val rooms = c.roomIds.filter { it != roomId } + listOfNotNull(roomId.takeIf { c.id in channelIds })
            list.put(JSONObject().put("id", c.id).put("RoomIds", JSONArray(rooms)))
        }
        request("PATCH", "", JSONObject().put("HeatingChannel", list).toString())
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
        patch("System/RequestOverride", """{"Type":"${if (enabled) "Away" else "None"}"}""")

    /** Boosts every room for an hour; the official app offers no other length. */
    fun boostAllRooms() = patch("System/RequestOverride", """{"Type":"Boost","DurationMinutes":60}""")

    /** Cancels every boost and manual override in the house. */
    fun cancelAllOverrides() = patch("System", """{"RequestOverride":{"Type":"CancelUserOverrides"}}""")

    fun setAwayTemp(tempC: Double) =
        patch("System", """{"AwayModeSetPointLimit":${(tempC.coerceIn(MIN_TEMP_C, MAX_TEMP_C) * 10).roundToInt()}}""")

    // The reference library sends these two flags as the strings "true"/"false".
    fun setAwayAffectsHotWater(enabled: Boolean) =
        patch("System/AwayModeAffectsHotWater", enabled.toString())

    /** Sets the IANA time zone (Europe/London); the hub works out summer time from it. */
    fun setTimeZone(id: String) =
        patch("System/", JSONObject().put("TimeZoneIdentifier", id).toString())

    /** Sets the hub clock to [unixSeconds] (UTC). */
    fun setHubClock(unixSeconds: Long) = patch("System/", """{"UpdateUnixTime":$unixSeconds}""")

    fun setTimeZoneOffset(offset: Int) = patch("System/", """{"TimeZoneOffset":$offset}""")

    /** Lets the hub install firmware updates by itself, as the official app does. */
    // Same request as the official app (EnableHubUpgrade). On controller firmware 4.52.3 the
    // hub answers 404 for this path even though System lists FotaEnabled; kept for other hubs.
    fun setAutoFirmwareUpdates(enabled: Boolean) = patch("System/FotaEnabled", enabled.toString())

    /** Restarts the hub; it stops answering for a few minutes. */
    fun restartHub() = patch("System/RequestReboot", "true")

    fun setValveProtection(enabled: Boolean) = patch("System", """{"ValveProtectionEnabled":$enabled}""")

    /** Turns the hub's hot water channel on or off, as the official app does. */
    fun setHotWaterChannel(enabled: Boolean) = patch("System/HotWaterEnabled", enabled.toString())

    /** "Legacy PowerTag energy mode": lets older PowerTag energy meters report over Zigbee. */
    fun setLegacyPowerTagMode(enabled: Boolean) = patch("Zigbee/RequestLegacyLongPacketSupport", enabled.toString())

    /**
     * The heat source type: "Gas", "Oil", "Electric" or "HeatPump". The hub then picks the
     * boiler's cycle rate itself (3 an hour for oil, 6 for gas).
     */
    fun setFuelType(fuel: String) = patch("System/BoilerSettings/", """{"FuelType":"$fuel"}""")

    /** The boiler type: "HeatSourceType_RelayControlled" (Standard) or "HeatSourceType_OpenTherm". */
    fun setBoilerControlType(type: String) = patch("System/BoilerSettings/", """{"ControlType":"$type"}""")

    /** Lets new devices join the hub for [seconds]. */
    fun permitJoin(seconds: Int) = patch("System/RequestPermitJoin", seconds.toString())

    /** Stops the hub accepting new devices, as the official app does ("0"). */
    fun stopPairing() = patch("System/RequestPermitJoin", "0")

    /**
     * Lets one device with an install code join (devices that ask for one, like some
     * PowerTags): the official app sends the device's MAC as IeeeAddress with its code.
     */
    fun pairWithInstallCode(ieeeAddress: String, installCode: String, seconds: Int = 120) = patch(
        "System/RequestZigbeeJoin",
        JSONObject().put("Timeout", seconds).put("IeeeAddress", ieeeAddress).put("InstallCode", installCode).toString(),
    )

    fun renameDevice(deviceId: Int, name: String) =
        patch("Device/$deviceId/", JSONObject().put("Name", name).toString())

    /** Radiator valves only: 0 alkaline, 1 rechargeable. */
    fun setBatteryType(deviceId: Int, type: Int) =
        patch("Device/$deviceId/", JSONObject().put("BatteryType", type).toString())

    fun setDeviceLock(deviceId: Int, locked: Boolean) =
        patch("Device/$deviceId", """{"DeviceLockEnabled":$locked}""")

    /** Starts or stops the device flashing so it can be told apart from others. */
    /**
     * Moves [device] into the room [toRoomId], or out of its room when null. The hub keeps
     * this on the room for heating devices (RoomStatId, SmartValveIds) and on the device for
     * the rest (RoomId), so take it out of the old room first, then add it to the new one.
     */
    fun moveDevice(state: HubState, device: Device, toRoomId: Int?) {
        val fromRoomId = device.roomId
        if (fromRoomId == toRoomId) return
        when (device.type) {
            "RoomStat" -> {
                if (fromRoomId != null) request("DELETE", "Room/$fromRoomId/RoomStatId", null)
                if (toRoomId != null) patch("Room/$toRoomId/", """{"RoomStatId":${device.id}}""")
            }
            "iTRV" -> {
                fun valves(roomId: Int) = state.devices.filter { it.type == "iTRV" && it.roomId == roomId }.map { it.id }
                if (fromRoomId != null) setRoomValves(fromRoomId, valves(fromRoomId) - device.id)
                if (toRoomId != null) setRoomValves(toRoomId, valves(toRoomId) + device.id)
            }
            else -> {
                if (toRoomId != null) patch("Device/${device.id}", """{"RoomId":$toRoomId}""")
                else request("DELETE", "Device/${device.id}/RoomId", null)
            }
        }
    }

    private fun setRoomValves(roomId: Int, ids: List<Int>) =
        patch("Room/$roomId/", JSONObject().put("SmartValveIds", JSONArray(ids)).toString())

    /** Removes the device from the hub entirely; it has to be paired again to come back. */
    fun removeDevice(deviceId: Int) {
        request("DELETE", "Device/$deviceId/", null)
    }

    fun identifyDevice(deviceId: Int, on: Boolean) = patch("Device/$deviceId", """{"Identify":$on}""")

    /** The hub's Wi-Fi details as (label, value) pairs; empty if it does not report them. */
    /** Sends one of the [Equipment] requests: paths under /data/v2/domain/, or /data/v2 when they start with "/". */
    fun send(req: Req) {
        if (req.path.startsWith("/")) {
            attempt(req.method, "/data/v2${req.path}", req.body) ?: throw IOException("Hub returned HTTP 404 for ${req.path}")
        } else {
            request(req.method, req.path, req.body)
        }
    }

    /** The hub's Wi-Fi MAC address, which also names it in Wiser's cloud. */
    fun fetchMacAddress(): String? {
        val text = attempt("GET", "/data/v2/network/", null) ?: return null
        return JSONObject(text).optJSONObject("Station")?.optStringOrNull("MacAddress")
    }

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

    /**
     * Renames a Moment or changes its icon without touching what it does, for Moments
     * this app can't describe: their actions are read back from the hub exactly as
     * stored and sent again, the way the official app saves an edit.
     */
    fun rewriteMoment(momentId: Int, name: String, iconId: Int) {
        // The hub answers with LF between actions; the official app sends CRLF.
        val body = rootRequest("GET", "Moment/$momentId", null).trim().lines().joinToString("\r\n")
        rootRequest("PATCH", "Moment/$momentId?" + momentQuery(name, iconId), body)
    }

    /**
     * Creates a Moment, in the format the official app uses: name and icon in the
     * query string, one action per line in the body, each override as a JSON string.
     */
    fun createMoment(name: String, iconId: Int, actions: List<MomentAction>) {
        rootRequest("POST", "Moment?" + momentQuery(name, iconId), momentBody(actions))
    }

    /**
     * What a Moment does, read with GET Moment/{id}. Null when any part of it is
     * something this app cannot describe (for example a room boost or a wait), so
     * that saving from this app never drops what the official app set up.
     */
    fun fetchMomentActions(momentId: Int): List<MomentAction>? {
        val objects = momentObjects(rootRequest("GET", "Moment/$momentId", null)) ?: return null
        if (objects.isEmpty()) return null
        return objects.map { parseMomentAction(it) ?: return null }
    }

    private fun parseMomentAction(json: JSONObject): MomentAction? {
        // A wait or a notification is a line of its own, with every other field empty.
        val noTarget = json.isNull("Domain") || json.optString("Domain").isEmpty()
        if (noTarget && json.has("Wait") && !json.isNull("Wait")) {
            val hhmm = json.optInt("Wait")
            return MomentAction("", 0, 0, hhmm / 100 * 60 + hhmm % 100, MomentAction.TYPE_WAIT)
        }
        if (noTarget && json.optBoolean("EnableNotification", false)) {
            return MomentAction("", 0, 0, null, MomentAction.TYPE_NOTIFY)
        }
        // Extras the official app sends as null; anything set alongside an action is beyond this app.
        if (json.has("Wait") && !json.isNull("Wait")) return null
        if (json.optBoolean("EnableNotification", false)) return null
        val id = json.optInt("Id")
        val raw = json.optString("Value")
        when (json.optString("Domain") to json.optString("Action")) {
            "Room" to "HVACMode" -> return MomentAction("Room", id, 0, null, MomentAction.TYPE_HVAC, raw)
            "Room" to "ClimateOnOff" -> return MomentAction("Room", id, 0, null, MomentAction.TYPE_CLIMATE, raw)
            "EVSE" to "OverrideRequest", "PTC" to "OverrideRequest" -> {
                val v = raw.takeIf { it.startsWith("{") }?.let { JSONObject(it) } ?: return null
                val on = if (v.optString("Output").equals("On", true)) 1 else 0
                val type = if (json.optString("Domain") == "EVSE") MomentAction.TYPE_EV_OUTPUT else MomentAction.TYPE_PTC
                return MomentAction(json.optString("Domain"), id, on, null, type)
            }
            "EVSE" to "RequestAction" -> return MomentAction("EVSE", id, 0, null, MomentAction.TYPE_EV_ACTION, raw)
            "FanController" to "RequestOverride" -> {
                val v = raw.takeIf { it.startsWith("{") }?.let { JSONObject(it) } ?: return null
                val speed = if (v.optString("state").equals("Off", true)) "Off" else v.optString("speed").ifEmpty { "Medium" }
                return MomentAction("FanController", id, 0, null, MomentAction.TYPE_FAN, speed)
            }
            "SmartPlug" to "RequestOutput" ->
                return MomentAction("SmartPlug", id, if (raw.equals("On", true)) 1 else 0, null, MomentAction.TYPE_PLUG)
            "Light" to "RequestOverride" -> {
                val v = raw.takeIf { it.startsWith("{") }?.let { JSONObject(it) } ?: return null
                val on = v.optString("state").equals("On", true)
                val level = if (!on) 0 else if (v.has("percentage")) v.optInt("percentage") else 100
                return MomentAction("Light", id, level, null, MomentAction.TYPE_LIGHT)
            }
            "Shutter" to "RequestAction" -> {
                val v = raw.takeIf { it.startsWith("{") }?.let { JSONObject(it) } ?: return null
                val action = v.optString("action").takeIf { it == "LiftTo" || it == "TiltTo" } ?: return null
                return MomentAction("Shutter", id, v.optInt("percentage").coerceIn(0, 100), null, MomentAction.TYPE_SHUTTER, action)
            }
        }
        if (json.optString("Action") != "RequestOverride") return null
        if (json.has("Wait") && !json.isNull("Wait")) return null
        if (json.has("EnableNotification") && !json.isNull("EnableNotification") && json.optBoolean("EnableNotification")) return null
        val value = json.optString("Value").takeIf { it.startsWith("{") }?.let { JSONObject(it) } ?: return null
        val domain = json.optString("Domain")
        if (domain != "Room" && domain != "HotWater") return null
        val minutes = value.takeIf { it.has("durationMinutes") }?.optInt("durationMinutes")
        val type = value.optString("type")
        return when {
            type.equals(MomentAction.TYPE_MANUAL, ignoreCase = true) && value.has("setPoint") ->
                MomentAction(domain, json.optInt("Id"), value.optInt("setPoint"), minutes)
            // A room boost: the hub raises the room for the given time, with no set point.
            type.equals(MomentAction.TYPE_BOOST, ignoreCase = true) && domain == "Room" && minutes != null && minutes > 0 ->
                MomentAction(domain, json.optInt("Id"), 0, minutes, MomentAction.TYPE_BOOST)
            // Cancels a boost or override: back to the schedule.
            type.equals(MomentAction.TYPE_NONE, ignoreCase = true) ->
                MomentAction(domain, json.optInt("Id"), 0, null, MomentAction.TYPE_NONE)
            // A room set by heating level rather than temperature.
            type.equals(MomentAction.TYPE_MANUAL, ignoreCase = true) && domain == "Room" && value.has("percentageDemand") ->
                MomentAction(domain, json.optInt("Id"), value.optInt("percentageDemand"), null, MomentAction.TYPE_PERCENT)
            else -> null
        }
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
        rootRequest("PATCH", "Moment/${moment.id}?" + momentQuery(moment.name, moment.iconId), momentBody(actions))
    }

    private fun momentQuery(name: String, iconId: Int) =
        // Spaces go as "+", exactly as the official app sends them, so both apps read the name back the same way.
        "Name=" + URLEncoder.encode(name, "UTF-8") + "&IconId=$iconId"

    /**
     * The actions one per line (CRLF, which matches the length the official app sent),
     * each with its override as a JSON string inside "Value".
     */
    private fun momentBody(actions: List<MomentAction>): String = actions.joinToString("\r\n") { action ->
        if (action.type == MomentAction.TYPE_WAIT || action.type == MomentAction.TYPE_NOTIFY) {
            // The official app's shape for these: every field empty except the one that matters.
            val minutes = action.durationMinutes ?: 0
            return@joinToString JSONObject()
                .put("Domain", JSONObject.NULL)
                .put("Id", JSONObject.NULL)
                .put("Action", JSONObject.NULL)
                .put("Value", JSONObject.NULL)
                .put("Wait", if (action.type == MomentAction.TYPE_WAIT) minutes / 60 * 100 + minutes % 60 else JSONObject.NULL)
                .put("EnableNotification", if (action.type == MomentAction.TYPE_NOTIFY) true else JSONObject.NULL)
                .toString()
        }
        // Other actions, in the official app's shapes (Value is a plain string or a JSON string).
        val other: Pair<String, String>? = when (action.type) {
            MomentAction.TYPE_HVAC -> "HVACMode" to (action.text ?: "Auto")
            MomentAction.TYPE_CLIMATE -> "ClimateOnOff" to (action.text ?: "On")
            MomentAction.TYPE_PLUG -> "RequestOutput" to (if (action.setPoint != 0) "On" else "Off")
            MomentAction.TYPE_LIGHT -> "RequestOverride" to JSONObject().put("state", if (action.setPoint > 0) "On" else "Off").also { o ->
                // Only dimmers get a level; the official app leaves it out for plain lights and for off.
                if (action.setPoint in 1..99) o.put("percentage", action.setPoint)
            }.toString()
            MomentAction.TYPE_SHUTTER -> "RequestAction" to JSONObject()
                .put("action", action.text ?: "LiftTo").put("percentage", action.setPoint.coerceIn(0, 100)).toString()
            MomentAction.TYPE_EV_OUTPUT, MomentAction.TYPE_PTC ->
                "OverrideRequest" to JSONObject().put("Output", if (action.setPoint != 0) "On" else "Off").toString()
            MomentAction.TYPE_EV_ACTION -> "RequestAction" to (action.text ?: "Start")
            MomentAction.TYPE_FAN -> "RequestOverride" to JSONObject().also { o ->
                val speed = action.text ?: "Off"
                o.put("state", if (speed == "Off") "Off" else "On")
                if (speed != "Off") o.put("speed", speed)
            }.toString()
            MomentAction.TYPE_PERCENT -> "RequestOverride" to JSONObject()
                .put("type", "Manual").put("percentageDemand", action.setPoint.coerceIn(0, 100)).toString()
            else -> null
        }
        if (other != null) {
            return@joinToString JSONObject()
                .put("Domain", action.domain)
                .put("Id", action.id)
                .put("Action", other.first)
                .put("Value", other.second)
                .put("Wait", JSONObject.NULL)
                .put("EnableNotification", JSONObject.NULL)
                .toString()
        }
        // The same three shapes the official app writes.
        val override = when (action.type) {
            MomentAction.TYPE_BOOST -> JSONObject().put("type", "Boost").put("durationMinutes", action.durationMinutes ?: 60)
            MomentAction.TYPE_NONE -> JSONObject().put("type", "None")
            else -> JSONObject().put("type", "Manual").put("setPoint", action.setPoint).also { o ->
                // A timed hot water boost; the official app adds the duration to the same override.
                action.durationMinutes?.let { o.put("durationMinutes", it) }
            }
        }
        JSONObject()
            .put("Domain", action.domain)
            .put("Id", action.id)
            .put("Action", "RequestOverride")
            .put("Value", override.toString())
            .put("Wait", JSONObject.NULL)
            .put("EnableNotification", JSONObject.NULL)
            .toString()
    }

    /**
     * What an automation does. Like Moments, this lives at the hub's root
     * (GET /Automation/{id}). The hub answers with capitalised keys but the official
     * app saves with lower-case first letters, so keys are converted on the way in.
     */
    fun fetchAutomation(id: Int): AutomationDetail =
        AutomationDetail.parse(camelKeys(JSONObject(rootRequest("GET", "Automation/$id", null))) as JSONObject)

    /**
     * Creates ([id] null) or replaces an automation, as the official app does: name,
     * on/off and icon in the query string, the whole automation in the body.
     */
    fun saveAutomation(id: Int?, name: String, enabled: Boolean, iconId: Int, detail: AutomationDetail) {
        val query = "Name=" + URLEncoder.encode(name, "UTF-8") + "&Enabled=$enabled&IconId=$iconId"
        val body = detail.toJson().toString()
        if (id == null) rootRequest("POST", "Automation?$query", body)
        else rootRequest("PATCH", "Automation/$id?$query", body)
    }

    /** Turns an automation on or off, as the official app does: the bare true/false to its Enabled path. */
    fun setAutomationEnabled(id: Int, enabled: Boolean) = patch("Automation/$id/Enabled", enabled.toString())

    fun deleteAutomation(id: Int) {
        request("DELETE", "Automation/$id", null)
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
     * The hub's OpenTherm block: boiler readings (only while the boiler answers over
     * OpenTherm) and the two settings the official app's OpenTherm Settings screen shows.
     */
    fun fetchOpenTherm(): OpenThermInfo {
        val text = attempt("GET", "/data/v2/openTherm/", null)
            ?: attempt("GET", "/data/v2/opentherm/", null)
            ?: return OpenThermInfo.NONE
        val json = JSONObject(text)
        val params = json.optJSONObject("preDefinedRemoteBoilerParameters") ?: JSONObject()
        fun setpoint(key: String, fallback: OpenThermSetpoint): OpenThermSetpoint {
            val min = fromHub(params.optInt(key + "LowerBound", NO_READING))
            val max = fromHub(params.optInt(key + "UpperBound", NO_READING))
            return OpenThermSetpoint(
                fromHub(params.optInt(key, NO_READING)),
                if (min != null && max != null && min < max) min else fallback.minC,
                if (min != null && max != null && min < max) max else fallback.maxC,
            )
        }
        // Without limits from the boiler, the hub's own heating flow range is the next best guide.
        val flowMin = fromHub(json.optInt("chFlowActiveLowerSetpoint", NO_READING))
        val flowMax = fromHub(json.optInt("chFlowActiveUpperSetpoint", NO_READING))
        val heatingFallback = if (flowMin != null && flowMax != null && flowMin < flowMax) {
            OpenThermSetpoint(null, flowMin, flowMax)
        } else OpenThermInfo.NONE.maxHeating
        return OpenThermInfo(
            readings = openThermReadings(json),
            hotWater = setpoint("dhwSetpoint", OpenThermInfo.NONE.hotWater),
            maxHeating = setpoint("maxChSetpoint", heatingFallback),
        )
    }

    /** "Domestic Hot Water Setpoint", in °C. */
    fun setOpenThermHotWater(tempC: Double) = setOpenThermParameter("dhwSetpoint", tempC)

    /** "Max. Central Heating Water Temperature", in °C. */
    fun setOpenThermMaxHeating(tempC: Double) = setOpenThermParameter("maxChSetpoint", tempC)

    private fun setOpenThermParameter(key: String, tempC: Double) {
        val body = """{"$key":${(tempC * 10).roundToInt()}}"""
        // Newer firmware spells it openTherm; older firmware opentherm.
        attempt("PATCH", "/data/v2/openTherm/preDefinedRemoteBoilerParameters/", body)
            ?: attempt("PATCH", "/data/v2/opentherm/preDefinedRemoteBoilerParameters/", body)
            ?: throw IOException("This hub does not offer OpenTherm settings")
    }

    private fun openThermReadings(json: JSONObject): List<Pair<String, String>> {
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
        // Burner and pump statistics from the boiler (the official app's extended diagnostics).
        fun count(label: String, vararg keys: String) {
            val key = keys.firstOrNull { counters.has(it) } ?: return
            out += label to counters.optLong(key).toString()
        }
        count("Burner hours", "burnerHours", "BurnerHours")
        count("Burner starts", "successfulBurnerStarts", "SuccessfulBurnerStarts")
        count("Failed burner starts", "unsuccessfulBurnerStarts", "UnsuccessfulBurnerStarts")
        count("Flame signal too low", "numberFlameSignalTooLow", "NumberFlameSignalTooLow")
        count("Burner hours on hot water", "burnerOnInDhwModeHours", "BurnerOnInDhwModeHours")
        count("Burner starts on hot water", "burnerStartsDuringDhwMode", "BurnerStartsDuringDhwMode")
        count("Heating pump hours", "chPumpHours", "ChPumpHours")
        count("Heating pump starts", "chPumpStarts", "ChPumpStarts")
        count("Hot water pump or valve hours", "dhwPumpOrValveHours", "DhwPumpOrValveHours")
        count("Hot water pump or valve starts", "dhwPumpOrValveStarts", "DhwPumpOrValveStarts")
        count("Boiler power cycles", "boilerPowerCycles", "BoilerPowerCycles")
        count("Service code", "oemSpecificServiceCode", "OemSpecificServiceCode")
        return out
    }

    /** All heating and on/off schedules on the hub. Needs firmware with the v2 API. */
    fun fetchSchedules(): List<Schedule> {
        val text = attempt("GET", SCHEDULES_PATH, null)
            ?: throw IOException("This hub's firmware does not offer schedules over the local API")
        val json = JSONObject(text)
        val schedules = mutableListOf<Schedule>()
        // Level schedules (lights, shutters, fans): {"Time": [630, 2200], "Level": [100, 0]}.
        json.optJSONArray("Level").forEachObject { o ->
            val days = DAYS.associateWith { day ->
                val d = o.optJSONObject(day)
                val times = d?.optJSONArray("Time")
                val levels = d?.optJSONArray("Level")
                val slots = mutableListOf<Slot>()
                if (times != null && levels != null) {
                    for (j in 0 until minOf(times.length(), levels.length())) slots += Slot(fromHhmm(times.getInt(j)), levels.getInt(j))
                }
                slots.sortedBy { it.minutes }
            }
            schedules += Schedule(
                id = o.getInt("id"),
                name = o.optString("Name"),
                heating = false,
                days = days,
                level = true,
                levelType = o.optStringOrNull("Type"),
                currentValue = if (o.has("CurrentLevel")) o.optInt("CurrentLevel") else null,
            )
        }
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
                    currentValue = if (heating && o.has("CurrentSetpoint")) o.optInt("CurrentSetpoint") else null,
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
            if (schedule.level) {
                body.put(
                    day,
                    JSONObject()
                        .put("Time", JSONArray(slots.map { toHhmm(it.minutes) }))
                        .put("Level", JSONArray(slots.map { it.value })),
                )
            } else if (schedule.heating) {
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
        val type = if (schedule.level) "Level" else if (schedule.heating) "Heating" else "OnOff"
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
        "$SCHEDULES_PATH${if (schedule.level) "Level" else if (schedule.heating) "Heating" else "OnOff"}/${schedule.id}"

    private fun patch(path: String, body: String) {
        request("PATCH", path, body)
    }

    /**
     * What a Moment or automation does lives at the hub's root (/Moment/3, /Automation/1), where
     * the official app sends it; /data/v2/domain/ only holds names, icons and on/off.
     * Falls back to the domain path for firmware without the root one.
     */
    private fun rootRequest(method: String, path: String, body: String?): String =
        attempt(method, "/$path", body) ?: request(method, path, body)

    private fun request(method: String, path: String, body: String?): String {
        // Newer firmware serves /data/v2/domain/, older firmware only /data/domain/.
        for (base in BASE_PATHS) {
            attempt(method, "$base$path", body)?.let { return it }
        }
        throw IOException("Hub returned HTTP 404 for $path")
    }

    /**
     * One request; null on 404. Goes to the hub directly, or through Wiser's cloud when
     * [relay] is set and the hub can't be reached (then the cloud is used for a minute
     * before the direct route is tried again, so each request doesn't wait for a timeout).
     */
    private fun attempt(method: String, fullPath: String, body: String?): String? {
        if (relay != null && System.currentTimeMillis() < localDownUntil) return relayAttempt(relay, method, fullPath, body)
        return try {
            localAttempt(method, fullPath, body)
        } catch (e: HubServerError) {
            // The hub is there but its data service failed (the 502s seen on 2026-11-21). Reads
            // are safe to repeat through the cloud; a change may already have been applied, so
            // it is not sent twice.
            if (relay == null || method != "GET") throw e
            relayAttempt(relay, method, fullPath, body)
        } catch (e: IOException) {
            if (relay == null || !unreachable(e)) throw e
            localDownUntil = System.currentTimeMillis() + LOCAL_RETRY_MS
            relayAttempt(relay, method, fullPath, body)
        }
    }

    private fun relayAttempt(relay: CloudRelay, method: String, fullPath: String, body: String?): String? {
        val (code, text) = relay.send(method, fullPath, body)
        usedCloud = true
        return when {
            code == 404 -> null
            code !in 200..299 -> throw IOException("Hub returned HTTP $code (through Wiser's cloud)")
            else -> text
        }
    }

    /** Only failures to connect at all; a hub that is slow to answer may still have acted. */
    private fun unreachable(e: IOException): Boolean = when (e) {
        is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.UnknownHostException -> true
        is java.net.SocketTimeoutException -> e.message.orEmpty().contains("connect", ignoreCase = true)
        else -> false
    }

    /** One request straight to the hub, switching to HTTPS if the hub asks for it. Returns null on 404. */
    private fun localAttempt(method: String, fullPath: String, body: String?): String? {
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
                    code in 500..599 -> throw HubServerError(code)
                    code !in 200..299 -> throw IOException("Hub returned HTTP $code")
                    else -> return conn.inputStream.bufferedReader().use { it.readText() }
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Hub kept redirecting")
    }

    /**
     * The hub's live event stream (server-sent events), as the official app uses on the home
     * network: GET https://<hub>/api/wiser-home/db?sse&primary with the secret. Hubs that
     * support it say so with FeatureCapability.Sse. Close the stream to stop reading.
     */
    fun openEventStream(): HubEventStream {
        // HTTPS, as the official app uses; plain HTTP only for a hub that never asked for HTTPS (test hubs).
        val scheme = if (host in httpsHosts || !host.contains(':')) "https" else "http"
        val conn = open(URL("$scheme://$host/api/wiser-home/db?sse&primary"))
        conn.connectTimeout = 5_000
        conn.readTimeout = 0 // events can be minutes apart; close() ends the read
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Secret", secret)
        val code = conn.responseCode
        if (code == 401 || code == 403) { conn.disconnect(); throw IOException(SECRET_REJECTED) }
        if (code !in 200..299) { conn.disconnect(); throw IOException("Hub returned HTTP $code for its event stream") }
        return HubEventStream(conn)
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
        /** Automation time codes for sunrise and sunset; other times are HHMM. */
        const val SUNRISE = 3000
        const val SUNSET = 4000

        val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        private val BASE_PATHS = listOf("/data/v2/domain/", "/data/domain/")

        /** Until when the hub counts as out of reach, so requests go straight to the cloud. */
        @Volatile private var localDownUntil = 0L
        private const val LOCAL_RETRY_MS = 60_000L
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

        private val BOILER_KEY = Regex("HeatSource|ControlType|Boiler|Fuel|HydronicChannel")

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

        /** A copy of [value] with the first letter of every key in lower case. */
        fun camelKeys(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().also { out ->
                value.keys().forEach { key -> out.put(key.replaceFirstChar { it.lowercase() }, camelKeys(value.opt(key))) }
            }
            is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(camelKeys(value.opt(i))) }
            else -> value
        }

        private inline fun JSONArray?.forEachObject(block: (JSONObject) -> Unit) {
            if (this == null) return
            for (i in 0 until length()) optJSONObject(i)?.let(block)
        }

        /** "ExternalAntenna" -> "External antenna". */
        private fun spacedWords(s: String): String =
            s.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }

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
