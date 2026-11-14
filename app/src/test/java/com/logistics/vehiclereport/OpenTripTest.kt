package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Today shows vehicles still at the DC from an earlier check-in; a check-out closes the trip. */
class OpenTripTest {
    private val day = ReportBuilder.startOfDay()
    private val h = 3_600_000L
    private fun before(n: Int) = ReportBuilder.startOfDay(day - n * ReportBuilder.DAY_MS + ReportBuilder.DAY_MS / 2)
    private fun row(v: String, inAt: Long, out: Long = 0) = DrishtiRow("1041", v, "", "", "", inAt, out)

    private val y = before(1)
    private val y2 = before(2)
    private val logs = mapOf(
        y to mapOf(
            "OPEN1" to row("OPEN1", y + 20 * h),                 // in yesterday, never out -> still at DC
            "CLOSED" to row("CLOSED", y + 10 * h, y + 15 * h),   // in and out yesterday -> closed
            "LEFTTODAY" to row("LEFTTODAY", y + 22 * h, day + 11 * h), // out today after 9 AM -> Departed today
            "EARLYOUT" to row("EARLYOUT", y + 21 * h, day + 7 * h),    // out today before 9 AM -> yesterday's trip
            "AGAIN" to row("AGAIN", y + 8 * h)                   // also checked in again today
        ),
        y2 to mapOf(
            "OPEN2" to row("OPEN2", y2 + 9 * h),                 // in 2 days ago, never out
            "CLOSED" to row("CLOSED", y2 + 9 * h)                // older open trip, but yesterday's trip is the latest
        )
    )
    private val marks = mapOf(
        y to mapOf("HAND" to GateMark(Gate.DEPARTED, y + 9 * h, y + 12 * h), "HANDOPEN" to GateMark(Gate.REPORTED, y + 11 * h))
    )

    private val trips = GateMarks.openTrips(day, setOf("AGAIN"), { marks[it].orEmpty() }, { logs[it].orEmpty() })

    @Test fun openTripsCarry() {
        assertEquals(Gate.REPORTED, trips.first["OPEN1"]?.gate)
        assertEquals(y + 20 * h, trips.first["OPEN1"]?.reportedAt)
        assertEquals(Gate.REPORTED, trips.first["OPEN2"]?.gate)
        assertEquals(Gate.REPORTED, trips.first["HANDOPEN"]?.gate)
    }

    @Test fun checkOutClosesTrip() {
        assertFalse("CLOSED" in trips.first)
        assertFalse("HAND" in trips.first)
    }

    @Test fun leftTodayIsDeparted() {
        assertEquals(Gate.DEPARTED, trips.first["LEFTTODAY"]?.gate)
        assertEquals(day + 11 * h, trips.first["LEFTTODAY"]?.departedAt)
    }

    @Test fun outBefore9amIsYesterdays() {
        assertFalse("EARLYOUT" in trips.first)
        // Checked in today at 2 AM, out at 8 AM: yesterday's loading -> not today's, shown Departed on yesterday.
        val today = GateMarks.earlyMorning(day, emptyMap(), mapOf("X" to row("X", day + 2 * h, day + 8 * h)), emptyMap(), emptyMap())
        assertFalse("X" in today.first || "X" in today.second)
        val yest = GateMarks.earlyMorning(y, emptyMap(), emptyMap(), emptyMap(), mapOf("X" to row("X", day + 2 * h, day + 8 * h)))
        assertEquals(Gate.DEPARTED, yest.first["X"]?.gate)
        assertEquals(day + 8 * h, yest.first["X"]?.departedAt)
        // Out at 10 AM: today's own trip, stays today.
        val late = GateMarks.earlyMorning(day, emptyMap(), mapOf("Z" to row("Z", day + 2 * h, day + 10 * h)), emptyMap(), emptyMap())
        assertTrue("Z" in late.second)
    }

    @Test fun newCheckInTodayWins() {
        assertFalse("AGAIN" in trips.first)
        assertTrue("OPEN1" in trips.second)
    }
}
