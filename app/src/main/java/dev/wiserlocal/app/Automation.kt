package dev.wiserlocal.app

import org.json.JSONArray
import org.json.JSONObject

/*
 * Automations as the hub stores them (formats captured from the official app on a
 * real hub). Each part keeps the JSON it came from, with lower-case first letters as
 * the official app sends it, so saving an automation from this app never drops
 * anything it doesn't understand (a device trigger, say). New parts are built in the
 * same format by the helpers below.
 */

/** What an automation does: its If ([triggers], combined by [triggerMode]), When ([times]) and Then ([actions]). */
data class AutomationDetail(
    /** "All" or "Any". */
    val triggerMode: String,
    val triggers: List<AutomationTrigger>,
    val times: List<AutomationTime>,
    val actions: List<AutomationAction>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("triggerMode", triggerMode)
        .put("triggers", JSONArray(triggers.map { it.json }))
        .put("timeConstraints", JSONArray(times.map { it.json }))
        .put("actions", JSONArray(actions.map { it.json }))

    companion object {
        /** From the hub's answer with keys already converted by [WiserHub.camelKeys]. */
        fun parse(json: JSONObject) = AutomationDetail(
            triggerMode = json.optString("triggerMode", "All"),
            triggers = json.objects("triggers").map(AutomationTrigger::parse),
            times = json.objects("timeConstraints").map(AutomationTime::parse),
            actions = json.objects("actions").map(AutomationAction::parse),
        )
    }
}

/** "If [domain] [id]'s [property] [comparator] [compareTo]", e.g. HotWater 2 WaterHeatingState Equals Off. */
data class AutomationTrigger(
    val domain: String,
    val id: Int,
    val property: String,
    val comparator: String,
    val compareTo: String,
    val json: JSONObject,
) {
    companion object {
        fun parse(o: JSONObject): AutomationTrigger {
            // compareTo holds one typed value, e.g. {"stringValue":"Off"}.
            val compare = o.optJSONObject("compareTo")
            val compareText = compare?.keys()?.asSequence()?.firstOrNull()?.let { compare.opt(it)?.toString() } ?: ""
            return AutomationTrigger(
                domain = o.optString("domain"),
                id = o.optInt("id"),
                property = o.optString("get"),
                comparator = o.optString("comparator"),
                compareTo = compareText,
                json = o,
            )
        }

        /** Away mode turning on: the system's override type becomes "Away". */
        fun awayModeOn() = parse(
            JSONObject().put("domain", "System").put("get", "OverrideType").put("comparator", "Equals")
                .put("compareTo", JSONObject().put("stringValue", "Away")),
        )

        /** The hot water turning on or off. */
        fun hotWater(id: Int, on: Boolean) = parse(
            JSONObject().put("domain", "HotWater").put("id", id).put("get", "WaterHeatingState").put("comparator", "Equals")
                .put("compareTo", JSONObject().put("stringValue", if (on) "On" else "Off")),
        )
    }
}

/**
 * A time condition. A period has [start] and [end]; a single time has only [start].
 * Times are HHMM (705 = 07:05), or [WiserHub.SUNRISE] / [WiserHub.SUNSET]. [days]
 * are spelled as in [WiserHub.DAYS].
 */
data class AutomationTime(val start: Int?, val end: Int?, val days: List<String>, val json: JSONObject) {
    companion object {
        fun parse(o: JSONObject): AutomationTime {
            fun int(key: String) = if (o.has(key)) o.optInt(key) else null
            val days = o.optJSONObject("activeDays")
            return AutomationTime(
                start = int("startTime") ?: int("time"),
                end = int("endTime"),
                days = WiserHub.DAYS.filter { days?.optBoolean(it.lowercase()) == true },
                json = o,
            )
        }

        private fun activeDays(days: List<String>) =
            JSONObject().apply { WiserHub.DAYS.forEach { put(it.lowercase(), it in days) } }

        fun at(hhmm: Int, days: List<String>) = parse(JSONObject().put("time", hhmm).put("activeDays", activeDays(days)))

        fun between(start: Int, end: Int, days: List<String>) =
            parse(JSONObject().put("startTime", start).put("endTime", end).put("activeDays", activeDays(days)))
    }
}

/**
 * One step. Usually [action] on [domain] [id] with [value] (often a JSON string such as
 * {"type":"Boost",...}); a push notification step has [notify] set, a pause has
 * [waitHhmm] (102 = 1 hour 2 minutes).
 */
data class AutomationAction(
    val domain: String,
    val id: Int,
    val action: String,
    val value: String,
    val notify: Boolean?,
    val waitHhmm: Int?,
    val json: JSONObject,
) {
    companion object {
        fun parse(o: JSONObject) = AutomationAction(
            domain = o.optString("domain"),
            id = o.optInt("id"),
            action = o.optString("action"),
            value = o.optString("value"),
            notify = if (o.has("enableNotification")) o.optBoolean("enableNotification") else null,
            waitHhmm = if (o.has("wait")) o.optInt("wait") else null,
            json = o,
        )

        /** A push notification to phones signed in to the official app. */
        fun notification() = parse(JSONObject().put("enableNotification", true))

        fun wait(minutes: Int) = parse(JSONObject().put("wait", minutes / 60 * 100 + minutes % 60))

        fun runMoment(momentId: Int) =
            parse(JSONObject().put("domain", "System").put("action", "TriggerMoment").put("value", momentId.toString()))

        /**
         * A room or the hot water put on a manual setting ([setPoint] in tenths, or
         * [WiserHub.SCHEDULE_OFF] / [WiserHub.HOT_WATER_ON]), for [minutes] if given.
         */
        fun manual(domain: String, id: Int, setPoint: Int, minutes: Int? = null): AutomationAction {
            val override = JSONObject().put("type", "Manual").put("setPoint", setPoint)
            minutes?.let { override.put("durationMinutes", it) }
            return parse(
                JSONObject().put("domain", domain).put("id", id).put("action", "RequestOverride").put("value", override.toString()),
            )
        }

        /** A room boost for [minutes], as the official app sends it. */
        fun boost(domain: String, id: Int, minutes: Int) = parse(
            JSONObject().put("domain", domain).put("id", id).put("action", "RequestOverride")
                .put("value", JSONObject().put("type", "Boost").put("durationMinutes", minutes).toString()),
        )
    }
}

private fun JSONObject.objects(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
