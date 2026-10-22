package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class RateCardTest {

    private fun xlsx(rows: List<List<String>>): ByteArray {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(out, "Rates", rows, emptyList())
        return out.toByteArray()
    }

    @Test fun oneRowPerRate() {
        val card = RateCard.fromFile(xlsx(listOf(
            listOf("Store Code", "Store Name", "Vehicle Type", "Rate"),
            listOf("5304", "Suryabagh Vizag DMart", "24 Feet", "18,500"),
            listOf("5841", "Askihal Raichur", "32ft MXL", "26000")
        )))
        assertEquals(2, card.rates.size)
        assertEquals("18500", card.lookup("5304", "24 FT")?.amount)
        assertEquals("26000", card.lookup("5841", "32 FT")?.amount)   // only one 32 FT rate for the store
        assertNull(card.lookup("5304", "22 FT"))
    }

    @Test fun columnPerVehicleTypeTemplate() {
        val card = RateCard.fromFile(xlsx(listOf(
            listOf("Store Code", "Store Name", "14 FT", "22 FT", "32 FT SXL", "32 FT MXL"),
            listOf("5332", "Eluru", "", "15000", "21000", "24000"),
            listOf("5102", "Kurnool", "9000", "", "", "")
        )))
        assertEquals(4, card.rates.size)
        assertEquals("15000", card.lookup("5332", "22FT")?.amount)
        assertEquals("24000", card.lookup("5332", "32 FT MXL")?.amount)
        assertNull(card.lookup("5332", "32 FT"))                       // SXL or MXL? not guessed
        assertEquals("9000", card.lookup("5102", "14 feet")?.amount)
    }

    @Test fun reportPicksRateFromStoreAndType() {
        val masters = Masters(listOf(Store("5332", "Eluru West Godavari AP D-Mart", "Eluru", "Andhra Pradesh 2")), emptyList())
        val rates = RateCard(listOf(Rate("5332", "", "22 FT", "15000")))
        val rec = VehicleRecord("1", "TG07W3184", "1041 DC Transport Team", "Dharmendra", "TG07W3184.\nELURU 22FT", ReportBuilder.startOfDay() + 3_600_000,
            MatchType.FULL, "TG07W3184", listOf("TG07W3184"), city = "Eluru", size = "22 FT")
        val rows = ReportBuilder.logRows(emptyList(), listOf(rec), ReportBuilder.Lookups(masters, rates))
        val h = rows[0]
        assertEquals("5332", rows[1][h.indexOf("Store Code")])
        assertEquals("22 FT", rows[1][h.indexOf("Vehicle Type")])
        assertEquals("15000", rows[1][h.indexOf("Rate (₹)")])
        assertEquals("TOTAL", rows[2][0])
        assertEquals("15000", rows[2][h.indexOf("Rate (₹)")])
    }
}

class UniquePerDayTest {
    private val day = ReportBuilder.startOfDay() + 8 * 3_600_000L
    private fun rec(v: String, t: Long, match: String = MatchType.FULL, cands: List<String> = listOf(v), repl: String = "", city: String = "") =
        VehicleRecord("$v$t", v, "g", "s", "msg $v $t", t, match, v, cands, city = city, replacement = repl)

    @Test fun eachVehicleOncePerDate() {
        val list = listOf(
            rec("TS12UD9713", day), rec("TS12UD9713", day + 60_000, city = "Kurnool"),
            rec("TS12UD9713 / AP39XX9713", day + 120_000, MatchType.MULTI, listOf("TS12UD9713", "AP39XX9713")),
            rec("TS12UD2502", day + 180_000, repl = "TG08V5926"), rec("TG08V5926", day + 30_000),
            rec("TS12UD9713", day + ReportBuilder.DAY_MS)   // next day stays separate
        )
        val u = ReportBuilder.uniquePerDay(list)
        assertEquals(3, u.size)
        val v = u.first { it.vehicle == "TS12UD9713" && it.time == day }
        assertEquals(3, v.count)
        assertEquals("Kurnool", v.city)
        val r = u.single { it.vehicle == "TS12UD2502" }
        assertEquals(day + 30_000, r.time)   // earliest report of the replacement vehicle
        assertEquals(2, r.count)
    }
}

class RecordOnlyTest {
    @Test fun recordOnlyVehiclesAreNeverNotReported() {
        val day = ReportBuilder.startOfDay()
        val vehicles = listOf(DedicatedVehicle("TS12UD9713"), DedicatedVehicle("TG08V5925", dedicated = false), DedicatedVehicle("AP39WJ1149", dedicated = false))
        val rec = VehicleRecord("1", "AP39WJ1149", "g", "s", "1149 Suryabagh", day + 3_600_000, MatchType.LAST4, "1149", listOf("AP39WJ1149"))
        val days = ReportBuilder.dayStatus(vehicles, listOf(rec), day)
        assertEquals(listOf("TS12UD9713", "AP39WJ1149"), days.map { it.vehicle.number })   // TG08V5925 hidden: record only, not reported
        assertEquals(DayStatus.PENDING, days[0].status)
        assertEquals(DayStatus.REPORTED, days[1].status)
    }
}

class CodeAfterImportTest {
    @Test fun codeSavedBeforeImportCountsAsReported() {
        val day = ReportBuilder.startOfDay()
        // what the app saved at 08:46 when 5090 was not in the list yet ...
        val code = VehicleRecord("1", "5090", "g", "Dharmendra", "5090 Khammam\nTeamplay", day + 3_600_000, MatchType.CODE, "5090", listOf("5090"), city = "Khammam")
        // ... is matched by the list imported later (same rule the app applies when it loads entries)
        val list = listOf(DedicatedVehicle("TS12UD5090"))
        val resolved = listOf(code).map { r ->
            val c = list.map { it.number }.filter { VehicleParser.last4(it) == r.typed }
            if (r.match == MatchType.CODE && c.size == 1) r.copy(match = MatchType.LAST4, candidates = c, vehicle = c[0]) else r
        }
        val d = ReportBuilder.dayStatus(list, resolved, day).single()
        assertEquals(DayStatus.REPORTED, d.status)
        assertEquals(emptyList<VehicleDay>(), ReportBuilder.notInList(list, resolved, day))
    }
}
