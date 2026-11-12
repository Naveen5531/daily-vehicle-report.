package com.logistics.vehiclereport

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Road km between a clubbed vehicle's stores (store 1 → store 2 …), as Google Maps would drive it, from the free
 * OSRM road router (OpenStreetMap roads; no key). Looked up once per store pair and kept in the account's storage.
 */
object RoadKm {
    private const val KEY = "road_km_json"
    private val main = Handler(Looper.getMainLooper())
    private val asked = HashSet<String>()

    private fun key(pts: List<LatLon>) = pts.joinToString("|") { String.format(Locale.US, "%.5f,%.5f", it.lat, it.lon) }

    private fun read(c: Context): JSONObject = try { JSONObject(VehicleStore.prefs(c).getString(KEY, null) ?: "{}") } catch (e: Exception) { JSONObject() }

    /** Road km kept for these stops, or null when not looked up yet. */
    fun cached(c: Context, pts: List<LatLon>): Double? =
        read(c).optDouble(key(pts), Double.NaN).takeIf { !it.isNaN() && it > 0 }

    /** Straight-line km stop to stop (shown with "~" until the road km is known). */
    fun straight(pts: List<LatLon>): Double = pts.zipWithNext().sumOf { (a, b) -> VehiclePlanner.km(a, b) }

    /** Looks the road km up in the background (once per app run for a route not found); [done] runs when it is known. */
    fun fetch(c: Context, pts: List<LatLon>, done: () -> Unit) {
        if (pts.size < 2) return
        val k = key(pts)
        synchronized(asked) { if (!asked.add(k)) return }
        val app = c.applicationContext
        Thread {
            val km = try {
                val path = pts.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
                val con = (URL("https://router.project-osrm.org/route/v1/driving/$path?overview=false").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 15000
                    setRequestProperty("User-Agent", app.packageName)
                }
                val body = con.inputStream.bufferedReader().use { it.readText() }
                con.disconnect()
                val o = JSONObject(body)
                if (o.optString("code") == "Ok") o.getJSONArray("routes").getJSONObject(0).getDouble("distance") / 1000.0 else null
            } catch (e: Exception) { null }
            if (km != null && km > 0) {
                synchronized(this) {
                    val o = read(app); o.put(k, km)
                    VehicleStore.prefs(app).edit().putString(KEY, o.toString()).apply()
                }
                main.post(done)
            }
        }.start()
    }
}
