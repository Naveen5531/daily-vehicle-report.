package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class WhatsAppChatParserTest {

    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }

    @Test fun androidFormat12h() {
        val txt = "14/10/2026, 6:31 am - Messages and calls are end-to-end encrypted.\n" +
            "14/10/2026, 6:31 am - Ravi: 1234 Hyderabad\n" +
            "14/10/2026, 6:45 pm - +91 98765 43210: TS09UB1234 Vijayawada\n"
        val m = WhatsAppChatParser.parse(txt, "Transport")
        assertEquals(2, m.size)
        assertEquals("Ravi", m[0].sender)
        assertEquals("1234 Hyderabad", m[0].text)
        val c = cal(m[1].time)
        assertEquals(18, c.get(Calendar.HOUR_OF_DAY)); assertEquals(45, c.get(Calendar.MINUTE))
        assertEquals(14, c.get(Calendar.DAY_OF_MONTH)); assertEquals(Calendar.OCTOBER, c.get(Calendar.MONTH))
        assertEquals("+91 98765 43210", m[1].sender)
    }

    @Test fun twentyFourHourTwoDigitYear() {
        val m = WhatsAppChatParser.parse("03/10/26, 18:05 - Suresh: 5678 Guntur", "G").single()
        val c = cal(m.time)
        assertEquals(2026, c.get(Calendar.YEAR)); assertEquals(3, c.get(Calendar.DAY_OF_MONTH)); assertEquals(18, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun iphoneFormat() {
        val m = WhatsAppChatParser.parse("[14/10/26, 6:31:05 AM] Ravi: 1234 Hyd", "G").single()
        assertEquals("1234 Hyd", m.text)
        assertEquals(6, cal(m.time).get(Calendar.HOUR_OF_DAY))
    }

    @Test fun multiLineMessage() {
        val txt = "14/10/2026, 7:00 am - Ravi: Today placement\n1234 Hyderabad\n5678 Guntur\n14/10/2026, 7:05 am - Mahesh: ok"
        val m = WhatsAppChatParser.parse(txt, "G")
        assertEquals(2, m.size)
        assertEquals("Today placement\n1234 Hyderabad\n5678 Guntur", m[0].text)
    }

    @Test fun monthFirstDetected() {
        val txt = "10/14/26, 6:31 AM - Ravi: 1234 Hyd\n10/03/26, 6:31 AM - Ravi: 5678 Pune"
        val m = WhatsAppChatParser.parse(txt, "G")
        assertEquals(14, cal(m[0].time).get(Calendar.DAY_OF_MONTH))
        assertEquals(3, cal(m[1].time).get(Calendar.DAY_OF_MONTH))
    }

    @Test fun skipsMedia() {
        assertEquals(0, WhatsAppChatParser.parse("14/10/2026, 6:31 am - Ravi: <Media omitted>", "G").size)
    }

    @Test fun chatName() {
        assertEquals("Transport Dedicated", WhatsAppChatParser.chatNameFrom("WhatsApp Chat with Transport Dedicated.txt"))
        assertEquals("Transport Dedicated", WhatsAppChatParser.chatNameFrom("WhatsApp Chat - Transport Dedicated.zip"))
    }
}
