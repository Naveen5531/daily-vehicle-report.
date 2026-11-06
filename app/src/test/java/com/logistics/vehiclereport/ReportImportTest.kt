package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ReportImportTest {

    private val day = ReportBuilder.startOfDay()
    private val now = day + 11 * 3_600_000L
    private val known = setOf("TS09UB4821", "TS08UC3307", "AP09TA1852")

    @Test fun excelWithHeadersTakesColumnsByName() {
        val sheet = listOf(
            listOf("Gate register"),
            listOf("S.No", "Vehicle No", "Vehicle Type", "Driver", "Driver Mobile", "In Time", "Out Time"),
            listOf("1", "TS 09 UB 4821", "1109", "Ravi", "9876543210", "09:15", "11:40"),
            listOf("2", "3307", "407", "", "", "0.4375", ""),
            listOf("3", "KA01AB1234", "", "", "", "10:00", "")
        )
        val f = ReportImport.fromSheets(listOf(sheet), known, day, now)
        assertEquals(listOf("TS09UB4821", "TS08UC3307"), f.cells.map { it[DrishtiData.VEHICLE] })
        assertEquals(listOf("KA01AB1234"), f.unmatched)
        val rows = DrishtiData.rows(f.cells, day, "")
        assertEquals(day + (9 * 60 + 15) * 60_000L, rows[0].checkIn)
        assertEquals(day + (11 * 60 + 40) * 60_000L, rows[0].checkOut)
        assertEquals(day + (10 * 60 + 30) * 60_000L, rows[1].checkIn) // Excel time fraction
        assertEquals("Ravi", rows[0].driver)
    }

    @Test fun excelWithoutHeadersAndNoTimeUsesNow() {
        val f = ReportImport.fromSheets(listOf(listOf(listOf("AP09TA1852"), listOf("junk"))), known, day, now)
        assertEquals(now, DrishtiData.rows(f.cells, day, "").single().checkIn)
    }

    @Test fun excelSerialDate() {
        val cal = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 17, 10, 30) }
        assertEquals(cal.timeInMillis, ReportImport.timeCell("46312.4375", day).toLong())
    }

    @Test fun photoLinesFindNumbersAndTimes() {
        val f = ReportImport.fromLines(listOf("1 TS09UB4821 1109 RAVI 09:15 AM 11:40 AM", "2 MH12XY9999 08:00", "no number"), known, now)
        assertEquals(listOf("MH12XY9999"), f.unmatched)
        val r = DrishtiData.rows(f.cells, day, "").single()
        assertEquals(day + (9 * 60 + 15) * 60_000L, r.checkIn)
        assertEquals(day + (11 * 60 + 40) * 60_000L, r.checkOut)
    }

    @Test fun last4OnlyWhenUnique() {
        assertEquals("TS09UB4821", ReportImport.resolve("4821", known, true))
        assertNull(ReportImport.resolve("4821", known, false))
        assertNull(ReportImport.resolve("1234", known, true))
        assertTrue(ReportImport.lines(listOf(
            OcrWord("11:40", 300f, 12f, 360f, 30f), OcrWord("TS09UB4821", 0f, 10f, 200f, 30f), OcrWord("next", 0f, 60f, 50f, 80f)
        )).first().startsWith("TS09UB4821"))
    }
}
