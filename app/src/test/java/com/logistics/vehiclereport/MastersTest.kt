package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class MastersTest {

    private val m = MasterData.fromWorkbook(File("src/test/resources/store_vendor_master.xlsx").readBytes())

    private fun code(location: String) = m.matchStore(location)?.store?.code

    @Test fun readsBuiltInWorkbook() {
        assertEquals(98, m.stores.size)
        assertEquals(17, m.vendors.size)
        assertEquals("Andhra Pradesh 3", m.stores.first { it.code == "5304" }.region)
    }

    @Test fun locationsFromTheGroup() {
        assertEquals("5102", code("Kurnool"))
        assertEquals("5304", code("Suryabagh"))
        assertEquals("5332", code("Eluru"))
        assertEquals("5334", code("Rajmundhry"))
        assertEquals("5841", code("Isnapur to Raichur"))
        assertEquals("5301", code("Madhurvada"))
        assertEquals("5205", code("Machalipatnam"))
        assertEquals("5210", code("Nuziveedu"))
        assertNull(code("Isnapur"))
        assertNull(code("1041 Isnapur DC"))
        assertEquals("5841", code("Raichur from Isnapur DC"))
    }

    @Test fun vendorsFromTheGroup() {
        assertEquals("VEERABHADRA CORGO MOVERS", m.matchVendor("Veerbhadra Cargo")?.name)
        assertEquals("MISHEETHA LOGISTICS", m.matchVendor("Misheetha Logistics")?.name)
        assertEquals("VR LOGOSTIC SERVICES", m.matchVendor("Vr Logistics")?.name)
        assertEquals("TEAMPLAY MANAGEMENT SOLUTIONS PRIVA", m.matchVendor("Teamplay")?.name)
        assertEquals("SRI SAI TRANSPORT", m.matchVendor("Sri Sai Transport")?.name)
        assertNull(m.matchVendor("Good morning"))
        // From the WhatsApp group's name
        assertEquals("KOHINOOR CARGO MOVERS", m.resolveVendor("", "4924 Psr", "\uD83D\uDE98 1041 KOHINOOR TPT", "")?.name)
        assertEquals("HAPPY ROAD LINES", m.resolveVendor("", "5717-Ramanthapur", "1041 Happy Local", "")?.name)
        assertEquals("MOHAMMED AMJAD", m.resolveVendor("", "9978 -Ael", "Amjad transport 1041 dc isnapur", "")?.name)
        assertEquals("SSR TRANSPORT AND TRAVELS", m.resolveVendor("", "Ap29TB 4702 kompally", "1041 DC SSR Transport", "")?.name)
        // Named in the message, or from the vehicle list
        assertEquals("HIMAGIRI GOODS CARRIERS", m.resolveVendor("", "TS07UE3029\nNUZIVEEDU\nHimagiri", "1041 DC Transport Team", "")?.name)
        assertEquals("SAI KIRAN TRANSPORT", m.resolveVendor("", "Gandham guda", "1041 DC Transport Team", "SAI KIRAN")?.name)
        assertEquals("Location + Vendor", Masters.matchedLabel(true, true))
        assertEquals("Vendor only", Masters.matchedLabel(false, true))
    }
}
