package com.logistics.vehiclereport

/**
 * Reads an Indian number plate out of camera text (ML Kit), forgiving the usual misreads:
 * O/0, I/1, B/8, S/5, Z/2, G/6, D/0, Q/0. Two-line truck plates ("TS 09 UB" over "4821"),
 * the "IND" strip and dots or dashes are handled by reading the text as one run of letters and digits.
 */
object PlateReader {

    private val STATES = setOf(
        "AN", "AP", "AR", "AS", "BR", "CH", "CG", "DD", "DL", "DN", "GA", "GJ", "HR", "HP", "JK", "JH", "KA", "KL", "LA", "LD",
        "MP", "MH", "MN", "ML", "MZ", "NL", "OD", "OR", "PY", "PB", "RJ", "SK", "TN", "TS", "TG", "TR", "UP", "UK", "UA", "WB"
    )

    private val TO_DIGIT = mapOf('O' to '0', 'Q' to '0', 'D' to '0', 'I' to '1', 'L' to '1', 'Z' to '2', 'S' to '5', 'B' to '8', 'G' to '6')
    private val TO_LETTER = mapOf('0' to 'O', '1' to 'I', '2' to 'Z', '5' to 'S', '8' to 'B', '6' to 'G')

    private fun digit(c: Char): Char? = if (c.isDigit()) c else TO_DIGIT[c]
    private fun letter(c: Char): Char? = if (c in 'A'..'Z') c else TO_LETTER[c]

    /** The text as one run of capital letters and digits ("TS 09 UB\n4821" -> "TS09UB4821"). */
    fun compact(text: String): String = text.uppercase().filter { it in 'A'..'Z' || it.isDigit() }

    /** Two characters the camera often confuses count as the same. */
    private fun same(a: Char, b: Char): Boolean =
        a == b || digit(a) != null && digit(a) == digit(b) && (a.isDigit() || b.isDigit()) || letter(a) != null && letter(a) == letter(b)

    /**
     * A plate from [known] that appears in [text] (at most one character misread, and its last 4 read right),
     * e.g. the list vehicles ending with the 4 digits that were posted.
     */
    fun findKnown(text: String, known: List<String>): String? {
        val s = compact(text)
        var best: String? = null
        var bestMiss = Int.MAX_VALUE
        for (k in known.map { compact(it) }.filter { it.length >= 6 }.distinct()) {
            for (i in 0..s.length - k.length) {
                var miss = 0
                for (j in k.indices) {
                    if (!same(s[i + j], k[j])) { miss++; if (miss > 1) break }
                }
                // The last 4 must be the same digits.
                val tail = (k.length - 4 until k.length).all { j -> digit(s[i + j]) == k[j] }
                if (miss <= 1 && tail && miss < bestMiss) { best = k; bestMiss = miss }
            }
        }
        return best
    }

    /**
     * Plates that can be read in [text] (state + district + series + 4 digits, or Bharat series 22BH1234AA),
     * best first. With [code] only plates ending in those 4 digits.
     */
    fun plates(text: String, code: String? = null): List<String> {
        val s = compact(text)
        val out = LinkedHashSet<String>()
        // Every place 4 digits (after fixing misreads) end; look back for state, district and series.
        for (end in 4..s.length) {
            val num = (end - 4 until end).map { digit(s[it]) }
            if (num.any { it == null }) continue
            val last4 = num.joinToString("")
            if (code != null && last4 != code) continue
            // Not in the middle of a longer number.
            if (end < s.length && s[end].isDigit()) continue
            val before = s.substring(0, end - 4)
            plateBefore(before)?.let { out += it + last4 }
            // Bharat series: 22 BH 1234 AA
            if (before.length >= 4 && end + 1 <= s.length) {
                val y = before.substring(before.length - 4, before.length - 2).map { digit(it) }
                val bh = before.substring(before.length - 2)
                if (y.none { it == null } && bh.map { letter(it) }.joinToString("") == "BH") {
                    val tail = s.substring(end).take(2).map { letter(it) }.takeWhile { it != null }.joinToString("")
                    if (tail.isNotEmpty()) out += y.joinToString("") + "BH" + last4 + tail
                }
            }
        }
        return out.toList()
    }

    /** "TS09UB" / "DL3C" / "TG07W" right before the 4 digits, or null. */
    private fun plateBefore(before: String): String? {
        // series 0-3 letters, district 1-2 digits, state 2 letters; prefer 2-digit districts and longer series.
        for (dLen in intArrayOf(2, 1)) for (sLen in intArrayOf(2, 1, 3, 0)) {
            val len = 2 + dLen + sLen
            if (before.length < len) continue
            val part = before.substring(before.length - len)
            val state = part.substring(0, 2).map { letter(it) }
            val district = part.substring(2, 2 + dLen).map { digit(it) }
            val series = part.substring(2 + dLen).map { letter(it) }
            if (state.any { it == null } || district.any { it == null } || series.any { it == null }) continue
            val st = state.joinToString("")
            if (st !in STATES) continue
            // A series letter read as a digit is fixed only when the raw text has a letter there in most places.
            val rawSeries = part.substring(2 + dLen)
            if (rawSeries.count { it.isDigit() } > 1) continue
            return st + district.joinToString("").padStart(2, '0') + series.joinToString("")
        }
        return null
    }

    /**
     * What the camera read for a vehicle posted as [code]: one of [known] (list vehicles ending in [code]) when it
     * is there, else the best plate ending in [code], else any plate.
     */
    fun read(text: String, code: String?, known: List<String>): String? =
        findKnown(text, known) ?: plates(text, code).firstOrNull() ?: if (code == null) null else plates(text).firstOrNull()

    /** "TS09UB4821" -> "TS 09 UB 4821" for showing. */
    fun pretty(plate: String): String {
        val m = Regex("^([A-Z]{2})(\\d{1,2})([A-Z]{0,3})(\\d{4})$").find(plate) ?: return plate
        return m.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
