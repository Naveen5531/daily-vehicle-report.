package com.logistics.vehiclereport

import android.content.Context

/**
 * Details written about a vehicle in WhatsApp posts: vehicle size, driver name, driver mobile,
 * vendor (spelled as in the vendor list) and store (as in the store list, else the place as written).
 * The latest post that has a value wins. Posts whose 4 digits fit several vehicles are ignored,
 * since they cannot say which vehicle they were about.
 */
object PostedDetails {

    /** [posts] must be the vehicle's own posts, latest first. */
    fun of(context: Context, number: String, posts: List<VehicleRecord>): DedicatedVehicle {
        val m = MasterData.get(context)
        val sure = posts.filter { it.match != MatchType.MULTI }
        fun pick(f: (VehicleRecord) -> String) = sure.firstOrNull { f(it).isNotBlank() }?.let(f)?.trim().orEmpty()
        val vendor = sure.firstOrNull { it.vendor.isNotBlank() }
            ?.let { m.resolveVendor(it.vendor, it.message, it.chat, "")?.name ?: it.vendor.trim() }.orEmpty()
        val city = pick { it.city }
        val store = m.matchStore(city)?.store
        return DedicatedVehicle(
            number = number,
            type = pick { it.size },
            driver = pick { it.driver },
            mobile = pick { it.mobile },
            transporter = vendor,
            route = store?.let { "${it.code} ${it.name}" } ?: city
        )
    }

    /** Posted details for each of [vehicles] that has posts. */
    fun forVehicles(context: Context, vehicles: List<DedicatedVehicle>, records: List<VehicleRecord>): Map<String, DedicatedVehicle> {
        if (vehicles.isEmpty()) return emptyMap()
        val wanted = vehicles.map { it.number }.toHashSet()
        val byVehicle = HashMap<String, MutableList<VehicleRecord>>()
        for (r in records) {
            if (r.match == MatchType.MULTI || r.match == MatchType.CODE) continue
            for (c in r.candidates.ifEmpty { listOf(r.vehicle) }) if (c in wanted) byVehicle.getOrPut(c) { ArrayList() } += r
        }
        return byVehicle.mapValues { (num, posts) -> of(context, num, posts.sortedByDescending { it.time }) }
    }

    /** Fills only the empty details of [v] with [p]; what you typed or imported is never replaced. */
    fun fillBlanks(v: DedicatedVehicle, p: DedicatedVehicle) = v.copy(
        type = v.type.ifBlank { p.type },
        driver = v.driver.ifBlank { p.driver },
        mobile = v.mobile.ifBlank { p.mobile },
        transporter = v.transporter.ifBlank { p.transporter },
        route = v.route.ifBlank { p.route }
    )

    fun hasBlanks(v: DedicatedVehicle) =
        v.type.isBlank() || v.driver.isBlank() || v.mobile.isBlank() || v.transporter.isBlank() || v.route.isBlank()
}
