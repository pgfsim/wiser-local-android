package dev.wiserlocal.app

import java.util.Locale

/**
 * A schedule as plain text, one line per day, for copying, keeping or pasting:
 *
 *     Monday: 06:00 19.0, 08:00 17.5, 16:30 18.5, 21:30 Off
 *     Tuesday: 07:30 On, 08:30 Off        (hot water)
 *
 * Days left out of pasted text are left as they are.
 */
object ScheduleText {

    fun format(days: Map<String, List<Slot>>, heating: Boolean): String =
        WiserHub.DAYS.joinToString("\n") { day ->
            val slots = days[day].orEmpty().sortedBy { it.minutes }
            "$day: " + slots.joinToString(", ") { s ->
                val time = "%02d:%02d".format(Locale.ROOT, s.minutes / 60, s.minutes % 60)
                val value = when {
                    !heating -> if (s.value != 0) "On" else "Off"
                    s.value <= WiserHub.SCHEDULE_OFF -> "Off"
                    else -> String.format(Locale.ROOT, "%.1f", s.value / 10.0)
                }
                "$time $value"
            }
        }

    /** The days found in [text], or a message saying what is wrong with it. */
    fun parse(text: String, heating: Boolean, maxPerDay: Int): Result<Map<String, List<Slot>>> = runCatching {
        val out = linkedMapOf<String, List<Slot>>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val colon = line.indexOf(':')
            require(colon > 0) { "“$line” should start with a day, like “Monday:”" }
            val dayText = line.substring(0, colon).trim()
            val day = WiserHub.DAYS.firstOrNull {
                it.equals(dayText, ignoreCase = true) || (dayText.length >= 3 && it.startsWith(dayText, ignoreCase = true))
            } ?: throw IllegalArgumentException("“$dayText” isn't a day of the week")
            val slots = line.substring(colon + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
                val parts = entry.split(Regex("\\s+"))
                require(parts.size == 2) { "$day: “$entry” should be a time and a setting, like “06:00 19.0”" }
                val (h, m) = parts[0].split(':').map { it.toIntOrNull() }.let {
                    require(it.size == 2 && it[0] != null && it[1] != null) { "$day: “${parts[0]}” isn't a time like 06:00" }
                    it[0]!! to it[1]!!
                }
                require(h in 0..23 && m in 0..59) { "$day: “${parts[0]}” isn't a time like 06:00" }
                val word = parts[1].removeSuffix("°").removeSuffix("C").removeSuffix("°")
                val value = when {
                    word.equals("off", ignoreCase = true) -> if (heating) WiserHub.SCHEDULE_OFF else 0
                    word.equals("on", ignoreCase = true) && !heating -> 1
                    heating -> {
                        val temp = word.toDoubleOrNull() ?: throw IllegalArgumentException("$day: “${parts[1]}” isn't a temperature or Off")
                        require(temp in WiserHub.MIN_TEMP_C..WiserHub.MAX_TEMP_C) {
                            "$day: ${parts[1]} is outside ${WiserHub.MIN_TEMP_C.toInt()}–${WiserHub.MAX_TEMP_C.toInt()}°"
                        }
                        (Math.round(temp * 2) * 5).toInt() // to the half degree, in tenths
                    }
                    else -> throw IllegalArgumentException("$day: hot water is On or Off, not “${parts[1]}”")
                }
                Slot(h * 60 + m, value)
            }.associateBy { it.minutes }.values.sortedBy { it.minutes }
            require(slots.size <= maxPerDay) { "$day has ${slots.size} changes; the hub allows $maxPerDay" }
            out[day] = slots
        }
        require(out.isNotEmpty()) { "No days found. Each line should look like “Monday: 06:00 19.0, 22:00 Off”." }
        out
    }
}
