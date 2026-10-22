package com.logistics.vehiclereport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What one pull from Drishti did. */
data class DrishtiResult(
    val read: Int,
    val inList: Int,
    val reported: Int,
    val departed: Int,
    val marksChanged: Int,
    val newVehicles: Int
)

/**
 * The DMart Drishti gate log in the app: settings, the last pulls, and applying a pull to the vehicle list and
 * Today's Reported / Departed marks. The Drishti sign-in itself stays in the in-app browser (its cookies);
 * no password is kept by the app.
 */
object DrishtiStore {
    const val URL = "https://w4.meradmart.com/app/drishti/report/log-vehicle-summary"
    const val HOST = "meradmart.com"

    private const val KEY_SITE = "drishti_site"
    private const val KEY_LOG = "drishti_log_json"
    const val KEY_STATUS = "drishti_status"
    private const val KEY_LAST = "drishti_last_pull"
    private const val KEY_LAST_COUNT = "drishti_last_count"
    private const val KEY_AUTO = "drishti_auto"
    private const val KEEP_DAYS = 14

    /** Status of the last try: OK, LOGIN (sign in needed), NO_TABLE (page did not show the vehicle log), "" never. */
    const val OK = "OK"; const val LOGIN = "LOGIN"; const val NO_TABLE = "NO_TABLE"

    /** Auto pull every this long while the app is open. */
    const val AUTO_EVERY_MS = 20L * 60 * 1000

    fun isDrishtiUrl(url: String?): Boolean {
        if (url == null || !url.startsWith("https://")) return false
        val host = try { android.net.Uri.parse(url).host } catch (e: Exception) { null } ?: return false
        return host == HOST || host.endsWith(".$HOST")
    }

    /** The DC's site code in Drishti; only its rows are taken. Defaults to the code in the DC name (e.g. 1041). */
    fun site(c: Context): String {
        val p = VehicleStore.prefs(c)
        p.getString(KEY_SITE, null)?.let { return it }
        return "1041"
    }

    fun setSite(c: Context, code: String) {
        VehicleStore.prefs(c).edit().putString(KEY_SITE, code.filter { it.isLetterOrDigit() }).apply()
    }

    fun autoOn(c: Context) = VehicleStore.prefs(c).getBoolean(KEY_AUTO, true)
    fun setAuto(c: Context, on: Boolean) = VehicleStore.prefs(c).edit().putBoolean(KEY_AUTO, on).apply()

    fun status(c: Context): String = VehicleStore.prefs(c).getString(KEY_STATUS, "") ?: ""
    fun lastPull(c: Context): Long = VehicleStore.prefs(c).getLong(KEY_LAST, 0L)
    fun lastCount(c: Context): Int = VehicleStore.prefs(c).getInt(KEY_LAST_COUNT, 0)

    fun setStatus(c: Context, status: String) {
        VehicleStore.prefs(c).edit().putString(KEY_STATUS, status).apply()
    }

    /** Drishti has worked on this phone at least once (auto pull only runs then). */
    fun everWorked(c: Context) = lastPull(c) > 0

    /** Saves what was read from the page and applies it. [cells] = one list of cells per row (see [DrishtiData]). */
    fun apply(c: Context, cells: List<List<String>>, pageDay: Long): DrishtiResult {
        val rows = DrishtiData.rows(cells, pageDay, site(c))
        saveLog(c, rows)
        val (added, _) = VehicleStore.addFromGate(c, rows)
        val vehicles = VehicleStore.getVehicles(c)
        val records = VehicleStore.getAll(c)
        val entries = DrishtiData.marks(rows, vehicles, records) { day -> GateMarks.markedOn(c, day) }
        val changed = GateMarks.applyGate(c, entries)
        val dedicated = vehicles.filter { it.dedicated }.map { it.number }.toSet()
        VehicleStore.prefs(c).edit()
            .putString(KEY_STATUS, OK)
            .putLong(KEY_LAST, System.currentTimeMillis())
            .putInt(KEY_LAST_COUNT, rows.size)
            .apply()
        return DrishtiResult(
            read = rows.size,
            inList = rows.count { it.vehicle in dedicated },
            reported = entries.count { it.gate == Gate.REPORTED },
            departed = entries.count { it.gate == Gate.DEPARTED },
            marksChanged = changed,
            newVehicles = added
        )
    }

    /** The gate log by day, newest pull wins for a vehicle + check-in. Kept [KEEP_DAYS] days. */
    private fun saveLog(c: Context, rows: List<DrishtiRow>) {
        if (rows.isEmpty()) return
        val p = VehicleStore.prefs(c)
        val all = try { JSONObject(p.getString(KEY_LOG, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        for ((day, list) in rows.filter { it.day > 0 }.groupBy { it.day }) {
            val d = all.optJSONObject(day.toString()) ?: JSONObject()
            for (r in list) d.put(r.vehicle + "|" + r.checkIn, JSONArray()
                .put(r.site).put(r.vehicle).put(r.type).put(r.driver).put(r.mobile).put(r.checkIn).put(r.checkOut).put(r.destination))
            all.put(day.toString(), d)
        }
        val oldest = ReportBuilder.startOfDay() - KEEP_DAYS * ReportBuilder.DAY_MS
        all.keys().asSequence().toList().filter { (it.toLongOrNull() ?: 0L) < oldest }.forEach { all.remove(it) }
        p.edit().putString(KEY_LOG, all.toString()).apply()
    }

    /** The gate log saved for the day starting at [dayStart], in check-in order. */
    fun log(c: Context, dayStart: Long): List<DrishtiRow> {
        val all = try { JSONObject(VehicleStore.prefs(c).getString(KEY_LOG, "{}") ?: "{}") } catch (e: Exception) { return emptyList() }
        val d = all.optJSONObject(dayStart.toString()) ?: return emptyList()
        return d.keys().asSequence().mapNotNull { k ->
            val a = d.optJSONArray(k) ?: return@mapNotNull null
            DrishtiRow(a.optString(0), a.optString(1), a.optString(2), a.optString(3), a.optString(4),
                a.optLong(5), a.optLong(6), a.optString(7))
        }.sortedBy { it.checkIn }.toList()
    }
}
