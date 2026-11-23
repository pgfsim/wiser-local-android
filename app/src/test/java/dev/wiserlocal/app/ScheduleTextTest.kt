package dev.wiserlocal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTextTest {

    @Test
    fun heatingRoundTrips() {
        val days = WiserHub.DAYS.associateWith { listOf(Slot(360, 190), Slot(480, 175), Slot(1290, WiserHub.SCHEDULE_OFF)) }
        val text = ScheduleText.format(days, heating = true)
        assertTrue(text.startsWith("Monday: 06:00 19.0, 08:00 17.5, 21:30 Off"))
        assertEquals(days, ScheduleText.parse(text, heating = true, maxPerDay = 6).getOrThrow())
    }

    @Test
    fun hotWaterRoundTrips() {
        val days = WiserHub.DAYS.associateWith { listOf(Slot(450, 1), Slot(510, 0)) }
        val text = ScheduleText.format(days, heating = false)
        assertTrue(text.contains("Tuesday: 07:30 On, 08:30 Off"))
        assertEquals(days, ScheduleText.parse(text, heating = false, maxPerDay = 6).getOrThrow())
    }

    @Test
    fun acceptsShortDaysDegreesAndRoundsToHalfDegrees() {
        val parsed = ScheduleText.parse("sat: 7:05 19.3°, 22:00 off", heating = true, maxPerDay = 6).getOrThrow()
        assertEquals(mapOf("Saturday" to listOf(Slot(425, 195), Slot(1320, WiserHub.SCHEDULE_OFF))), parsed)
    }

    @Test
    fun rejectsBadText() {
        assertTrue(ScheduleText.parse("hello", heating = true, maxPerDay = 6).isFailure)
        assertTrue(ScheduleText.parse("Monday: 25:00 19", heating = true, maxPerDay = 6).isFailure)
        assertTrue(ScheduleText.parse("Monday: 06:00 40", heating = true, maxPerDay = 6).isFailure)
        assertTrue(ScheduleText.parse("Monday: 06:00 19.0", heating = false, maxPerDay = 6).isFailure)
        assertTrue(ScheduleText.parse("Monday: 01:00 19, 02:00 19, 03:00 19", heating = true, maxPerDay = 2).isFailure)
    }

    @Test
    fun readsHubWifiQrCodes() {
        assertEquals(HubWifi("Wiser_ABC123", "p;w:d"), HubSetupWifi.parse("WIFI:S:Wiser_ABC123;T:WPA;P:p\\;w\\:d;;"))
        assertEquals(HubWifi("WiserHeat0A1B2C", null), HubSetupWifi.parse("WIFI:T:nopass;S:WiserHeat0A1B2C;;"))
        assertNull(HubSetupWifi.parse("https://example.com"))
    }
}
