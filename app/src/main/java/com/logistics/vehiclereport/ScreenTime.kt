package com.logistics.vehiclereport

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Turns WhatsApp's on-screen date dividers ("TODAY", "14 October 2026") and bubble times ("6:31 am") into a timestamp. */
object ScreenTime {

    private val TIME = Regex("^(\\d{1,2})[:.](\\d{2})\\s*([aApP])?\\.?\\s?[mM]?\\.?$")
    private val WEEKDAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private val PATTERNS = listOf("d MMMM yyyy", "MMMM d, yyyy", "d MMM yyyy", "MMM d, yyyy", "dd/MM/yyyy", "d/M/yy", "dd-MM-yyyy")

    fun clean(s: String) = s.replace(" ", " ").replace(" ", " ").replace("‎", "").replace("‏", "").trim()

    fun isTime(s: String) = TIME.matches(clean(s))

    /** Start of the day named by a divider, or null if [label] isn't a date. */
    fun dayStart(label: String, now: Long = System.currentTimeMillis()): Long? {
        val l = clean(label).lowercase(Locale.ENGLISH)
        val today = ReportBuilder.startOfDay(now)
        when (l) {
            "today" -> return today
            "yesterday" -> return ReportBuilder.startOfDay(today - ReportBuilder.DAY_MS / 2)
        }
        val wd = WEEKDAYS.indexOf(l)
        if (wd >= 0) {
            val cal = Calendar.getInstance().apply { timeInMillis = today }
            for (back in 1..7) {
                cal.add(Calendar.DAY_OF_MONTH, -1)
                if (cal.get(Calendar.DAY_OF_WEEK) - 1 == wd) return cal.timeInMillis
            }
        }
        for (p in PATTERNS) {
            try {
                val f = SimpleDateFormat(p, Locale.ENGLISH).apply { isLenient = false }
                val d = f.parse(clean(label)) ?: continue
                return ReportBuilder.startOfDay(d.time)
            } catch (e: Exception) { }
        }
        return null
    }

    /** Timestamp for a bubble time on a given day. Without a day: today, or yesterday if that would be in the future. */
    fun at(dayStart: Long?, timeText: String, now: Long = System.currentTimeMillis()): Long? {
        val m = TIME.matchEntire(clean(timeText)) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        when (m.groupValues[3].lowercase()) {
            "p" -> if (h < 12) h += 12
            "a" -> if (h == 12) h = 0
        }
        if (h !in 0..23 || min !in 0..59) return null
        val base = dayStart ?: ReportBuilder.startOfDay(now)
        var t = Calendar.getInstance().apply {
            timeInMillis = base
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min)
        }.timeInMillis
        if (dayStart == null && t > now + 5 * 60_000) t -= ReportBuilder.DAY_MS
        return t
    }
}
