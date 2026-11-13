package com.logistics.vehiclereport

/**
 * One vehicle report found in a WhatsApp message.
 * [typed]: what was written for the dedicated vehicle ("1234" or "TS09UB1234").
 * [candidates]: dedicated vehicles it matches (for an unknown full number: the number itself).
 * [replacement]: the replacement vehicle sent instead of the dedicated one, "" if none.
 */
data class Capture(
    val typed: String,
    val candidates: List<String>,
    val full: Boolean,
    val city: String,
    val replacement: String,
    /** Driver mobile written with the vehicle ("" if none). */
    val mobile: String = "",
    /** Vendor / transporter written on the last line of the vehicle's block ("" if none). */
    val vendor: String = "",
    /** Vehicle size / type written with it: "22 FT", "32 FT MXL", "407" ("" if none). */
    val size: String = "",
    /** Driver name written with the vehicle ("" if none). */
    val driver: String = "",
    /** false = a 4-digit code that matches no vehicle in your list (still a reported vehicle). */
    val inList: Boolean = true
)

data class ParseResult(val captures: List<Capture>, val unmatchedCodes: List<String>)

/**
 * Reads messages such as
 *   "1234 Hyderabad"
 *   "TS09UB1234 Vijayawada"
 *   "1234 Hyd\n5678 Guntur"
 *   "1234 replacement TS08XY9999 Hyderabad"
 *   "TS08XY9999 in place of 1234 - Pune"
 * and block posts (one vehicle per block, blocks split by a blank line or by the next vehicle number):
 *   TS12UD9713          <- vehicle (full or last 4)
 *   9398481461          <- driver mobile
 *   Kurnool             <- delivery location
 *   Veerbhadra cargo    <- vendor (optional, last line)
 */
object MessageParser {

