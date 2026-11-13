package dev.wiserlocal.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
)

data class HotWater(
    val id: Int,
    val on: Boolean,
    val boosted: Boolean,
    /** When the current override ends (epoch millis), or 0 if there is none. */
    val overrideUntilMs: Long,
    /** Schedule the hot water follows, or 0 if none. */
    val scheduleId: Int,
)

data class HubState(val rooms: List<Room>, val hotWater: HotWater?)

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
)

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

        val rooms = mutableListOf<Room>()
        json.optJSONArray("Room")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getInt("id")
                rooms += Room(
                    id = id,
                    name = o.optString("Name", "Room $id"),
                    tempC = fromHub(o.optInt("CalculatedTemperature", NO_READING)),
                    setPointC = fromHub(o.optInt("CurrentSetPoint", NO_READING)),
                    heating = o.optString("ControlOutputState") == "On" ||
                        o.optInt("PercentageDemand", 0) > 0,
                    overridden = o.optString("OverrideType", "None") != "None",
                    scheduleId = o.optInt("ScheduleId", 0),
                )
            }
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
            )
        }

        return HubState(rooms, hotWater)
    }

    fun setRoomTemp(roomId: Int, tempC: Double) {
        val setPoint = (tempC.coerceIn(MIN_TEMP_C, MAX_TEMP_C) * 10).roundToInt()
        patch("Room/$roomId", """{"RequestOverride":{"Type":"Manual","SetPoint":$setPoint}}""")
    }

    /** Drops any manual override so the room follows its schedule again. */
    fun resumeRoomSchedule(roomId: Int) =
        patch("Room/$roomId", """{"RequestOverride":{"Type":"None"}}""")

    fun boostHotWater(hotWaterId: Int, minutes: Int) = patch(
        "HotWater/$hotWaterId",
        """{"RequestOverride":{"Type":"Manual","DurationMinutes":$minutes,"SetPoint":$HOT_WATER_ON}}""",
    )

    fun cancelHotWaterBoost(hotWaterId: Int) =
        patch("HotWater/$hotWaterId", """{"RequestOverride":{"Type":"None"}}""")

    /** All heating and on/off schedules on the hub. Needs firmware with the v2 API. */
    fun fetchSchedules(): List<Schedule> {
        val text = attempt("GET", SCHEDULES_PATH, null)
            ?: throw IOException("This hub's firmware does not offer schedules over the local API")
        val json = JSONObject(text)
        val schedules = mutableListOf<Schedule>()
        for ((key, heating) in listOf("Heating" to true, "OnOff" to false)) {
            val arr = json.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
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
                schedules += Schedule(o.getInt("id"), o.optString("Name"), heating, days, wrapped)
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
                    code == 401 || code == 403 -> throw IOException("The hub rejected the secret")
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

        /** Heating schedule value meaning "off" (-20°C in tenths). */
        const val SCHEDULE_OFF = -200

        /** Day names exactly as the hub's schedule API spells them. */
        val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        private val BASE_PATHS = listOf("/data/v2/domain/", "/data/domain/")
        private const val SCHEDULES_PATH = "/data/v2/schedules/"
        private const val HOT_WATER_ON = 1100
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

        private fun fromHhmm(hhmm: Int): Int = (hhmm / 100) * 60 + hhmm % 100
        private fun toHhmm(minutes: Int): Int = (minutes / 60) * 100 + minutes % 60

        fun formatTemp(c: Double?): String =
            if (c == null) "--" else String.format(Locale.getDefault(), "%.1f°", c)

        fun formatSetPoint(c: Double?): String = when {
            c == null -> "--"
            c < MIN_TEMP_C -> "Off"
            else -> formatTemp(c)
        }
    }
}
