package com.logistics.vehiclereport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageParserTest {

    private val dedicated = listOf("TS09UB1234", "AP39TV5678", "TS07UC4521", "MH12AB4521", "22BH9090AA")
    private val byLast4 = dedicated.groupBy { VehicleParser.last4(it) }
    private fun parse(t: String) = MessageParser.parse(t, byLast4, dedicated.toSet())

    @Test fun last4WithCity() {
        val c = parse("1234 Hyderabad").captures.single()
        assertEquals(listOf("TS09UB1234"), c.candidates)
        assertEquals("1234", c.typed)
        assertEquals("Hyderabad", c.city)
        assertEquals("", c.replacement)
    }

    @Test fun fullNumberWithCity() {
        val c = parse("TS09UB1234 Vijayawada").captures.single()
        assertEquals(listOf("TS09UB1234"), c.candidates)
        assertTrue(c.full)
        assertEquals("Vijayawada", c.city)
    }

    @Test fun spacedFullNumber() {
        val c = parse("TS 09 UB 1234 reached Secunderabad").captures.single()
        assertEquals("TS09UB1234", c.typed)
        assertEquals("Secunderabad", c.city)
    }

    @Test fun mobileGluedToPlace() {
        val c = parse("TS09UB1234 GAJUWAKA VIZAG+919908764329\nMisheetha logistics").captures.single()
        assertEquals("9908764329", c.mobile)
        assertEquals("Gajuwaka Vizag", c.city)
    }

    @Test fun cityBeforeNumber() {
        assertEquals("Hyderabad", parse("Reached Hyderabad 1234").captures.single().city)
    }

    @Test fun timeIsNotACode() {
        val r = parse("1234 reached at 10:30 Hyderabad")
        assertEquals("Hyderabad", r.captures.single().city)
        assertTrue(r.unmatchedCodes.isEmpty())
    }

    @Test fun severalLines() {
        val r = parse("1234 Hyd\n5678 Guntur").captures
        assertEquals(listOf("TS09UB1234", "AP39TV5678"), r.map { it.candidates.single() })
        assertEquals(listOf("Hyd", "Guntur"), r.map { it.city })
    }

    @Test fun severalOnOneLine() {
        val r = parse("1234 Hyderabad, 5678 Vijayawada").captures
        assertEquals(listOf("Hyderabad", "Vijayawada"), r.map { it.city })
    }

    @Test fun replacementAfter() {
        val c = parse("1234 replacement TS08XY9999 Hyderabad").captures.single()
        assertEquals(listOf("TS09UB1234"), c.candidates)
        assertEquals("TS08XY9999", c.replacement)
        assertEquals("Hyderabad", c.city)
    }

    @Test fun replacementInPlaceOf() {
        val c = parse("TS08XY9999 in place of 1234 - Pune").captures.single()
        assertEquals(listOf("TS09UB1234"), c.candidates)
        assertEquals("TS08XY9999", c.replacement)
        assertEquals("Pune", c.city)
    }

    @Test fun replacementVehicleFor() {
        val c = parse("Replacement vehicle KA01AB7777 for 5678 at Guntur").captures.single()
        assertEquals(listOf("AP39TV5678"), c.candidates)
        assertEquals("KA01AB7777", c.replacement)
        assertEquals("Guntur", c.city)
    }

    @Test fun replacementWithFullDedicated() {
        val c = parse("AP39TV5678 replaced by KA01AB7777 Ongole").captures.single()
        assertEquals(listOf("AP39TV5678"), c.candidates)
        assertEquals("KA01AB7777", c.replacement)
        assertEquals("Ongole", c.city)
    }

    @Test fun unknownCode() {
        val r = parse("3333 Hyderabad")
        assertEquals(listOf("3333"), r.unmatchedCodes)
        // still counted as a reported vehicle, marked as not in the list
        val c = r.captures.single()
        assertEquals("3333", c.typed)
        assertEquals("Hyderabad", c.city)
        assertTrue(!c.inList)
    }

    @Test fun sharedLast4() {
        assertEquals(2, parse("4521 Pune").captures.single().candidates.size)
    }

    @Test fun bharatSeries() {
        assertEquals(listOf("22BH9090AA"), parse("9090 Chennai").captures.single().candidates)
    }

    @Test fun noVehicle() {
        val r = parse("Good morning all")
        assertTrue(r.captures.isEmpty() && r.unmatchedCodes.isEmpty())
    }

    @Test fun phoneNumberIgnored() {
        val r = parse("Call 9876543210")
        assertTrue(r.captures.isEmpty() && r.unmatchedCodes.isEmpty())
    }

    @Test fun otpIgnored() {
        val r = parse("Your OTP is 1234. Do not share it")
        assertTrue(r.captures.isEmpty() && r.unmatchedCodes.isEmpty())
        assertTrue(parse("4521 is your verification code").captures.isEmpty())
    }

    // ---- Block posts: vehicle, driver mobile, location, vendor (from the 1041 DC Transport group) ----

    private val group = listOf("TS12UD9713", "AP39WJ1149", "TG07W3184", "TS19T9909", "TS05UD2052", "TG15U7041", "TS07UE5029")
    private fun parseGroup(t: String) = MessageParser.parse(t, group.groupBy { VehicleParser.last4(it) }, group.toSet())

    @Test fun blockPostWithSeveralVehicles() {
        val r = parseGroup(
            "TS12UD9713\n9398481461\nKurnool\n\nAP39WJ1149\n 63095 97199\nSuryabagh 24 feet\nVeerbhadra cargo"
        ).captures
        assertEquals(listOf("TS12UD9713", "AP39WJ1149"), r.map { it.typed })
        assertEquals(listOf("9398481461", "6309597199"), r.map { it.mobile })
        assertEquals(listOf("Kurnool", "Suryabagh"), r.map { it.city })
        assertEquals(listOf("Veerbhadra Cargo", "Veerbhadra Cargo"), r.map { it.vendor })   // one vendor for the whole post
    }

    @Test fun cityOnVehicleLineAndPlusNinetyOne() {
        val r = parseGroup("TG07W3184.\nELURU 22FT 09392764599\n\n TS19T9909 RAJMUNDHRY\n+919959131824\nMisheetha logistics").captures
        assertEquals(listOf("TG07W3184", "TS19T9909"), r.map { it.typed })
        assertEquals(listOf("Eluru", "Rajmundhry"), r.map { it.city })
        assertEquals(listOf("9392764599", "9959131824"), r.map { it.mobile })
        assertEquals(listOf("Misheetha Logistics", "Misheetha Logistics"), r.map { it.vendor })
    }

    @Test fun routeAndLast4Block() {
        val r = parseGroup("TS05UD2052\n9030339488\nIsnapur to Raichur\n\nTG15U7041\n7675946998\nMadhurvada\nVr logistics").captures
        assertEquals("Isnapur to Raichur", r[0].city)
        assertEquals("Madhurvada", r[1].city)
        assertEquals("Vr Logistics", r[1].vendor)
        val c = parseGroup("5029\nMachalipatnam\nTeamplay").captures.single()
        assertEquals(listOf("TS07UE5029"), c.candidates)
        assertEquals("Machalipatnam", c.city)
        assertEquals("Teamplay", c.vendor)
        assertEquals("", c.mobile)
    }

    @Test fun spacedMobileIsNotAVehicleCode() {
        val r = parseGroup("TS07UE5029\n+91 95818 08417\nNUZIVEEDU")
        assertEquals("9581808417", r.captures.single().mobile)
        assertEquals("Nuziveedu", r.captures.single().city)
        assertTrue(r.unmatchedCodes.isEmpty())
    }

    @Test fun vendorUnderLocationAndCodeLine() {
        val c = parse("RC puram 1234\nVeerbhadra cargo").captures.single()
        assertEquals("Rc Puram", c.city)
        assertEquals("Veerbhadra Cargo", c.vendor)
    }

    @Test fun vehicleSize() {
        val r = parseGroup("AP39WJ1149\n 63095 97199\nSuryabagh 24 feet\nVeerbhadra cargo\n\nTG07W3184.\nELURU 22FT 09392764599").captures
        assertEquals(listOf("24 FT", "22 FT"), r.map { it.size })
        assertEquals("32 FT MXL", MessageParser.sizeOf("Kurnool 32 ft mxl"))
        assertEquals("407", MessageParser.sizeOf("Ap29TB 4702 kompally 407"))
        assertEquals("", MessageParser.sizeOf("Kurnool"))
    }

    // ---- Formats from the 1041 DC Transport Team chat ----

    private fun any4(t: String) = MessageParser.parse(t,
        Regex("(?<![0-9])\\d{4}(?![0-9])").findAll(t).map { it.value }.associateWith { listOf("XX00XX" + it) }, emptySet())

    @Test fun placeWrittenBeforeTheCode() {
        val r = any4("Rcpuram \n9578\nNarsingi \n9573\nIndosai transport").captures
        assertEquals(listOf("9578", "9573"), r.map { it.typed })
        assertEquals(listOf("Rcpuram", "Narsingi"), r.map { it.city })
        assertEquals(listOf("Indosai Transport", "Indosai Transport"), r.map { it.vendor })
    }

    @Test fun placeBeforeCodeWithBlankLinesAndSize() {
        val r = any4("Uppalbag+uppalranga 909\n2201\n\n My home \n1987\n\nBalanagar \n6381\nSai Kiran transport").captures
        assertEquals(listOf("Uppalbag Uppalranga", "My Home", "Balanagar"), r.map { it.city })
        assertEquals("909", r[0].size)
        assertTrue(r.all { it.vendor == "Sai Kiran Transport" })
    }

    @Test fun codeListWithOneVendorAtTheEnd() {
        val r = any4("1852-Medipally pickup \n5717-Bandalguda 709\n 2447- vanasthali puram \n5000- Madinaguda\nHappy roadlines").captures
        assertEquals(listOf("Medipally", "Bandalguda", "Vanasthali Puram", "Madinaguda"), r.map { it.city })
        assertEquals(listOf("PICKUP", "709", "", ""), r.map { it.size })
        assertTrue(r.all { it.vendor == "Happy Roadlines" })
    }

    @Test fun driverNamesNextToTheMobile() {
        val r = MessageParser.parse("TS15UE2428\nGanapati +91 97053 52405\nVepgunta \n\nHanumanthu\nTS08UD4034\nVizag \n+91 87123 05711\n\nSquad logistics",
            emptyMap(), emptySet()).captures
        assertEquals(listOf("Ganapati", "Hanumanthu"), r.map { it.driver })
        assertEquals(listOf("9705352405", "8712305711"), r.map { it.mobile })
        assertEquals(listOf("Vepgunta", "Vizag"), r.map { it.city })
        assertTrue(r.all { it.vendor == "Squad Logistics" })
    }

    @Test fun replacementLineUnderAVehicle() {
        val r = any4("TG08V5926\nGajuwaka \nSri laxmi chenna keshava transport\n+917654614945\nReplacement 2502").captures
        val c = r.single()
        assertEquals("2502", c.typed)
        assertEquals("TG08V5926", c.replacement)
        assertEquals("Gajuwaka", c.city)
        assertEquals("7654614945", c.mobile)
    }

    @Test fun remarksAreNotPlacesOrVendors() {
        val c = MessageParser.parse("Replace \n08.10.2026\nTG08X4999 (24ft)\n+917997813622\nHyderabad to Vizag in dc\nSri anjana tpt", emptyMap(), emptySet()).captures.single()
        assertEquals("Hyderabad to Vizag", c.city)
        assertEquals("Sri Anjana Tpt", c.vendor)
        assertEquals("24 FT", c.size)
        val d = MessageParser.parse("Replace TG07W3184 KAKINADA 22Feet+ +919392764599 in dc Misheetha tpt", emptyMap(), emptySet()).captures.single()
        assertEquals("Kakinada", d.city)
        assertEquals("Misheetha Tpt", d.vendor)
        assertEquals("22 FT", d.size)
    }

    @Test fun statusReportsAreIgnored() {
        assertTrue(any4("Vehicle Planning of Isnapur DC** \n\nDate: 06.10.2026\n\nVehicles Planned :- 51\nVehicles Reported :-51\nVehicles not reported :-00").captures.isEmpty())
        assertTrue(any4("Vehicle not reported:- 01\n1) Vizag -2428\nSQUAD TPT").captures.isEmpty())
        assertTrue(any4("Dear, Ali \nThis vehicle Not a 22 FT\nTotal volume 1050").captures.isEmpty())
        assertTrue(any4("E-way bill extended date 07.10.2026").captures.isEmpty())
    }
}
