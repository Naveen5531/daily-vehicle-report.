package com.logistics.vehiclereport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Number plates read by the camera for vehicles that were posted with only their last 4 digits.
 * Per day and per 4 digits: the full numbers scanned, with the time ("2026-10-16": {"4821": [["TS09UB4821", t]]}).
 * When the entries are loaded, that day's "4821" posts get the scanned full number (see VehicleStore.getAll).
 * Kept in the account's own storage, so it goes to its cloud copy too.
 */
object PlateScans {
    const val KEY = "plate_scans_json"
    private const val KEEP_DAYS = 400

    fun dayKey(time: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))

    private fun read(c: Context): JSONObject = try {
        JSONObject(VehicleStore.prefs(c).getString(KEY, null) ?: "{}")
    } catch (e: Exception) { JSONObject() }

    /** day -> last 4 -> (full number, time scanned) */
    fun all(c: Context): Map<String, Map<String, List<Pair<String, Long>>>> {
        val o = read(c)
        val out = HashMap<String, Map<String, List<Pair<String, Long>>>>()
        for (day in o.keys()) {
            val d = o.optJSONObject(day) ?: continue
            val codes = HashMap<String, List<Pair<String, Long>>>()
            for (code in d.keys()) {
                val arr = d.optJSONArray(code) ?: continue
                codes[code] = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONArray(i)?.let { e -> e.optString(0).takeIf { it.isNotBlank() }?.let { it to e.optLong(1) } }
                }
            }
            out[day] = codes
        }
        return out
    }

    /** Saves [plate] for the posts of [code] on the day of [dayStart] (scanning the same plate again updates its time). */
    fun add(c: Context, dayStart: Long, code: String, plate: String) = edit(c, dayStart, code) { list ->
        list.filter { it.first != plate } + (plate to System.currentTimeMillis())
    }

    /** Takes a scanned plate back (read wrong). */
    fun remove(c: Context, dayStart: Long, code: String, plate: String) = edit(c, dayStart, code) { list ->
        list.filter { it.first != plate }
    }

    private fun edit(c: Context, dayStart: Long, code: String, change: (List<Pair<String, Long>>) -> List<Pair<String, Long>>) {
        val all = read(c)
        val key = dayKey(dayStart)
        val day = all.optJSONObject(key) ?: JSONObject()
        val arr = day.optJSONArray(code)
        val now = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            arr.optJSONArray(i)?.let { e -> e.optString(0) to e.optLong(1) }
        }
        val next = change(now)
        if (next.isEmpty()) day.remove(code)
        else day.put(code, JSONArray().apply { next.forEach { (p, t) -> put(JSONArray().put(p).put(t)) } })
        if (day.length() == 0) all.remove(key) else all.put(key, day)
        val oldest = dayKey(System.currentTimeMillis() - KEEP_DAYS * ReportBuilder.DAY_MS)
        all.keys().asSequence().toList().filter { it < oldest }.forEach { all.remove(it) }
        VehicleStore.prefs(c).edit().putString(KEY, all.toString()).apply()
    }

    /**
     * Entries posted with only the last 4 digits get the full number the camera read for that day
     * (two plates scanned for the same 4 digits = two vehicles came; the entry counts for both).
     */
    fun apply(records: List<VehicleRecord>, scans: Map<String, Map<String, List<Pair<String, Long>>>>): List<VehicleRecord> {
        if (scans.isEmpty()) return records
        return records.flatMap { r ->
            if (r.match == MatchType.FULL) return@flatMap listOf(r)
            val code = VehicleParser.last4(r.typed.ifBlank { r.vehicle })
            val plates = scans[dayKey(r.time)]?.get(code).orEmpty()
            if (plates.isEmpty()) listOf(r)
            else plates.mapIndexed { i, (plate, at) ->
                r.copy(id = if (i == 0) r.id else "${r.id}#$plate", vehicle = plate, match = MatchType.FULL,
                    candidates = listOf(plate), scannedAt = at)
            }
        }
    }
}
