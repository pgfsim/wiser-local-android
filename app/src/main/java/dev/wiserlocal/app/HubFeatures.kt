package dev.wiserlocal.app

import org.json.JSONObject

/**
 * Which features this hub supports. The hub reports three things: its firmware version,
 * a list of device types it can pair (DeviceCapabilityMatrix) and a list of feature limits
 * (FeatureCapability). The firmware minimums below are the ones the hub's firmware needs
 * for each feature.
 *
 * When something is missing (the hub sent no firmware version or no list), the check
 * passes, so a hub that reports less is never locked out of what it can already do.
 */
data class HubFeatures(
    /** Major, minor and build of the hub's firmware, or null if the hub didn't say. */
    val firmware: List<Int>? = null,
    /** DeviceCapabilityMatrix: device type name to whether this hub can pair it. */
    val deviceTypes: Map<String, Boolean>? = null,
    /** FeatureCapability.Automation limits, or null if the hub didn't send them. */
    val automationLimits: AutomationLimits? = null,
    /** FeatureCapability's yes/no flags, such as Sse and RoomSupports2HeatChannels. */
    val flags: Map<String, Boolean> = emptyMap(),
) {
    data class AutomationLimits(val maxActions: Int, val maxTriggers: Int, val maxTimes: Int, val wait: Boolean)

    /** The firmware version as text, such as "4.52.3", if known. */
    val firmwareText: String? get() = firmware?.joinToString(".")

    /** True if the firmware is at least [major].[minor].[build], or if the version is unknown. */
    fun atLeast(major: Int, minor: Int = 0, build: Int = 0): Boolean {
        val have = firmware ?: return true
        val want = listOf(major, minor, build)
        for (i in 0..2) {
            val a = have.getOrElse(i) { 0 }
            if (a != want[i]) return a > want[i]
        }
        return true
    }

    /**
     * Whether the hub can pair [deviceType] (a DeviceCapabilityMatrix name such as "SmartPlug"),
     * with firmware at least [minimum]. The list is only trusted from firmware 2.23.11.
     */
    fun supportsDevice(deviceType: String, minimum: Triple<Int, Int, Int> = Triple(0, 0, 0)): Boolean {
        if (!atLeast(minimum.first, minimum.second, minimum.third)) return false
        if (!atLeast(2, 23, 11)) return false
        return deviceTypes?.get(deviceType) ?: (deviceTypes == null)
    }

    val awayMode get() = atLeast(2, 12, 2)
    val deviceLock get() = atLeast(2, 19, 78)
    val openWindowDetection get() = atLeast(2, 18, 100)
    val boostAll get() = atLeast(2, 29, 0)
    val openTherm get() = atLeast(2, 29, 0)
    val heatPump get() = atLeast(3, 3, 3)
    val moments get() = atLeast(3, 7, 14)
    val automations get() = atLeast(4, 6, 0)
    val legacyPowerTagMode get() = atLeast(4, 34, 11)
    /** Assigning a schedule to rooms or devices. */
    val assignableSchedules get() = atLeast(2, 55, 0)
    /** The hub keeps each room's heating type (radiators, underfloor, electric). */
    val roomHeatingType get() = atLeast(2, 55, 0)

    /** Most actions, conditions and times one automation can have. */
    val automationMaxActions get() = automationLimits?.maxActions?.coerceAtLeast(1) ?: DEFAULT_AUTOMATION_PARTS
    val automationMaxTriggers get() = automationLimits?.maxTriggers?.coerceAtLeast(1) ?: DEFAULT_AUTOMATION_PARTS
    val automationMaxTimes get() = automationLimits?.maxTimes?.coerceAtLeast(0) ?: DEFAULT_AUTOMATION_PARTS
    /** Whether automations can have a Wait step. */
    val automationWait get() = automationLimits?.wait ?: true

    companion object {
        const val DEFAULT_AUTOMATION_PARTS = 10

        fun parse(domain: JSONObject): HubFeatures {
            var version: String? = null
            domain.optJSONArray("Device")?.let { devices ->
                for (i in 0 until devices.length()) {
                    val d = devices.optJSONObject(i) ?: continue
                    if (d.optString("ProductType") == "Controller") {
                        version = d.optString("ActiveFirmwareVersion").takeIf { it.isNotBlank() }
                        break
                    }
                }
            }
            if (version == null) {
                version = domain.optJSONObject("System")?.optString("ActiveSystemVersion")?.takeIf { it.isNotBlank() }
            }

            val matrix = domain.optJSONObject("DeviceCapabilityMatrix")?.let { m ->
                m.keys().asSequence().associateWith { m.optBoolean(it) }
            }
            val capability = domain.optJSONObject("FeatureCapability")
            val limits = capability?.optJSONObject("Automation")?.let { a ->
                AutomationLimits(a.optInt("MaxActions"), a.optInt("MaxTriggers"), a.optInt("MaxTimeConstraints"), a.optBoolean("Wait"))
            }
            val flags = capability?.let { c ->
                c.keys().asSequence().filter { c.opt(it) is Boolean }.associateWith { c.optBoolean(it) }
            } ?: emptyMap()
            return HubFeatures(version?.let(::parseVersion), matrix, limits, flags)
        }

        /** "4.52.3-51e4355" to [4, 52, 3]; null if it doesn't start with a number. */
        fun parseVersion(text: String): List<Int>? =
            Regex("""^\D*(\d+)(?:\.(\d+))?(?:\.(\d+))?""").find(text)?.groupValues?.drop(1)
                ?.map { it.toIntOrNull() ?: 0 }
    }
}
