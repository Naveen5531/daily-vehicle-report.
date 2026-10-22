package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class VehicleTemplateTest {

    private fun roundTrip(rows: List<List<String>>): List<DedicatedVehicle> {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(out, "Vehicles", rows, VehicleTemplate.widths)
        return VehicleListImporter.read(out.toByteArray())
    }

    @Test fun untouchedTemplateImportsNothing() {
        assertEquals(emptyList<DedicatedVehicle>(), roundTrip(VehicleTemplate.rows))
    }

    @Test fun filledTemplateImportsDetailsAndSkipsExample() {
        val filled = VehicleTemplate.rows.dropLast(1) + listOf(
            listOf("1", "AP39TV5678", "Sai Logistics", "20 FT")
        )
        val v = roundTrip(filled).single()
        assertEquals("AP39TV5678", v.number)
        assertEquals("Sai Logistics", v.transporter)
        assertEquals("20 FT", v.type)
    }
}
