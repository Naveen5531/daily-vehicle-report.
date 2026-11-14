package com.logistics.vehiclereport

import android.content.Context
import java.util.Date

/**
 * One trip of a vehicle, anchored on its DC dispatch (check-out from this DC). All four times belong to that same
 * trip whatever day each one falls on: DC In = the gate check-in of the visit that ended in the dispatch, DC Out =
 * the dispatch, Store In = first store check-in after the dispatch, Store Out = store check-out after the store in.
 * 0 = not known (yet).
 */
data class TripTimes(val dcIn: Long, val dcOut: Long, val storeIn: Long, val storeOut: Long)

object StoreTimes {
    private const val DAYS = 14
    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** Every DC visit (check-in, check-out) of every vehicle in the saved gate log, oldest first. */
    private fun visits(c: Context): Map<String, List<DrishtiRow>> {
        val today = ReportBuilder.startOfDay()
        val all = ArrayList<DrishtiRow>()
        for (k in 0..DAYS) all += DrishtiStore.log(c, ReportBuilder.startOfDay(today - k * ReportBuilder.DAY_MS + ReportBuilder.DAY_MS / 2))
        return all.distinctBy { it.vehicle + "|" + it.checkIn }.sortedBy { it.checkIn }.groupBy { it.vehicle }
    }

    /** Times (millis) of [col] for [vehicle] in a table Excel sent, other than rows at this DC's own site. */
    private fun times(c: Context, sheet: String, vehicle: String, timeCols: Array<String>, ownSite: Boolean): List<Long> {
        val t = YardStore.table(c, sheet)
        if (t.rows.isEmpty()) return emptyList()
        val v = t.col("vehicle number", "vehicle no", "vehicle"); if (v < 0) return emptyList()
        val tc = timeCols.map { t.col(it) }.firstOrNull { it >= 0 } ?: return emptyList()
        val sc = t.col("site id", "last visited")
        val dc = DrishtiStore.site(c).filter { it.isLetterOrDigit() }
        val want = norm(vehicle)
        val out = ArrayList<Long>()
        for (r in t.rows) {
            if (norm(r.getOrNull(v).orEmpty().substringBefore("(")) != want) continue
            if (!ownSite && sc >= 0) {
                val site = r.getOrNull(sc).orEmpty().trim().takeWhile { it.isDigit() }
                if (site == dc) continue
            }
            val ms = DrishtiData.parseTime(r.getOrNull(tc).orEmpty().replace(",", " "), 0L)
            if (ms > 0) out += ms
        }
        return out.sorted()
    }

    /**
     * The trip of [vehicle] dispatched on [dayStart]; when it has no dispatch that day, the visit that checked in that
     * day (still inside, no dispatch yet). null = the vehicle is not in the gate log for that day.
     */
    fun trip(c: Context, vehicle: String, dayStart: Long): TripTimes? = try { run(c, vehicle, dayStart) } catch (e: Exception) { null }

    private fun run(c: Context, vehicle: String, dayStart: Long): TripTimes? {
        val end = dayStart + ReportBuilder.DAY_MS
        val list = visits(c)[vehicle].orEmpty()
        val v = list.lastOrNull { it.checkOut in dayStart until end }
            ?: list.lastOrNull { it.checkIn in dayStart until end && it.checkOut == 0L }
            ?: return null
        if (v.checkOut <= 0) return TripTimes(v.checkIn, 0, 0, 0)
        val next = list.firstOrNull { it.checkIn > v.checkOut }?.checkIn ?: Long.MAX_VALUE   // back at the DC = trip over
        // Store in: first check-in at a store after the dispatch (before the vehicle is back at the DC).
        val sIn = times(c, "Standing", vehicle, arrayOf("checked in", "checkedin"), false).firstOrNull { it > v.checkOut && it < next } ?: 0L
        // Store out: the store check-out after the store in (Empty / Transit / Coming = vehicle left a store).
        val outs = listOf("Empty", "Transit", "Coming to 1041").flatMap {
            times(c, it, vehicle, arrayOf("checkedout", "checked out"), false)
        }.sorted()
        val sOut = if (sIn > 0) outs.firstOrNull { it > sIn && it < next } ?: 0L else 0L
        return TripTimes(v.checkIn, v.checkOut, sIn, sOut)
    }

    fun text(ms: Long) = if (ms > 0) ReportBuilder.timeFmt().format(Date(ms)) else ""
    fun dateText(ms: Long) = if (ms > 0) ReportBuilder.dateFmt().format(Date(ms)) else ""
}
