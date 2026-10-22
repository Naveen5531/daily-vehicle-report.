package com.logistics.vehiclereport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One rate: trip cost for a vehicle type to a store. */
data class Rate(val storeCode: String, val storeName: String, val vehicleType: String, val amount: String)

/**
 * Master rate card (Rates tab): Store Code + Vehicle Type -> cost.
 * Imported from Excel / CSV in either layout:
 *  - one row per rate: Store Code | (Store Name) | Vehicle Type | Rate
 *  - one row per store, one column per vehicle type: Store Code | 14 FT | 17 FT | 22 FT | 32 FT MXL ...
 */
class RateCard(val rates: List<Rate>) {

    private val byStore: Map<String, List<Rate>> = rates.groupBy { cleanCode(it.storeCode) }

    /** Cost for a store and vehicle type ("22FT", "22 feet", "32 FT MXL"); null if the card has none. */
    fun lookup(storeCode: String, vehicleType: String): Rate? {
        if (storeCode.isBlank() || vehicleType.isBlank()) return null
        val list = byStore[cleanCode(storeCode)] ?: return null
        val want = normType(vehicleType)
        list.firstOrNull { normType(it.vehicleType) == want }?.let { return it }
        // Same length in feet when only one rate for that length exists ("22 FT" vs "22 FT SXL").
        val feet = feetOf(want) ?: return null
        return list.filter { feetOf(normType(it.vehicleType)) == feet }.singleOrNull()
    }

    companion object {
        private const val PREF_KEY = "rate_card_json"
        @Volatile private var cached: RateCard? = null

        /** Forgets the card in memory (another account signed in). */
        fun reset() { cached = null }

        fun get(context: Context): RateCard = cached ?: synchronized(this) {
            cached ?: load(context).also { cached = it }
        }

        fun save(context: Context, card: RateCard) {
            val arr = JSONArray()
            card.rates.forEach {
                arr.put(JSONObject().put("code", it.storeCode).put("name", it.storeName).put("type", it.vehicleType).put("amount", it.amount))
            }
            VehicleStore.prefs(context).edit().putString(PREF_KEY, arr.toString()).apply()
            cached = card
        }

        private fun load(context: Context): RateCard = try {
            val arr = JSONArray(VehicleStore.prefs(context).getString(PREF_KEY, "[]") ?: "[]")
            RateCard((0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Rate(it.optString("code"), it.optString("name"), it.optString("type"), it.optString("amount"))
            })
        } catch (e: Exception) { RateCard(emptyList()) }

        fun cleanCode(s: String) = s.trim().removeSuffix(".0").trimStart('0').ifEmpty { s.trim() }

        /** "22ft" / "22 Feet" / "22FT MXL" -> "22 FT" / "22 FT MXL"; anything else upper-cased. */
        fun normType(s: String): String {
            val t = s.uppercase().replace(Regex("(\\d)\\s*(FEET|FEETS|FT|FIT|')"), "$1 FT")
                .replace("MULTI AXLE", "MXL").replace("SINGLE AXLE", "SXL")
                .replace(Regex("[^A-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            return t
        }

        private fun feetOf(norm: String) = Regex("^(\\d{1,2}) FT").find(norm)?.groupValues?.get(1)

        private fun looksLikeType(h: String): Boolean {
            val n = normType(h)
            return Regex("\\d{1,2} FT|\\b407\\b|\\bACE\\b|PICK ?UP|DOST|EICHER|BOLERO|MXL|SXL|TATA").containsMatchIn(n)
        }

        private fun amountOf(s: String): String {
            val v = s.replace(",", "").replace("₹", "").replace("Rs", "", ignoreCase = true).trim().removeSuffix(".0")
            return if (v.toDoubleOrNull() != null && v.toDouble() > 0) v else ""
        }

        /** Reads a rate card from Excel / CSV bytes (every sheet). */
        fun fromFile(bytes: ByteArray): RateCard {
            val sheets = if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
                VehicleListImporter.xlsxSheets(bytes) else listOf(VehicleListImporter.textRows(bytes))
            val out = mutableListOf<Rate>()
            for (rows in sheets) {
                val hi = rows.take(10).indexOfFirst { r -> r.any { it.lowercase().contains("code") || it.lowercase().contains("store") } }
                if (hi < 0) continue
                val h = rows[hi].map { it.trim().lowercase() }
                val cCode = h.indexOfFirst { it.contains("code") }.takeIf { it >= 0 } ?: h.indexOfFirst { it.contains("store") || it.contains("site") }
                val cName = h.indexOfFirst { (it.contains("name") || it.contains("store")) && !it.contains("code") }.takeIf { it != cCode } ?: -1
                val cType = h.indexOfFirst { it.contains("type") || it.contains("size") || it.contains("capacity") || it == "vehicle" }
                val cRate = h.indexOfFirst { listOf("rate", "cost", "amount", "freight", "price", "charge").any { k -> it.contains(k) } }
                val body = rows.drop(hi + 1)
                if (cType >= 0 && cRate >= 0) {
                    for (r in body) {
                        val code = r.getOrNull(cCode).orEmpty().trim().removeSuffix(".0")
                        val type = r.getOrNull(cType).orEmpty().trim()
                        val amt = amountOf(r.getOrNull(cRate).orEmpty())
                        if (code.isNotEmpty() && type.isNotEmpty() && amt.isNotEmpty())
                            out += Rate(code, r.getOrNull(cName).orEmpty().trim(), normType(type), amt)
                    }
                } else {
                    val typeCols = rows[hi].indices.filter { it != cCode && it != cName && looksLikeType(rows[hi][it]) }
                    for (r in body) {
                        val code = r.getOrNull(cCode).orEmpty().trim().removeSuffix(".0")
                        if (code.isEmpty()) continue
                        for (c in typeCols) {
                            val amt = amountOf(r.getOrNull(c).orEmpty())
                            if (amt.isNotEmpty()) out += Rate(code, r.getOrNull(cName).orEmpty().trim(), normType(rows[hi][c]), amt)
                        }
                    }
                }
            }
            return RateCard(out.distinctBy { cleanCode(it.storeCode) + "|" + it.vehicleType })
        }

        /** Template: every store from the store list, with the common vehicle types as columns. */
        fun templateRows(masters: Masters): List<List<String>> {
            val types = listOf("14 FT", "17 FT", "19 FT", "20 FT", "22 FT", "24 FT", "32 FT SXL", "32 FT MXL")
            return listOf(listOf("Store Code", "Store Name") + types) +
                masters.stores.map { listOf(it.code, it.name) + types.map { "" } }
        }
        val TEMPLATE_WIDTHS = listOf(11, 32, 9, 9, 9, 9, 9, 9, 11, 11)
    }
}
