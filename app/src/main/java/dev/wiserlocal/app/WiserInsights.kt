package dev.wiserlocal.app

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ReportRange(val page: String) {
    DAY("DayTemperature"),
    WEEK("WeekTemperature"),
    MONTH("MonthTemperature"),
}

enum class SeriesKind { ROOM, SETPOINT, OUTDOOR }

/** One line on the chart: times in epoch millis, values in °C. */
data class ReportSeries(val name: String, val kind: SeriesKind, val times: LongArray, val values: DoubleArray)

data class HeatReport(
    val range: ReportRange,
    val date: LocalDate,
    val startMs: Long,
    val endMs: Long,
    val minC: Double,
    val maxC: Double,
    /** Room temperatures, their set points and the outdoor temperature. */
    val series: List<ReportSeries>,
    /** Night time (sunset to sunrise) on the day view. */
    val nights: List<Pair<Long, Long>>,
)

/**
 * Wiser's Insights pages (insights.wiserhome.com). They are web pages built for the
 * official app's web view, so this reads the chart data out of the heat report's
 * script rather than a JSON API. Requests carry the Wiser account's access token;
 * the site also keeps a session cookie, which is held here in memory.
 */
class WiserInsights(
    private val base: String = DEFAULT_BASE,
    private val accessToken: () -> String,
) {
    private val cookies = linkedMapOf<String, String>()
    private var sessionReady = false

    @Synchronized
    fun heatReport(range: ReportRange, date: LocalDate): HeatReport {
        val start = startOf(range, date)
        val query = "?Date=" + URLEncoder.encode(start.format(SERVER_DATE), "UTF-8") + "&AppVersion=$APP_VERSION&DarkMode=false"
        if (!sessionReady) openSession()
        var html = get("/Home/${range.page}$query")
        if (html == null) {
            // The session ran out (the site answers 440); start a new one and try once more.
            sessionReady = false
            cookies.clear()
            openSession()
            html = get("/Home/${range.page}$query") ?: throw IOException("Wiser's Insights session could not be restored")
        }
        return HeatReportParser.parse(html, range, start)
    }

    /** Opens Insights the way the app's web view does, so the site sets up its session. */
    private fun openSession() {
        get("/?EcoModeEnabled=False&AppVersion=$APP_VERSION&ProductType=Schneider&darkmode=false")
        get("/Home/Temperatures?AppVersion=$APP_VERSION&darkmode=false")
        sessionReady = true
    }

    /** The page body, or null when the site says the session has expired. */
    private fun get(path: String): String? {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Authorization", "Bearer ${accessToken()}")
            conn.setRequestProperty("Accept", "text/html,*/*")
            if (cookies.isNotEmpty()) {
                conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            val code = conn.responseCode
            conn.headerFields["Set-Cookie"].orEmpty().forEach { header ->
                val pair = header.substringBefore(';')
                val name = pair.substringBefore('=', "").trim()
                if (name.isNotEmpty()) cookies[name] = pair.substringAfter('=')
            }
            if (code == 440) return null
            if (code == 401 || code == 403) throw IOException("Wiser's Insights site did not accept the sign-in (HTTP $code)")
            if (code !in 200..299) throw IOException("Wiser's Insights site returned HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://insights.wiserhome.com"
        private const val APP_VERSION = "7.8.0"

        /** The site wants US-style dates: 11/18/2026 00:00:00. */
        private val SERVER_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")

        /** Day: that day. Week: its Monday. Month: the 1st. */
        fun startOf(range: ReportRange, date: LocalDate): LocalDateTime = when (range) {
            ReportRange.DAY -> date
            ReportRange.WEEK -> date.minusDays((date.dayOfWeek.value - 1).toLong())
            ReportRange.MONTH -> date.withDayOfMonth(1)
        }.atStartOfDay()
    }
}

/** Reads the Plotly chart set-up out of a heat report page. */
object HeatReportParser {

    private val TRACE = Regex(
        """\{x:\[(.*?)\],y:\[(.*?)\],type:"scatter",name:(null|"(?:[^"\\]|\\.)*"),(.*?)line:\{(.*?)\}""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val NIGHT = Regex("""type:"rect",xref:"x",yref:"paper",x0:"([^"]+)",x1:"([^"]+)"""")
    private val X_RANGE = Regex("""range:\["(\d{4}-[^"]+)","(\d{4}-[^"]+)"\]""")
    private val Y_RANGE = Regex("""yaxis:\{range:\[(-?[\d.]+),(-?[\d.]+)\]""")
    private val LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parse(html: String, range: ReportRange, start: LocalDateTime): HeatReport {
        val series = mutableListOf<ReportSeries>()
        for (m in TRACE.findAll(html)) {
            val (xs, ys, nameRaw, _, line) = m.destructured
            val times = JSONArray("[$xs]").let { a -> LongArray(a.length()) { time(a.getString(it)) } }
            val values = JSONArray("[$ys]").let { a -> DoubleArray(a.length()) { a.optDouble(it, Double.NaN) } }
            if (times.isEmpty() || times.size != values.size) continue
            val name = if (nameRaw == "null") null else JSONArray("[$nameRaw]").getString(0)
            val kind = when {
                // The outdoor band is drawn as a fill down to a hidden line at -100.
                name == null && values.all { it <= -99 } -> continue
                name == null -> SeriesKind.OUTDOOR
                // Room readings are smooth thick lines; set points are thin steps.
                Regex("""width:\s*1\b""").containsMatchIn(line) && !line.contains("spline") -> SeriesKind.SETPOINT
                else -> SeriesKind.ROOM
            }
            series += ReportSeries(name ?: "Outdoor", kind, times, values)
        }
        if (series.isEmpty()) throw IOException("The heat report had no readings in it")

        val nights = NIGHT.findAll(html).map { time(it.groupValues[1]) to time(it.groupValues[2]) }.toList()
        val xRange = X_RANGE.find(html)
        val startMs = xRange?.let { time(it.groupValues[1]) } ?: series.minOf { it.times.first() }
        val endMs = xRange?.let { time(it.groupValues[2]) } ?: series.maxOf { it.times.last() }
        val all = series.flatMap { it.values.filter { v -> !v.isNaN() } }
        val yRange = Y_RANGE.find(html)
        val minC = yRange?.groupValues?.get(1)?.toDoubleOrNull() ?: (all.minOrNull() ?: 0.0) - 1
        val maxC = yRange?.groupValues?.get(2)?.toDoubleOrNull() ?: (all.maxOrNull() ?: 25.0) + 1
        return HeatReport(range, start.toLocalDate(), startMs, endMs, minC, maxC, series, nights)
    }

    /** "2026-11-18 06:02:12" in local time, or an ISO time with an offset. */
    private fun time(text: String): Long = runCatching {
        LocalDateTime.parse(text, LOCAL_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrElse { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
}
