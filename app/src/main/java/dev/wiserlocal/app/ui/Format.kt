package dev.wiserlocal.app.ui

import android.content.Context
import android.text.format.DateFormat
import dev.wiserlocal.app.HotWater
import dev.wiserlocal.app.Room
import dev.wiserlocal.app.Schedule
import dev.wiserlocal.app.WiserHub
import java.util.Calendar
import java.util.Date

/** Durations offered for boosts and timed holds, in minutes. */
val DURATIONS = listOf(30, 60, 120, 180)

/** Same step the official app uses for a room boost. */
const val BOOST_DELTA_C = 2.0

/** Most changes a Moment can hold in the official app. */
const val MAX_MOMENT_ACTIONS = 60

fun durationText(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/** Minutes past midnight in the phone's 12 or 24 hour style. */
fun Context.clockText(minutes: Int): String {
    val time = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minutes / 60)
        set(Calendar.MINUTE, minutes % 60)
    }
    return DateFormat.getTimeFormat(this).format(time.time)
}

fun Context.timeText(millis: Long): String = DateFormat.getTimeFormat(this).format(Date(millis))

/** Today's name as the hub spells it ("Monday"). Calendar counts Sunday as 1. */
fun today(): String = WiserHub.DAYS[(Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7]

/** "TwoThirds" -> "Two thirds", for the hub's run-together labels. */
fun spaced(value: String): String =
    value.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }

fun Room.isOff(): Boolean = setPointC != null && setPointC < WiserHub.MIN_TEMP_C

/** One line saying what the room is doing right now. */
fun Context.roomStatus(room: Room): String = when {
    room.boosted && room.overrideUntilMs > 0 -> "Boosted until ${timeText(room.overrideUntilMs)}"
    room.boosted -> "Boosted"
    room.overridden && room.overrideUntilMs > 0 -> "Held until ${timeText(room.overrideUntilMs)}"
    room.heating && room.demandPercent > 0 -> "Heating · ${room.demandPercent}% demand"
    room.heating -> "Heating"
    room.mode == "Manual" && room.isOff() -> "Off"
    room.mode == "Manual" -> "Manual, schedule ignored"
    room.overridden -> "Manual until the next scheduled change"
    else -> "Following schedule"
}

fun Context.hotWaterStatus(hw: HotWater): String = when {
    hw.boosted && hw.overrideUntilMs > 0 -> "Boosted until ${timeText(hw.overrideUntilMs)}"
    hw.boosted -> "Boosted"
    hw.mode == "Manual" -> if (hw.on) "Always on" else "Always off"
    else -> "Following schedule"
}

/** "Next: 16.0° at 22:30", or null if the hub did not say what comes next. */
fun Context.nextChange(schedules: List<Schedule>, scheduleId: Int, heating: Boolean): String? {
    val schedule = schedules.firstOrNull { it.id == scheduleId && it.heating == heating } ?: return null
    val value = schedule.nextValue ?: return null
    val minutes = schedule.nextMinutes ?: return null
    val label = when {
        heating -> WiserHub.formatSetPoint(value / 10.0)
        value != 0 -> "On"
        else -> "Off"
    }
    val day = schedule.nextDay
    return if (day == null || day == today()) "Next: $label at ${clockText(minutes)}"
    else "Next: $label at ${clockText(minutes)} ${day.take(3)}"
}

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
