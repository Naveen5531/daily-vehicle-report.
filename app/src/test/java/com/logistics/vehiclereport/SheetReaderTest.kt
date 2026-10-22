package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Four phone photos of a "VEHICLE REQ" sheet on a PC screen, as the phone's text reader read them (word, box, slant).
 * Every store line must come out with the right volume: each photo's total matches the total printed on the sheet.
 */
class SheetReaderTest {
    private val words = File("src/test/resources/vehicle_req_words.tsv").readLines().filter { it.startsWith("V") }
    private val masters = MasterData.fromWorkbook(File("src/test/resources/store_vendor_master.xlsx").readBytes())
    private val known = masters.stores.associate { it.code to it.name }

    private fun photo(n: Int): SheetReader.Result = SheetReader.fromWords(
        words.filter { it.startsWith("V$n\t") }.map { it.split("\t") }
            .map { OcrWord(it[1], it[2].toFloat(), it[3].toFloat(), it[4].toFloat(), it[5].toFloat(), it[6].toFloat()) },
        known
    )

    @Test fun everyPhotoMatchesItsSheetTotal() {
        val expected = mapOf(1 to (4 to 2001.192), 2 to (24 to 14730.1), 3 to (7 to 4636.214), 4 to (28 to 14037.95))
        for ((n, e) in expected) {
            val r = photo(n)
            assertEquals("photo $n stores", e.first, r.stops.size)
            assertEquals("photo $n sheet total", e.second, r.sheetTotal!!, 0.01)
            assertEquals("photo $n read total", e.second, r.readTotal, 0.6)
            assertTrue("photo $n check", r.totalOk)
        }
    }

    @Test fun lostDecimalPointsAndSlipsArePutRight() {
        val all = (1..4).flatMap { photo(it).stops }.associateBy { it.code }
        assertEquals(425.239, all.getValue("5013").volume, 1e-6)   // read "425239"
        assertEquals(541.121, all.getValue("5304").volume, 1e-6)   // read "541121"
        assertEquals(330.269, all.getValue("5332").volume, 1e-6)   // read "330269"
        assertEquals(11.84, all.getValue("5302").volume, 1e-6)     // read "1184"
        assertEquals(704.461, all.getValue("5201").volume, 1e-6)   // read "I704-461"
        assertEquals(512.753, all.getValue("5107").volume, 1e-6)   // read "512.7532441.032"
        assertEquals(507.242, all.getValue("5231").volume, 1e-6)   // read "507/242"
        assertEquals(3421.23, all.getValue("5305").weight, 1e-6)   // read "342123" (mouse pointer over it)
    }

    @Test fun storeCodesReadWrongComeFromTheStoreList() {
        val codes = (1..4).flatMap { photo(it).stops }.map { it.code }.toSet()
        // read as "HI5203", "31Kakinada", "5988", "521oNuzividu"
        for (c in listOf("5203", "5331", "5338", "5210")) assertTrue(c, c in codes)
        assertTrue("501280" in codes)   // not in the store list: kept as read
    }

    @Test fun circleRemarkAndVehicles() {
        val p1 = photo(1).stops.associateBy { it.code }
        assertEquals("BLR", p1.getValue("5841").circle)
        assertEquals(1, p1.getValue("5841").sheetVehicles)
        assertEquals("Odisha", p1.getValue("7531").circle)
        assertEquals("Only Cons.", p1.getValue("7531").remark)
        assertEquals(0, p1.getValue("7531").sheetVehicles)
        assertEquals("Telangana 1", photo(2).stops.first().circle)
    }

    @Test fun excelSheetIsRead() {
        val rows = listOf(
            listOf("", "", "VEHICLE REQ 16.10.2026", "", "", "", "", ""),
            listOf("", "", "4636.214", "22240.782", "2155", "7", "", ""),
            listOf("Ship-To", "Name of the ship-to party", "Volume", "Total Wght", "Boxes", "Vehicle", "Circle", "Remark"),
            listOf("5029", "Laxmipuram Warangal DMart", "1129.891", "5003.917", "479", "2", "Telangana 2", ""),
            listOf("5063.0", "Kodad", "23.58", "440", "22", "0", "Telangana 2", "Only Cons.")
        )
        val r = SheetReader.fromRows(listOf(rows))
        assertEquals(listOf("5029", "5063"), r.stops.map { it.code })
        assertEquals(4636.214, r.sheetTotal!!, 1e-6)
        assertEquals(2, r.stops[0].sheetVehicles)
        assertEquals("Only Cons.", r.stops[1].remark)
    }
}
