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

    /** All marks at once, for reports over several days. */
    fun gates(c: Context): ReportBuilder.Gates {
        val all = read(c)
        val cache = HashMap<String, Map<String, GateMark>>()
        return ReportBuilder.Gates(since(c)) { day -> dayKey(day).let { k -> cache.getOrPut(k) { parse(all.optJSONObject(k)) } } }
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
