package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ScreenTimeTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 14, 10, 0, 0) }.timeInMillis
    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }

    @Test fun todayAndTime() {
        val t = ScreenTime.at(ScreenTime.dayStart("TODAY", now), "6:31 am", now)!!
        assertEquals(14, cal(t).get(Calendar.DAY_OF_MONTH)); assertEquals(6, cal(t).get(Calendar.HOUR_OF_DAY)); assertEquals(31, cal(t).get(Calendar.MINUTE))
    }

    @Test fun pmAnd24h() {
        assertEquals(18, cal(ScreenTime.at(null, "6:45 pm", now + 12 * 3600_000L)!!).get(Calendar.HOUR_OF_DAY))
        assertEquals(18, cal(ScreenTime.at(ScreenTime.dayStart("Yesterday", now), "18:05", now)!!).get(Calendar.HOUR_OF_DAY))
    }

    @Test fun yesterday() {
        assertEquals(13, cal(ScreenTime.dayStart("YESTERDAY", now)!!).get(Calendar.DAY_OF_MONTH))
    }

    @Test fun fullDate() {
        assertEquals(3, cal(ScreenTime.dayStart("3 October 2026", now)!!).get(Calendar.DAY_OF_MONTH))
        assertEquals(3, cal(ScreenTime.dayStart("October 3, 2026", now)!!).get(Calendar.DAY_OF_MONTH))
    }

    @Test fun noDividerFutureTimeMeansYesterday() {
        assertEquals(13, cal(ScreenTime.at(null, "11:30 pm", now)!!).get(Calendar.DAY_OF_MONTH))
    }

    @Test fun notADate() {
        assertNull(ScreenTime.dayStart("1234 Hyderabad", now))
        assertTrue(ScreenTime.isTime("6:31 am"))
    }
}
