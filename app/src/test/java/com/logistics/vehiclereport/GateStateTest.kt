package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Today: a WhatsApp post means "Yet to report" until the DC marks the vehicle Reported, then Departed. */
class GateStateTest {
    private val day = ReportBuilder.startOfDay()
    private val vehicles = listOf(DedicatedVehicle("TS09UB4821"), DedicatedVehicle("TS08UC3307"), DedicatedVehicle("AP28TD6142"), DedicatedVehicle("TS12UE2290"))
    private fun post(v: String, at: Long) = VehicleRecord("id$v", v, "g", "Ravi", "${v.takeLast(4)} Kukatpally", at, MatchType.LAST4, v.takeLast(4), listOf(v))
    private val records = listOf(post("TS09UB4821", day + 3_600_000), post("TS08UC3307", day + 7_200_000), post("AP28TD6142", day + 9_000_000))
    private val marks = mapOf(
        "TS08UC3307" to GateMark(Gate.REPORTED, reportedAt = day + 8_000_000),
        "AP28TD6142" to GateMark(Gate.DEPARTED, reportedAt = day + 9_100_000, departedAt = day + 12_000_000),
        "TS12UE2290" to GateMark(Gate.REPORTED, reportedAt = day + 10_000_000)   // came without a post
    )

    @Test fun postedIsYetToReportUntilMarked() {
        val days = ReportBuilder.dayStatus(vehicles, records, day, marks).associateBy { it.vehicle.number }
        assertEquals(TodayState.YET, days.getValue("TS09UB4821").state)
        assertEquals(TodayState.REPORTED, days.getValue("TS08UC3307").state)
        assertEquals(TodayState.DEPARTED, days.getValue("AP28TD6142").state)
        assertEquals(TodayState.REPORTED, days.getValue("TS12UE2290").state)
        assertFalse(days.getValue("TS09UB4821").arrived)
        assertTrue(days.getValue("AP28TD6142").arrived)
    }

    @Test fun notPostedStaysSeparate() {
        val d = ReportBuilder.dayStatus(vehicles, records, day).associateBy { it.vehicle.number }
        assertEquals(TodayState.NOT_POSTED, d.getValue("TS12UE2290").state)
    }

    @Test fun daysBeforeMarksKeepPostsAsReported() {
        val d = ReportBuilder.dayStatus(vehicles, records, day, legacy = true).associateBy { it.vehicle.number }
        assertEquals(TodayState.REPORTED, d.getValue("TS09UB4821").state)
    }

    @Test fun excelHasStatusAndTimes() {
        val gates = ReportBuilder.Gates(since = day) { if (it == day) marks else emptyMap() }
        val rows = ReportBuilder.dailyRows(vehicles, records, day, day, null, gates)
        val h = rows.first()
        assertEquals(ReportBuilder.DAILY_WIDTHS.size, h.size)
        val st = h.indexOf("Status"); val r = h.indexOf("Reported At"); val dep = h.indexOf("Departed At"); val num = h.indexOf("Vehicle Number")
        assertEquals(st + 1, r); assertEquals(st + 2, dep)
        val byNum = rows.drop(1).filter { it[0] != "TOTAL" }.associateBy { it[num] }
        rows.drop(1).forEach { assertEquals(h.size, it.size) }
        assertEquals("Yet to report", byNum.getValue("TS09UB4821")[st])
        assertEquals("", byNum.getValue("TS09UB4821")[r])
        assertEquals("Reported", byNum.getValue("TS08UC3307")[st])
        assertEquals(ReportBuilder.timeFmt().format(java.util.Date(day + 8_000_000)), byNum.getValue("TS08UC3307")[r])
        assertEquals("Departed", byNum.getValue("AP28TD6142")[st])
        assertEquals(ReportBuilder.timeFmt().format(java.util.Date(day + 12_000_000)), byNum.getValue("AP28TD6142")[dep])
        assertEquals("Reported", byNum.getValue("TS12UE2290")[st])
    }

    @Test fun scannedPlateGivesTheFullNumber() {
        val scans = mapOf(PlateScans.dayKey(day + 3_600_000) to mapOf("4821" to listOf("TS09UB4821" to day + 4_000_000L)))
        val recs = PlateScans.apply(records, scans)
        val d = ReportBuilder.dayStatus(vehicles, recs, day).associateBy { it.vehicle.number }
        assertFalse(d.getValue("TS09UB4821").last4Only)
        assertEquals(day + 4_000_000, d.getValue("TS09UB4821").scannedAt)
        assertTrue(d.getValue("TS08UC3307").last4Only)
        val rows = ReportBuilder.dailyRows(vehicles, recs, day, day)
        val h = rows.first(); val num = h.indexOf("Vehicle Number"); val chk = h.indexOf("Number Check")
        assertEquals(h.size - 1, chk)
        val byNum = rows.drop(1).filter { it[0] != "TOTAL" }.associateBy { it[num] }
        assertTrue(byNum.getValue("TS09UB4821")[chk].startsWith("Plate scanned"))
        assertEquals("Last 4 only – full number not checked", byNum.getValue("TS08UC3307")[chk])
    }

    @Test fun plateOfAnotherVehicleMovesThePost() {
        val scans = mapOf(PlateScans.dayKey(day + 3_600_000) to mapOf("4821" to listOf("TS15XY4821" to day + 4_000_000L)))
        val recs = PlateScans.apply(records, scans)
        val d = ReportBuilder.dayStatus(vehicles, recs, day).associateBy { it.vehicle.number }
        assertEquals(TodayState.NOT_POSTED, d.getValue("TS09UB4821").state)
        assertEquals(listOf("TS15XY4821"), ReportBuilder.notInList(vehicles, recs, day).map { it.vehicle.number })
    }

    @Test fun summaryCountsMarkedDays() {
        val gates = ReportBuilder.Gates(since = day) { if (it == day) marks else emptyMap() }
        val rows = ReportBuilder.summaryRows(vehicles, records, day, day, gates)
        val h = rows.first()
        assertEquals(ReportBuilder.SUMMARY_WIDTHS.size, h.size)
        val byNum = rows.drop(1).associateBy { it[1] }
        val rep = h.indexOf("Days Reported"); val yet = h.indexOf("Days Yet to Report"); val np = h.indexOf("Days Not Posted")
        assertEquals("0", byNum.getValue("TS09UB4821")[rep]); assertEquals("1", byNum.getValue("TS09UB4821")[yet])
        assertEquals("1", byNum.getValue("AP28TD6142")[rep])
        assertEquals("1", byNum.getValue("TS12UE2290")[rep]); assertEquals("0", byNum.getValue("TS12UE2290")[np])
    }
}
