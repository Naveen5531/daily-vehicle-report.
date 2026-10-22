package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Number plates as the camera text reader returns them. */
class PlateReaderTest {

    @Test fun oneLinePlate() {
        assertEquals("TS09UB4821", PlateReader.read("TS 09 UB 4821", "4821", emptyList()))
        assertEquals("AP28TD6142", PlateReader.read("IND\nAP28 TD 6142", "6142", emptyList()))
    }

    @Test fun twoLineTruckPlate() {
        assertEquals("TS12UE2290", PlateReader.read("TS 12 UE\n2290", "2290", emptyList()))
    }

    @Test fun misreadsAreFixed() {
        // O for 0, I for 1, 8 for B, S for 5
        assertEquals("TS09UB4821", PlateReader.read("TS O9 U8 482I", "4821", emptyList()))
        assertEquals("TS10UC8853", PlateReader.read("T5 10 UC 88S3", "8853", emptyList()))
    }

    @Test fun shortSeriesAndDelhiStyle() {
        assertEquals("TG07W5521", PlateReader.read("TG 07 W 5521", "5521", emptyList()))
        assertEquals("DL03C1234", PlateReader.read("DL 3C 1234", "1234", emptyList()))
        assertEquals("MH12ABC1234", PlateReader.read("MH-12-ABC-1234", null, emptyList()))
    }

    @Test fun bharatSeries() {
        assertEquals("22BH4821AA", PlateReader.read("22 BH 4821 AA", "4821", emptyList()))
    }

    @Test fun listVehiclePreferred() {
        // One letter misread (B read as R): still the list vehicle.
        assertEquals("TS09UB4821", PlateReader.read("TS 09 UR 4821", "4821", listOf("TS09UB4821", "AP28TC4821")))
        // The other vehicle with the same last 4
        assertEquals("AP28TC4821", PlateReader.read("AP 28 TC 4821", "4821", listOf("TS09UB4821", "AP28TC4821")))
    }

    @Test fun otherTextIgnored() {
        assertEquals("TS09UB4821", PlateReader.read("SRI SAI TRANSPORT\nCALL 98480\nTS 09 UB\n4821\nGOODS CARRIER", "4821", emptyList()))
    }

    @Test fun wrongLastFourNotTaken() {
        assertNull(PlateReader.read("TS 09 UB 4822", "4821", listOf("TS09UB4821")).takeIf { it?.endsWith("4821") == true })
    }

    @Test fun prettyText() {
        assertEquals("TS 09 UB 4821", PlateReader.pretty("TS09UB4821"))
    }
}
