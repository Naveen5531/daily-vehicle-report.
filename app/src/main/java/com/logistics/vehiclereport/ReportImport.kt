package com.logistics.vehiclereport

import java.util.Calendar

/**
 * "Mark reported" from any list: an Excel / CSV file (a gate register, a Drishti export someone sends) or a photo
 * (of a register, a screen). Every vehicle number found is matched to the vehicle list; a match is marked Reported
 * at its in-time (or now when no time is given), and Departed when an out-time is given.
 * The rows come out in the same cell order as the Drishti reader ([DrishtiData]), so they go through the same
 * gate log, Today marks, mapping and daily report. No Android here.
 */
object ReportImport {

    /** What was found: [cells] for [DrishtiData.rows], and the numbers that matched nothing in the list. */
    data class Found(val cells: List<List<String>>, val unmatched: List<String>)

    private val VEHICLE_HEAD = Regex("(?i)vehicle|veh\\.?\\s*no|truck|lorry|reg(istration)?\\s*no|^v\\.?\\s*no")
    private val TYPE_HEAD = Regex("(?i)type|size|capacity")
    private val DRIVER_HEAD = Regex("(?i)driver(?!.*(mobile|phone|no|number|contact))")
    private val MOBILE_HEAD = Regex("(?i)mobile|phone|contact|cell")
    private val IN_HEAD = Regex("(?i)check(ed)?[\\s-]*in|in[\\s-]*time|entry|arriv|report(ed)?|gate[\\s-]*in|^in$")
    private val OUT_HEAD = Regex("(?i)check(ed)?[\\s-]*out|out[\\s-]*time|exit|depart|dispatch(ed)?\\s*time|gate[\\s-]*out|^out$")
    private val DEST_HEAD = Regex("(?i)destination|location|store|city|route|to\\b")

    /** A date (optional) and a time in one piece of text: "17-10-2026 10:14:16 AM", "10:14", "9.30 pm". */
    private val TIME = Regex(
        "(?i)(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\s+)?\\d{1,2}[:.]\\d{2}([:.]\\d{2})?\\s*(am|pm)?"
    )

    /**
     * The vehicle a written number stands for: the full number when it is a plate that is in the list (or posted
     * today), or the only list vehicle ending with 4 digits written alone (when [allowLast4]).
     */
    fun resolve(text: String, known: Set<String>, allowLast4: Boolean): String? {
        DrishtiData.plate(text)?.let { p -> return p.takeIf { it in known } }
        if (!allowLast4) return null
        val digits = text.filter { it.isDigit() }
        if (digits.length != 4 || text.count { it.isLetter() } > 0) return null
        return known.filter { VehicleParser.last4(it) == digits }.singleOrNull()
    }

    /**
     * Excel serial dates ("45950.43" = a day and time) and fractions ("0.4375" = 10:30 AM) -> millis; other text is
     * left for [DrishtiData.parseTime]. Returns the text to put in the cell.
     */
    fun timeCell(raw: String, day: Long): String {
        val s = raw.trim()
        val n = s.toDoubleOrNull() ?: return s
        return when {
            n in 0.0..0.99999 -> (day + Math.round(n * 86_400_000.0)).toString()
            n in 20_000.0..80_000.0 -> {
                val cal = Calendar.getInstance()
                val days = Math.floor(n).toInt()
                cal.clear(); cal.set(1899, Calendar.DECEMBER, 30); cal.add(Calendar.DAY_OF_YEAR, days)
                (cal.timeInMillis + Math.round((n - days) * 86_400_000.0)).toString()
            }
            else -> s
        }
    }

