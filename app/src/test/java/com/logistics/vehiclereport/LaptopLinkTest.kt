package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/** The Chrome add-on's encryption (browser WebCrypto: AES-GCM over gzip) opens in the app. */
class LaptopLinkTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    // Made with the same code as the add-on's background.js (iv + AES-GCM of gzip(JSON)).
    private val box = Base64.getDecoder().decode(
        "ttSIFE86vH8NpowC+G3v5E/0t+IY0cMx7IQUQyN1OVRm9dlh77c/i6I3TYzXjtQxXvanuwyId3O8j1VjorpENxI9WL1vvjMP3j79wZ7XUW8NWUBmvuugYrTPY2U+Qigr+5SA1LFQnBg0F0Uf6TYABkYXp7gASDr3+33LB1UNTO3aKARranEPD6j61Xjt4E2vF914DD+e967MQLHXW5MYFDdQn+v0RUScw5tCJ6H/2ngANw=="
    )

    @Test fun opensWhatTheAddOnSent() {
        val r = LaptopLink.open(box, key)!!
        assertEquals(DrishtiStore.OK, r.status)
        assertEquals("TS09UB4821", r.cells.single()[DrishtiData.VEHICLE])
        assertEquals(1792222222000L, r.at)
        val rows = DrishtiData.rows(r.cells, r.pageDay, "1041")
        assertEquals("Kukatpally", rows.single().destination)
    }

    @Test(expected = Exception::class) fun wrongKeyFails() {
        LaptopLink.open(box, ByteArray(32))
    }

    @Test fun tooShortIsNothing() { assertNull(LaptopLink.open(ByteArray(10), key)) }
}
