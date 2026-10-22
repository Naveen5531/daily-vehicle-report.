package com.logistics.vehiclereport

import kotlin.math.abs

/** One store's line on the vehicle request sheet ("VEHICLE REQ"), with its city and region from the store list. */
data class PlanStop(
    val code: String,
    val name: String,
    val volume: Double,
    val weight: Double = 0.0,
    val boxes: Int = 0,
    /** Vehicles written on the sheet (the planner's own count), if any. */
    val sheetVehicles: Int? = null,
    val circle: String = "",
    val remark: String = "",
    val city: String = "",
    val region: String = "",
    /** The store's name in the store list (cleaner than a name read from a photo), if it is there. */
    val listName: String = ""
) {
    /** Stores share a vehicle only inside one cluster: the store list's region, else the sheet's circle. */
    val cluster: String get() = region.ifBlank { circle }.ifBlank { "Other" }
    val shownName: String get() = listName.ifBlank { name }
}

/** A place on the map (degrees). */
data class LatLon(val lat: Double, val lon: Double)

/** A vehicle size and the most volume it carries; [on] = used in plans. */
data class VehicleCap(val name: String, val capacity: Double, val on: Boolean = true)

/**
 * One vehicle of the plan: its size and what it carries (whole stores, or a big store's part).
 * [routeKm]: DC to every stop, shortest order (straight-line km); [extraKm]: what the extra stops add to going straight
 * to the farthest one. Null when a place is not known.
 */
data class PlanLoad(
    val cluster: String, val parts: List<Pair<PlanStop, Double>>, val type: VehicleCap?, val clubbed: Boolean,
    val routeKm: Double? = null, val extraKm: Double? = null
) {
    val volume: Double get() = parts.sumOf { it.second }
    val fill: Double get() = if (type == null || type.capacity <= 0) 0.0 else volume / type.capacity
    val codes: List<String> get() = parts.map { it.first.code }.distinct()
}

/** The plan: every vehicle, how many of each size, and the volume moved. */
data class PlanResult(val loads: List<PlanLoad>, val byType: List<Pair<VehicleCap, Int>>, val totalVolume: Double) {
    val vehicles: Int get() = loads.size
    val fill: Double get() {
        val cap = loads.sumOf { it.type?.capacity ?: 0.0 }
        return if (cap <= 0) 0.0 else totalVolume / cap
    }
}

/**
 * Works out the vehicles for a day's volume:
 *  1. every store gets full loads of the biggest vehicle while its volume is more than that vehicle carries,
 *     and one more load for the rest;
 *  2. stores chosen by hand to share a vehicle go together;
 *  3. with clubbing on, a small load (below [Options.clubBelow]) rides with another load only when the stores are near each
 *     other or on the way of the other's vehicle from the DC (it adds little to that trip, see [fitsOnRoute]), and they
 *     fit in one vehicle, with at most [Options.maxStops] stores on it: the partner with the least extra km is taken, then
 *     the least bigger vehicle, then the fuller one.
 *     Without map places, only stores of the same town (same region and a shared place name) are clubbed;
 *  4. every load gets the smallest vehicle that carries it.
 */
object VehiclePlanner {

    /** 20 ft is the biggest vehicle unless 22 ft / 24 ft are ticked in Sizes. */
    val DEFAULT_TYPES = listOf(
        VehicleCap("24 ft", 1070.0, on = false),
        VehicleCap("22 ft", 1010.0, on = false),
        VehicleCap("20 ft (1109)", 850.0),
        VehicleCap("17 ft (909)", 620.0),
        VehicleCap("14 ft (709)", 400.0),
        VehicleCap("407", 280.0),
        VehicleCap("Pickup", 180.0)
    )

