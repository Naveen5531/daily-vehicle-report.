package com.logistics.vehiclereport

import android.content.Context
import org.json.JSONObject

/**
 * Standing / In-transit / At DC now tables that the office Excel (App Data workbook) sends with the gate log
 * through the laptop link. Kept per phone; the newest send replaces the old one.
 */
object YardStore {
    const val KEY_SHEETS = "yard_sheets"
    const val KEY_AT = "yard_at"

    /** A table as Excel sent it: column names and rows of text. */
    data class Table(val headers: List<String>, val rows: List<List<String>>) {
        fun col(vararg names: String): Int {
            for (n in names) {
                val i = headers.indexOfFirst { it.contains(n, ignoreCase = true) }
                if (i >= 0) return i
            }
            return -1
        }
    }

    private fun p(c: Context) = c.getSharedPreferences("yard", Context.MODE_PRIVATE)

    fun save(c: Context, sheets: JSONObject, at: Long) {
        p(c).edit().putString(KEY_SHEETS, sheets.toString()).putLong(KEY_AT, at).apply()
    }

    fun lastAt(c: Context): Long = p(c).getLong(KEY_AT, 0L)

    fun table(c: Context, name: String): Table {
        val all = try { JSONObject(p(c).getString(KEY_SHEETS, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        return parse(all.optJSONObject(name))
    }

    fun parse(o: JSONObject?): Table {
        if (o == null) return Table(emptyList(), emptyList())
        val h = o.optJSONArray("headers")
        val headers = if (h == null) emptyList() else (0 until h.length()).map { h.optString(it) }
        val r = o.optJSONArray("rows")
        val rows = if (r == null) emptyList() else (0 until r.length()).mapNotNull { i ->
            val a = r.optJSONArray(i) ?: return@mapNotNull null
            (0 until a.length()).map { a.optString(it) }
        }
        return Table(headers, rows)
    }
}
