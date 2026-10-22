package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Test

class DispatchZoneTest {
    private val cities = DispatchZone.dcCityNames("Isnapur")
    private fun z(s: PlanStop, mapState: String? = null, km: Double? = null) =
        DispatchZone.zone(s, DispatchZone.storeState(s, mapState), cities, "Telangana", km, 40.0)

    @Test fun isnapurDcServesHyderabadAsLocal() {
        assertEquals("Hyderabad", DispatchZone.dcCityLabel("Isnapur"))
        assertEquals("Telangana", DispatchZone.stateOf("Isnapur"))
        assertEquals(Zone.LOCAL, z(PlanStop("5007", "Kukatpally", 300.0, city = "Hyderabad")))
        assertEquals(Zone.LOCAL, z(PlanStop("5010", "DMart Miyapur", 300.0)))
    }

    @Test fun sameStateOtherCityIsNonLocal() {
        assertEquals(Zone.NON_LOCAL, z(PlanStop("5042", "Warangal", 300.0, city = "Warangal")))
        assertEquals(Zone.NON_LOCAL, z(PlanStop("5050", "Some Store", 300.0, city = "Bhupalpally"), mapState = "Telangana"))
    }

    @Test fun otherStateIsUpcountry() {
        assertEquals(Zone.UPCOUNTRY, z(PlanStop("5100", "Raichur", 300.0, city = "Raichur")))
        assertEquals(Zone.UPCOUNTRY, z(PlanStop("5101", "Danavaipeta Rajahmundry AP DMart", 300.0)))
        assertEquals(Zone.UPCOUNTRY, z(PlanStop("5102", "X", 300.0, city = "Somewhere"), mapState = "Andhra Pradesh"))
    }

    @Test fun unknownWithoutStateAndKmFallbackForLocal() {
        assertEquals(Zone.UNKNOWN, z(PlanStop("5200", "Unknown Place", 300.0)))
        assertEquals(Zone.LOCAL, z(PlanStop("5201", "Unknown Place", 300.0), km = 22.0))
    }

    @Test fun vehicleTakesItsFarthestStopsZone() {
        assertEquals(Zone.UPCOUNTRY, DispatchZone.ofLoad(listOf(Zone.LOCAL, Zone.UPCOUNTRY)))
        assertEquals(Zone.NON_LOCAL, DispatchZone.ofLoad(listOf(Zone.LOCAL, Zone.NON_LOCAL)))
    }

    @Test fun smallUpcountryAndLocalStoresAreHeldBack() {
        val stops = listOf(PlanStop("U1", "a", 150.0), PlanStop("U2", "b", 250.0), PlanStop("L1", "c", 60.0),
            PlanStop("L2", "d", 90.0), PlanStop("N1", "e", 20.0))
        val zones = mapOf("U1" to Zone.UPCOUNTRY, "U2" to Zone.UPCOUNTRY, "L1" to Zone.LOCAL, "L2" to Zone.LOCAL, "N1" to Zone.NON_LOCAL)
        val min = { z: Zone -> when (z) { Zone.UPCOUNTRY -> 200.0; Zone.LOCAL -> 80.0; else -> 0.0 } }
        assertEquals(setOf("U1", "L1"), DispatchZone.held(stops, zones, min))
        assertEquals(setOf("L1"), DispatchZone.held(stops, zones, min, force = setOf("U1")))
    }
}