    class Options(
        val types: List<VehicleCap> = DEFAULT_TYPES,
        /** Loads below this volume may share a vehicle with others of the same cluster. */
        val clubBelow: Double = 300.0,
        val autoClub: Boolean = true,
        /** Most stores one vehicle delivers to (clubbed: 2 by default). */
        val maxStops: Int = 2,
        /** Stores kept on their own vehicle (a club was split by hand). */
        val noClub: Set<String> = emptySet(),
        /** Stores that share one vehicle, chosen by hand. */
        val clubs: List<Set<String>> = emptyList(),
        /** Where each store is (by store code), and the DC the vehicles leave from. */
        val places: Map<String, LatLon> = emptyMap(),
        val dc: LatLon? = null,
        /** Stores this close (km) count as nearby; on longer trips an extra stop may add up to [detourShare] of the trip. */
        val nearKm: Double = 10.0,
        val detourShare: Double = 0.08,
        /** Never more than this many km out of the way for a clubbed stop (unless [nearKm] is set higher). */
        val maxDetourKm: Double = 30.0
    )

    /** Straight-line km between two places. */
    fun km(a: LatLon, b: LatLon): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)))
    }

    /** Shortest trip from the DC through all [pts] (any order; up to a few stops) and the farthest stop's distance. */
    private fun trip(dc: LatLon, pts: List<LatLon>): Pair<Double, Double> {
        fun perms(l: List<LatLon>): List<List<LatLon>> = if (l.size <= 1) listOf(l) else l.flatMap { x -> perms(l - x).map { listOf(x) + it } }
        val best = perms(pts.distinct()).minOf { order ->
            var at = dc; var d = 0.0
            for (p in order) { d += km(at, p); at = p }
            d
        }
        return best to pts.maxOf { km(dc, it) }
    }

    /**
     * Can the stores [add] ride on a vehicle that already goes to [host]? With the DC and every store on the map: yes when
     * they add at most [Options.nearKm] km, or [Options.detourShare] of the vehicle's trip, to it: nearby stores, or stores
     * on the way. Never a store beyond the vehicle's last stop or off its road. With places but no DC: each within
     * [Options.nearKm] of a stop of the vehicle. Without places: same region and a shared town name.
     */
    fun fitsOnRoute(host: List<PlanStop>, add: List<PlanStop>, o: Options): Boolean {
        val all = host + add
        if (all.map { it.code }.distinct().size <= 1) return true
        val hp = host.map { o.places[it.code] }
        val ap = add.map { o.places[it.code] }
        if ((hp + ap).all { it != null }) {
            val h = hp.filterNotNull(); val a = ap.filterNotNull()
            val dc = o.dc
            if (dc != null) {
                val before = trip(dc, h).first
                val after = trip(dc, h + a).first
                return after - before <= maxOf(o.nearKm, minOf(o.maxDetourKm, o.detourShare * before))
            }
            return a.all { x -> h.any { y -> km(x, y) <= o.nearKm } }
        }
        if (all.map { it.cluster }.distinct().size > 1) return false
        val words = all.map { places(it) }
        return words.all { w -> w.isNotEmpty() && words.all { x -> x.any { it in w } } }
    }

    /** Km a vehicle runs from the DC through all its stops, and what the stops other than its main store add; null when a place is unknown. */
    fun routeKm(parts: List<Pair<PlanStop, Double>>, o: Options): Pair<Double, Double>? {
        val dc = o.dc ?: return null
        val pts = parts.map { it.first.code }.distinct().map { o.places[it] ?: return null }
        if (pts.isEmpty()) return null
        val main = parts.maxByOrNull { it.second }?.first?.code?.let { o.places[it] } ?: return null
        val total = trip(dc, pts).first
        return total to (total - trip(dc, listOf(main)).first)
    }

    private const val EPS = 1e-6

    val EXPORT_WIDTHS = listOf(9, 18, 12, 14, 10, 12, 8, 7, 10, 36, 22, 14, 14, 13, 8, 12, 14, 9, 11, 11)

    /**
     * The plan as an Excel sheet: one line per store on each vehicle, then the vehicles needed by size. Trip km (DC to all
     * stops, straight line) and what the clubbed stops add, when the stores are on the map.
     */
    fun exportRows(r: PlanResult, zoneOf: (PlanLoad) -> String = { "" }): List<List<String>> {
        fun n(v: Double) = "%.3f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
        val rows = mutableListOf(listOf("Vehicle", "Region", "Zone", "Vehicle Type", "Capacity", "Load Volume", "Fill %", "Stops",
            "Ship-To", "Name of the ship-to party", "City", "Circle", "Volume on Vehicle", "Store Volume", "Boxes", "Total Wght", "Remark", "Clubbed",
            "Trip km", "Extra km"))
        r.loads.forEachIndexed { i, l ->
            for ((s, part) in l.parts) {
                rows += listOf((i + 1).toString(), l.cluster, zoneOf(l), l.type?.name ?: "Too big", l.type?.let { n(it.capacity) }.orEmpty(), n(l.volume),
                    "${Math.round(l.fill * 100)}", l.codes.size.toString(), s.code, s.shownName, s.city, s.circle, n(part), n(s.volume),
                    s.boxes.toString(), n(s.weight), s.remark, if (l.clubbed) "Yes" else "",
                    l.routeKm?.let { Math.round(it).toString() }.orEmpty(),
                    if (l.clubbed) l.extraKm?.let { Math.round(it).toString() }.orEmpty() else "")
            }
        }
        rows += List(20) { "" }
        rows += listOf("Vehicle Type", "Vehicles", "Capacity", "Total Capacity") + List(16) { "" }
        for ((t, c) in r.byType) rows += listOf(t.name, c.toString(), n(t.capacity), n(t.capacity * c)) + List(16) { "" }
        rows += listOf("TOTAL", r.vehicles.toString(), "", n(r.loads.sumOf { it.type?.capacity ?: 0.0 }), n(r.totalVolume), "${Math.round(r.fill * 100)}") + List(14) { "" }
        return rows
    }

    /** Smallest vehicle in use that carries [volume]; null when none does. */
    private val NOISE = setOf("dmart", "mart", "road", "centre", "center", "circle", "mall", "west", "east", "north", "south",
        "colony", "nagar", "andhra", "pradesh", "andhrapradesh", "telangana", "district", "limited", "square", "near", "main", "store")
    private val ALIAS = mapOf("vizag" to "visakhapatnam", "hyd" to "hyderabad", "hydrabad" to "hyderabad", "vijaywada" to "vijayawada",
        "rjy" to "rajahmundry", "rajamundry" to "rajahmundry", "bangalore" to "bengaluru", "blr" to "bengaluru", "gazuvaka" to "gajuwaka")

    /** Place words of a store (city, area): two stores sharing one are in the same town ("… Rajahmundry AP DMart"). */
    fun places(s: PlanStop): Set<String> = "${s.name} ${s.listName} ${s.city}".lowercase().replace("d-mart", " ")
        .split(Regex("[^a-z]+")).map { ALIAS[it] ?: it }.filter { it.length >= 4 && it !in NOISE }.toSet()

    fun fit(volume: Double, types: List<VehicleCap>): VehicleCap? =
        types.filter { it.on && it.capacity > 0 && it.capacity >= volume - EPS }.minByOrNull { it.capacity }

    private class Load(val cluster: String, val parts: MutableList<Pair<PlanStop, Double>>, var clubbed: Boolean = false, val full: Boolean = false) {
        val volume get() = parts.sumOf { it.second }
        val codes get() = parts.map { it.first.code }.toSet()
    }

    fun plan(stops: List<PlanStop>, o: Options = Options()): PlanResult {
        val types = o.types.filter { it.on && it.capacity > 0 }.sortedByDescending { it.capacity }
        val valid = stops.filter { it.volume > EPS }
        if (types.isEmpty()) {
            return PlanResult(valid.map { PlanLoad(it.cluster, listOf(it to it.volume), null, false) }, emptyList(), valid.sumOf { it.volume })
        }
        val max = types.first().capacity
        val loads = mutableListOf<Load>()
        // The rest of each store (after full loads): the part that can share a vehicle.
        val rest = LinkedHashMap<String, Load>()
        for (s in valid) {
            var left = s.volume
            while (left > max + EPS) {
                loads += Load(s.cluster, mutableListOf(s to max), full = true)
                left -= max
            }
            val l = Load(s.cluster, mutableListOf(s to left))
            loads += l
            rest[s.code] = l
        }

        // Hand-made clubs: the stores' rests go together (as many as fit in the biggest vehicle, up to the most stores).
        val inHandClub = HashSet<String>()
        for (club in o.clubs) {
            val members = club.mapNotNull { rest[it] }.filter { it in loads }.sortedByDescending { it.volume }
            if (members.size < 2) continue
            val host = members.first()
            for (m in members.drop(1)) {
                if (host.volume + m.volume > max + EPS || (host.codes + m.codes).size > o.maxStops) continue
                host.parts += m.parts
                loads.remove(m)
                host.clubbed = true
            }
            inHandClub += host.codes
        }

        if (o.autoClub) {
            fun capOf(v: Double) = fit(v, types)?.capacity ?: Double.MAX_VALUE
            val candidates = loads.filter { l ->
                !l.full && l.parts.size == 1 && l.volume < o.clubBelow - EPS &&
                    l.parts[0].first.code !in o.noClub && l.parts[0].first.code !in inHandClub
            }.sortedByDescending { it.volume }
            for (c in candidates) {
                if (c !in loads) continue
                // How far out of its way the vehicle goes, and how different its trip is from the store's own distance
                // (a city store rides with a city vehicle before a long-distance one that passes by).
                fun cost(l: Load): Double {
                    val dc = o.dc ?: return 0.0
                    val h = l.parts.map { o.places[it.first.code] ?: return 0.0 }
                    val a = c.parts.map { o.places[it.first.code] ?: return 0.0 }
                    val before = trip(dc, h)
                    val added = trip(dc, h + a).first - before.first
                    return added + 0.15 * abs(before.second - a.maxOf { km(dc, it) })
                }
                val best = loads.filter { other ->
                    other !== c && !other.full && other.volume >= c.volume - EPS &&
                        other.codes.none { it in o.noClub } && other.codes.none { it in inHandClub } &&
                        (other.codes + c.codes).size <= o.maxStops && other.volume + c.volume <= max + EPS &&
                        fitsOnRoute(other.parts.map { it.first }, c.parts.map { it.first }, o)
                }.minWithOrNull(
                    // least out of the way (in 5 km steps), then the smallest step up in vehicle size, then the fuller vehicle
                    compareBy<Load> { Math.floor(cost(it) / 5.0) }
                        .thenBy { capOf(it.volume + c.volume) - capOf(it.volume) }
                        .thenByDescending { (it.volume + c.volume) / capOf(it.volume + c.volume) }
                ) ?: continue
                best.parts += c.parts
                best.clubbed = true
                loads.remove(c)
            }
        }

        // Clusters in the order their first store comes on the sheet; inside one, the fullest loads first.
        val order = valid.map { it.cluster }.distinct().withIndex().associate { it.value to it.index }
        val out = loads.map { l ->
            val route = routeKm(l.parts, o)
            PlanLoad(l.cluster, l.parts.toList(), fit(l.volume, types), l.clubbed, route?.first, route?.second)
        }
            .sortedWith(compareBy<PlanLoad> { order[it.cluster] ?: Int.MAX_VALUE }.thenByDescending { it.volume })
        val byType = types.map { t -> t to out.count { it.type == t } }.filter { it.second > 0 }
        return PlanResult(out, byType, valid.sumOf { it.volume })
    }
}
