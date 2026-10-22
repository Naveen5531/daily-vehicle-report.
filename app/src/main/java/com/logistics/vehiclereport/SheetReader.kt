package com.logistics.vehiclereport

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.tan

/** A word the camera text reader found, with its box on the photo and the slant of its line (degrees). */
data class OcrWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val angle: Float = 0f)

/**
 * Reads a "VEHICLE REQ" sheet: Ship-To, Name of the ship-to party, Volume, Total Wght, Boxes, Vehicle, Circle, Remark.
 * From a photo (camera text, word by word) or from the Excel file itself.
 *
 * Photos of a screen are read with slips this fixes: a lost decimal point ("425239" for 425.239, put back where the
 * weight and boxes say it belongs), "-" or "/" for the point, two numbers run together, "o" for 0, "|" from the
 * grid lines, and store codes read wrong (put right from the store list by the store's name).
 */
object SheetReader {

    /** What one photo or file gave: the store lines and, when the sheet shows it, its total volume (to check the reading). */
    data class Result(val stops: List<PlanStop>, val sheetTotal: Double?) {
        val readTotal: Double get() = stops.sumOf { it.volume }
        /** True when the sheet's own total matches what was read (or the sheet shows no total). */
        val totalOk: Boolean get() = sheetTotal == null || abs(sheetTotal - readTotal) < 0.6
    }

    private val CODE = Regex("^[A-Za-z|!]{0,2}(\\d{2,6})([oO](?=[A-Z|]|$))?[|!]?(.*)$")
    private val NUMBER = Regex("^[0-9][0-9,]*([.][0-9]+)?$")
    private val CIRCLE_START = Regex("^([0-9oO])?([|/J!lI1])?([A-Z][A-Za-z]+.*)$")

    /** "1,436.065" -> 1436.065; also "1436,065", "704-461", "507/242", "I704.461". */
    fun number(t: String): Double? = numberText(t)?.toDoubleOrNull()

    private fun numberText(t: String): String? {
        var s = t.trim().trimEnd('.', ',', ':', ';', '|', '!').trimStart('|', '!', 'I', 'l').replace(" ", "")
        if (s.isEmpty() || !s[0].isDigit()) return null
        s = s.replace(Regex("(?<=\\d)[-/](?=\\d)"), ".")
        // The sheet has no thousands commas: a comma without a point is a point read wrong (58782,128).
        if (s.contains(',') && !s.contains('.')) s = s.substring(0, s.lastIndexOf(',')).replace(",", "") + "." + s.substring(s.lastIndexOf(',') + 1)
        s = s.replace(",", "")
        return if (NUMBER.matches(s)) s else null
    }

    /** Decimal places a number was written with ("425239" -> 0, "425.239" -> 3). */
    private fun decimals(t: String): Int = numberText(t)?.let { s -> if (s.contains('.')) s.length - s.indexOf('.') - 1 else 0 } ?: 0

    // ---------- Photo ----------

    fun fromWords(words: List<OcrWord>, known: Map<String, String> = emptyMap()): Result {
        if (words.isEmpty()) return Result(emptyList(), null)
        // Straighten with the slant of lines that hold several words (single words report no slant).
        val slanted = words.map { it.angle }.filter { abs(it) >= 0.1f }.sorted()
        val angle = if (slanted.size >= 3) slanted[slanted.size / 2] else 0f
        val slope = tan(Math.toRadians(angle.toDouble())).toFloat()
        class W(val text: String, val x: Float, val y: Float, val h: Float, val left: Float)
        val ws = words.filter { it.text.isNotBlank() }.map {
            val cx = (it.left + it.right) / 2f
            val cy = (it.top + it.bottom) / 2f
            W(it.text.trim(), cx, cy - cx * slope, it.bottom - it.top, it.left)
        }
        if (ws.isEmpty()) return Result(emptyList(), null)
        val hs = ws.map { it.h }.sorted()
        val h = hs[hs.size / 2].coerceAtLeast(4f)

        // Rows: words whose straightened middles are within about half a line of each other.
        val rows = mutableListOf<MutableList<W>>()
        for (w in ws.sortedBy { it.y }) {
            val row = rows.lastOrNull()
            if (row != null && abs(w.y - row.map { it.y }.average().toFloat()) < h * 0.6f) row += w else rows += mutableListOf(w)
        }

        val stops = mutableListOf<PlanStop>()
        var total: Double? = null
        for (row in rows) {
            val tokens = tokens(row.sortedBy { it.left }.map { it.text })
            val stop = parseRow(tokens)
            if (stop != null) stops += stop
            else if (stops.isEmpty() && total == null) total = totalOf(tokens)
        }
        return Result(fixCodes(dedupe(stops), known), total)
    }

