package dev.wiserlocal.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wiserlocal.app.HeatReport
import dev.wiserlocal.app.ReportRange
import dev.wiserlocal.app.ReportSeries
import dev.wiserlocal.app.SeriesKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Line colours for rooms, in order; outdoor always uses the hot-water blue. */
@Composable
fun roomColors(): List<Color> = listOf(
    LocalAccents.current.heat,
    MaterialTheme.colorScheme.primary,
    Color(0xFF9C6ADE),
    Color(0xFFD4A20F),
    Color(0xFFE0568A),
    Color(0xFF3FA7A0),
)

private val ZONE = ZoneId.systemDefault()
private val HOUR = DateTimeFormatter.ofPattern("H")
private val DAY_NAME = DateTimeFormatter.ofPattern("EEE")
private val DAY_NUMBER = DateTimeFormatter.ofPattern("d")
private val READOUT_DAY = DateTimeFormatter.ofPattern("HH:mm")
private val READOUT_WEEK = DateTimeFormatter.ofPattern("EEE HH:mm")
private val READOUT_MONTH = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * Room temperatures (thick), their set points (dashed) and the outdoor temperature
 * (blue band), with night shaded. Touch or drag to read the values at a time.
 */
@Composable
fun HeatChart(report: HeatReport, hidden: Set<String>, modifier: Modifier = Modifier) {
    val accents = LocalAccents.current
    val colors = roomColors()
    val rooms = report.series.filter { it.kind != SeriesKind.OUTDOOR }.map { it.name }.distinct()
    fun colorOf(s: ReportSeries) = if (s.kind == SeriesKind.OUTDOOR) accents.water else colors[rooms.indexOf(s.name).coerceAtLeast(0) % colors.size]
    val visible = report.series.filter { it.name !in hidden }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val nightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val cursorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    var touchFraction by remember(report) { mutableStateOf<Float?>(null) }

    val span = (report.endMs - report.startMs).coerceAtLeast(1)
    val gapMs = when (report.range) {
        ReportRange.DAY -> 45 * 60_000L
        ReportRange.WEEK -> 4 * 3_600_000L
        ReportRange.MONTH -> 36 * 3_600_000L
    }

    Column(modifier) {
        // Readout for the touched time, or a hint.
        val readout = touchFraction?.let { f ->
            val t = report.startMs + (f * span).toLong()
            val stamp = Instant.ofEpochMilli(t).atZone(ZONE).format(
                when (report.range) {
                    ReportRange.DAY -> READOUT_DAY
                    ReportRange.WEEK -> READOUT_WEEK
                    ReportRange.MONTH -> READOUT_MONTH
                },
            )
            val parts = visible.mapNotNull { s ->
                val gap = if (s.kind == SeriesKind.OUTDOOR) outdoorGap(gapMs) else gapMs
                val v = valueAt(s, t, gap) ?: return@mapNotNull null
                val label = when (s.kind) {
                    SeriesKind.SETPOINT -> "${s.name} set"
                    else -> s.name
                }
                "$label ${"%.1f".format(v)}°"
            }
            "$stamp · " + parts.joinToString(" · ").ifEmpty { "no readings" }
        } ?: "Touch the chart to read values"
        Text(
            readout,
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
            modifier = Modifier.padding(bottom = 6.dp).heightIn(min = 32.dp),
        )

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .pointerInput(report) {
                    detectTapGestures { pos -> touchFraction = fraction(pos.x, size.width.toFloat(), density) }
                }
                .pointerInput(report) {
                    // Sideways only, so an up/down swipe over the chart still scrolls the page.
                    detectHorizontalDragGestures(
                        onDragStart = { pos -> touchFraction = fraction(pos.x, size.width.toFloat(), density) },
                        onHorizontalDrag = { change, _ -> touchFraction = fraction(change.position.x, size.width.toFloat(), density) },
                    )
                },
        ) {
            val left = LEFT_DP * density
            val right = 6 * density
            val top = 6 * density
            val bottom = 20 * density
            val w = size.width - left - right
            val h = size.height - top - bottom
            val minC = report.minC
            val maxC = report.maxC
            fun x(t: Long) = left + (t - report.startMs).toFloat() / span * w
            fun y(v: Double) = top + ((1 - (v - minC) / (maxC - minC)) * h).toFloat()

            // Night shading.
            report.nights.forEach { (a, b) ->
                val x0 = x(a).coerceIn(left, left + w)
                val x1 = x(b).coerceIn(left, left + w)
                drawRect(nightColor, Offset(x0, top), Size(x1 - x0, h))
            }

            // Temperature grid every 5° (every 2° when the range is narrow).
            val step = if (maxC - minC <= 10) 2 else 5
            var v = ceil(minC / step) * step
            while (v <= maxC) {
                val yy = y(v)
                drawLine(gridColor, Offset(left, yy), Offset(left + w, yy), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val text = measurer.measure("${v.toInt()}°", labelStyle)
                drawText(text, topLeft = Offset(left - text.size.width - 4 * density, yy - text.size.height / 2))
                v += step
            }

            // Time labels along the bottom.
            timeTicks(report).forEach { (t, label) ->
                val xx = x(t)
                if (xx < left - 1 || xx > left + w + 1) return@forEach
                drawLine(gridColor.copy(alpha = 0.4f), Offset(xx, top), Offset(xx, top + h), strokeWidth = 1f)
                val text = measurer.measure(label, labelStyle)
                drawText(text, topLeft = Offset((xx - text.size.width / 2).coerceIn(0f, size.width - text.size.width), top + h + 3 * density))
            }

            clipRect(left, top, left + w, top + h) {
                // Outdoor band first, so room lines sit on top.
                visible.filter { it.kind == SeriesKind.OUTDOOR }.forEach { s ->
                    // Outdoor readings are hourly (or coarser), so allow wider spacing before breaking the line.
                    val line = seriesPath(s, outdoorGap(gapMs), ::x, ::y)
                    val fill = Path().apply {
                        addPath(line)
                        lineTo(x(s.times.last()), top + h)
                        lineTo(x(s.times.first()), top + h)
                        close()
                    }
                    drawPath(fill, accents.water.copy(alpha = 0.15f))
                    drawPath(line, accents.water.copy(alpha = 0.7f), style = Stroke(1.5f * density))
                }
                visible.filter { it.kind == SeriesKind.SETPOINT }.forEach { s ->
                    drawPath(
                        seriesPath(s, Long.MAX_VALUE, ::x, ::y),
                        colorOf(s).copy(alpha = 0.75f),
                        style = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))),
                    )
                }
                visible.filter { it.kind == SeriesKind.ROOM }.forEach { s ->
                    drawPath(seriesPath(s, gapMs, ::x, ::y), colorOf(s), style = Stroke(2.5f * density))
                }
            }

            touchFraction?.let { f ->
                val xx = left + f * w
                drawLine(cursorColor, Offset(xx, top), Offset(xx, top + h), strokeWidth = 1.5f)
            }
        }
    }
}

