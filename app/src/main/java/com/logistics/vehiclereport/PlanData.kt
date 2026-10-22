package com.logistics.vehiclereport

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The vehicle plan being worked on: the stores read from the request sheet (photos or Excel), the clubs made or
 * split by hand, and the vehicle sizes. Kept in the account's own storage (so it goes to its cloud copy too).
 */
object PlanData {
    private const val KEY = "plan_json"
    private const val KEY_TYPES = "plan_types_json"
    private const val KEY_CLUB_BELOW = "plan_club_below"
    private const val KEY_AUTO = "plan_auto_club"
    private const val KEY_NEAR = "plan_near_km"
    private const val KEY_MAX_STOPS = "plan_max_stops"
    /** Set once 22 ft and 24 ft were turned off in sizes saved before 20 ft became the biggest by default. */
    private const val KEY_MAX20 = "plan_sizes_max20"

    /** One photo or file read into the plan, with the sheet's own total and what was read (to check the reading). */
    data class Source(val label: String, val rows: Int, val sheetTotal: Double?, val readTotal: Double)

    data class Plan(
        val created: Long,
        val stops: List<PlanStop>,
        val noClub: Set<String>,
        val clubs: List<Set<String>>,
        val sources: List<Source>
    )

    private fun p(c: Context) = VehicleStore.prefs(c)

