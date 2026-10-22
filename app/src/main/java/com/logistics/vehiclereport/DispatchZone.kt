package com.logistics.vehiclereport

/** How far a store is from the dispatch DC: same city, same state, or another state. */
enum class Zone { LOCAL, NON_LOCAL, UPCOUNTRY, UNKNOWN }

/**
 * Sorts the day's stores into Local (same city as the DC), Non-local (same state, another city) and Upcountry (another
 * state). The store's state comes from the phone's map search when it found the store, else from the place names on
 * the store list (city, region, store name: "Rajahmundry AP", "Raichur").
 */
object DispatchZone {

    /** State names and short forms as written on store lists. */
    private val STATE_NAMES = linkedMapOf(
        "andhra pradesh" to "Andhra Pradesh", "telangana" to "Telangana", "karnataka" to "Karnataka",
        "maharashtra" to "Maharashtra", "tamil nadu" to "Tamil Nadu", "tamilnadu" to "Tamil Nadu", "kerala" to "Kerala",
        "gujarat" to "Gujarat", "odisha" to "Odisha", "orissa" to "Odisha", "chhattisgarh" to "Chhattisgarh",
        "madhya pradesh" to "Madhya Pradesh", "uttar pradesh" to "Uttar Pradesh", "rajasthan" to "Rajasthan",
        "west bengal" to "West Bengal", "goa" to "Goa", "punjab" to "Punjab", "haryana" to "Haryana", "delhi" to "Delhi",
        "bihar" to "Bihar", "jharkhand" to "Jharkhand", "puducherry" to "Puducherry", "pondicherry" to "Puducherry"
    )
    private val STATE_SHORT = mapOf(
        "ap" to "Andhra Pradesh", "ts" to "Telangana", "tg" to "Telangana", "tel" to "Telangana", "ka" to "Karnataka",
        "kar" to "Karnataka", "mh" to "Maharashtra", "tn" to "Tamil Nadu", "kl" to "Kerala", "gj" to "Gujarat",
        "od" to "Odisha", "cg" to "Chhattisgarh", "mp" to "Madhya Pradesh", "up" to "Uttar Pradesh", "wb" to "West Bengal"
    )

    private fun cities(state: String, vararg names: String) = names.map { it to state }

