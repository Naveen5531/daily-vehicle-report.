package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drishti gate mode: Today and the daily report go by the gate log, mapped to the vehicle list and posts. */
class GateModeTest {

    private val day = ReportBuilder.startOfDay()
    private fun gate(v: String, inH: Int, outH: Int = 0, dest: String = "") =
        DrishtiRow("1041", v, "1109", "RAJU", "9876543210", day + inH * 3_600_000L, if (outH > 0) day + outH * 3_600_000L else 0L, dest)

    @Test fun notAtGateIsYetToReportNotNotPosted() {
        val v = listOf(DedicatedVehicle("TS09UB4821"), DedicatedVehicle("TS09UB1111"))
        val days = ReportBuilder.dayStatus(v, emptyList(), day, emptyMap(), false,
            DrishtiData.byVehicle(listOf(gate("TS09UB4821", 8))), gateOnly = true)
        val byNo = days.associateBy { it.vehicle.number }
        assertEquals(TodayState.YET, byNo.getValue("TS09UB1111").state)
        assertEquals("TS09UB4821", byNo.getValue("TS09UB4821").gate?.vehicle)
        // Without gate mode it stays as before.
        val old = ReportBuilder.dayStatus(v, emptyList(), day)
        assertTrue(old.all { it.state == TodayState.NOT_POSTED })
    }

    @Test fun replacementGateRowCountsForTheDedicatedVehicle() {
        val v = listOf(DedicatedVehicle("TS09UB1111"))
        val post = VehicleRecord("1", "TS09UB1111", "g", "s", "1111 replacement AP09TA1852", day + 3_600_000, MatchType.LAST4, "1111",
            listOf("TS09UB1111"), replacement = "AP09TA1852")
        val rows = listOf(gate("AP09TA1852", 9, 11, "Kukatpally"))
        val d = ReportBuilder.dayStatus(v, listOf(post), day, emptyMap(), false, DrishtiData.byVehicle(rows), true).single()
        assertEquals("AP09TA1852", d.gate?.vehicle)
        assertTrue(ReportBuilder.gateText(d).contains("as AP09TA1852"))
        val m = DrishtiData.map(rows, v, listOf(post), day).single()
        assertEquals(DrishtiData.Link.REPLACEMENT, m.link)
        assertEquals("TS09UB1111", m.forVehicle)
    }

    @Test fun byVehicleKeepsFirstInAndLastOut() {
        val m = DrishtiData.byVehicle(listOf(gate("TS09UB4821", 10, 12), gate("TS09UB4821", 7, 8)))
        val r = m.getValue("TS09UB4821")
        assertEquals(day + 7 * 3_600_000L, r.checkIn)
        assertEquals(day + 12 * 3_600_000L, r.checkOut)
        // Came back in after leaving and is inside again.
        val back = DrishtiData.byVehicle(listOf(gate("TS09UB4821", 7, 8), gate("TS09UB4821", 10))).getValue("TS09UB4821")
        assertEquals(0L, back.checkOut)
    }

    @Test fun dailyReportListsEveryGateVehicleOnce() {
        val v = listOf(DedicatedVehicle("TS09UB4821"), DedicatedVehicle("TS09UB1111"))
        val log = DrishtiData.byVehicle(listOf(gate("TS09UB4821", 8, 10, "Kukatpally"), gate("KA01AB1234", 9)))
        val gates = ReportBuilder.Gates(since = 0L, gateOnly = true, gateLog = { if (it == day) log else emptyMap() })
        val rows = ReportBuilder.dailyRows(v, emptyList(), day, day, null, gates)
        val header = rows.first()
        assertTrue(rows.all { it.size == header.size })
        val numbers = rows.drop(1).dropLast(1).map { it[2] }
        assertEquals(listOf("TS09UB4821", "TS09UB1111", "KA01AB1234"), numbers)
        val gateCol = header.indexOf("Drishti Gate")
        assertTrue(rows[1][gateCol].startsWith("In "))
        assertEquals("Not at gate yet", rows[2][gateCol])
        assertEquals("Drishti gate", rows[1][header.indexOf("Reported By")])
    }
}
