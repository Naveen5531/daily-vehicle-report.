package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vehicles by volume: 24 ft 1070, 22 ft 1010, 20 ft 850, 17 ft 620, 14 ft 400, 407 280, pickup 180; 20 ft is the biggest
 * unless 22 ft / 24 ft are ticked.
 * Clubbing only nearby or on the way: places are rough map points of real towns, the DC at Isnapur.
 */
class VehiclePlannerTest {
    private fun s(code: String, v: Double, name: String = "Store $code", region: String = "R1") = PlanStop(code, name, v, region = region)

    private val dc = LatLon(17.550, 78.250)                    // Isnapur
    private val at = mapOf(
        "CHM" to LatLon(17.345, 78.515),                       // Champapet, Hyderabad
        "KCG" to LatLon(17.390, 78.500),                       // Kachiguda, Hyderabad (~5 km away)
        "WGL" to LatLon(17.970, 79.590),                       // Warangal
        "VJA" to LatLon(16.510, 80.640),                       // Vijayawada
        "KDD" to LatLon(16.990, 79.970),                       // Kodad: on the road to Vijayawada
        "GJW" to LatLon(17.690, 83.210),                       // Gajuwaka, Vizag
        "SHL" to LatLon(17.710, 83.200),                       // Sheelanagar, Vizag
        "VPG" to LatLon(17.740, 83.230),                       // Vepagunta, Vizag
        "CTC" to LatLon(20.460, 85.880),                       // Cuttack: far past Vizag
        "HMB" to LatLon(17.770, 77.130)                        // Humnabad: the other way
    )
    private fun geo(clubBelow: Double = 300.0) = VehiclePlanner.Options(places = at, dc = dc, clubBelow = clubBelow)
    /** Every size ticked, 22 ft and 24 ft too. */
    private val all = VehiclePlanner.DEFAULT_TYPES.map { it.copy(on = true) }

    @Test fun twentyFeetIsTheBiggestUnlessBiggerSizesAreTicked() {
        val r = VehiclePlanner.plan(listOf(s("5222", 1975.592), s("5034", 1033.391)), VehiclePlanner.Options(autoClub = false))
        assertEquals(listOf("20 ft (1109)", "20 ft (1109)", "407"), r.loads.filter { "5222" in it.codes }.map { it.type?.name })
        assertEquals(listOf("20 ft (1109)", "407"), r.loads.filter { "5034" in it.codes }.map { it.type?.name })
        assertTrue(r.loads.none { it.type!!.capacity > 850 })
        val ticked = VehiclePlanner.plan(listOf(s("5222", 1975.592)), VehiclePlanner.Options(types = all, autoClub = false))
        assertEquals(listOf("24 ft", "22 ft"), ticked.loads.map { it.type?.name })
    }

    @Test fun smallestVehicleThatCarriesTheVolume() {
        val r = VehiclePlanner.plan(listOf(s("1", 1012.6), s("2", 947.9), s("3", 586.3), s("4", 345.6), s("5", 250.0), s("6", 150.0)),
            VehiclePlanner.Options(types = all, autoClub = false))
        assertEquals(listOf("24 ft", "22 ft", "17 ft (909)", "14 ft (709)", "407", "Pickup"), r.loads.map { it.type?.name })
    }

    @Test fun bigStoreGetsFullLoadsThenTheRest() {
        val r = VehiclePlanner.plan(listOf(s("5222", 1975.592)), VehiclePlanner.Options(types = all, autoClub = false))
        assertEquals(2, r.vehicles)
        assertEquals(listOf("24 ft", "22 ft"), r.loads.map { it.type?.name })
        assertEquals(1975.592, r.loads.sumOf { it.volume }, 1e-6)
    }

    @Test fun nearbyStoresShareAVehicle() {
        val r = VehiclePlanner.plan(listOf(s("CHM", 634.9), s("KCG", 205.5), s("WGL", 175.5)), geo())
        assertEquals(2, r.vehicles)
        val hyd = r.loads.first { "CHM" in it.codes }
        assertEquals(setOf("CHM", "KCG"), hyd.codes.toSet())
        assertEquals("20 ft (1109)", hyd.type?.name)
        assertTrue(hyd.clubbed)
        assertTrue("small detour: ${hyd.extraKm}", hyd.extraKm!! < 10)
        // Warangal is 100+ km away from both: its own vehicle
        assertEquals(listOf("WGL"), r.loads.first { "WGL" in it.codes }.codes)
    }

    @Test fun storeOnTheWayRidesAlong() {
        val r = VehiclePlanner.plan(listOf(s("VJA", 800.0), s("KDD", 23.58)), geo())
        assertEquals(1, r.vehicles)
        val l = r.loads.single()
        assertEquals("20 ft (1109)", l.type?.name)
        assertTrue("Kodad is on the way: ${l.extraKm}", l.extraKm!! < 15)
    }

    @Test fun farOrOtherWayStoresAreNotClubbed() {
        // Cuttack is far past Vizag, Humnabad is the other way from the DC: each goes alone though both are tiny
        val r = VehiclePlanner.plan(listOf(s("GJW", 600.0), s("CTC", 12.0), s("HMB", 59.0), s("VJA", 400.0)), geo())
        assertEquals(4, r.vehicles)
        assertTrue(r.loads.none { it.clubbed })
    }

