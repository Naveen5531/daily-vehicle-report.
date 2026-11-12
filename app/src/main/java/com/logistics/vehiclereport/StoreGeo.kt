package com.logistics.vehiclereport

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
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

    /** How far the DMart store found may be from the store's own town (more means a wrong same-named store). */
    private const val DMART_NEAR_KM = 15.0

    /** "DMart Tuni, India", then "DMart Tuni, Kakinada, India": the store itself rather than the middle of its area. */
    private fun dmartQueries(s: PlanStop): List<String> {
        val name = clean(s.shownName).ifBlank { clean(s.name) }
        if (name.length < 3) return emptyList()
        val city = clean(s.city).takeIf { it.isNotBlank() && !name.contains(it, ignoreCase = true) }
        return listOfNotNull("DMart $name, India", city?.let { "DMart $name, $it, India" }).distinct()
    }

    /** Marker kept as the place name for a store pinned exactly (Google Maps link or lat, long). */
    const val EXACT = "Pinned from Google Maps"

    /** How a store's place was set: exact pin, found by map search (approximate), or not on the map. */
    enum class Kind { EXACT, AUTO, NONE }

    fun kind(c: Context, code: String): Kind {
        val a = read(c).optJSONArray(code.trim()) ?: return Kind.NONE
        if (a.optDouble(0, 0.0) == 0.0 && a.optDouble(1, 0.0) == 0.0) return Kind.NONE
        return if (a.optString(2).startsWith(EXACT)) Kind.EXACT else Kind.AUTO
    }

    /** Pins a store exactly (from the store location master); kept for good, never searched again. */
    fun setExact(c: Context, code: String, at: LatLon) = save(c.applicationContext, code.trim(), EXACT, at, null)

    /** Takes a store's pin off (it is searched again on the Plan tab). */
    @Synchronized fun clear(c: Context, code: String) {
        val o = read(c); o.remove(code.trim())
        p(c).edit().putString(KEY, o.toString()).apply()
    }

    /** True when the store's place was pinned exactly by hand. */
    fun isExact(c: Context, code: String): Boolean = read(c).optJSONArray(code)?.optString(2)?.startsWith(EXACT) == true

    /** True when the text is a Google Maps link or "lat, long" (not a place name to search). */
    fun isLink(text: String): Boolean {
        val t = text.trim()
        return t.contains("http", true) || t.contains("goo.gl", true) || t.contains("google.", true) || coords(t) != null
    }

    /** Lat, long out of a Google Maps link (place pin first) or plain "17.12, 82.45". */
    fun coords(text: String): LatLon? {
        val t = try { URLDecoder.decode(text, "UTF-8") } catch (e: Exception) { text }
        val num = "(-?\\d{1,2}\\.\\d{3,})"
        val pats = listOf(
            Regex("!3d$num!4d(-?\\d{1,3}\\.\\d{3,})"),                       // the place's own pin
            Regex("[?&](?:q|query|ll|destination|daddr|center)=$num,\\s*(-?\\d{1,3}\\.\\d{3,})"),
            Regex("/place/$num,\\s*(-?\\d{1,3}\\.\\d{3,})"),
            Regex("/search/$num,\\s*\\+?(-?\\d{1,3}\\.\\d{3,})"),
            Regex("@$num,(-?\\d{1,3}\\.\\d{3,})"),                             // map centre (last resort)
            Regex("^\\s*$num\\s*[, ]\\s*(-?\\d{1,3}\\.\\d{3,})\\s*$")
        )
        for (r in pats) {
            val m = r.find(t) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in 5.0..38.0 && lon in 66.0..99.0) return LatLon(lat, lon)   // inside India
        }
        return null
    }

    /** Follows a short link (maps.app.goo.gl) to the full Google Maps link and reads the pin from it. Network: call off the main thread. */
    fun resolveLink(text: String): LatLon? {
        coords(text)?.let { return it }
        var url = Regex("https?://\\S+").find(text)?.value ?: return null
        try {
            repeat(6) {
                val con = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false; connectTimeout = 10000; readTimeout = 10000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                }
                val code = con.responseCode
                val next = con.getHeaderField("Location")
                if (code in 300..399 && next != null) {
                    con.disconnect()
                    url = URL(URL(url), next).toString()
                    coords(url)?.let { return it }
                } else {
                    // No more redirects: the page itself may hold the pin.
                    val body = try { con.inputStream.bufferedReader().use { r -> r.readText().take(400000) } } catch (e: Exception) { "" }
                    con.disconnect()
                    return coords(url) ?: coords(body)
                }
            }
        } catch (e: Exception) { }
        return coords(url)
    }

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
            a == null || (a.optDouble(0, 0.0) == 0.0 && a.optDouble(1, 0.0) == 0.0 && now - a.optLong(3, 0L) > RETRY_MS) ||
                // Found earlier from the area name only: checked once more for the DMart store itself.
                (!a.optBoolean(6, false) && a.optString(2).endsWith(", India") && (a.optDouble(0, 0.0) != 0.0 || a.optDouble(1, 0.0) != 0.0))
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
                    val handTyped = typed != null && !typed.endsWith(", India")
                    if (handTyped) { found = lookup(app, "$typed, India", dc); used = typed.orEmpty() }
                    else for (q in queries(s)) { found = lookup(app, q, dc); used = q; if (found != null) break }
                    // The DMart store itself, only when the map search finds it in the store's own town.
                    val area = found
                    if (!handTyped && area != null) {
                        for (dq in dmartQueries(s)) {
                            val store = lookup(app, dq, dc) ?: continue
                            if (VehiclePlanner.km(area.at, store.at) <= DMART_NEAR_KM) {
                                found = Found(store.at, store.state ?: area.state); used = dq; break
                            }
                        }
                    }
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
            if (isLink(query)) {
                // Exact pin from a Google Maps link or lat, long: kept as is, never searched again.
                val at = resolveLink(query)
                if (at != null) save(app, code, EXACT, at, if (Geocoder.isPresent()) stateAt(app, at) else null)
                main.post { done(at != null) }
                return@Thread
            }
            val found = lookup(app, if (query.contains("india", true)) query else "$query, India", dc(app))
            save(app, code, query, found?.at, found?.state)
            main.post { done(found != null) }
        }.start()
    }

    /** The DC's place typed by hand: looked up now. */
    fun setDcPlace(c: Context, query: String, done: (Boolean) -> Unit) {
        val app = c.applicationContext
        Thread {
            if (isLink(query)) {
                val at = resolveLink(query)
                if (at != null) setDc(app, dcQuery(app), at, if (Geocoder.isPresent()) stateAt(app, at) else null)
                main.post { done(at != null) }
                return@Thread
            }
            val found = lookup(app, if (query.contains("india", true)) query else "$query, India", null)
            if (found != null) setDc(app, query, found.at, found.state)
            main.post { done(found != null) }
        }.start()
    }

    private fun setDc(c: Context, query: String, at: LatLon, state: String?) =
        p(c).edit().putString(KEY_DC, JSONArray().put(at.lat).put(at.lon).put(query).put(state.orEmpty()).toString()).apply()

    @Synchronized private fun save(c: Context, code: String, query: String, at: LatLon?, state: String?) {
        val o = read(c)
        // [5] state tried, [6] DMart store checked.
        o.put(code, JSONArray().put(at?.lat ?: 0.0).put(at?.lon ?: 0.0).put(query).put(System.currentTimeMillis()).put(state.orEmpty())
            .put(state != null).put(true))
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
