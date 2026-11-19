package dev.wiserlocal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import dev.wiserlocal.app.HeatReport
import dev.wiserlocal.app.ReportRange
import dev.wiserlocal.app.SeriesKind
import dev.wiserlocal.app.WiserInsights
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.wiserlocal.app.R

/**
 * Heating history lives in Wiser's cloud, not on the hub, so this tab needs the
 * Wiser account. For now it handles signing in and out; the report comes next.
 */
@Composable
fun InsightsTab(vm: HubViewModel, modifier: Modifier) {
    LaunchedEffect(Unit) { vm.cloudCheck() }
    LaunchedEffect(vm.cloudSignedIn) {
        if (vm.cloudSignedIn) {
            vm.loadOverview()
            if (vm.report == null) vm.showReport()
        }
    }
    TabScaffold("Insights", vm, modifier) {
        if (vm.cloudSignedIn) {
            vm.overview?.let { o ->
                if (o.savedPercent != null || o.savings.isNotEmpty()) item { SavingsCard(o, vm) }
                if (o.heatingHours.isNotEmpty()) item { HeatingWeatherCard(o) }
                if (o.activity.isNotEmpty()) item { ActivityCard(o) }
            }
            vm.overviewError?.let { item { NoteCard("Couldn't load your savings and activity: $it") } }
            item { HeatReportCard(vm) }
            item { AccountCard(vm) }
        } else {
            item { SignInCard(vm) }
        }
    }
}

@Composable
private fun SignInCard(vm: HubViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    // Plain remember, so the password is never written into saved instance state.
    var password by remember { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painterResource(R.drawable.ic_insights),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Text("Sign in to your Wiser account", style = MaterialTheme.typography.titleLarge)
            Text(
                "The heat report comes from Wiser's cloud, not the hub, so it needs the account you " +
                    "use in the official app. Your password goes only to Wiser and is not saved on this phone; " +
                    "the app keeps a sign-in token, encrypted, until you sign out.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (!vm.cloudBusy && email.isNotBlank() && password.isNotEmpty()) vm.cloudSignIn(email, password)
                }),
                trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )
            vm.cloudError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { vm.cloudSignIn(email, password) },
                enabled = !vm.cloudBusy && email.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.cloudBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text("Sign in")
            }
        }
    }
}

@Composable
private fun AccountCard(vm: HubViewModel) {
    val dialogs = LocalDialogs.current
    val account = vm.cloudAccount
    Section("Wiser account") {
        val name = listOfNotNull(account?.firstName, account?.lastName).joinToString(" ")
        InfoRow(name.ifEmpty { "Signed in" }, account?.email)
        account?.hubModel?.let { RowDivider(); InfoRow("Hub model", it) }
        val region = listOfNotNull(account?.countryCode, account?.timeZoneId).joinToString(" · ")
        if (region.isNotEmpty()) { RowDivider(); InfoRow("Region", region) }
        vm.cloudError?.let { RowDivider(); InfoRow("Problem", it) }
        RowDivider()
        Column(Modifier.padding(16.dp)) {
            OutlinedButton(
                onClick = { dialogs.confirm("Sign out of your Wiser account on this phone?", "Sign out") { vm.cloudSignOut() } },
                enabled = !vm.cloudBusy,
            ) { Text("Sign out") }
        }
    }
}

/** Day, week or month of room temperatures, set points and outdoor temperature. */
@Composable
private fun HeatReportCard(vm: HubViewModel) {
    var hidden by remember { mutableStateOf(setOf<String>()) }
    val today = LocalDate.now()
    val range = vm.reportRange
    val date = vm.reportDate
    val nextDate = step(range, date, 1)
    val canGoNext = WiserInsights.startOf(range, nextDate).toLocalDate() <= today

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Heat report", style = MaterialTheme.typography.titleLarge)
            ModeButtons(
                labels = listOf("Day", "Week", "Month"),
                selected = range.ordinal,
            ) { index -> vm.showReport(ReportRange.entries[index], date) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showReport(range, step(range, date, -1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Earlier")
                }
                Text(
                    periodLabel(range, date, today),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.showReport(range, nextDate) }, enabled = canGoNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Later")
                }
            }

            val report = vm.report?.takeIf { it.range == range && it.date == WiserInsights.startOf(range, date).toLocalDate() }
            when {
                report != null -> {
                    Box {
                        HeatChart(report, hidden)
                        if (vm.reportBusy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                    Legend(report, hidden) { name -> hidden = if (name in hidden) hidden - name else hidden + name }
                }
                vm.reportBusy -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                vm.reportError != null -> Column {
                    Text("Couldn't load the heat report: ${vm.reportError}", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.showReport() }) { Text("Try again") }
                }
            }
            if (report != null && vm.reportError != null) {
                Text("Couldn't refresh: ${vm.reportError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Tap a name to hide or show its lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(report: HeatReport, hidden: Set<String>, onToggle: (String) -> Unit) {
    val colors = roomColors()
    val rooms = report.series.filter { it.kind != SeriesKind.OUTDOOR }.map { it.name }.distinct()
    val names = rooms + report.series.filter { it.kind == SeriesKind.OUTDOOR }.map { it.name }.distinct()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { name ->
            val color = if (name in rooms) colors[rooms.indexOf(name) % colors.size] else LocalAccents.current.water
            FilterChip(
                selected = name !in hidden,
                onClick = { onToggle(name) },
                label = { Text(name) },
                leadingIcon = { Box(Modifier.size(10.dp).background(color, CircleShape)) },
            )
        }
    }
    Text(
        listOfNotNull(
            "Solid lines: room temperature.",
            if (report.series.any { it.kind == SeriesKind.SETPOINT }) "Dashed: set point." else null,
            if (report.series.any { it.kind == SeriesKind.OUTDOOR }) "Blue band: outdoor." else null,
            if (report.nights.isNotEmpty()) "Shading: night." else null,
        ).joinToString(" "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun step(range: ReportRange, date: LocalDate, by: Long): LocalDate = when (range) {
    ReportRange.DAY -> date.plusDays(by)
    ReportRange.WEEK -> date.plusWeeks(by)
    ReportRange.MONTH -> date.plusMonths(by)
}

private val DAY_LABEL = DateTimeFormatter.ofPattern("EEE d MMM")
private val MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun periodLabel(range: ReportRange, date: LocalDate, today: LocalDate): String = when (range) {
    ReportRange.DAY -> when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DAY_LABEL)
    }
    ReportRange.WEEK -> {
        val start = WiserInsights.startOf(range, date).toLocalDate()
        if (start == WiserInsights.startOf(range, today).toLocalDate()) "This week" else "Week of ${start.format(DAY_LABEL)}"
    }
    ReportRange.MONTH -> date.format(MONTH_LABEL)
}
