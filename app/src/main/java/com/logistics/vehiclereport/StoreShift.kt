package com.logistics.vehiclereport

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * Per-store unloading shift: Day or Night. Decides when a store's "day" starts, so overdue trips are counted only
 * from the current operating window: Day stores start at 6 AM, Night stores at 10 PM. Default = Day.
 */
object StoreShift {
    private const val KEY = "store_shift_json"   // {"5043":"N", ...}  (missing = Day)
    const val DAY_HOUR = 6       // 6 AM
    const val NIGHT_HOUR = 22    // 10 PM

    private fun p(c: Context) = VehicleStore.prefs(c)
    private fun read(c: Context) = try { JSONObject(p(c).getString(KEY, null) ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun norm(code: String) = code.filter { it.isLetterOrDigit() }

    fun isNight(c: Context, code: String): Boolean = read(c).optString(norm(code)) == "N"

    fun setNight(c: Context, code: String, night: Boolean) {
        val o = read(c); val k = norm(code)
        if (night) o.put(k, "N") else o.remove(k)   // Day is the default, so store only the Night ones
        p(c).edit().putString(KEY, o.toString()).apply()
    }

    /** Sets many stores at once from an imported store sheet (code -> true = Night); other stores are left as they are. */
    fun setAll(c: Context, map: Map<String, Boolean>) {
        val o = read(c)
        for ((code, night) in map) { val k = norm(code); if (night) o.put(k, "N") else o.remove(k) }
        p(c).edit().putString(KEY, o.toString()).apply()
    }

    /** Start of the store's current operating window, in millis. */
    fun windowStart(c: Context, code: String, now: Long = System.currentTimeMillis()): Long {
        val hour = if (isNight(c, code)) NIGHT_HOUR else DAY_HOUR
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis > now) cal.add(Calendar.DAY_OF_MONTH, -1)  // window started yesterday at that hour
        return cal.timeInMillis
    }
}
