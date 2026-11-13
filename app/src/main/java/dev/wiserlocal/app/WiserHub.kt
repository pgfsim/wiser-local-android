package dev.wiserlocal.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
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
)

data class HotWater(
    val id: Int,
    val on: Boolean,
    val boosted: Boolean,
    /** When the current override ends (epoch millis), or 0 if there is none. */
    val overrideUntilMs: Long,
)

data class HubState(val rooms: List<Room>, val hotWater: HotWater?)

/**
 * Minimal client for the Wiser hub's unofficial local REST API.
 * All calls block, so run them off the main thread.
 *
 * Payloads follow https://github.com/msp1974/wiserheatapiv2. The hub works in
 * tenths of a degree, so 20.5°C is sent as 205.
 */
class WiserHub(host: String, private val secret: String) {

    private val host = host.trim().removePrefix("http://").trimEnd('/')

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

    private fun patch(path: String, body: String) {
        request("PATCH", path, body)
    }

    private fun request(method: String, path: String, body: String?): String {
        var notFound: IOException? = null
        // Newer firmware serves /data/v2/domain/, older firmware only /data/domain/.
        for (base in BASE_PATHS) {
            val conn = URL("http://$host$base$path").openConnection() as HttpURLConnection
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
                if (code == 404) {
                    notFound = IOException("Hub returned HTTP 404 for $base$path")
                    continue
                }
                if (code == 401 || code == 403) throw IOException("The hub rejected the secret")
                if (code !in 200..299) throw IOException("Hub returned HTTP $code")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw notFound ?: IOException("No response from hub")
    }

    companion object {
        const val MIN_TEMP_C = 5.0
        const val MAX_TEMP_C = 30.0

        private val BASE_PATHS = listOf("/data/v2/domain/", "/data/domain/")
        private const val HOT_WATER_ON = 1100
        private const val NO_READING = -32768

        /** Hub values are tenths of a degree; out-of-range values mean "no reading". */
        private fun fromHub(raw: Int): Double? = if (raw in -400..1999) raw / 10.0 else null

        fun formatTemp(c: Double?): String =
            if (c == null) "--" else String.format(Locale.getDefault(), "%.1f°", c)

        fun formatSetPoint(c: Double?): String = when {
            c == null -> "--"
            c < MIN_TEMP_C -> "Off"
            else -> formatTemp(c)
        }
    }
}
