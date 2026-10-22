package com.logistics.vehiclereport

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Reads a vehicle list from Excel (.xlsx), CSV or text.
 * Columns are found by header name: Vehicle No, Type, Driver, Mobile, Transporter, Route.
 * Without headers it still picks up every vehicle number it finds.
 * Old .xls files: only the vehicle numbers are picked up (save as .xlsx to import details).
 */
object VehicleListImporter {

    fun read(bytes: ByteArray): List<DedicatedVehicle> {
        val rows = when {
            isZip(bytes) -> xlsxRows(bytes)
            looksLikeText(bytes) -> csvRows(bytes.toString(Charsets.UTF_8).removePrefix("﻿"))
            else -> emptyList()
        }
        val fromRows = vehiclesFromRows(rows)
        if (fromRows.isNotEmpty()) return fromRows

        // Fallback (e.g. old .xls): scan all text for numbers.
        val text = if (isZip(bytes)) rows.filterNot(VehicleTemplate::isExampleRow).flatten().joinToString(" ")
        else String(bytes, Charsets.ISO_8859_1).replace("\u0000", "")
        return VehicleParser.extract(text).map { DedicatedVehicle(it) }
    }

    // ---------- Column detection ----------

    private enum class Col { NUMBER, TYPE, DRIVER, MOBILE, TRANSPORTER, ROUTE }

    private fun classify(header: String): Col? {
        val h = header.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (h.isEmpty()) return null
        if (Regex("^(s|sl|sr) ?no$|serial|^sno$|^#$").containsMatchIn(h)) return null
        return when {
            Regex("mobile|phone|contact|cell|whatsapp").containsMatchIn(h) -> Col.MOBILE
            Regex("driver").containsMatchIn(h) -> Col.DRIVER
            Regex("transport|vendor|owner|agency|supplier|operator|party").containsMatchIn(h) -> Col.TRANSPORTER
            Regex("route|location|area|place|branch|site|zone|plant").containsMatchIn(h) -> Col.ROUTE
            Regex("type|size|capacity|model|body|tonnage|feet|\\bft\\b").containsMatchIn(h) -> Col.TYPE
            Regex("vehicle|veh|truck|lorry|reg|registration|number|\\bno\\b").containsMatchIn(h) -> Col.NUMBER
            else -> null
        }
    }

    private fun vehiclesFromRows(rows: List<List<String>>): List<DedicatedVehicle> {
        if (rows.isEmpty()) return emptyList()

        // Header = first of the top 10 rows that has a recognised "vehicle number" column and no plate in it.
        var headerIndex = -1
        var map: Map<Col, Int> = emptyMap()
        for (i in 0 until minOf(10, rows.size)) {
            val row = rows[i]
            if (row.any { VehicleParser.extract(it).isNotEmpty() }) continue
            val m = mutableMapOf<Col, Int>()
            row.forEachIndexed { c, cell -> classify(cell)?.let { col -> m.putIfAbsent(col, c) } }
            if (Col.NUMBER in m || m.size >= 2) { headerIndex = i; map = m; break }
        }

        val out = LinkedHashMap<String, DedicatedVehicle>()
        for (r in (headerIndex + 1) until rows.size) {
            val row = rows[r]
            if (VehicleTemplate.isExampleRow(row)) continue
            fun cell(col: Col) = map[col]?.let { row.getOrNull(it) }?.trim().orEmpty()

            val number = VehicleParser.extract(cell(Col.NUMBER)).firstOrNull()
                ?: row.firstNotNullOfOrNull { VehicleParser.extract(it).firstOrNull() }
                ?: continue
            out[number] = DedicatedVehicle(
                number = number,
                type = cell(Col.TYPE),
                driver = cell(Col.DRIVER),
                mobile = cleanMobile(cell(Col.MOBILE)),
                transporter = cell(Col.TRANSPORTER),
                route = cell(Col.ROUTE)
            )
        }
        return out.values.toList()
    }

    /** Excel can store 9876543210 as 9.87654321E9 */
    private fun cleanMobile(s: String): String {
        val d = s.toBigDecimalOrNull()
        return if (d != null && s.contains('E', ignoreCase = true)) d.toBigInteger().toString() else s.removeSuffix(".0")
    }

    // ---------- File formats ----------

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private fun looksLikeText(b: ByteArray): Boolean {
        val sample = b.take(2000)
        return sample.isNotEmpty() && sample.count { it == 0.toByte() } == 0
    }

    /** Rows of a CSV / text file. */
    fun textRows(bytes: ByteArray): List<List<String>> = csvRows(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))

    private fun csvRows(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(',', ';', '\t', '|').maxByOrNull { c -> firstLine.count { it == c } } ?: ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == sep -> { row += cell.toString(); cell.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotBlank() }) rows += row
                    row = mutableListOf()
                }
                else -> cell.append(ch)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    private fun xlsxRows(bytes: ByteArray): List<List<String>> = xlsxSheets(bytes).firstOrNull().orEmpty()

    /** Every sheet of an .xlsx (sheet1, sheet2, …), each as rows of cell text. */
    fun xlsxSheets(bytes: ByteArray): List<List<List<String>>> {
        var shared = ""
        val sheets = sortedMapOf<Int, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val num = Regex("^xl/worksheets/sheet(\\d+)\\.xml$").find(e.name)?.groupValues?.get(1)?.toIntOrNull()
                when {
                    e.name == "xl/sharedStrings.xml" -> shared = zip.readBytes().toString(Charsets.UTF_8)
                    num != null -> sheets[num] = zip.readBytes().toString(Charsets.UTF_8)
                }
                e = zip.nextEntry
            }
        }
        val strings = Regex("<si>([\\s\\S]*?)</si>").findAll(shared).map { si ->
            Regex("<t[^>]*>([\\s\\S]*?)</t>").findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
        }.toList()

        return sheets.values.map { sheet -> sheetRows(sheet, strings) }
    }

    private fun sheetRows(sheet: String, strings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        Regex("<row[^>]*>([\\s\\S]*?)</row>").findAll(sheet).forEach { rowMatch ->
            val cells = sortedMapOf<Int, String>()
            Regex("<c\\s([^>]*?)(?:/>|>([\\s\\S]*?)</c>)").findAll(rowMatch.groupValues[1]).forEach { c ->
                val attrs = c.groupValues[1]
                val body = c.groupValues[2]
                val ref = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1) ?: return@forEach
                val type = Regex("t=\"(\\w+)\"").find(attrs)?.groupValues?.get(1)
                val v = Regex("<v>([\\s\\S]*?)</v>").find(body)?.groupValues?.get(1)
                val value = when (type) {
                    "s" -> v?.toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
                    "inlineStr" -> Regex("<t[^>]*>([\\s\\S]*?)</t>").findAll(body).joinToString("") { unescape(it.groupValues[1]) }
                    else -> unescape(v.orEmpty())
                }
                cells[colIndex(ref)] = value
            }
            if (cells.isNotEmpty()) {
                val max = cells.lastKey()
                rows += (0..max).map { cells[it].orEmpty() }
            }
        }
        return rows
    }

    private fun colIndex(letters: String): Int = letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