    /** Towns with DMart stores or DCs, and their state. */
    private val CITY_STATE: Map<String, String> = (
        cities("Telangana", "hyderabad", "secunderabad", "isnapur", "patancheru", "sangareddy", "medak", "siddipet",
            "warangal", "hanamkonda", "karimnagar", "nizamabad", "khammam", "nalgonda", "suryapet", "miryalaguda",
            "mahabubnagar", "mahbubnagar", "adilabad", "mancherial", "ramagundam", "godavarikhani", "kothagudem", "jagtial",
            "kamareddy", "zaheerabad", "medchal", "shamshabad", "kompally", "kukatpally", "gachibowli", "uppal",
            "ghatkesar", "bhongir", "bhuvanagiri", "jadcherla", "vikarabad", "tandur", "bodhan", "nirmal", "wanaparthy",
            "gadwal", "sircilla", "peddapalli", "jangaon", "mahabubabad", "bhadrachalam", "hayathnagar", "dilsukhnagar",
            "miyapur", "bachupally", "nizampet", "manikonda", "attapur", "kondapur", "madinaguda", "bowenpally", "ecil",
            "nagole", "vanasthalipuram", "chandanagar", "ameenpur", "boduppal", "alwal", "saroornagar", "malkajgiri") +
        cities("Andhra Pradesh", "visakhapatnam", "vizag", "vijayawada", "guntur", "nellore", "tirupati", "kakinada",
            "rajahmundry", "rajamahendravaram", "eluru", "ongole", "kurnool", "anantapur", "anantapuram", "kadapa",
            "chittoor", "srikakulam", "vizianagaram", "bhimavaram", "machilipatnam", "gajuwaka", "tenali", "narasaraopet",
            "proddatur", "hindupur", "madanapalle", "tadepalligudem", "amalapuram", "nandyal", "guntakal",
            "chilakaluripet", "gudivada", "tanuku", "palakollu", "kavali", "gudur", "chirala", "bapatla", "dharmavaram",
            "tadipatri", "adoni", "anakapalle", "madhurawada", "mangalagiri", "jaggaiahpet", "nuzvid", "samalkot",
            "peddapuram", "ramachandrapuram", "srikalahasti", "puttur", "markapur", "rayachoti", "kadiri", "parvathipuram") +
        cities("Karnataka", "bengaluru", "bangalore", "mysuru", "mysore", "hubli", "hubballi", "dharwad", "belagavi",
            "belgaum", "mangaluru", "mangalore", "raichur", "ballari", "bellary", "kalaburagi", "gulbarga", "bidar",
            "davangere", "davanagere", "shivamogga", "shimoga", "tumakuru", "tumkur", "vijayapura", "bijapur", "hospet",
            "hosapete", "gadag", "koppal", "yadgir", "chitradurga", "udupi", "hassan", "mandya", "kolar", "bagalkot") +
        cities("Maharashtra", "mumbai", "pune", "nagpur", "nashik", "nasik", "aurangabad", "sambhajinagar", "solapur",
            "kolhapur", "thane", "nanded", "latur", "amravati", "akola", "sangli", "satara", "ahmednagar", "jalgaon",
            "kalyan", "dombivli", "bhiwandi", "vasai", "virar", "panvel", "chandrapur", "parbhani", "osmanabad",
            "dharashiv", "beed", "pimpri", "chinchwad", "ichalkaranji") +
        cities("Tamil Nadu", "chennai", "coimbatore", "madurai", "hosur", "salem", "trichy", "tiruchirappalli", "vellore",
            "tirunelveli", "erode", "tiruppur", "thoothukudi", "krishnagiri") +
        cities("Gujarat", "ahmedabad", "surat", "vadodara", "baroda", "rajkot", "gandhinagar", "bharuch",
            "navsari", "vapi", "jamnagar", "bhavnagar") +
        cities("Madhya Pradesh", "indore", "bhopal", "ujjain") +
        cities("Chhattisgarh", "raipur", "bhilai", "durg", "bilaspur") +
        cities("Odisha", "bhubaneswar", "cuttack", "berhampur", "brahmapur") +
        cities("Rajasthan", "jaipur", "kota", "ajmer", "udaipur") +
        cities("West Bengal", "kolkata", "howrah", "durgapur", "asansol", "siliguri")
    ).toMap()

    /** Suburbs and areas that belong to a bigger city (a DC at Isnapur serves Hyderabad as its local city). */
    private val METRO = mapOf(
        "Hyderabad" to listOf("hyderabad", "secunderabad", "isnapur", "patancheru", "medchal", "shamshabad", "kompally",
            "kukatpally", "gachibowli", "uppal", "ghatkesar", "hayathnagar", "dilsukhnagar", "miyapur", "bachupally",
            "nizampet", "manikonda", "attapur", "kondapur", "madinaguda", "bowenpally", "ecil", "nagole", "vanasthalipuram",
            "chandanagar", "ameenpur", "boduppal", "alwal", "saroornagar", "malkajgiri", "rangareddy", "cyberabad"),
        "Visakhapatnam" to listOf("visakhapatnam", "vizag", "gajuwaka", "madhurawada"),
        "Bengaluru" to listOf("bengaluru", "bangalore"),
        "Mumbai" to listOf("mumbai", "thane", "kalyan", "dombivli", "bhiwandi", "vasai", "virar", "panvel"),
        "Pune" to listOf("pune", "pimpri", "chinchwad")
    )

