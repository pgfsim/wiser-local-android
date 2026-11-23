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

/** A figure and what it means, e.g. ("1 time", "turned the heating down"). */
data class InsightStat(val value: String, val label: String, val warning: Boolean = false)

/** The Insights landing page: savings, activity, and heating hours against the weather. */
data class InsightsOverview(
    /** "0%" */
    val savedPercent: String?,
    /** "Energy saved so far in November" */
    val savedTitle: String?,
    /** "Your previous 3-month average is 0%" */
    val previousAverage: String?,
    /** Saving per month, oldest first, as (month start, percent). */
    val monthlySavings: List<Pair<LocalDate, Double>>,
    /** Hours saved in away mode and eco mode. */
    val savings: List<InsightStat>,
    val ecoDisabled: Boolean,
    /** Times the heating was turned down or up, hours of sunshine. */
    val activity: List<InsightStat>,
    /** Hours the heating was on, one per day for the last 30 days; the last is today. */
    val heatingHours: List<Double>,
    /** Days whose figure is incomplete or missing (drawn grey). */
    val incompleteDays: Set<Int>,
    /** Daily outdoor minimum and maximum, as (day index, °C). */
    val minTemps: List<Pair<Int, Double>>,
    val maxTemps: List<Pair<Int, Double>>,
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
    /** Debug builds only: receives each heat report page, to check the parser against. */
    private val debugDump: ((String) -> Unit)? = null,
) {
    private val cookies = linkedMapOf<String, String>()
    private var sessionReady = false

    @Synchronized
    fun heatReport(range: ReportRange, date: LocalDate): HeatReport {
        val start = startOf(range, date)
        val query = "?Date=" + URLEncoder.encode(start.format(SERVER_DATE), "UTF-8") + "&AppVersion=$APP_VERSION&DarkMode=true"
        if (!sessionReady) openSession()
        var html = get("/Home/${range.page}$query")
        if (html == null) {
            // The session ran out (the site answers 440); start a new one and try once more.
            sessionReady = false
            cookies.clear()
            openSession()
            html = get("/Home/${range.page}$query") ?: throw IOException("Wiser's Insights session could not be restored")
        }
        debugDump?.let { dump -> runCatching { dump(html) } }
        return HeatReportParser.parse(html, range, start)
    }

    // Pages are requested in dark mode, as the official app does: the light-mode pages
    // are laid out differently and are not what this parser was written against.

    /** The Insights landing page. [ecoEnabled] is passed on as the official app does. */
    @Synchronized
    fun overview(ecoEnabled: Boolean?): InsightsOverview {
        val path = "/?EcoModeEnabled=${if (ecoEnabled == true) "True" else "False"}" +
            "&AppVersion=$APP_VERSION&ProductType=Schneider&darkmode=true"
        val html = get(path) ?: run {
            cookies.clear()
            get(path) ?: throw IOException("Wiser's Insights session could not be restored")
        }
        // Opening the landing page alone is not enough for the heat report: the site only
        // includes the outdoor temperature once its Temperatures page has been opened, so
        // leave sessionReady for heatReport() to set up.
        return OverviewParser.parse(html)
    }

    /** Opens Insights the way the app's web view does, so the site sets up its session. */
    private fun openSession() {
        get("/?EcoModeEnabled=False&AppVersion=$APP_VERSION&ProductType=Schneider&darkmode=true")
        get("/Home/Temperatures?AppVersion=$APP_VERSION&darkmode=true")
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
            // Dotted lines only bridge a gap in a room's readings (the hub didn't report for a
            // while). They repeat the room's name, so leave them out; the chart shows the gap.
            if (Regex("""dash:\s*"dot"""").containsMatchIn(line)) continue
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
        mergeRepeats(series)

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

    /**
     * Joins series that share a name and kind into one, in time order, so a room is never
     * listed twice if the page splits its line into pieces.
     */
    private fun mergeRepeats(series: MutableList<ReportSeries>) {
        val merged = series.groupBy { it.name to it.kind }.values.map { group ->
            if (group.size == 1) return@map group[0]
            val points = group.flatMap { s -> s.times.indices.map { s.times[it] to s.values[it] } }.sortedBy { it.first }
            ReportSeries(group[0].name, group[0].kind, LongArray(points.size) { points[it].first }, DoubleArray(points.size) { points[it].second })
        }
        series.clear()
        series += merged
    }

    /** "2026-11-18 06:02:12" in local time, or an ISO time with an offset. */
    private fun time(text: String): Long = runCatching {
        LocalDateTime.parse(text, LOCAL_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrElse { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
}

/** Reads the figures and charts out of the Insights landing page. Each part is optional. */
object OverviewParser {

    private val STAT = Regex(
        """<div[^>]*class="heading2"[^>]*>\s*([^<]+?)\s*</div>\s*<div class="heading5( red)?">\s*([^<]+?)\s*</div>""",
    )
    private val SAVED = Regex("""<div class="heading expanded">\s*([^<]+?)\s*</div>""")
    private val SAVED_TITLE = Regex("""<div class="heading3">\s*([^<]+?)\s*<br""")
    private val PREVIOUS = Regex("""<div style="margin-top:5px">\s*([^<]+?)\s*</div>""")
    private val MONTH_BARS = Regex("""type:"bar",x:\[(.*?)\],.*?y:\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val HOUR_BARS = Regex("""type:"bar",hoverinfo:"none",marker:\{color:\[(.*?)\]\},y:\[(.*?)\]""")
    private val LINE = Regex("""type:"scatter",x:\[(.*?)\],.*?color:"(#\w+)".*?y:\[(.*?)\]""")

    fun parse(html: String): InsightsOverview {
        val savings = section(html, "savings")
        val recent = section(html, "recent")
        val weather = section(html, "weather")

        val months = MONTH_BARS.find(savings)?.let { m ->
            val xs = JSONArray("[${m.groupValues[1]}]")
            val ys = JSONArray("[${m.groupValues[2]}]")
            (0 until minOf(xs.length(), ys.length())).mapNotNull { i ->
                runCatching { OffsetDateTime.parse(xs.getString(i)).toLocalDate() to ys.getDouble(i) }.getOrNull()
            }
        }.orEmpty()

        val savingStats = STAT.findAll(savings).map { stat(it) }.toList()
        val activity = STAT.findAll(recent).map { stat(it) }.toList()

        var hours = emptyList<Double>()
        var incomplete = emptySet<Int>()
        HOUR_BARS.find(weather)?.let { m ->
            val colors = JSONArray("[${m.groupValues[1]}]")
            val ys = JSONArray("[${m.groupValues[2]}]")
            // The y list can run past the bars; the colour list says how many days there are.
            val days = colors.length()
            hours = (0 until days).map { ys.optDouble(it, 0.0) }
            // Grey bars are days with incomplete or missing data.
            incomplete = (0 until days).filter { colors.getString(it).equals("#c0c0c0", ignoreCase = true) }.toSet()
        }
        // Two lines: the daily minimum and maximum. Tell them apart by their values (the
        // colours differ between the site's themes); missing days come through as null.
        val lines = LINE.findAll(weather).map { m ->
            val xs = JSONArray("[${m.groupValues[1]}]")
            val ys = JSONArray("[${m.groupValues[3]}]")
            (0 until minOf(xs.length(), ys.length()))
                .filter { !ys.isNull(it) && !xs.isNull(it) }
                .map { xs.getInt(it) to ys.getDouble(it) }
        }.filter { it.isNotEmpty() }.sortedBy { line -> line.sumOf { it.second } / line.size }.toList()

        val overview = InsightsOverview(
            savedPercent = SAVED.find(savings)?.groupValues?.get(1),
            savedTitle = SAVED_TITLE.find(savings)?.groupValues?.get(1)?.let(::text),
            previousAverage = PREVIOUS.find(savings)?.groupValues?.get(1)?.let(::text),
            monthlySavings = months,
            savings = savingStats,
            ecoDisabled = savingStats.any { it.warning && it.label.contains("Eco", ignoreCase = true) },
            activity = activity,
            heatingHours = hours,
            incompleteDays = incomplete,
            minTemps = if (lines.size >= 2) lines.first() else emptyList(),
            maxTemps = if (lines.size >= 2) lines.last() else lines.firstOrNull().orEmpty(),
        )
        if (overview.savedPercent == null && activity.isEmpty() && hours.isEmpty()) {
            throw IOException("The Insights page had none of the expected figures")
        }
        return overview
    }

    private fun stat(m: MatchResult) = InsightStat(text(m.groupValues[1]), text(m.groupValues[3]), m.groupValues[2].isNotEmpty())

    /** The HTML of the section with this id, up to the next section. */
    private fun section(html: String, id: String): String {
        val start = html.indexOf("<section id=\"$id\"")
        if (start < 0) return ""
        val end = html.indexOf("</section>", start).let { if (it < 0) html.length else it }
        return html.substring(start, end)
    }

    /** Undoes the few HTML entities these pages use. */
    private fun text(s: String) = s.replace("&#x27;", "'").replace("&amp;", "&").replace("&nbsp;", " ")
        .replace("&quot;", "\"").trim()
}