    /** Splits words into tokens: grid lines "|", two numbers run together, a vehicle count stuck to the circle. */
    fun tokens(texts: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (t in texts) for (p0 in t.replace('|', ' ').replace('!', ' ').trim().split(' ')) {
            if (p0.isBlank()) continue
            // 512.7532441.032 -> 512.753 2441.032
            val two = Regex("^(\\d+[.]\\d{3})(\\d+[.]\\d+)$").find(p0)
            if (two != null) { out += two.groupValues[1]; out += two.groupValues[2]; continue }
            // 4955922Telangan -> 4955922 Telangan (but codes like 5043Nalla are taken apart by the row reader)
            val numText = Regex("^(\\d{1,})([A-Z][A-Za-z].*)$").find(p0)
            if (numText != null && out.isNotEmpty()) { out += numText.groupValues[1]; out += numText.groupValues[2]; continue }
            out += p0
        }
        return out
    }

    /** The yellow totals line above the table: only numbers; the volume total gets its point back from the weight. */
    private fun totalOf(tokens: List<String>): Double? {
        val nums = tokens.mapNotNull { t -> number(t)?.let { t to it } }
        if (nums.size < 3 || nums.size < tokens.size - 1) return null
        val (vt, v) = nums[0]
        val w = nums[1].second
        return if (decimals(vt) == 0) repair(v, w, null, 1.5, 8.0) else v
    }

    /**
     * Puts back a lost decimal point: of [v] / 10, 100, 1000 takes the one whose weight per volume ([w]) and boxes per
     * volume ([boxes]) look like a normal store (about 2–8 kg and 0.3–1.2 boxes per unit), else 3 places.
     */
    private fun repair(v: Double, w: Double?, boxes: Int?, lo: Double, hi: Double): Double {
        val options = listOf(v / 1000, v / 100, v / 10, v)
        fun score(x: Double): Double {
            var s = 0.0
            if (w != null && w > 0) { val r = w / x; s += if (r in lo..hi) 0.0 else abs(ln(r / ((lo + hi) / 2))) }
            if (boxes != null && boxes > 0) { val r = boxes / x; s += if (r in 0.3..1.3) 0.0 else abs(ln(r / 0.6)) }
            return s
        }
        if ((w == null || w <= 0) && (boxes == null || boxes <= 0)) return v / 1000
        return options.minByOrNull { score(it) } ?: v
    }

