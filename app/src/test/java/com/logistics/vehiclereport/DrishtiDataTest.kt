package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class DrishtiDataTest {

    private fun at(s: String) = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).parse(s)!!.time
    private val day = ReportBuilder.startOfDay(at("17-10-2026 12:00:00"))

    private fun row(site: String, no: String, type: String, inT: String, outT: String = "") =
        listOf(site, no, type, "SEDHU", "9094768438", inT, outT, "")

    @Test fun readsTheScreenshotFormat() {
        assertEquals(at("17-10-2026 10:14:16"), DrishtiData.parseTime("17-10-2026\n10:14:16 AM", day))
        assertEquals(at("17-10-2026 22:01:44"), DrishtiData.parseTime("17-10-2026 10:01:44 pm", day))
        assertEquals(at("17-10-2026 09:10:00"), DrishtiData.parseTime("17/10/2026 09:10", day))
        assertEquals(at("17-10-2026 09:10:46"), DrishtiData.parseTime("09:10:46 AM", day))
        assertEquals(0L, DrishtiData.parseTime("-", day))
        assertEquals(0L, DrishtiData.parseTime("", day))
    }

    @Test fun rowsKeepOnlyTheSiteAndDropRepeats() {
        val rows = DrishtiData.rows(listOf(
            row("1041", "TN12BH6176", "1109", "17-10-2026 10:14:16 AM"),
            row("1041", "TN12BH6176", "1109", "17-10-2026 10:14:16 AM", "17-10-2026 01:00:00 PM"),
            row("1042", "TG08V4638", "PKUP", "17-10-2026 10:01:44 AM"),
            row("1041", "", "PKUP", "17-10-2026 10:01:44 AM"),
            row("1041", "ap 09 ta 1852", "PKUP", "17-10-2026 10:01:09 AM")
        ), day, "1041")
        assertEquals(listOf("TN12BH6176", "AP09TA1852"), rows.map { it.vehicle })
        // The repeat with a check-out time wins.
        assertEquals(at("17-10-2026 13:00:00"), rows[0].checkOut)
        assertEquals("9094768438", rows[0].mobile)
        assertEquals(day, rows[1].day)
    }

    @Test fun plateRejectsNonPlates() {
        assertNull(DrishtiData.plate("SEDHU"))
        assertNull(DrishtiData.plate("1041"))
        assertEquals("TS08UK0437", DrishtiData.plate("TS08UK0437"))
    }

    @Test fun marksDedicatedPostedAndReplacedVehiclesOnly() {
        val vehicles = listOf(
            DedicatedVehicle("TS09UB4821"),                       // dedicated, came to the gate
            DedicatedVehicle("TS09UB1111"),                       // dedicated, replaced by AP09TA1852
            DedicatedVehicle("TG08V4638", dedicated = false)      // record only, posted today
        )
        val t = at("17-10-2026 08:00:00")
        val records = listOf(
            VehicleRecord("1", "TG08V4638", "g", "s", "4638 Khammam", t, MatchType.LAST4, "4638", listOf("TG08V4638")),
            VehicleRecord("2", "TS09UB1111", "g", "s", "1111 replacement AP09TA1852", t, MatchType.LAST4, "1111",
                listOf("TS09UB1111"), replacement = "AP09TA1852")
        )
        val rows = DrishtiData.rows(listOf(
            row("1041", "TS09UB4821", "1109", "17-10-2026 09:00:00 AM", "17-10-2026 11:00:00 AM"),
            row("1041", "TG08V4638", "PKUP", "17-10-2026 10:00:00 AM"),
            row("1041", "AP09TA1852", "PKUP", "17-10-2026 10:30:00 AM"),
            row("1041", "KA01AB9999", "1109", "17-10-2026 10:45:00 AM")   // nobody's vehicle: log only
        ), day, "1041")
        val marks = DrishtiData.marks(rows, vehicles, records) { emptySet() }.associateBy { it.vehicle }
        assertEquals(Gate.DEPARTED, marks.getValue("TS09UB4821").gate)
        assertEquals(at("17-10-2026 11:00:00"), marks.getValue("TS09UB4821").departedAt)
        assertEquals(Gate.REPORTED, marks.getValue("TG08V4638").gate)
        assertEquals(at("17-10-2026 10:30:00"), marks.getValue("TS09UB1111").reportedAt)
        assertTrue("KA01AB9999" !in marks)
        assertTrue("AP09TA1852" !in marks)
    }
}
