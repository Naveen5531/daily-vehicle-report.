package com.logistics.vehiclereport


data class Store(val code: String, val name: String, val city: String, val region: String)
data class Vendor(val name: String, val code: String = "")

/** A store found for a location written in the group. [others] = other stores that matched as well. */
data class StoreMatch(val store: Store, val others: Int)

/**
 * Store list (Store Code, Store Name, Actual City, Region) and vendor list (name, optional code).
 * Imported by each user on the Vehicles tab (the app starts with empty lists).
 * Locations and vendors written by drivers are matched leniently: spelling like
 * "Machalipatnam", "Rajmundhry", "Nuziveedu", "Veerbhadra" still finds the right entry.
 */
class Masters(val stores: List<Store>, val vendors: List<Vendor>) {

    // ---------- Stores ----------

    private class StoreKey(val store: Store, val segments: List<List<String>>)

    private val storeKeys: List<StoreKey> = stores.map { st ->
        val segs = (splitSegments(st.city) + splitSegments(st.name))
            .map { seg -> words(seg).filter { it !in STORE_NOISE } }
            .filter { it.isNotEmpty() }
        StoreKey(st, segs)
    }

    /** Best store for a delivery location such as "Suryabagh", "Isnapur to Raichur", "ELURU". */
    fun matchStore(location: String): StoreMatch? {
        if (location.isBlank() || stores.isEmpty()) return null
        // "A to B": the delivery point is the last place that matches.
        val parts = location.split(Regex("\\bto\\b", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
        for (part in parts.reversed()) {
            val loc = words(part).filter { it !in STORE_NOISE && it !in ORIGIN_WORDS && it != "1041" }
            if (loc.isEmpty()) continue
            val scored = storeKeys.map { k -> k.store to score(loc, k) }.filter { it.second > 0 }
            val best = scored.maxOfOrNull { it.second } ?: continue
            val top = scored.filter { it.second == best }.map { it.first }
            return StoreMatch(top.first(), top.size - 1)
        }
        return null
    }

    private fun score(loc: List<String>, k: StoreKey): Int {
        var best = 0
        val locJoin = loc.joinToString("")
        k.segments.forEachIndexed { i, seg ->
            val firstCity = if (i == 0) 1 else 0
            val sc = when {
                same(locJoin, seg.joinToString("")) -> 4
                // every word of the store's place is in the location ("Raipur club" -> "Raipur")
                seg.filter { it.length > 1 }.let { w -> w.isNotEmpty() && w.all { s -> loc.any { l -> same(l, s) } } } -> 4
                // written as one word: "Himyatnagar" = "Himayat Nagar", "Sd road" = "S.D Road"
                seg.indices.any { a -> (a until seg.size).any { b -> same(locJoin, seg.subList(a, b + 1).joinToString("")) } } -> 3
                loc.any { l -> l.length >= 4 && seg.any { s -> same(l, s) } } -> 1
                else -> 0
            }
            if (sc > 0) best = maxOf(best, sc + firstCity)
        }
        return best
    }

    // ---------- Vendors ----------

    /** Vendor from the list for what was written, e.g. "Veerbhadra cargo" -> VEERABHADRA CORGO MOVERS. */
    fun matchVendor(written: String): Vendor? {
        val w = words(written)
        if (w.isEmpty() || vendors.isEmpty()) return null
        val wKey = w.filter { it !in VENDOR_NOISE }.ifEmpty { w }
        var best: Vendor? = null
        var bestScore = 0.0
        var tie = false
        for (v in vendors) {
            val all = words(v.name)
            val key = all.filter { it !in VENDOR_NOISE }.ifEmpty { all }
            val hits = wKey.count { a -> key.any { b -> same(a, b) } }
            if (hits == 0) continue
            val score = hits.toDouble() / maxOf(wKey.size, key.size)
            when {
                score > bestScore -> { best = v; bestScore = score; tie = false }
                score == bestScore -> tie = true
            }
        }
        return if (best != null && !tie && bestScore >= 0.5) best else null
    }

    /** Lets the message reader tell places and vendors from driver names and remarks. */
    fun asHints(): MessageParser.Hints = object : MessageParser.Hints {
        override fun place(text: String): Int = matchStore(text)?.let { if (it.others == 0) 2 else 1 } ?: 0
        override fun vendor(text: String): Boolean = matchVendor(text) != null
    }

    /** Vendors whose name words all appear in [text] (e.g. "...Nuziveedu Himagiri" -> HIMAGIRI GOODS CARRIERS). */
    fun vendorsIn(text: String): List<Vendor> {
        val tw = words(text)
        if (tw.isEmpty()) return emptyList()
        return vendors.filter { v ->
            val key = words(v.name).filter { it !in VENDOR_NOISE && it.length >= 2 }
            key.isNotEmpty() && key.all { k -> tw.any { same(it, k) } }
        }
    }

    /**
     * Vendor for one entry, from the best source available:
     * the vendor line under the vehicle, a vendor named in the message, the WhatsApp group's name
     * ("1041 KOHINOOR TPT", "Amjad transport 1041 dc isnapur"), then the vehicle list's transporter.
     */
    fun resolveVendor(written: String, message: String, chat: String, transporter: String): Vendor? {
        if (written.isNotBlank()) matchVendor(written)?.let { return it }
        vendorsIn(message).singleOrNull()?.let { return it }
        if (chat.isNotBlank()) matchVendor(chat)?.let { return it }
        if (transporter.isNotBlank()) matchVendor(transporter)?.let { return it }
        return null
    }

    companion object {
        /** Every vehicle is dispatched from this DC; it is never the delivery point. */
        /** The user's own DC / warehouse name (set on first start; "" until then). Shown as the dispatch point. */
        @Volatile var dispatchName: String = ""
        val DISPATCH_FROM: String get() = dispatchName.ifBlank { "DC" }
        private val ORIGIN_WORDS = setOf("isnapur", "isnapuram", "dc", "depot")

        fun matchedLabel(store: Boolean, vendor: Boolean) = when {
            store && vendor -> "Location + Vendor"
            store -> "Location only"
            vendor -> "Vendor only"
            else -> "Not matched"
        }

        val STORE_NOISE = setOf(
            "mart", "dmart", "dma", "ap", "tl", "near", "dist", "district", "the", "and", "road", "rd", "club"
        )
        val VENDOR_NOISE = setOf(
            "logistics", "logistic", "logostic", "logistik", "services", "service", "cargo", "corgo", "movers", "mover",
            "transport", "transports", "travels", "carriers", "carrier", "goods", "roadlines", "roadways", "road", "lines",
            "corporation", "corpn", "corp", "pvt", "private", "priva", "limited", "ltd", "l", "and", "co", "company",
            "management", "solutions", "solution", "sri", "sree", "shri", "trans", "transways", "enterprises",
            // words in WhatsApp group names
            "tpt", "local", "team", "group", "dc", "isnapur", "depot", "vehicles", "vehicle"
        )


        fun splitSegments(s: String) = s.split(Regex("[,()/\\-]+")).map { it.trim() }.filter { it.isNotEmpty() }

        fun words(s: String): List<String> =
            s.lowercase().replace(Regex("\\bd[\\s_.-]*ma(?:rt?)?\\b"), " ").split(Regex("[^a-z]+")).filter { it.isNotEmpty() }

        /** Same place / name allowing common spelling differences (vowels, h, w/v, doubled letters). */
        fun same(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.length < 5 || b.length < 5) return false
            val sa = skeleton(a); val sb = skeleton(b)
            return sa.length >= 3 && sa == sb
        }

        private fun skeleton(w: String): String {
            val s = w.replace("ph", "f").replace('w', 'v').replace('z', 'j').filter { it !in "aeiouyh" }
            val out = StringBuilder()
            for (ch in s) if (out.isEmpty() || out.last() != ch) out.append(ch)
            return out.toString()
        }
    }
}