    /** Rows of a sheet (Excel or CSV). With a header row the columns are taken by name; without one, any cell. */
    fun fromSheets(sheets: List<List<List<String>>>, known: Set<String>, day: Long, now: Long): Found {
        val cells = mutableListOf<List<String>>()
        val unmatched = LinkedHashSet<String>()
        for (rows in sheets) {
            val headAt = rows.take(15).indexOfFirst { r -> r.any { VEHICLE_HEAD.containsMatchIn(it) && it.length < 40 } }
            if (headAt >= 0) {
                val h = rows[headAt].map { it.trim() }
                fun col(re: Regex, not: Int = -1) = h.indices.firstOrNull { it != not && re.containsMatchIn(h[it]) } ?: -1
                // The vehicle number column ("Vehicle No", not "Vehicle Type").
                val v = h.indices.firstOrNull { VEHICLE_HEAD.containsMatchIn(h[it]) && !TYPE_HEAD.containsMatchIn(h[it]) } ?: continue
                val type = col(TYPE_HEAD); val driver = col(DRIVER_HEAD); val mobile = col(MOBILE_HEAD)
                val outC = col(OUT_HEAD); val inC = col(IN_HEAD, outC); val dest = col(DEST_HEAD, v)
                for (r in rows.drop(headAt + 1)) {
                    val written = r.getOrNull(v).orEmpty().trim()
                    if (written.isEmpty()) continue
                    val number = resolve(written, known, allowLast4 = true)
                    if (number == null) { DrishtiData.plate(written)?.let { unmatched += it }; continue }
                    fun at(i: Int) = if (i < 0) "" else r.getOrNull(i).orEmpty().trim()
                    val inT = timeCell(at(inC), day).ifBlank { now.toString() }
                    cells += listOf("", number, at(type), at(driver), at(mobile), inT, timeCell(at(outC), day), at(dest))
                }
            } else {
                // No header: every cell is looked at; times in the same row are in (first) and out (second).
                for (r in rows) {
                    val numbers = r.mapNotNull { DrishtiData.plate(it) }.distinct()
                    if (numbers.isEmpty()) continue
                    val times = r.filter { DrishtiData.plate(it) == null }.map { timeCell(it, day) }
                        .filter { t -> DrishtiData.parseTime(t, day) > 0 }
                    for (p in numbers) {
                        if (p !in known) { unmatched += p; continue }
                        cells += listOf("", p, "", "", "", times.getOrNull(0) ?: now.toString(), times.getOrNull(1).orEmpty(), "")
                    }
                }
            }
        }
        return Found(cells, unmatched.toList())
    }

    /** Lines of text read from a photo: each vehicle number found, with the times written on the same line. */
    fun fromLines(lines: List<String>, known: Set<String>, now: Long): Found {
        val cells = mutableListOf<List<String>>()
        val unmatched = LinkedHashSet<String>()
        for (line in lines) {
            val numbers = VehicleParser.extract(line).distinct()
            if (numbers.isEmpty()) continue
            val times = TIME.findAll(line).map { it.value.trim() }.filter { DrishtiData.parseTime(it, ReportBuilder.startOfDay(now)) > 0 }.toList()
            for (p in numbers) {
                if (p !in known) { unmatched += p; continue }
                cells += listOf("", p, "", "", "", times.getOrNull(0) ?: now.toString(), times.getOrNull(1).orEmpty(), "")
            }
        }
        return Found(cells, unmatched.toList())
    }

    /** Words of a photo put back into lines (top to bottom, left to right). */
    fun lines(words: List<OcrWord>): List<String> {
        if (words.isEmpty()) return emptyList()
        val sorted = words.sortedBy { (it.top + it.bottom) / 2 }
        val out = mutableListOf<MutableList<OcrWord>>()
        for (w in sorted) {
            val mid = (w.top + w.bottom) / 2
            val h = (w.bottom - w.top).coerceAtLeast(1f)
            val line = out.lastOrNull()?.takeIf { l -> val m = l.map { (it.top + it.bottom) / 2 }.average(); Math.abs(m - mid) < h * 0.6 }
            if (line != null) line += w else out += mutableListOf(w)
        }
        return out.map { l -> l.sortedBy { it.left }.joinToString(" ") { it.text } }
    }
}
