package com.logistics.vehiclereport

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Where the stores and the DC are on the map, for clubbing only nearby or on-the-way stores. Looked up once with the
 * phone's map search (place names from the store list: city, region, store name) and kept per store code in the
 * account's storage. A place found wrong can be typed by hand (tap the store on the Plan tab).
 */
object StoreGeo {
    private const val KEY = "store_geo_json"   // {"5043": [lat, lon, "query", at, "State"], "5999": [0, 0, "query", triedAt]}
    private const val KEY_DC = "dc_geo_json"   // [lat, lon, "query", "State"]
    private const val RETRY_MS = 24L * 60 * 60 * 1000

    private val main = Handler(Looper.getMainLooper())
    /** True while stores are being looked up. */
    @Volatile var busy = false
        private set
    /** When the DC was last looked up and not found (tried again after a day, or when the app starts again). */
    @Volatile private var dcTriedAt = 0L

    private fun p(c: Context) = VehicleStore.prefs(c)
    private fun read(c: Context): JSONObject = try { JSONObject(p(c).getString(KEY, null) ?: "{}") } catch (e: Exception) { JSONObject() }

    fun places(c: Context): Map<String, LatLon> {
        val o = read(c)
        val out = HashMap<String, LatLon>()
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            if (a.optDouble(0, 0.0) != 0.0 || a.optDouble(1, 0.0) != 0.0) out[k] = LatLon(a.optDouble(0), a.optDouble(1))
        }
        return out
    }

    /** Each store's state from the map search (by store code), where it was found. */
    fun states(c: Context): Map<String, String> {
        val o = read(c)
        val out = HashMap<String, String>()
        for (k in o.keys()) o.optJSONArray(k)?.optString(4)?.takeIf { it.isNotBlank() }?.let { out[k] = it }
        return out
    }

    /** The DC's state from the map search, if known. */
    fun dcState(c: Context): String? = try {
        p(c).getString(KEY_DC, null)?.let { JSONArray(it).optString(3) }?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    /** The place name used for a store (typed by hand, or the one looked up). */
    fun query(c: Context, code: String): String? = read(c).optJSONArray(code)?.optString(2)?.takeIf { it.isNotBlank() }

    fun dc(c: Context): LatLon? = try {
        p(c).getString(KEY_DC, null)?.let { JSONArray(it) }?.let { LatLon(it.getDouble(0), it.getDouble(1)) }
    } catch (e: Exception) { null }

    /** The DC's place name: as typed, else the DC name without its number ("1041 - Isnapur DC" -> "Isnapur"). */
    fun dcQuery(c: Context): String = try { p(c).getString(KEY_DC, null)?.let { JSONArray(it).optString(2) } } catch (e: Exception) { null }
        ?.takeIf { it.isNotBlank() }
        ?: Masters.dispatchName.replace(Regex("^[\\d\\s\\-–:]+"), "").replace(Regex("(?i)\\b(dc|warehouse|wh)\\b"), "").trim()

    private fun clean(s: String) = s.replace(Regex("\\(.*?\\)"), " ").replace(Regex("(?i)d[- _]?mart"), " ")
        .replace(Regex("[^A-Za-z0-9 ,.-]"), " ").replace(Regex("\\s+"), " ").trim().trim(',', '-', ' ')

    /**
     * Place names to try for a store, best first: the store's name usually holds the area and the town
     * ("Gajuwaka Vizag", "Medipally Hyderabad"); then the store list's city. (Regions are the DC's own route groups,
     * not states, so they are not used.)
     */
    private fun queries(s: PlanStop): List<String> =
        listOf("${clean(s.shownName)}, India", "${clean(s.city)}, India", "${clean(s.name)}, India")
            .filter { it.length > 9 && !it.startsWith(",") }.distinct()

    /** Map search, kept within about 900 km of the DC when the DC is known (a same-named place far away is skipped). */
    @Suppress("DEPRECATION")
    private fun lookup(c: Context, query: String, near: LatLon?): Found? {
        if (!Geocoder.isPresent()) return null
        return try {
            val g = Geocoder(c, Locale("en", "IN"))
            val list = if (near == null) g.getFromLocationName(query, 1).orEmpty()
                else g.getFromLocationName(query, 1, near.lat - 8, near.lon - 8, near.lat + 8, near.lon + 8).orEmpty()
            list.firstOrNull()?.let { Found(LatLon(it.latitude, it.longitude), it.adminArea ?: stateAt(g, it.latitude, it.longitude)) }
        } catch (e: Exception) { null }
    }

    private class Found(val at: LatLon, val state: String?)

    /** State of a place on the map (reverse search); null when not known. */
    @Suppress("DEPRECATION")
    private fun stateAt(g: Geocoder, lat: Double, lon: Double): String? = try {
        g.getFromLocation(lat, lon, 1)?.firstOrNull()?.adminArea?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    private fun stateAt(c: Context, at: LatLon): String? =
        if (!Geocoder.isPresent()) null else stateAt(Geocoder(c, Locale("en", "IN")), at.lat, at.lon)

    /**
     * Looks up, in the background, the stores that have no place yet (and the DC); [done] runs on the main thread with
     * how many stores now have a place. Stores not found are tried again after a day.
     */
    fun fill(c: Context, stops: List<PlanStop>, done: (Int) -> Unit) {
        if (busy) return
        val app = c.applicationContext
        val known = read(app)
        val now = System.currentTimeMillis()
        val todo = stops.filter { s ->
            val a = known.optJSONArray(s.code)
            a == null || (a.optDouble(0, 0.0) == 0.0 && a.optDouble(1, 0.0) == 0.0 && now - a.optLong(3, 0L) > RETRY_MS)
        }.distinctBy { it.code }
        // Stores found before states were kept: their state is looked up once from their place.
        val noState = stops.map { it.code }.distinct().filter { code ->
            val a = known.optJSONArray(code)
            a != null && (a.optDouble(0, 0.0) != 0.0 || a.optDouble(1, 0.0) != 0.0) && a.optString(4).isBlank() && !a.optBoolean(5, false)
        }
        val needDc = dc(app) == null && dcQuery(app).isNotBlank() && now - dcTriedAt > RETRY_MS
        val needDcState = !needDc && dc(app) != null && dcState(app) == null && now - dcTriedAt > RETRY_MS
        if (todo.isEmpty() && noState.isEmpty() && !needDc && !needDcState) return
        busy = true
        Thread {
            try {
                if (needDc) {
                    val q = dcQuery(app)
                    val at = lookup(app, "$q, India", null)
                    if (at != null) setDc(app, q, at.at, at.state) else dcTriedAt = System.currentTimeMillis()
                }
                if (needDcState) {
                    val at = dc(app)
                    val st = at?.let { stateAt(app, it) }
                    if (at != null && st != null) setDc(app, dcQuery(app), at, st) else dcTriedAt = System.currentTimeMillis()
                }
                val dc = dc(app)
                for (s in todo) {
                    var found: Found? = null
                    var used = ""
                    // A place typed earlier (not found then) is tried again first.
                    val typed = known.optJSONArray(s.code)?.optString(2)?.takeIf { it.isNotBlank() }
                    for (q in (listOfNotNull(typed) + queries(s)).distinct()) { found = lookup(app, q, dc); used = q; if (found != null) break }
                    save(app, s.code, used, found?.at, found?.state)
                }
                val placesNow = places(app)
                for (code in noState) {
                    val at = placesNow[code] ?: continue
                    saveState(app, code, stateAt(app, at))
                }
            } catch (e: Throwable) { } finally {
                busy = false
                main.post { done(places(app).size) }
            }
        }.start()
    }

    /** A place typed by hand for a store: looked up now; [done] gets whether it was found. */
    fun setPlace(c: Context, code: String, query: String, done: (Boolean) -> Unit) {
        val app = c.applicationContext
        Thread {
            val found = lookup(app, if (query.contains("india", true)) query else "$query, India", dc(app))
            save(app, code, query, found?.at, found?.state)
            main.post { done(found != null) }
        }.start()
    }

    /** The DC's place typed by hand: looked up now. */
    fun setDcPlace(c: Context, query: String, done: (Boolean) -> Unit) {
        val app = c.applicationContext
        Thread {
            val found = lookup(app, if (query.contains("india", true)) query else "$query, India", null)
            if (found != null) setDc(app, query, found.at, found.state)
            main.post { done(found != null) }
        }.start()
    }

    private fun setDc(c: Context, query: String, at: LatLon, state: String?) =
        p(c).edit().putString(KEY_DC, JSONArray().put(at.lat).put(at.lon).put(query).put(state.orEmpty()).toString()).apply()

    @Synchronized private fun save(c: Context, code: String, query: String, at: LatLon?, state: String?) {
        val o = read(c)
        o.put(code, JSONArray().put(at?.lat ?: 0.0).put(at?.lon ?: 0.0).put(query).put(System.currentTimeMillis()).put(state.orEmpty()))
        p(c).edit().putString(KEY, o.toString()).apply()
    }

    /** A state found later for a store already on the map (tried once: index 5 marks it). */
    @Synchronized private fun saveState(c: Context, code: String, state: String?) {
        val o = read(c)
        val a = o.optJSONArray(code) ?: return
        while (a.length() < 4) a.put("")
        a.put(4, state.orEmpty()); a.put(5, true)
        p(c).edit().putString(KEY, o.toString()).apply()
    }
}
