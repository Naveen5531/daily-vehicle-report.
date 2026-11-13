package com.logistics.vehiclereport

import android.content.Context

/**
 * Today's Departed marks from the In-transit table the office Excel sends: a vehicle whose last visited site is this
 * DC and whose check-out there is today has left the DC, even when the gate log row for it never came (for example
 * it checked in on an earlier day). Same rule as the gate log for which vehicles get a mark: dedicated, posted today,
 * already marked, or the replacement of a dedicated vehicle.
 */
object TransitMarks {
    const val SHEET = "Transit"

    /** Returns how many marks changed. */
    fun apply(c: Context): Int = try { run(c) } catch (e: Exception) { 0 }

    private fun run(c: Context): Int {
        val t = YardStore.table(c, SHEET)
        if (t.rows.isEmpty()) return 0
        val vCol = t.col("vehicle number", "vehicle no", "vehicle")
        val oCol = t.col("checkedout", "checked out", "check out")
        if (vCol < 0 || oCol < 0) return 0
        val sCol = t.col("last visited", "last site")
        val site = DrishtiStore.site(c).filter { it.isLetterOrDigit() }

        val today = ReportBuilder.startOfDay()
        val end = today + ReportBuilder.DAY_MS

        // Last check-out from this DC today, per vehicle.
        val left = HashMap<String, Long>()
        for (r in t.rows) {
            val plate = DrishtiData.plate(r.getOrNull(vCol).orEmpty().substringBefore("(")) ?: continue
            if (sCol >= 0 && site.isNotEmpty()) {
                val last = r.getOrNull(sCol).orEmpty().trim().takeWhile { it.isLetterOrDigit() }
                if (last.isNotEmpty() && !last.equals(site, ignoreCase = true)) continue
            }
            val out = DrishtiData.parseTime(r.getOrNull(oCol).orEmpty().replace(",", " "), today)
            if (out < today || out >= end) continue
            left[plate] = maxOf(left[plate] ?: 0L, out)
        }
        if (left.isEmpty()) return 0

        val dedicated = VehicleStore.getVehicles(c).filter { it.dedicated }.map { it.number }.toSet()
        val posts = VehicleStore.getAll(c).filter { it.time >= today && it.time < end }
        val marks = GateMarks.forDay(c, today)

        val entries = ArrayList<GateEntry>()
        fun add(vehicle: String, out: Long) {
            val old = marks[vehicle]
            if (old?.gate == Gate.DEPARTED) return            // the gate log (or a hand mark) already says so
            if (entries.any { it.vehicle == vehicle }) return
            entries += GateEntry(today, vehicle, old?.reportedAt ?: 0L, out)
        }
        for ((plate, out) in left) {
            val posted = posts.any { plate in it.candidates || it.vehicle == plate }
            if (plate in dedicated || posted || plate in marks.keys) add(plate, out)
            // Went out as the replacement of a dedicated vehicle.
            for (p in posts) {
                if (p.replacement.isBlank() || p.candidates.size != 1) continue
                if (DrishtiData.plate(p.replacement) == plate && p.candidates[0] != plate) add(p.candidates[0], out)
            }
        }
        return GateMarks.applyGate(c, entries)
    }
}
