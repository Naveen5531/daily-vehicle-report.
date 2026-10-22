package com.logistics.vehiclereport

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One row of DMart Drishti's Vehicle Summary (gate log): a vehicle that checked in at the DC, and when it left.
 * Times are phone time in millis (0 = not given).
 */
data class DrishtiRow(
    val site: String,
    val vehicle: String,
    val type: String,
    val driver: String,
    val mobile: String,
    val checkIn: Long,
    val checkOut: Long,
    val destination: String = ""
) {
    /** The day the vehicle checked in (start of day), or 0. */
    val day: Long get() = if (checkIn > 0) ReportBuilder.startOfDay(checkIn) else 0L
}

/** A gate mark to set from Drishti: [vehicle] reported at [reportedAt] and (if > 0) departed at [departedAt]. */
data class GateEntry(val day: Long, val vehicle: String, val reportedAt: Long, val departedAt: Long) {
    val gate: Gate get() = if (departedAt > 0) Gate.DEPARTED else Gate.REPORTED
}

/** Turns what was read from the Drishti page into rows, and decides which Today marks they set. No Android here. */
object DrishtiData {

    /** Order of the cells the page reader sends for each row. */
    const val SITE = 0; const val VEHICLE = 1; const val TYPE = 2; const val DRIVER = 3
    const val MOBILE = 4; const val IN = 5; const val OUT = 6; const val DEST = 7

    private val withDate = listOf(
        "dd-MM-yyyy hh:mm:ss a", "dd-MM-yyyy hh:mm a", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm",
        "dd/MM/yyyy hh:mm:ss a", "dd/MM/yyyy hh:mm a", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm",
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm",
        "dd-MMM-yyyy hh:mm:ss a", "dd-MMM-yyyy hh:mm a", "dd MMM yyyy hh:mm a", "dd-MMM-yyyy HH:mm"
    )
    private val timeOnly = listOf("hh:mm:ss a", "hh:mm a", "HH:mm:ss", "HH:mm")

    /**
     * "17-10-2026 10:14:16 AM" (also with a line break, "/" dates, 24-hour or month names) -> millis.
     * A time without a date is taken on [pageDay]. Blank, "-" or unreadable -> 0.
     */
    fun parseTime(raw: String, pageDay: Long): Long {
        val s = raw.replace(Regex("\\s+"), " ").trim()
            .replace(Regex("(?i)\\b(am|pm)\\b")) { it.value.uppercase() }
        if (s.isEmpty() || s.none { it.isDigit() }) return 0L
        for (f in withDate) whole(s, f)?.let { return it }
        for (f in timeOnly) whole(s, f)?.let { t ->
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = t }
            val mins = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            return pageDay + mins * 60_000L + cal.get(java.util.Calendar.SECOND) * 1000L
        }
        // Epoch millis or seconds.
        s.toLongOrNull()?.let { n -> return if (n > 100_000_000_000L) n else if (n > 1_000_000_000L) n * 1000 else 0L }
        return 0L
    }

    private fun whole(s: String, pattern: String): Long? {
        val f = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val pos = ParsePosition(0)
        val d = f.parse(s, pos) ?: return null
        return if (pos.index == s.length) d.time else null
    }

    /**
     * Rows from the cells the page reader sent. Rows without a vehicle number are skipped; the same vehicle with the
     * same check-in appears once (the page is read while scrolling and paging, so a row can be seen twice).
     * Only rows of [site] are kept when the row says its site ([site] blank = every site).
     */
    fun rows(cells: List<List<String>>, pageDay: Long, site: String): List<DrishtiRow> {
        val wantSite = site.filter { it.isLetterOrDigit() }
        val out = LinkedHashMap<String, DrishtiRow>()
        for (c in cells) {
            fun at(i: Int) = c.getOrNull(i).orEmpty().replace(Regex("\\s+"), " ").trim()
            val number = plate(at(VEHICLE)) ?: continue
            val rowSite = at(SITE).filter { it.isLetterOrDigit() }
            if (wantSite.isNotEmpty() && rowSite.isNotEmpty() && !rowSite.equals(wantSite, ignoreCase = true)) continue
            val row = DrishtiRow(
                site = at(SITE), vehicle = number, type = at(TYPE).takeUnless { it == "-" }.orEmpty(),
                driver = at(DRIVER).takeUnless { it == "-" }.orEmpty(),
                mobile = at(MOBILE).filter { it.isDigit() }.takeLast(10),
                checkIn = parseTime(at(IN), pageDay), checkOut = parseTime(at(OUT), pageDay),
                destination = at(DEST).takeUnless { it == "-" }.orEmpty()
            )
            val key = row.vehicle + "|" + row.checkIn
            out[key] = out[key]?.let { old -> if (old.checkOut == 0L && row.checkOut > 0) row else old } ?: row
        }
        return out.values.toList()
    }

    /** "TN12BH6176", "ts 08 uk 0437" -> canonical plate; anything that is not a plate -> null. */
    fun plate(text: String): String? {
        if (text.isBlank()) return null
        VehicleParser.extract(text).firstOrNull()?.let { return it }
        val compact = text.uppercase().filter { it.isLetterOrDigit() }
        // A plate the reader does not know (temporary, other formats): letters and at least 4 digits, sensible length.
        return compact.takeIf { it.length in 7..12 && it.count { ch -> ch.isDigit() } >= 4 && it.any { ch -> ch.isLetter() } }
    }

    /**
     * Which Today marks the gate log sets. A row counts for:
     *  - the vehicle itself, when it is a dedicated vehicle, was posted in WhatsApp that day, or is already marked;
     *  - the dedicated vehicle it replaced, when a post that day named it as the replacement.
     * Vehicles of the gate log that nobody posted and that are not dedicated get no mark (they stay in the log only),
     * so Today is not filled with every truck that came to the DC.
     * [records] must already be matched against the vehicle list as it is after saving the gate log's vehicles.
     */
    fun marks(
        rows: List<DrishtiRow>, vehicles: List<DedicatedVehicle>, records: List<VehicleRecord>,
        marked: (day: Long) -> Set<String>
    ): List<GateEntry> {
        val dedicated = vehicles.filter { it.dedicated }.map { it.number }.toSet()
        val out = LinkedHashMap<String, GateEntry>()
        fun add(day: Long, vehicle: String, r: DrishtiRow) {
            val key = "$day|$vehicle"
            val e = GateEntry(day, vehicle, r.checkIn, r.checkOut)
            val old = out[key]
            // Several gate rows for one vehicle on one day: first arrival, last departure.
            out[key] = if (old == null) e else GateEntry(day, vehicle,
                minOf(old.reportedAt, e.reportedAt).takeIf { it > 0 } ?: maxOf(old.reportedAt, e.reportedAt),
                maxOf(old.departedAt, e.departedAt))
        }
        val byDay = records.groupBy { ReportBuilder.startOfDay(it.time) }
        for (r in rows) {
            if (r.checkIn <= 0) continue
            val day = r.day
            val posts = byDay[day].orEmpty()
            val posted = posts.any { r.vehicle in it.candidates || it.vehicle == r.vehicle }
            if (r.vehicle in dedicated || posted || r.vehicle in marked(day)) add(day, r.vehicle, r)
            // Came as the replacement of a dedicated vehicle.
            for (p in posts) {
                if (p.replacement.isBlank() || p.candidates.size != 1) continue
                if (plate(p.replacement) == r.vehicle && p.candidates[0] != r.vehicle) add(day, p.candidates[0], r)
            }
        }
        return out.values.toList()
    }
}