private const val LEFT_DP = 30f

private fun outdoorGap(gapMs: Long) = maxOf(gapMs * 4, 3 * 3_600_000L)

/** Where along the plot area a touch at [px] falls, 0 to 1. */
private fun fraction(px: Float, width: Float, density: Float): Float {
    val left = LEFT_DP * density
    val w = width - left - 6 * density
    return ((px - left) / w).coerceIn(0f, 1f)
}

/** A line through the points, broken wherever readings are missing for longer than [gapMs]. */
private fun seriesPath(s: ReportSeries, gapMs: Long, x: (Long) -> Float, y: (Double) -> Float): Path {
    val path = Path()
    var penDown = false
    var last = Long.MIN_VALUE
    for (i in s.times.indices) {
        val value = s.values[i]
        if (value.isNaN()) { penDown = false; continue }
        val t = s.times[i]
        if (penDown && t - last <= gapMs) path.lineTo(x(t), y(value)) else path.moveTo(x(t), y(value))
        penDown = true
        last = t
    }
    return path
}

/** The value of [s] at time [t]: the step value for set points, the nearest reading otherwise. */
private fun valueAt(s: ReportSeries, t: Long, gapMs: Long): Double? {
    if (s.times.isEmpty() || t < s.times.first() - gapMs || t > s.times.last() + gapMs) return null
    if (s.kind == SeriesKind.SETPOINT) {
        val i = s.times.indexOfLast { it <= t }
        return if (i >= 0) s.values[i] else null
    }
    var best = -1
    var bestDistance = Long.MAX_VALUE
    for (i in s.times.indices) {
        val d = abs(s.times[i] - t)
        if (d < bestDistance && !s.values[i].isNaN()) { best = i; bestDistance = d }
    }
    return if (best >= 0 && bestDistance <= gapMs) s.values[best] else null
}

/** Hours every 3 on the day view, day names on the week, every 5th day on the month. */
private fun timeTicks(report: HeatReport): List<Pair<Long, String>> {
    val start = Instant.ofEpochMilli(report.startMs).atZone(ZONE)
    val out = mutableListOf<Pair<Long, String>>()
    when (report.range) {
        ReportRange.DAY -> for (hour in 0..21 step 3) {
            val t = start.plusHours(hour.toLong())
            out += t.toInstant().toEpochMilli() to t.format(HOUR)
        }
        ReportRange.WEEK -> for (day in 0..6) {
            val t = start.plusDays(day.toLong())
            out += t.plusHours(12).toInstant().toEpochMilli() to t.format(DAY_NAME)
        }
        ReportRange.MONTH -> {
            val days = floor((report.endMs - report.startMs) / 86_400_000.0).toInt()
            for (day in 0 until days step 5) {
                val t = start.plusDays(day.toLong())
                out += t.toInstant().toEpochMilli() to t.format(DAY_NUMBER)
            }
        }
    }
    return out
}