    fun load(c: Context): Plan {
        val o = try { JSONObject(p(c).getString(KEY, null) ?: "{}") } catch (e: Exception) { JSONObject() }
        val stops = o.optJSONArray("stops")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { s ->
                PlanStop(
                    s.optString("code"), s.optString("name"), s.optDouble("vol", 0.0), s.optDouble("wt", 0.0), s.optInt("box"),
                    if (s.has("veh")) s.optInt("veh") else null, s.optString("circle"), s.optString("remark")
                )
            }
        }.orEmpty()
        fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
        val clubs = o.optJSONArray("clubs")?.let { a -> (0 until a.length()).map { strings(a.optJSONArray(it)).toSet() } }.orEmpty()
        val sources = o.optJSONArray("sources")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { s ->
                Source(s.optString("label"), s.optInt("rows"), if (s.has("sheet")) s.optDouble("sheet") else null, s.optDouble("read"))
            }
        }.orEmpty()
        return Plan(o.optLong("created", 0L), stops, strings(o.optJSONArray("noClub")).toSet(), clubs, sources)
    }

    fun save(c: Context, plan: Plan) {
        val o = JSONObject()
        o.put("created", plan.created)
        o.put("stops", JSONArray().apply {
            plan.stops.forEach { s ->
                put(JSONObject().put("code", s.code).put("name", s.name).put("vol", s.volume).put("wt", s.weight).put("box", s.boxes)
                    .apply { s.sheetVehicles?.let { put("veh", it) } }.put("circle", s.circle).put("remark", s.remark))
            }
        })
        o.put("noClub", JSONArray(plan.noClub.toList()))
        o.put("clubs", JSONArray().apply { plan.clubs.forEach { put(JSONArray(it.toList())) } })
        o.put("sources", JSONArray().apply {
            plan.sources.forEach { s ->
                put(JSONObject().put("label", s.label).put("rows", s.rows).put("read", s.readTotal).apply { s.sheetTotal?.let { put("sheet", it) } })
            }
        })
        p(c).edit().putString(KEY, o.toString()).apply()
    }

    /** Adds what a photo or file gave; a store already in the plan is replaced by the new line. */
    fun add(c: Context, label: String, r: SheetReader.Result): Plan {
        val now = load(c)
        val stops = SheetReader.dedupe(now.stops + r.stops)
        val plan = now.copy(
            created = if (now.stops.isEmpty()) System.currentTimeMillis() else now.created,
            stops = stops,
            sources = now.sources + Source(label, r.stops.size, r.sheetTotal, r.readTotal)
        )
        save(c, plan)
        return plan
    }

    fun clear(c: Context) = p(c).edit().remove(KEY).remove(KEY_FORCE).apply()

    fun types(c: Context): List<VehicleCap> {
        val a = try { JSONArray(p(c).getString(KEY_TYPES, null) ?: return VehiclePlanner.DEFAULT_TYPES) } catch (e: Exception) { return VehiclePlanner.DEFAULT_TYPES }
        val saved = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { VehicleCap(it.optString("name"), it.optDouble("cap", 0.0), it.optBoolean("on", true)) }
            .ifEmpty { return VehiclePlanner.DEFAULT_TYPES }
        if (p(c).getBoolean(KEY_MAX20, false)) return saved
        // Sizes saved earlier: 20 ft becomes the biggest once (22 ft / 24 ft can be ticked again)
        val off = VehiclePlanner.DEFAULT_TYPES.filter { !it.on }.map { it.name }.toSet()
        val now = saved.map { if (it.name in off) it.copy(on = false) else it }
        saveTypes(c, now)
        return now
    }

    fun saveTypes(c: Context, types: List<VehicleCap>) {
        p(c).edit().putString(KEY_TYPES, JSONArray().apply {
            types.forEach { put(JSONObject().put("name", it.name).put("cap", it.capacity).put("on", it.on)) }
        }.toString()).putBoolean(KEY_MAX20, true).apply()
    }

    fun clubBelow(c: Context): Double = p(c).getFloat(KEY_CLUB_BELOW, 300f).toDouble()
    fun setClubBelow(c: Context, v: Double) = p(c).edit().putFloat(KEY_CLUB_BELOW, v.toFloat()).apply()
    fun autoClub(c: Context): Boolean = p(c).getBoolean(KEY_AUTO, true)
    fun setAutoClub(c: Context, on: Boolean) = p(c).edit().putBoolean(KEY_AUTO, on).apply()

    /** Stores this close (km on the map) count as nearby for clubbing. */
    fun nearKm(c: Context): Double = p(c).getFloat(KEY_NEAR, 10f).toDouble()
    fun setNearKm(c: Context, v: Double) = p(c).edit().putFloat(KEY_NEAR, v.toFloat()).apply()

    /** Most stores on one vehicle when clubbing. */
    fun maxStops(c: Context): Int = p(c).getInt(KEY_MAX_STOPS, 2).coerceAtLeast(1)
    fun setMaxStops(c: Context, n: Int) = p(c).edit().putInt(KEY_MAX_STOPS, n.coerceIn(1, 6)).apply()

    private const val KEY_ZONE = "plan_zone_filter"
    private const val KEY_DC_CITY = "plan_dc_city"
    private const val KEY_DC_STATE = "plan_dc_state"
    private const val KEY_LOCAL_KM = "plan_local_km"

    /** The Local / Non-local / Upcountry filter chosen on the Plan tab; null = all vehicles. */
    fun zoneFilter(c: Context): Zone? = p(c).getString(KEY_ZONE, null)?.let { n -> Zone.values().firstOrNull { it.name == n } }
    fun setZoneFilter(c: Context, z: Zone?) = p(c).edit().putString(KEY_ZONE, z?.name).apply()

    /** The DC's local city as typed (blank = worked out from the DC's place, e.g. Isnapur -> Hyderabad). */
    fun dcCityTyped(c: Context): String = p(c).getString(KEY_DC_CITY, "").orEmpty()
    fun dcCity(c: Context): String = dcCityTyped(c).ifBlank { DispatchZone.dcCityLabel(StoreGeo.dcQuery(c)) }
    /** The DC's state as typed, else from the map search or its place name. */
    fun dcStateTyped(c: Context): String = p(c).getString(KEY_DC_STATE, "").orEmpty()
    fun dcState(c: Context): String? = DispatchZone.canonical(dcStateTyped(c))
        ?: DispatchZone.canonical(StoreGeo.dcState(c)) ?: DispatchZone.stateOf(StoreGeo.dcQuery(c))
    fun setDcCityState(c: Context, city: String, state: String) =
        p(c).edit().putString(KEY_DC_CITY, city.trim()).putString(KEY_DC_STATE, state.trim()).apply()
    /** A store with no city on the list counts as local within this many km of the DC. */
    fun localKm(c: Context): Double = p(c).getFloat(KEY_LOCAL_KM, 40f).toDouble()
    fun setLocalKm(c: Context, v: Double) = p(c).edit().putFloat(KEY_LOCAL_KM, v.toFloat()).apply()

    private const val KEY_MIN = "plan_zone_min_"
    private const val KEY_FORCE = "plan_force_json"

    /** Stores of a zone with less volume than this get no vehicle that day (Upcountry 200, Local 80 by default; 0 = none). */
    fun zoneMin(c: Context, z: Zone): Double = p(c).getFloat(KEY_MIN + z.name, when (z) {
        Zone.UPCOUNTRY -> 200f; Zone.LOCAL -> 80f; else -> 0f
    }).toDouble()
    fun setZoneMin(c: Context, z: Zone, v: Double) = p(c).edit().putFloat(KEY_MIN + z.name, v.coerceAtLeast(0.0).toFloat()).apply()

    /** Stores below their zone's minimum that are planned anyway (chosen by hand; cleared with a new plan). */
    fun forced(c: Context): Set<String> = try {
        JSONArray(p(c).getString(KEY_FORCE, null) ?: "[]").let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
    } catch (e: Exception) { emptySet() }
    fun setForced(c: Context, codes: Set<String>) = p(c).edit().putString(KEY_FORCE, JSONArray(codes.toList()).toString()).apply()

    private const val KEY_UP_STOPS = "plan_up_max_stops"

    /** The only vehicle used for Upcountry stores: the 20 ft (1109), with its capacity from Sizes. */
    fun upcountryType(c: Context): VehicleCap =
        (types(c).firstOrNull { it.name.startsWith("20 ft") } ?: VehiclePlanner.DEFAULT_TYPES.first { it.name.startsWith("20 ft") }).copy(on = true)
    /** Most stores on one Upcountry vehicle (route clubbing). */
    fun upcountryMaxStops(c: Context): Int = p(c).getInt(KEY_UP_STOPS, 3).coerceAtLeast(1)
    fun setUpcountryMaxStops(c: Context, n: Int) = p(c).edit().putInt(KEY_UP_STOPS, n.coerceIn(1, 6)).apply()

    /** Local / Non-local / Upcountry for each store of the plan (by store code). */
    fun zones(c: Context, stops: List<PlanStop>, places: Map<String, LatLon>, dc: LatLon?): Map<String, Zone> {
        val mapStates = StoreGeo.states(c)
        val cities = DispatchZone.dcCityNames(StoreGeo.dcQuery(c), dcCity(c))
        val state = dcState(c)
        val near = localKm(c)
        return stops.associate { s ->
            val km = places[s.code]?.let { at -> dc?.let { VehiclePlanner.km(it, at) } }
            s.code to DispatchZone.zone(s, DispatchZone.storeState(s, mapStates[s.code]), cities, state, km, near)
        }
    }

    fun options(c: Context, plan: Plan) = VehiclePlanner.Options(
        types = types(c), clubBelow = clubBelow(c), autoClub = autoClub(c), maxStops = maxStops(c), noClub = plan.noClub, clubs = plan.clubs,
        places = StoreGeo.places(c), dc = StoreGeo.dc(c), nearKm = nearKm(c)
    )

    /** City and region of each store from the store list (by store code). */
    fun withPlaces(c: Context, stops: List<PlanStop>): List<PlanStop> {
        val byCode = MasterData.get(c).stores.associateBy { it.code.trim() }
        return stops.map { s -> byCode[s.code]?.let { st -> s.copy(city = st.city.trim(), region = st.region.trim(), listName = st.name.trim()) } ?: s }
    }

    fun dayLabel(t: Long): String = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(if (t > 0) t else System.currentTimeMillis()))

    /** Reads the words on a sheet photo with the phone's text reader (Google Play services). */
    fun readPhoto(c: Context, uri: Uri, done: (List<OcrWord>?, Exception?) -> Unit) {
        val image = try { InputImage.fromFilePath(c, uri) } catch (e: Exception) { done(null, e); return }
        val reader = try { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) } catch (e: Exception) { done(null, e); return }
        try {
            reader.process(image)
                .addOnSuccessListener { text ->
                    val words = ArrayList<OcrWord>()
                    for (block in text.textBlocks) for (line in block.lines) for (el in line.elements) {
                        val b = el.boundingBox ?: continue
                        words += OcrWord(el.text, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), line.angle)
                    }
                    done(words, null)
                }
                .addOnFailureListener { done(null, it) }
                .addOnCompleteListener { try { reader.close() } catch (e: Exception) { } }
        } catch (e: Exception) {
            done(null, e)
        }
    }
}