    /** One table line: code, name words, then volume, weight, boxes, vehicles, circle words, remark words. */
    fun parseRow(tokensIn: List<String>): PlanStop? {
        if (tokensIn.isEmpty()) return null
        val tokens = tokensIn.toMutableList()
        val m = CODE.find(tokens[0]) ?: return null
        var code = m.groupValues[1]
        if (m.groupValues[2].isNotEmpty()) code += "0"   // "521oNuzividu": o for 0
        val glued = m.groupValues[3]
        if (glued.isNotEmpty() && glued.none { it.isLetter() }) return null
        tokens.removeAt(0)
        if (glued.isNotEmpty()) tokens.add(0, glued)
        // Name: words up to the first number ("5 GBL Square Mall": a short number before words is part of the name).
        val name = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val isNum = number(t) != null
            if (isNum && !(name.none { w -> w.any { it.isLetter() } } && t.length <= 2 && tokens.getOrNull(i + 1)?.any { it.isLetter() } == true)) break
            name += t; i++
        }
        if (name.none { w -> w.count { it.isLetter() } >= 2 }) return null
        val nums = mutableListOf<Pair<String, Double>>()
        while (i < tokens.size && nums.size < 4) {
            val v = number(tokens[i]) ?: break
            nums += tokens[i] to v; i++
        }
        if (nums.isEmpty()) return null
        // What follows: circle ("Telangana 1", "AP", "BLR"; may start with the vehicle count: "2AP", "o|AP"), then the remark.
        var tail = tokens.drop(i).toMutableList()
        var vehicles: Int? = nums.getOrNull(3)?.takeIf { it.second < 20 && decimals(it.first) == 0 }?.second?.toInt()
        tail.firstOrNull()?.let { first ->
            CIRCLE_START.find(first)?.let { c ->
                val d = c.groupValues[1]
                if (d.isNotEmpty() && vehicles == null) vehicles = if (d[0].isDigit()) d.toInt() else 0
                tail[0] = c.groupValues[3]
            }
        }
        val remarkAt = tail.indexOfFirst { it.equals("only", true) || it.startsWith("cons", true) }
        val circle = (if (remarkAt >= 0) tail.take(remarkAt) else tail).joinToString(" ")
        val remark = if (remarkAt >= 0) tail.drop(remarkAt).joinToString(" ").trim() else ""
        // Volume and weight get a lost decimal point back.
        val boxes = nums.getOrNull(2)?.takeIf { decimals(it.first) == 0 && it.second < 100000 }?.second?.toInt()
        var weight = nums.getOrNull(1)?.second
        var volume = nums[0].second
        if (decimals(nums[0].first) == 0 && volume >= 1000) volume = repair(volume, weight?.takeIf { decimals(nums[1].first) > 0 }, boxes, 1.5, 40.0)
        val w0 = weight
        if (w0 != null && decimals(nums[1].first) == 0 && w0 > volume * 40)
            weight = listOf(w0 / 1000, w0 / 100, w0 / 10).minByOrNull { abs(ln((it / volume) / 4.0)) }
        return PlanStop(code, cleanName(name), volume, weight ?: 0.0, boxes ?: 0, vehicles, cleanCircle(circle), remark)
    }

    private fun cleanName(words: List<String>): String {
        val w = words.toMutableList()
        // a stray letter from the header or the next cell at the end ("Guntur AP DMart P")
        while (w.size > 1 && w.last().length == 1 && w.last()[0].isLetter()) w.removeAt(w.size - 1)
        return w.joinToString(" ").replace(Regex("\\s+"), " ").trim().trim('|', '.', ',', '-', ' ')
    }

    /** "Telangan a1" -> "Telangana 1", "4955922Telangan a1" -> "Telangana 1". */
    private fun cleanCircle(s: String): String = s.replace(Regex("^[0-9|/!\\s]+"), "")
        .replace(Regex("(?<=[a-z]) (?=[a-z])"), "")
        .replace(Regex("([A-Za-z])(\\d)$"), "$1 $2")
        .replace(Regex("\\s+"), " ").trim().trim('|', '.', ',', '-', ' ')

    /** The same store read twice (overlapping photos): the later line wins. */
    fun dedupe(stops: List<PlanStop>): List<PlanStop> {
        val m = LinkedHashMap<String, PlanStop>()
        for (s in stops) { m.remove(s.code); m[s.code] = s }
        return m.values.toList()
    }

    // ---------- Store codes read wrong ----------

    private val NOISE = setOf("dmart", "d", "mart", "ap", "tl", "telangana", "the", "and")
    private fun letters(s: String) = s.lowercase().replace("d-mart", " ").replace(Regex("[^a-z ]"), " ")
        .split(' ').filter { it.isNotBlank() && it !in NOISE }.joinToString("")
    private fun grams(s: String) = if (s.length < 3) setOf(s) else (0..s.length - 3).map { s.substring(it, it + 3) }.toSet()
    private fun similar(a: String, b: String): Double {
        val x = grams(letters(a)); val y = grams(letters(b))
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 2.0 * x.intersect(y).size / (x.size + y.size)
    }
    private fun codeNear(read: String, real: String): Boolean =
        real.endsWith(read) || real.startsWith(read) ||
            (read.length == real.length && read.indices.count { read[it] != real[it] } <= 1)

    /**
     * A code not in the store list ([known]: code -> store name) is replaced by the listed store whose name matches the
     * line's name best (helped when the digits are close: "31" for 5331, "5988" for 5338).
     */
    fun fixCodes(stops: List<PlanStop>, known: Map<String, String>): List<PlanStop> {
        if (known.isEmpty()) return stops
        val taken = stops.map { it.code }.filter { it in known }.toMutableSet()
        return stops.map { s ->
            if (s.code in known) return@map s
            val scored = known.filter { it.key !in taken }.map { (code, name) ->
                code to similar(s.name, name) + if (codeNear(s.code, code)) 0.25 else 0.0
            }.sortedByDescending { it.second }
            val best = scored.firstOrNull() ?: return@map s
            val second = scored.getOrNull(1)?.second ?: 0.0
            if (best.second >= 0.55 && best.second - second >= 0.1) { taken += best.first; s.copy(code = best.first) } else s
        }
    }

    // ---------- Excel ----------

    /** Rows of every sheet of the workbook: finds the header (Ship-To, Name, Volume, …) and reads the lines under it. */
    fun fromRows(sheets: List<List<List<String>>>): Result {
        val out = mutableListOf<PlanStop>()
        var total: Double? = null
        for (rows in sheets) {
            val hi = rows.indexOfFirst { r -> r.any { it.lowercase().contains("vol") } && r.any { c -> c.lowercase().let { it.contains("ship") || it.contains("code") } } }
            if (hi < 0) continue
            val h = rows[hi].map { it.lowercase().trim() }
            fun col(vararg keys: String, not: String? = null) = h.indexOfFirst { c -> keys.any { c.contains(it) } && (not == null || !c.contains(not)) }
            val cCode = col("ship-to", "ship to", "code", "site", not = "name").let { if (it >= 0) it else col("ship") }
            val cName = col("name", "party", "store")
            val cVol = col("vol")
            val cWt = col("wght", "weight", "wgt")
            val cBox = col("box")
            val cVeh = col("vehicle")
            val cCircle = col("circle", "region", "zone")
            val cRemark = col("remark")
            if (cCode < 0 || cVol < 0) continue
            // A totals line above the header (only numbers) for the check
            if (total == null) rows.take(hi).lastOrNull { r -> r.getOrNull(cVol)?.let { number(it) } != null }?.let { r -> total = number(r[cVol]) }
            for (r in rows.drop(hi + 1)) {
                val code = r.getOrNull(cCode).orEmpty().trim().removeSuffix(".0")
                val vol = r.getOrNull(cVol)?.let { number(it.trim()) } ?: continue
                if (!Regex("^\\d{3,7}$").matches(code)) continue
                out += PlanStop(
                    code, r.getOrNull(cName).orEmpty().replace(Regex("\\s+"), " ").trim(), vol,
                    r.getOrNull(cWt)?.let { number(it.trim()) } ?: 0.0,
                    r.getOrNull(cBox)?.let { number(it.trim()) }?.toInt() ?: 0,
                    r.getOrNull(cVeh)?.let { number(it.trim()) }?.toInt(),
                    r.getOrNull(cCircle).orEmpty().trim(), r.getOrNull(cRemark).orEmpty().trim()
                )
            }
        }
        return Result(dedupe(out), total)
    }
}
