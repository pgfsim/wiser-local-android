package dev.wiserlocal.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wiserlocal.app.InsightStat
import dev.wiserlocal.app.InsightsOverview
import dev.wiserlocal.app.R
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

@Composable
private fun InsightCard(content: @Composable () -> Unit) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun StatRow(icon: Painter, tint: Color, stat: InsightStat, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(tint.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stat.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stat.label.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = if (stat.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing()
    }
}

/** Energy saved this month, the monthly trend, and hours saved by away and eco mode. */
@Composable
fun SavingsCard(o: InsightsOverview, vm: HubViewModel) {
    val accents = LocalAccents.current
    InsightCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                o.savedPercent ?: "–",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(12.dp))
            Text(o.savedTitle ?: "Energy saved this month", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        o.previousAverage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (o.monthlySavings.isNotEmpty()) MonthlyBars(o.monthlySavings.map { it.first.format(MONTH) to it.second })
        val ecoOn = vm.state?.system?.ecoMode == true
        o.savings.forEach { stat ->
            val eco = stat.label.contains("Eco", ignoreCase = true)
            val shown = if (eco && ecoOn && stat.warning) stat.copy(label = "Eco mode is on", warning = false) else stat
            StatRow(
                painterResource(if (eco) R.drawable.ic_eco else R.drawable.ic_away),
                if (eco) accents.good else MaterialTheme.colorScheme.primary,
                shown,
            ) {
                if (eco && stat.warning && !ecoOn) {
                    FilledTonalButton(onClick = { vm.run("Eco mode on") { it.setEcoMode(true) } }) { Text("Turn on") }
                }
            }
        }
    }
}

private val MONTH = DateTimeFormatter.ofPattern("MMM")

/** Small bar chart of the saving per month. */
@Composable
private fun MonthlyBars(months: List<Pair<String, Double>>) {
    val bar = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val top = max(10.0, months.maxOf { it.second })
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val bottom = 16 * density
        val h = size.height - bottom
        val slot = size.width / months.size
        drawLine(base, Offset(0f, h), Offset(size.width, h), strokeWidth = 1.5f)
        months.forEachIndexed { i, (label, value) ->
            val barH = (value / top * (h - 14 * density)).toFloat().coerceAtLeast(2 * density)
            val x = slot * i + slot * 0.25f
            drawRect(bar.copy(alpha = if (i == months.lastIndex) 1f else 0.55f), Offset(x, h - barH), Size(slot * 0.5f, barH))
            val pct = measurer.measure("${"%.0f".format(value)}%", style)
            drawText(pct, topLeft = Offset(x + slot * 0.25f - pct.size.width / 2, h - barH - pct.size.height))
            val text = measurer.measure(label, style)
            drawText(text, topLeft = Offset(x + slot * 0.25f - text.size.width / 2, h + 2 * density))
        }
    }
}

/** Times the heating was turned down or up, and hours of sunshine. */
@Composable
fun ActivityCard(o: InsightsOverview) {
    val accents = LocalAccents.current
    InsightCard {
        Text("Activity and environment", style = MaterialTheme.typography.titleLarge)
        o.activity.forEach { stat ->
            val label = stat.label.lowercase()
            val (icon, tint) = when {
                "down" in label -> rememberVectorPainter(Icons.Default.KeyboardArrowDown) to accents.water
                "up" in label -> rememberVectorPainter(Icons.Default.KeyboardArrowUp) to accents.heat
                "sun" in label -> painterResource(R.drawable.ic_sun) to Color(0xFFE0A800)
                else -> painterResource(R.drawable.ic_insights) to MaterialTheme.colorScheme.primary
            }
            StatRow(icon, tint, stat)
        }
    }
}

/** Hours of heating per day over the last 30 days, with the outdoor minimum and maximum. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeatingWeatherCard(o: InsightsOverview) {
    val accents = LocalAccents.current
    val barColor = MaterialTheme.colorScheme.primary
    val grey = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val days = o.heatingHours.size
    val maxHours = max(3.0, ceil(o.heatingHours.maxOrNull() ?: 0.0))
    val temps = (o.minTemps + o.maxTemps).map { it.second }
    val minC = floor(minOf(-5.0, temps.minOrNull() ?: -5.0) / 5) * 5
    val maxC = ceil(maxOf(15.0, temps.maxOrNull() ?: 15.0) / 5) * 5

    InsightCard {
        Text("Heating and the weather", style = MaterialTheme.typography.titleLarge)
        Text("Last 30 days", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val left = 26 * density
            val right = 30 * density
            val top = 14 * density
            val bottom = 18 * density
            val w = size.width - left - right
            val h = size.height - top - bottom
            val slot = w / days
            fun yHours(v: Double) = top + (1 - v / maxHours).toFloat() * h
            fun yTemp(v: Double) = top + (1 - (v - minC) / (maxC - minC)).toFloat() * h
            fun xDay(i: Int) = left + slot * (i + 0.5f)

            // Left axis: hours.
            var v = 0.0
            while (v <= maxHours) {
                val y = yHours(v)
                drawLine(grid, Offset(left, y), Offset(left + w, y), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val t = measurer.measure("${v.toInt()}h", style)
                drawText(t, topLeft = Offset(left - t.size.width - 4 * density, y - t.size.height / 2))
                v += 1.0
            }
            // Right axis: °C every 5.
            var c = minC
            while (c <= maxC) {
                val t = measurer.measure("${c.toInt()}°", style)
                drawText(t, topLeft = Offset(left + w + 4 * density, yTemp(c) - t.size.height / 2))
                c += 5
            }

            o.heatingHours.forEachIndexed { i, hours ->
                if (hours <= 0) return@forEachIndexed
                val color = when {
                    i in o.incompleteDays -> grey.copy(alpha = 0.6f)
                    i == days - 1 -> barColor
                    else -> barColor.copy(alpha = 0.55f)
                }
                val y = yHours(hours)
                drawRect(color, Offset(left + slot * i + slot * 0.15f, y), Size(slot * 0.7f, top + h - y))
            }

            listOf(o.minTemps to accents.water, o.maxTemps to accents.heat).forEach { (points, color) ->
                if (points.size < 2) return@forEach
                val path = Path()
                points.forEachIndexed { k, (i, temp) ->
                    if (k == 0) path.moveTo(xDay(i), yTemp(temp)) else path.lineTo(xDay(i), yTemp(temp))
                }
                drawPath(path, color, style = Stroke(2.5f * density))
            }

            drawLine(grid, Offset(left, top + h), Offset(left + w, top + h), strokeWidth = 1.5f)
            val ago = measurer.measure("30 days ago", style)
            drawText(ago, topLeft = Offset(left, top + h + 3 * density))
            val today = measurer.measure("Today", style)
            drawText(today, topLeft = Offset(left + w - today.size.width, top + h + 3 * density))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendDot(barColor, "Hours heating")
            LegendDot(accents.heat, "Outdoor max")
            LegendDot(accents.water, "Outdoor min")
            if (o.incompleteDays.isNotEmpty()) LegendDot(grey, "Incomplete day")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
