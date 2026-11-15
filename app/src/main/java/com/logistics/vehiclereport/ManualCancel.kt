package com.logistics.vehiclereport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hand-set "Vehicle cancelled" and "Replacement" for a vehicle on a day (when WhatsApp or the gate did not say so).
 * {"2026-11-15": {"TS09UB4821": ["TS08XX1234"]}} - the list holds the replacement plate ("" = cancelled, none named yet).
 */
object ManualCancel {
    private const val KEY = "manual_cancel_json"
    private const val KEEP_DAYS = 60

    /** [replacement] is blank while the vehicle is only marked cancelled. */
    data class Mark(val replacement: String)

    private fun dayKey(dayStart: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dayStart))

    private fun read(c: Context): JSONObject = try {
        JSONObject(VehicleStore.prefs(c).getString(KEY, null) ?: "{}")
    } catch (e: Exception) { JSONObject() }

    private var rawSeen: String? = null
    private var objSeen = JSONObject()

    @Synchronized
    private fun shared(c: Context): JSONObject {
        val raw = VehicleStore.prefs(c).getString(KEY, null) ?: "{}"
        if (raw != rawSeen) { objSeen = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }; rawSeen = raw }
        return objSeen
    }

    fun get(c: Context, dayStart: Long, vehicle: String): Mark? {
        val a = shared(c).optJSONObject(dayKey(dayStart))?.optJSONArray(vehicle) ?: return null
        return Mark(a.optString(0))
    }

    /** null clears the mark. */
    fun set(c: Context, dayStart: Long, vehicle: String, mark: Mark?) {
        val all = read(c)
        val key = dayKey(dayStart)
        val day = all.optJSONObject(key) ?: JSONObject()
        if (mark == null) day.remove(vehicle) else day.put(vehicle, JSONArray().put(mark.replacement.trim().uppercase().replace(" ", "")))
        if (day.length() == 0) all.remove(key) else all.put(key, day)
        val oldest = dayKey(ReportBuilder.startOfDay(System.currentTimeMillis() - KEEP_DAYS * ReportBuilder.DAY_MS))
        all.keys().asSequence().toList().filter { it < oldest }.forEach { all.remove(it) }
        VehicleStore.prefs(c).edit().putString(KEY, all.toString()).apply()
    }
}
