package com.logistics.vehiclereport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Loads / saves the store and vendor lists imported on the Vehicles tab (empty until the user imports them). */
object MasterData {
    private const val PREF_KEY = "masters_json"
    @Volatile private var cached: Masters? = null

    fun get(context: Context): Masters = cached ?: synchronized(this) {
        cached ?: load(context).also { cached = it }
    }

    /** Forgets the lists in memory (another account signed in). */
    fun reset() { cached = null }

    /** Saves an imported workbook's lists. */
    fun save(context: Context, m: Masters) {
        val o = JSONObject()
        o.put("stores", JSONArray().apply {
            m.stores.forEach { put(JSONObject().put("code", it.code).put("name", it.name).put("city", it.city).put("region", it.region)) }
        })
        o.put("vendors", JSONArray().apply { m.vendors.forEach { put(JSONObject().put("name", it.name).put("code", it.code)) } })
        VehicleStore.prefs(context).edit().putString(PREF_KEY, o.toString()).apply()
        cached = m
    }

    private fun load(context: Context): Masters {
        VehicleStore.prefs(context).getString(PREF_KEY, null)?.let { json ->
            try {
                val o = JSONObject(json)
                val st = o.getJSONArray("stores"); val vd = o.getJSONArray("vendors")
                return Masters(
                    (0 until st.length()).map { st.getJSONObject(it) }
                        .map { Store(it.optString("code"), it.optString("name"), it.optString("city"), it.optString("region")) },
                    (0 until vd.length()).map { vd.getJSONObject(it) }.map { Vendor(it.optString("name"), it.optString("code")) }
                )
            } catch (e: Exception) { }
        }
        return Masters(emptyList(), emptyList())
    }

    /** Reads a workbook: the sheet with a "Store Code" header is the store list; another sheet is the vendor list. */
    fun fromWorkbook(bytes: ByteArray): Masters {
        val sheets = VehicleListImporter.xlsxSheets(bytes).map { rows -> rows.map { r -> r.map { clean(it) } } }
        var stores = emptyList<Store>()
        var vendors = emptyList<Vendor>()
        for (rows in sheets) {
            val hi = rows.take(10).indexOfFirst { r -> r.any { it.lowercase().replace(" ", "") in setOf("storecode", "sitecode", "code") } &&
                r.any { it.lowercase().contains("city") || it.lowercase().contains("store name") } }
            if (hi >= 0 && stores.isEmpty()) {
                val h = rows[hi].map { it.lowercase() }
                fun col(vararg names: String) = h.indexOfFirst { c -> names.any { c.contains(it) } }
                val cCode = col("code"); val cName = col("name"); val cCity = col("city", "location"); val cRegion = col("region", "zone")
                stores = rows.drop(hi + 1).mapNotNull { r ->
                    val code = r.getOrNull(cCode).orEmpty().removeSuffix(".0")
                    if (code.isBlank()) null
                    else Store(code, r.getOrNull(cName).orEmpty(), r.getOrNull(cCity).orEmpty(), r.getOrNull(cRegion).orEmpty())
                }
            } else if (vendors.isEmpty() && rows.isNotEmpty()) {
                // Vendor name = the column with the most text; code = a column headed "code" (optional).
                val h = rows.first().map { it.lowercase() }
                val hasHeader = h.any { it.contains("vendor") || it.contains("name") || it.contains("code") || it.contains("transporter") }
                val body = if (hasHeader) rows.drop(1) else rows
                val cCode = if (hasHeader) h.indexOfFirst { it.contains("code") } else -1
                val width = body.maxOfOrNull { it.size } ?: 0
                val cName = (0 until width).filter { it != cCode }
                    .maxByOrNull { c -> body.count { r -> r.getOrNull(c).orEmpty().any { ch -> ch.isLetter() } } } ?: 0
                vendors = body.mapNotNull { r ->
                    val n = r.getOrNull(cName).orEmpty()
                    if (n.none { it.isLetter() }) null else Vendor(n, if (cCode >= 0) r.getOrNull(cCode).orEmpty().removeSuffix(".0") else "")
                }
            }
        }
        return Masters(stores, vendors)
    }

    private fun clean(s: String) = s.replace(' ', ' ').replace("​", "").replace("‎", "").trim()
}
