package com.logistics.vehiclereport

import android.content.Context

/**
 * Store check-in / check-out times for the driver details report, from the tables the office Excel sends:
 * a vehicle standing at a store site (Standing, Site Id other than this DC) gives Store In; the In-transit /
 * "Coming to" rows give Store Out when the vehicle's last visited site is a store. Blank when Excel has not sent it.
 */
object StoreTimes {
    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    private fun hit(c: Context, sheet: String, vehicle: String, date: String, siteNeeded: Boolean, timeCols: Array<String>): String {
        val t = YardStore.table(c, sheet)
        if (t.rows.isEmpty()) return ""
        val v = t.col("vehicle number", "vehicle no", "vehicle"); if (v < 0) return ""
        val tc = timeCols.map { t.col(it) }.firstOrNull { it >= 0 } ?: return ""
        val sc = t.col("site id", "site")
        val dc = DrishtiStore.site(c).filter { it.isLetterOrDigit() }
        val want = norm(vehicle)
        for (r in t.rows) {
            if (norm(r.getOrNull(v).orEmpty().substringBefore("(")) != want) continue
            if (siteNeeded && sc >= 0) {
                val site = r.getOrNull(sc).orEmpty().filter { it.isDigit() }
                if (site.isEmpty() || site == dc) continue
            }
            val raw = r.getOrNull(tc).orEmpty().trim()
            if (raw.isEmpty() || raw == "-") continue
            val ms = DrishtiData.parseTime(raw.replace(",", " "), 0L)
            if (ms <= 0) continue
            val d = ReportBuilder.dateFmt().format(java.util.Date(ms))
            if (date.isNotBlank() && d != date) continue
            return ReportBuilder.timeFmt().format(java.util.Date(ms))
        }
        return ""
    }

    fun inTime(c: Context, vehicle: String, date: String) = try {
        hit(c, "Standing", vehicle, date, true, arrayOf("checked in", "checkedin"))
    } catch (e: Exception) { "" }

    fun outTime(c: Context, vehicle: String, date: String) = try {
        hit(c, "Coming to 1041", vehicle, date, false, arrayOf("checked out", "checkedout"))
    } catch (e: Exception) { "" }
}
