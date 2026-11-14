package com.logistics.vehiclereport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The DC's hand marks on Today: Yet to report / Reported / Departed, per vehicle per day, with the time each was set.
 * Kept in the account's own storage (so they go to its cloud copy too):
 * {"2026-10-15": {"TS09UB4821": ["R", reportedAt, departedAt]}}. Days older than [KEEP_DAYS] are dropped.
 */
object GateMarks {
    const val KEY = "gate_marks_json"
    /** First day the marks were used; before it a WhatsApp post still counts as reported (old reports stay the same). */
    private const val SINCE = "gate_since"
    private const val KEEP_DAYS = 400

    private fun dayKey(dayStart: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dayStart))

    fun since(c: Context): Long {
        val p = VehicleStore.prefs(c)
        val v = p.getLong(SINCE, 0L)
        if (v > 0) return v
        val today = ReportBuilder.startOfDay()
        p.edit().putLong(SINCE, today).apply()
        return today
    }

    private fun read(c: Context): JSONObject = try {
        JSONObject(VehicleStore.prefs(c).getString(KEY, null) ?: "{}")
    } catch (e: Exception) { JSONObject() }

    private fun parse(day: JSONObject?): Map<String, GateMark> {
        if (day == null) return emptyMap()
        val out = HashMap<String, GateMark>()
        for (k in day.keys()) {
            val a = day.optJSONArray(k) ?: continue
            val gate = Gate.of(a.optString(0)) ?: continue
            out[k] = GateMark(gate, a.optLong(1), a.optLong(2))
        }
        return out
    }

    fun forDay(c: Context, dayStart: Long): Map<String, GateMark> = parse(read(c).optJSONObject(dayKey(dayStart)))

    /** How many days back an open trip (checked in, never checked out) is still shown at the DC. */
    const val CARRY_DAYS = 3

    /**
     * A check-out before 9 AM belongs to the previous day's loading (yesterday's vehicle leaving in the early morning):
     * on the Today screen it counts as Departed on the day before, not on the day it went out.
     */
    const val EARLY_OUT_MS = 9 * 3_600_000L

    /** Checked out in the first 9 hours of [day]: the trip belongs to the day before. */
    fun earlyOut(out: Long, day: Long) = out >= day && out < day + EARLY_OUT_MS

    /**
     * All marks at once, for reports over several days.
     * [carry] (Today screen): a vehicle that checked in on one of the last [CARRY_DAYS] days and has no check-out yet
     * is still at the DC, so it shows as Reported (with its first check-in time). Once it checks out (or is marked
     * Departed) the trip is closed and only a new check-in counts. Reports and the cloud copy keep each day as it was.
     */
    fun gates(c: Context, carry: Boolean = false): ReportBuilder.Gates {
        val all = read(c)
        val cache = HashMap<String, Map<String, GateMark>>()
        val logs = HashMap<Long, Map<String, DrishtiRow>>()
        fun ownMarks(day: Long) = dayKey(day).let { k -> cache.getOrPut(k) { parse(all.optJSONObject(k)) } }
        fun ownLog(day: Long) = logs.getOrPut(day) { DrishtiData.byVehicle(DrishtiStore.log(c, day)) }
        val carried = HashMap<Long, Pair<Map<String, GateMark>, Map<String, DrishtiRow>>>()
        fun carriedFor(day: Long) = carried.getOrPut(day) {
            openTrips(day, ownMarks(day).keys + ownLog(day).keys, ::ownMarks, ::ownLog)
        }
        val shifted = HashMap<Long, Pair<Map<String, GateMark>, Map<String, DrishtiRow>>>()
        fun shiftedFor(day: Long) = shifted.getOrPut(day) {
            val next = ReportBuilder.startOfDay(day + ReportBuilder.DAY_MS + ReportBuilder.DAY_MS / 2)
            earlyMorning(day, ownMarks(day), ownLog(day), ownMarks(next), ownLog(next))
        }
        return ReportBuilder.Gates(
            since = since(c),
            forDay = { day -> if (!carry) ownMarks(day) else carriedFor(day).first + shiftedFor(day).first },
            gateOnly = DrishtiStore.gateOnly(c),
            gateLog = { day -> if (!carry) ownLog(day) else carriedFor(day).second + shiftedFor(day).second }
        )
    }

    /**
     * [day]'s own marks and gate rows, minus vehicles that checked in that day and went out before 9 AM (they are the
     * day before's), plus vehicles of the next day that went out before 9 AM that morning (they are this day's,
     * shown Departed).
     */
    fun earlyMorning(
        day: Long, marks: Map<String, GateMark>, log: Map<String, DrishtiRow>,
        nextMarks: Map<String, GateMark>, nextLog: Map<String, DrishtiRow>
    ): Pair<Map<String, GateMark>, Map<String, DrishtiRow>> {
        val next = ReportBuilder.startOfDay(day + ReportBuilder.DAY_MS + ReportBuilder.DAY_MS / 2)
        fun outOf(m: GateMark?, r: DrishtiRow?) =
            (if (m?.gate == Gate.DEPARTED) m.departedAt else 0L).takeIf { it > 0 } ?: (r?.checkOut ?: 0L)
        val early = (marks.keys + log.keys).filter { earlyOut(outOf(marks[it], log[it]), day) }.toSet()
        val m = marks.filterKeys { it !in early }.toMutableMap()
        val l = log.filterKeys { it !in early }.toMutableMap()
        for (v in nextMarks.keys + nextLog.keys) {
            val nm = nextMarks[v]; val nr = nextLog[v]
            val out = outOf(nm, nr)
            if (!earlyOut(out, next)) continue
            if (m[v]?.gate == Gate.REPORTED || m[v] == null) {
                val inAt = nm?.reportedAt?.takeIf { it > 0 } ?: nr?.checkIn ?: 0L
                m[v] = GateMark(Gate.DEPARTED, m[v]?.reportedAt?.takeIf { it > 0 } ?: inAt, out)
            }
            if (nr != null && l[v] == null) l[v] = nr
        }
        return m to l
    }

    /**
     * Trips still open on [day]: per vehicle not seen on [day] itself ([seenToday]), its latest earlier day (up to
     * [CARRY_DAYS] back) with a check-in or mark. Open = checked in / marked Reported with no check-out, or checked
     * out on [day] itself (it left today: Departed). Checked out before [day] = closed, not shown.
     */
    fun openTrips(
        day: Long, seenToday: Set<String>,
        marksOn: (Long) -> Map<String, GateMark>, logOn: (Long) -> Map<String, DrishtiRow>
    ): Pair<Map<String, GateMark>, Map<String, DrishtiRow>> {
        val marks = HashMap<String, GateMark>()
        val rows = HashMap<String, DrishtiRow>()
        val done = HashSet<String>(seenToday)
        for (back in 1..CARRY_DAYS) {
            val prev = ReportBuilder.startOfDay(day - back * ReportBuilder.DAY_MS + ReportBuilder.DAY_MS / 2)
            val pm = marksOn(prev)
            val pl = logOn(prev)
            for (v in pm.keys + pl.keys) {
                if (v in done) continue
                val m = pm[v]
                val r = pl[v]
                if (m?.gate == Gate.YET && r == null) continue   // only "yet to report" that day: not a trip
                done += v                                         // the latest earlier trip decides
                val out = when {
                    m?.gate == Gate.DEPARTED -> m.departedAt.takeIf { it > 0 } ?: (r?.checkOut ?: 0L).takeIf { it > 0 } ?: prev
                    r != null && r.checkOut > 0 -> r.checkOut
                    else -> 0L
                }
                // Checked out before this day, or before 9 AM of it (yesterday's loading): trip closed.
                if (out in 1 until day + EARLY_OUT_MS) continue
                val inAt = m?.reportedAt?.takeIf { it > 0 } ?: r?.checkIn ?: 0L
                marks[v] = if (out >= day) GateMark(Gate.DEPARTED, inAt, out) else GateMark(Gate.REPORTED, inAt)
                if (r != null) rows[v] = r
            }
        }
        return marks to rows
    }

    /** Moves a day's mark from [from] to [to] (the camera showed the vehicle is really [to]); [to]'s own mark wins. */
    fun move(c: Context, dayStart: Long, from: String, to: String) {
        val all = read(c)
        val key = dayKey(dayStart)
        val day = all.optJSONObject(key) ?: return
        val mark = day.optJSONArray(from) ?: return
        if (!day.has(to)) day.put(to, mark)
        day.remove(from)
        all.put(key, day)
        VehicleStore.prefs(c).edit().putString(KEY, all.toString()).apply()
    }

    /** Vehicles with any mark on the day starting at [dayStart]. */
    fun markedOn(c: Context, dayStart: Long): Set<String> = forDay(c, dayStart).keys

    /**
     * Marks from the DC gate log (Drishti): Reported at the check-in time, Departed at the check-out time.
     * The gate's times replace hand-marked times; a vehicle already Departed is never put back to Reported.
     * Returns how many marks changed.
     */
    fun applyGate(c: Context, entries: List<GateEntry>): Int {
        if (entries.isEmpty()) return 0
        val all = read(c)
        var changed = 0
        for (e in entries) {
            if (e.reportedAt <= 0 && e.departedAt <= 0) continue
            val key = dayKey(e.day)
            val day = all.optJSONObject(key) ?: JSONObject()
            val old = parse(day)[e.vehicle]
            val mark = when {
                e.gate == Gate.DEPARTED -> GateMark(Gate.DEPARTED, e.reportedAt, e.departedAt)
                old != null && old.gate == Gate.DEPARTED -> GateMark(Gate.DEPARTED, e.reportedAt.takeIf { it > 0 } ?: old.reportedAt, old.departedAt)
                else -> GateMark(Gate.REPORTED, e.reportedAt)
            }
            if (mark == old) continue
            // "G" = set from the gate log (a hand mark rewrites the entry without it).
            day.put(e.vehicle, JSONArray().put(mark.gate.code).put(mark.reportedAt).put(mark.departedAt).put("G"))
            all.put(key, day)
            changed++
        }
        if (changed > 0) {
            since(c)
            VehicleStore.prefs(c).edit().putString(KEY, all.toString()).apply()
        }
        return changed
    }

    /**
     * Marks [vehicle] on the day starting at [dayStart]; null removes the mark (back to what WhatsApp says).
     * Reported keeps the first time it was marked reported; Departed keeps that and adds the time it left.
     */
    fun set(c: Context, dayStart: Long, vehicle: String, gate: Gate?) {
        val all = read(c)
        val key = dayKey(dayStart)
        val day = all.optJSONObject(key) ?: JSONObject()
        val old = parse(day)[vehicle]
        val now = System.currentTimeMillis()
        if (gate == null) day.remove(vehicle) else {
            val mark = when (gate) {
                Gate.YET -> GateMark(Gate.YET)
                Gate.REPORTED -> GateMark(Gate.REPORTED, reportedAt = old?.reportedAt?.takeIf { it > 0 } ?: now)
                Gate.DEPARTED -> GateMark(Gate.DEPARTED, reportedAt = old?.reportedAt ?: 0,
                    departedAt = old?.departedAt?.takeIf { it > 0 && old.gate == Gate.DEPARTED } ?: now)
            }
            day.put(vehicle, JSONArray().put(mark.gate.code).put(mark.reportedAt).put(mark.departedAt))
        }
        if (day.length() == 0) all.remove(key) else all.put(key, day)
        // Drop old days ("yyyy-MM-dd" sorts by date).
        val oldest = dayKey(ReportBuilder.startOfDay(now - KEEP_DAYS * ReportBuilder.DAY_MS))
        all.keys().asSequence().toList().filter { it < oldest }.forEach { all.remove(it) }
        since(c)
        VehicleStore.prefs(c).edit().putString(KEY, all.toString()).apply()
    }
}