    private fun norm(s: String) = " " + s.lowercase().replace("d-mart", " ").replace(Regex("[^a-z]+"), " ").trim() + " "
    private fun words(s: String) = norm(s).split(' ').filter { it.isNotEmpty() }

    /** The state named or implied in a piece of text ("Raichur", "Rajahmundry AP", "Telangana"); null when none. */
    fun stateOf(text: String): String? {
        if (text.isBlank()) return null
        val n = norm(text)
        STATE_NAMES.entries.firstOrNull { n.contains(" ${it.key} ") }?.let { return it.value }
        val w = words(text)
        w.firstNotNullOfOrNull { CITY_STATE[it] }?.let { return it }
        return w.firstNotNullOfOrNull { STATE_SHORT[it] }
    }

    /** A state name from a map search ("Telangana", "Andhra Pradesh") in the same spelling as [stateOf]. */
    fun canonical(state: String?): String? = state?.takeIf { it.isNotBlank() }?.let { stateOf(it) ?: it.trim() }

    /** Store's state: the store list's city or region, then the map search, then the store's name. */
    fun storeState(s: PlanStop, mapState: String?): String? =
        stateOf(s.city) ?: stateOf(s.region) ?: canonical(mapState) ?: stateOf(s.listName) ?: stateOf(s.name) ?: stateOf(s.circle)

    /** Place names counted as the DC's own city: the DC's area, and the city it belongs to with its suburbs. */
    fun dcCityNames(dcPlace: String, typed: String = ""): Set<String> {
        val base = (words(typed) + words(dcPlace)).filter { it.length >= 4 && it != "india" }.toSet()
        val metro = METRO.values.filter { list -> list.any { it in base } }.flatten()
        return base + metro
    }

    /** The city the DC serves as local, for showing ("Isnapur" -> "Hyderabad"). */
    fun dcCityLabel(dcPlace: String): String {
        val w = words(dcPlace).toSet()
        return METRO.entries.firstOrNull { e -> e.value.any { it in w } }?.key
            ?: dcPlace.trim().replaceFirstChar { it.uppercase() }
    }

    /**
     * Local when the store's city is one of [dcCities] (or, with no city on the list, its name has one, or it is
     * within [localKm] of the DC); else Non-local in the DC's state, Upcountry in another; UNKNOWN when its state is not known.
     */
    fun zone(s: PlanStop, state: String?, dcCities: Set<String>, dcState: String?, kmFromDc: Double?, localKm: Double): Zone {
        val cityWords = words(s.city)
        val local = if (cityWords.isNotEmpty()) cityWords.any { it in dcCities }
            else words("${s.listName} ${s.name}").any { it in dcCities } || (kmFromDc != null && kmFromDc <= localKm)
        if (local) return Zone.LOCAL
        if (state == null || dcState == null) return Zone.UNKNOWN
        return if (state.equals(dcState, ignoreCase = true)) Zone.NON_LOCAL else Zone.UPCOUNTRY
    }

    /**
     * Stores held back from the day's plan: volume below the minimum of their zone ([minimum], 0 = none), unless
     * chosen to go anyway ([force]).
     */
    fun held(stops: List<PlanStop>, zones: Map<String, Zone>, minimum: (Zone) -> Double, force: Set<String> = emptySet()): Set<String> =
        stops.filter { s ->
            val min = minimum(zones[s.code] ?: Zone.UNKNOWN)
            s.code !in force && min > 0 && s.volume < min - 1e-6
        }.map { it.code }.toSet()

    /** A vehicle's zone is its farthest stop's: Upcountry over Non-local over not known over Local. */
    fun ofLoad(zones: List<Zone>): Zone = zones.maxByOrNull { rank(it) } ?: Zone.UNKNOWN

    private fun rank(z: Zone) = when (z) { Zone.LOCAL -> 0; Zone.UNKNOWN -> 1; Zone.NON_LOCAL -> 2; Zone.UPCOUNTRY -> 3 }
}