    @Test fun cityStoreRidesWithTheCityVehicleNotAPassingLongTrip() {
        // A vehicle to Vijayawada passes Hyderabad, but Kachiguda goes with the Champapet vehicle
        val r = VehiclePlanner.plan(listOf(s("VJA", 800.0), s("CHM", 500.0), s("KCG", 100.0)), geo())
        assertEquals(setOf("CHM", "KCG"), r.loads.first { "KCG" in it.codes }.codes.toSet())
        assertEquals(listOf("VJA"), r.loads.first { "VJA" in it.codes }.codes)
    }

    @Test fun withoutMapPlacesOnlyTheSameTownIsClubbed() {
        val r = VehiclePlanner.plan(listOf(
            s("5026", 800.0, "Gajuwaka Vizag"), s("5063", 23.58, "Sheelanagar Visakhapatnam"),
            s("5304", 541.1, "Benz Circle Vijayawada"), s("5302", 11.84, "Kodad")
        ))
        assertEquals(3, r.vehicles)
        assertEquals(setOf("5026", "5063"), r.loads.first { it.clubbed }.codes.toSet())
        assertEquals("20 ft (1109)", r.loads.first { it.clubbed }.type?.name)
        assertEquals(listOf("5302"), r.loads.first { "5302" in it.codes }.codes)
    }

    @Test fun storesOfOtherRegionsWithoutPlacesStayApart() {
        val r = VehiclePlanner.plan(listOf(s("A", 600.0, "Gajuwaka Vizag", "AP 1"), s("B", 40.0, "Sheelanagar Vizag", "AP 2")))
        assertEquals(2, r.vehicles)
    }

    @Test fun storeMissingFromTheMapIsNotClubbedByDistance() {
        // KCG has no place: no km check is possible, and the names share no town, so it goes alone
        val r = VehiclePlanner.plan(listOf(s("CHM", 600.0, "Champapet"), s("KCG", 205.5, "Kachiguda")),
            VehiclePlanner.Options(places = at - "KCG", dc = dc))
        assertEquals(2, r.vehicles)
        assertFalse(r.loads.any { it.clubbed })
    }

    @Test fun twoStoresPerVehicleAtMost() {
        val stops = listOf(s("GJW", 600.0), s("SHL", 30.0), s("VPG", 20.0))
        val r = VehiclePlanner.plan(stops, geo())
        assertEquals(2, r.vehicles)
        assertTrue(r.loads.all { it.codes.size <= 2 })
        assertEquals(setOf("GJW", "SHL"), r.loads.first { it.clubbed }.codes.toSet())
        // with 3 allowed, all three go on one vehicle
        assertEquals(1, VehiclePlanner.plan(stops, VehiclePlanner.Options(places = at, dc = dc, maxStops = 3)).vehicles)
        // a hand club of three keeps only two together
        val hand = VehiclePlanner.plan(stops, VehiclePlanner.Options(autoClub = false, clubs = listOf(setOf("GJW", "SHL", "VPG"))))
        assertEquals(2, hand.vehicles)
    }

    @Test fun splitAndHandClubsAreKept() {
        val stops = listOf(s("CHM", 205.5), s("KCG", 175.5), s("HMB", 600.0))
        val split = VehiclePlanner.plan(stops, VehiclePlanner.Options(places = at, dc = dc, noClub = setOf("CHM", "KCG", "HMB")))
        assertEquals(3, split.vehicles)
        // a club made by hand is kept even when the stores are far apart
        val hand = VehiclePlanner.plan(stops, VehiclePlanner.Options(autoClub = false, clubs = listOf(setOf("CHM", "HMB"))))
        assertEquals(2, hand.vehicles)
        assertEquals(setOf("CHM", "HMB"), hand.loads.first { it.clubbed }.codes.toSet())
    }

    @Test fun sizeTurnedOffIsNotUsed() {
        val types = all.map { if (it.name == "24 ft") it.copy(on = false) else it }
        val r = VehiclePlanner.plan(listOf(s("1", 1050.0)), VehiclePlanner.Options(types = types, autoClub = false))
        assertEquals(listOf("22 ft", "Pickup"), r.loads.map { it.type?.name })
    }

    @Test fun exportHasOneLinePerStoreTripKmAndTotals() {
        val r = VehiclePlanner.plan(listOf(s("VJA", 932.441), s("KDD", 23.58), s("CHM", 1975.592)), geo())
        val rows = VehiclePlanner.exportRows(r)
        assertEquals(VehiclePlanner.EXPORT_WIDTHS.size, rows.first().size)
        rows.forEach { assertEquals(rows.first().size, it.size) }
        assertEquals("Zone", rows.first()[2])
        assertEquals("Trip km", rows.first()[18])
        val kodad = rows.first { it[8] == "KDD" }
        assertTrue(kodad[18].toInt() > 200)          // Isnapur to Vijayawada, straight line
        assertTrue(kodad[19].isNotEmpty())           // what the clubbed stop adds
        assertEquals("TOTAL", rows.last()[0])
        assertEquals(r.vehicles.toString(), rows.last()[1])
    }
}