    private val REPL = Regex(
        "\\b(replacement|replacing|replaced|replace|repl|rplc|rpl|instead\\s+of|in\\s+place\\s+of|inplace\\s+of|against|substitute|swap|swapped)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Words that are not part of a city name. */
    private val FILLER = setOf(
        "reached", "reach", "placed", "place", "placement", "arrived", "arrive", "reported", "report", "reporting",
        "at", "in", "on", "to", "for", "by", "with", "from", "of", "the", "is", "has", "been", "and", "now", "today",
        "vehicle", "vehicles", "veh", "vh", "no", "number", "num", "dedicated", "ded", "truck", "lorry", "new", "old",
        "loading", "unloading", "loaded", "unloaded", "done", "ok", "okay", "sir", "pls", "please", "city", "location",
        "loc", "am", "pm", "hrs", "time", "dock", "bay", "gate", "position", "pos", "posted", "available", "here",
        "replacement", "replacing", "replaced", "replace", "repl", "rplc", "rpl", "instead", "inplace", "against",
        "substitute", "swap", "swapped", "actual", "original", "given", "sent", "send", "via",
        "dc", "but", "shutter", "closed", "check", "load", "this", "ton", "tons", "change", "also", "store", "club"
    )

    /** Messages with one-time passwords or codes are never read or saved. */
    private val SENSITIVE = Regex(
        "\\b(otp|one[\\s-]?time[\\s-]?password|verification\\s+code|security\\s+code|login\\s+code|passcode|password|(?:upi|atm|your|m)\\s?pin|cvv|do\\s+not\\s+share)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isSensitive(text: String) = SENSITIVE.containsMatchIn(text)

    /**
     * Indian mobile: 9398481461, 63095 97199, +91 95818 08417, 09392764599, and "+91…" written straight after
     * a place with no space ("GAJUWAKA VIZAG+919908764329").
     */
    private val MOBILE = Regex("(?:(?<![0-9])\\+\\s?91[\\s-]*|(?<![0-9A-Za-z])(?:91[\\s-]+|0)?)([6-9]\\d{9}|[6-9]\\d{4}[\\s-]\\d{5}|[6-9]\\d{2}[\\s-]\\d{3}[\\s-]\\d{4})(?![0-9])")

    /** Vehicle size written with the location: "22FT", "24 feet", "32 ft" */
    private val SIZE = Regex(
        "\\b\\d{1,2}\\s*(?:ft|feet|fit|feets)\\b|\\bfeet\\b|\\bft\\b|(?<![0-9.])\\d{1,2}(?:\\.\\d)?\\s*(?:tons?|tonne|tn|mt)\\b|" +
            "\\b(?:407|709|909|tata\\s*ace|pick\\s*up|pickup|dost|eicher|bolero)\\b",
        RegexOption.IGNORE_CASE
    )

    /** What the app knows about places and vendors (store list, vendor list), to tell lines apart. */
    interface Hints {
        /** 0 = not a known place, 1 = matches several stores, 2 = matches one store. */
        fun place(text: String): Int = 0
        fun vendor(text: String): Boolean = false
    }

    object NoHints : Hints

    /** Remembers each answer: the same words are looked up in the store / vendor lists only once. */
    private class CachedHints(val inner: Hints) : Hints {
        private val places = HashMap<String, Int>()
        private val vendors = HashMap<String, Boolean>()
        override fun place(text: String): Int = synchronized(places) { places[text] } ?: inner.place(text).also { synchronized(places) { places[text] = it } }
        override fun vendor(text: String): Boolean = synchronized(vendors) { vendors[text] } ?: inner.vendor(text).also { synchronized(vendors) { vendors[text] = it } }
    }

    fun cached(h: Hints): Hints = if (h is CachedHints || h === NoHints) h else CachedHints(h)

    private val VENDOR_WORDS = Regex(
        "\\b(transport|transports|transporters|tpt|logistics|logistic|logostic|cargo|corgo|movers|packers|roadlines|roadways|road\\s*lines|" +
            "carriers|carrier|travels|trans|transways|express|enterprises|freight|freights|fleet|trucking|shipping|parcel|parcels|" +
            "goods|agency|agencies|services|vrl|navata|gati|safexpress|tci|rivigo)\\b",
        RegexOption.IGNORE_CASE
    )

    /** "Vendor: Navata", "Transporter - KRT", "vendor name Sri Sai": the words after it are the vendor. */
    private val VENDOR_LABEL = Regex("\\b(?:vendor|vender|vendar|transporter|transporters)\\b\\s*(?:name)?\\s*[:\\-=.]*\\s*", RegexOption.IGNORE_CASE)

    /** First words of many vendor names ("Sri Sai Transport"): where the vendor starts after the place. */
    private val VENDOR_START = setOf("sri", "shri", "sree", "shree", "srī", "om", "jai", "jay", "maa", "ma", "new", "the", "m/s", "ms")

    /**
     * Splits "Hyderabad Sri Sai Transport", "Vijayawada - Balaji Roadlines", "Guntur, vendor: Navata" or
     * "Warangal VRL" into the place and the vendor. Null when the text holds no vendor.
     */
    fun splitVendor(text: String, hints: Hints = NoHints): Pair<String, String>? {
        val t = text.trim()
        if (!t.any { it.isLetter() }) return null
        // 1. Written with a label.
        VENDOR_LABEL.find(t)?.let { m ->
            val v = t.substring(m.range.last + 1).trim(' ', ',', '-', ':', '|')
            if (v.any { it.isLetter() }) return t.substring(0, m.range.first).trim(' ', ',', '-', ':', '|') to v
        }
        fun isVendor(x: String) = VENDOR_WORDS.containsMatchIn(x) || hints.vendor(x.trim())
        // 2. Split by "-", ",", "|", "/" or wide spaces: the part with a vendor word (not the first part).
        val parts = t.split(Regex("\\s+[-–|/]\\s+|[,|]|\\s{3,}")).map { it.trim() }.filter { p -> p.any { it.isLetter() } }
        if (parts.size >= 2) {
            val idx = parts.indexOfLast { isVendor(it) }
            if (idx >= 1) return parts.subList(0, idx).joinToString(" ") to parts.subList(idx, parts.size).joinToString(" ")
        }
        // 3. One run of words: the vendor starts at "Sri / Shree / …" or the word before "Transport / Logistics / …".
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        val vi = words.indexOfFirst { VENDOR_WORDS.containsMatchIn(it) }
        if (vi >= 1) {
            val known = (1..vi).filter { i -> hints.place(words.subList(0, i).joinToString(" ")) > 0 }.maxOrNull()
            val start = known
                ?: (1 until vi).firstOrNull { words[it].lowercase().trim('.') in VENDOR_START }
                ?: maxOf(1, vi - 1).let { i -> if (cityOf(words.subList(0, i).joinToString(" "), emptyList()).isEmpty()) vi else i }
            if (cityOf(words.subList(0, start).joinToString(" "), emptyList()).isNotEmpty())
                return words.subList(0, start).joinToString(" ") to words.subList(start, words.size).joinToString(" ")
        }
        // 4. A short vendor code from your vendor list written in capitals after the place ("Warangal VRL").
        if (words.size >= 2) {
            val last = words.last().trim('.', ',')
            if (last.length in 2..5 && last.all { it.isUpperCase() } && hints.vendor(last) && hints.place(last) == 0 &&
                cityOf(words.dropLast(1).joinToString(" "), emptyList()).isNotEmpty()
            ) return words.dropLast(1).joinToString(" ") to last
        }
        return null
    }

    /** Lines that are remarks, not a place / name / vendor. */
    private val NOTE = Regex(
        "\\b(re\\s*placement|replacement|replace|number\\s+change|vehicle\\s+change|vehicle\\s+reported|reported|in\\s+dc|pls|please|load\\s+this|check|shutter)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Status / closing reports ("Vehicles Planned :- 51 …") and "not reported" lists are not vehicle reports. */
    private val STAT_LINE = Regex(":\\s*-?\\s*\\d")
    private val NOT_REPORTED = Regex("\\bnot\\s+reported\\b", RegexOption.IGNORE_CASE)

    /** Lines whose numbers are not vehicles: store codes, gate passes, boxes, invoices. */
    private val NOT_VEHICLE_LINE = Regex("\\b(store\\s*code|code|gate\\s*pass|gatepass|boxes|box|invoice|stn|volume|amount|rs)\\b", RegexOption.IGNORE_CASE)
    /** "@⁨Ali Dmart⁩" mentions */
    private val MENTION = Regex("@\u2068[^\u2069]*\u2069|@\\S+")

    private val DATE = Regex("(?<![0-9])\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}(?![0-9])")
    private val CLOCK = Regex("(?<![0-9])\\d{1,2}:\\d{2}(?::\\d{2})?(?![0-9])")

    private class Ln(val masked: String, val mobile: String, val mobileAt: IntRange?)

    fun parse(
        text: String, byLast4: Map<String, List<String>>, dedicated: Set<String>, hints: Hints = NoHints
    ): ParseResult {
        val hints = cached(hints)
        if (isSensitive(text)) return ParseResult(emptyList(), emptyList())
        if (NOT_REPORTED.containsMatchIn(text)) return ParseResult(emptyList(), emptyList())
        if (text.lines().count { STAT_LINE.containsMatchIn(it) } >= 3) return ParseResult(emptyList(), emptyList())
        // Letters to the team ("Dear Ali, …") are conversation, not vehicle reports.
        if (text.trimStart().startsWith("dear", ignoreCase = true)) return ParseResult(emptyList(), emptyList())

        val captures = mutableListOf<Capture>()
        val unmatched = mutableListOf<String>()

        // 1. Lines with phone numbers, dates and clock times hidden (their digits are never vehicle codes).
        val lines = text.replace(';', '\n').split('\n').map { line ->
            val raw = MENTION.replace(line) { " ".repeat(it.value.length) }
            val m = MOBILE.find(raw)
            var masked = mask(raw, MOBILE.findAll(raw).map { it.range }.toList())
            if (NOT_VEHICLE_LINE.containsMatchIn(masked)) masked = masked.replace(Regex("\\d"), " ")
            // In a long sentence only full vehicle numbers count, not 4-digit numbers.
            if (masked.split(Regex("\\s+")).count { w -> w.count { it.isLetter() } >= 3 } >= 9) {
                val plates = VehicleParser.refs(masked).filter { it.full }
                masked = masked.mapIndexed { i, ch -> if (ch.isDigit() && plates.none { i >= it.start && i < it.end }) ' ' else ch }.joinToString("")
            }
            masked = mask(masked, DATE.findAll(masked).map { it.range }.toList())
            masked = mask(masked, CLOCK.findAll(masked).map { it.range }.toList())
            Ln(masked, m?.groupValues?.get(1)?.filter { it.isDigit() }.orEmpty(), m?.range)
        }

        // 2. Anchors: lines that name vehicles. Each keeps the capture of its (last) vehicle.
        class Anchor(val line: Int, val capture: Int, val ownCity: Boolean) {
            val pre = mutableListOf<Int>()
            val post = mutableListOf<Int>()
        }
        val anchors = mutableListOf<Anchor>()
        lines.forEachIndexed { i, l ->
            if (VehicleParser.refs(l.masked).isEmpty()) return@forEachIndexed
            val before = captures.size
            parseLine(l.masked, byLast4, dedicated, captures, unmatched, hints)
            val cap = if (captures.size > before) captures.size - 1 else -1
            // A line naming several vehicles keeps its own words; extra lines go to its last vehicle only.
            if (cap >= 0 && captures.size - before == 1) {
                val c = captures[cap]
                captures[cap] = c.copy(mobile = l.mobile, size = sizeOf(l.masked))
            }
            anchors += Anchor(i, cap, cap >= 0 && captures[cap].city.isNotBlank())
        }
        if (anchors.isEmpty()) return ParseResult(emptyList(), unmatched.distinct())

        // 3. Lines between vehicles: a blank line splits them (before -> previous vehicle, after -> next).
        //    Without a blank line they go to the previous vehicle, unless the message writes the
        //    place first ("Rcpuram\n9578\nNarsingi\n9573").
        fun isText(i: Int) = lines[i].masked.isNotBlank() || lines[i].mobile.isNotEmpty()
        val firstPre = (0 until anchors[0].line).filter { isText(it) }
        val placeFirst = firstPre.isNotEmpty() && !anchors[0].ownCity &&
            firstPre.none { VENDOR_WORDS.containsMatchIn(lines[it].masked) || hints.vendor(lines[it].masked) }
        anchors[0].pre += firstPre
        for (k in anchors.indices) {
            val a = anchors[k]
            val end = if (k + 1 < anchors.size) anchors[k + 1].line else lines.size
            val gap = (a.line + 1 until end).toList()
            if (k + 1 == anchors.size) { a.post += gap.filter { isText(it) }; continue }
            val blank = gap.lastOrNull { !isText(it) }
            val next = anchors[k + 1]
            when {
                blank != null -> {
                    a.post += gap.filter { it < blank && isText(it) }
                    next.pre += gap.filter { it > blank && isText(it) }
                }
                placeFirst -> next.pre += gap.filter { isText(it) }
                else -> a.post += gap.filter { isText(it) }
            }
        }

        // 4. Each vehicle: mobile, driver name, location, vendor, size from its lines.
        for (a in anchors) {
            if (a.capture < 0) continue
            var c = captures[a.capture]
            var mobile = c.mobile
            var driver = ""
            var nameLine = ""
            val items = mutableListOf<Pair<String, Boolean>>()   // text, isPost
            val all = a.pre.map { it to false } + a.post.map { it to true }
            val blockLines = all.map { it.first }.toSet()
            for ((i, isPost) in all) {
                val l = lines[i]
                // "Ghansham\n7985723808\nVizianagaram": a short name just above the mobile, with the place after it,
                // is the driver (not the place).
                val t1 = l.masked.trim()
                if (l.mobile.isEmpty() && driver.isEmpty() && (i + 1) in blockLines && lines[i + 1].mobile.isNotEmpty() &&
                    !lines[i + 1].masked.any { it.isLetter() } && t1.any { it.isLetter() } && t1.split(Regex("\\s+")).size <= 3 &&
                    hints.place(locationOf(t1)) == 0 && !VENDOR_WORDS.containsMatchIn(t1) && VENDOR_LABEL.find(t1) == null &&
                    sizeOf(t1).isEmpty() && (i + 2 until (blockLines.maxOrNull() ?: 0) + 1).any { j -> j in blockLines && lines[j].masked.any { it.isLetter() } &&
                        !VENDOR_WORDS.containsMatchIn(lines[j].masked) && VENDOR_LABEL.find(lines[j].masked) == null }
                ) { driver = nameOf(t1); continue }
                if (l.mobile.isNotEmpty()) {
                    if (mobile.isEmpty()) mobile = l.mobile
                    // "Ganapati +91 97053 52405" / "Satish 093461 14652, Hyd to Vizag"
                    val at = l.mobileAt!!
                    val beforeTxt = l.masked.substring(0, minOf(at.first, l.masked.length)).trim(' ', ',', '-', ':')
                    val afterTxt = l.masked.substring(minOf(at.last + 1, l.masked.length)).trim(' ', ',', '-', ':')
                    if (beforeTxt.any { it.isLetter() }) {
                        // "Ganapati +91 …" is a name; "ELURU 22FT 0939…" is a place with a size.
                        if (hints.place(beforeTxt) == 0 && !VENDOR_WORDS.containsMatchIn(beforeTxt) && sizeOf(beforeTxt).isEmpty() && driver.isEmpty()) {
                            driver = nameOf(beforeTxt); nameLine = beforeTxt
                        } else items += beforeTxt to isPost
                    }
                    if (afterTxt.any { it.isLetter() }) items += afterTxt to isPost
                } else if (l.masked.any { it.isLetter() }) items += l.masked.trim() to isPost
            }
            var vendor = ""
            val places = mutableListOf<Pair<String, Int>>()
            val others = mutableListOf<Pair<String, Boolean>>()
            for ((t0, isPost) in items) {
                // "Hyderabad to Vizag in dc" -> "Hyderabad to Vizag"; "Re placement" -> nothing
                val t = NOTE.replace(t0, " ").trim(' ', '.', ',', '-')
                val labelled = VENDOR_LABEL.find(t)
                val split = if (labelled == null && VENDOR_WORDS.containsMatchIn(t)) splitVendor(t, hints) else null
                when {
                    // "Vendor: Navata" on its own line.
                    labelled != null && t.substring(labelled.range.last + 1).any { it.isLetter() } -> {
                        vendor = vendorOf(t.substring(labelled.range.last + 1))
                        t.substring(0, labelled.range.first).takeIf { b -> locationOf(b).isNotEmpty() }?.let { b ->
                            val p = hints.place(locationOf(b)); if (p > 0) places += b to p else others += b to isPost
                        }
                    }
                    locationOf(t).isEmpty() && !hints.vendor(t) -> Unit
                    // "Kurnool Veerbhadra cargo": a known place, then the vendor.
                    split != null && hints.place(locationOf(split.first)) > 0 -> {
                        vendor = vendorOf(split.second); places += split.first to hints.place(locationOf(split.first))
                    }
                    VENDOR_WORDS.containsMatchIn(t) || hints.vendor(t) -> vendor = vendorOf(t)
                    else -> {
                        val p = hints.place(locationOf(t))
                        if (p > 0) places += t to p else others += t to isPost
                    }
                }
            }
            var city = c.city
            // "TG07W3184 KAKINADA 22Feet +91… in dc Misheetha tpt": the vendor written on the vehicle's own line.
            if (city.isNotEmpty()) {
                val own = lines[a.line].masked
                val parts = NOTE.replace(own, " ").split(Regex("\\s{3,}|[+|,]")).map { it.trim() }.filter { it.any { ch -> ch.isLetter() } }
                val vParts = parts.filter { VENDOR_WORDS.containsMatchIn(it) || hints.vendor(it) }
                if (vParts.isNotEmpty() && parts.size > vParts.size) {
                    if (vendor.isEmpty()) vendor = vendorOf(vParts.last())
                    val rest = parts.filter { it !in vParts }.joinToString(" ")
                    city = cityOf(rest, VehicleParser.refs(rest).map { it.start until it.end })
                }
            }
            if (city.isEmpty()) {
                // "Vizag\n…\nMarripalem": the later, more exact place is the delivery point.
                val best = places.lastOrNull { it.second == 2 } ?: places.lastOrNull()
                // Without a known place: lines after the vehicle first, unless the post writes places first.
                val pick = if (placeFirst) others.firstOrNull() else (others.firstOrNull { it.second } ?: others.firstOrNull())
                if (best != null) city = locationOf(best.first)
                else if (pick != null) { city = locationOf(pick.first); others.remove(pick) }
                else if (nameLine.isNotEmpty()) { city = locationOf(nameLine); driver = "" }
            }
            // Leftover words: the last line under the vehicle is the vendor (if none yet); others are a driver name.
            if (vendor.isEmpty() && others.isNotEmpty() && others.last().second && a.post.isNotEmpty() &&
                lines[a.post.last()].masked.trim().let { it.isNotEmpty() && others.last().first.trim() == it }
            ) vendor = vendorOf(others.removeAt(others.size - 1).first)
            if (driver.isEmpty()) others.firstOrNull()?.let { driver = nameOf(it.first) }
            val size = c.size.ifEmpty { (a.pre + a.post).firstNotNullOfOrNull { sizeOf(lines[it].masked).ifEmpty { null } }.orEmpty() }
            c = c.copy(city = city, mobile = mobile, vendor = c.vendor.ifEmpty { vendor }, size = size, driver = driver)
            captures[a.capture] = c
        }

        // 5. "Replacement 2502" under a vehicle: that vehicle was sent in place of dedicated 2502.
        val dropped = HashSet<Int>()
        for (k in 1 until anchors.size) {
            val cur = anchors[k]; val prev = anchors[k - 1]
            val l = lines[cur.line].masked
            if (!REPL.containsMatchIn(l) || VehicleParser.refs(l).size != 1 || cur.capture < 0 || prev.capture < 0) continue
            val p = captures[prev.capture]; val c = captures[cur.capture]
            if (p.replacement.isNotEmpty() || p.full.not()) continue
            captures[cur.capture] = c.copy(
                replacement = p.typed, city = c.city.ifEmpty { p.city }, mobile = c.mobile.ifEmpty { p.mobile },
                vendor = c.vendor.ifEmpty { p.vendor }, size = c.size.ifEmpty { p.size }, driver = c.driver.ifEmpty { p.driver }
            )
            dropped += prev.capture
        }
        if (dropped.isNotEmpty()) {
            val keep = captures.filterIndexed { i, _ -> i !in dropped }
            captures.clear(); captures.addAll(keep)
        }

        // 6. One vendor for the whole post ("…\nSyndicate roadlines" at the end) applies to every vehicle in it.
        val vendors = captures.map { it.vendor }.filter { it.isNotBlank() }.distinct()
        if (vendors.size == 1) for (i in captures.indices) if (captures[i].vendor.isBlank()) captures[i] = captures[i].copy(vendor = vendors[0])

        return ParseResult(
            captures.distinctBy { "${it.typed}|${it.replacement}" },
            unmatched.distinct()
        )
    }

    /** "  satish " -> "Satish" */
    private fun nameOf(t: String) = t.split(Regex("[^\\p{L}.]+")).filter { it.isNotBlank() }
        .filterNot { it.lowercase().trim('.') in setOf("driver", "dr", "name", "mr", "sri") }.take(4)
        .joinToString(" ") { titleCase(it.trim('.')) }

    private fun parseLine(
        line: String, byLast4: Map<String, List<String>>, dedicated: Set<String>,
        captures: MutableList<Capture>, unmatched: MutableList<String>, hints: Hints = NoHints
    ) {
        val refs = VehicleParser.refs(line)
        if (refs.isEmpty()) return

        fun resolve(r: VehicleParser.Ref): List<String> =
            if (r.full) (if (r.value in dedicated) listOf(r.value) else emptyList())
            else byLast4[r.value].orEmpty()

        // ---- Replacement line: one dedicated vehicle + the vehicle sent instead ----
        if (REPL.containsMatchIn(line) && refs.size >= 2) {
            val known = refs.filter { resolve(it).isNotEmpty() }
            val actual = when {
                known.size == 1 -> known[0]
                known.size > 1 -> known.firstOrNull { !it.full } ?: known[0]
                else -> refs.firstOrNull { !it.full } ?: refs[0]
            }
            val repl = refs.firstOrNull { it !== actual && it.value != actual.value }
            if (repl != null) {
                val masked = mask(line, refs.map { it.start until it.end })
                val sv = splitVendor(REPL.replace(masked, " "), hints)
                val city = if (sv != null) cityOf(sv.first, emptyList()) else cityOf(line, refs.map { it.start until it.end })
                val vendor = sv?.let { vendorOf(it.second) }.orEmpty()
                val cands = resolve(actual)
                when {
                    cands.isNotEmpty() -> captures.add(Capture(actual.value, cands, actual.full, city, repl.value, vendor = vendor))
                    actual.full -> captures.add(Capture(actual.value, listOf(actual.value), true, city, repl.value, vendor = vendor))
                    else -> {
                        unmatched.add(actual.value)
                        captures.add(Capture(actual.value, listOf(actual.value), false, city, repl.value, vendor = vendor, inList = false))
                    }
                }
                return
            }
        }

        // ---- Normal line: every reference with the words next to it as the city ----
        val fullLast4 = refs.filter { it.full }.map { VehicleParser.last4(it.value) }.toSet()
        refs.forEachIndexed { i, r ->
            if (!r.full && r.value in fullLast4) return@forEachIndexed
            val after = line.substring(r.end, refs.getOrNull(i + 1)?.start ?: line.length)
            val before = line.substring(refs.getOrNull(i - 1)?.end ?: 0, r.start)
            // "1234 Hyderabad Sri Sai Transport": the place, then the vendor.
            val sv = splitVendor(after, hints) ?: splitVendor(before, hints)
            val city = if (sv != null) cityOf(sv.first, emptyList()).ifEmpty { cityOf(before, emptyList()) }
                else cityOf(after, emptyList()).ifEmpty { cityOf(before, emptyList()) }
            val vendor = sv?.let { vendorOf(it.second) }.orEmpty()
            val cands = resolve(r)
            when {
                r.full -> captures.add(Capture(r.value, cands.ifEmpty { listOf(r.value) }, true, city, "", vendor = vendor))
                cands.isNotEmpty() -> captures.add(Capture(r.value, cands, false, city, "", vendor = vendor))
                else -> {
                    // Not in the vehicle list yet: still a vehicle reported (counted), matched later if added.
                    unmatched.add(r.value)
                    captures.add(Capture(r.value, listOf(r.value), false, city, "", vendor = vendor, inList = false))
                }
            }
        }
    }

    private val SIZE_FT = Regex("\\b(\\d{2})\\s*(?:ft|feet|feets|fit)\\b(?:\\s*(mxl|sxl|multi\\s*axle|single\\s*axle))?", RegexOption.IGNORE_CASE)
    private val SIZE_NAMED = Regex("\\b(407|709|909|tata\\s*ace|ace|pick\\s*up|pickup|dost|eicher|bolero)\\b", RegexOption.IGNORE_CASE)
    private val SIZE_TON = Regex("(?<![0-9.])(\\d{1,2}(?:\\.\\d)?)\\s*(?:tons?|tonne|tn|mt)\\b", RegexOption.IGNORE_CASE)

    /** Vehicle size / type written in a line: "22FT" -> "22 FT", "32 ft mxl" -> "32 FT MXL", "407" -> "407". */
    fun sizeOf(line: String): String {
        SIZE_FT.find(line)?.let { m ->
            val axle = m.groupValues[2].lowercase().let {
                when { it.startsWith("m") -> " MXL"; it.startsWith("s") -> " SXL"; else -> "" }
            }
            return "${m.groupValues[1]} FT$axle"
        }
        SIZE_TON.find(line)?.let { return "${it.groupValues[1]} TON" }
        return SIZE_NAMED.find(line)?.value?.uppercase()?.replace(Regex("\\s+"), " ").orEmpty()
    }

    private fun mask(s: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return s
        val sb = StringBuilder(s)
        ranges.forEach { r -> for (i in r) if (i < sb.length) sb.setCharAt(i, ' ') }
        return sb.toString()
    }

    /** Delivery location from its own line: "Isnapur to Raichur", "ELURU 22FT" -> "Eluru". */
    fun locationOf(line: String): String {
        val words = SIZE.replace(line, " ").split(Regex("[^\\p{L}]+"))
            .filter { it.length >= 2 && (it.equals("to", true) || it.lowercase() !in FILLER) }
            .dropWhile { it.equals("to", true) }.dropLastWhile { it.equals("to", true) }
            .take(6)
        return words.joinToString(" ") { w -> if (w.equals("to", true)) "to" else titleCase(w) }
    }

    /** Vendor from the block's last line: "Veerbhadra cargo" -> "Veerbhadra Cargo". */
    fun vendorOf(line: String): String =
        SIZE.replace(NOTE.replace(VENDOR_LABEL.replace(line, " "), " "), " ").split(Regex("[^\\p{L}&.]+"))
            .map { it.trim('.') }.filter { it.isNotEmpty() }
            .take(6)
            // Short codes written in capitals stay so: "VRL", "KRT", "TCI".
            .joinToString(" ") { if (it.length in 2..4 && it.all { ch -> ch.isUpperCase() }) it else titleCase(it) }

    private fun titleCase(w: String) = w.lowercase().replaceFirstChar { it.uppercase() }

    /** City = the remaining words (max 3), without filler words, in Title Case. */
    fun cityOf(text: String, skip: List<IntRange>): String {
        val sb = StringBuilder(text)
        skip.forEach { r -> for (i in r) if (i < sb.length) sb.setCharAt(i, ' ') }
        val clean = SIZE.replace(REPL.replace(sb.toString(), " "), " ")
        return clean.split(Regex("[^\\p{L}]+"))
            .filter { it.length >= 2 && it.lowercase() !in FILLER }
            .take(3)
            .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
    }
}
