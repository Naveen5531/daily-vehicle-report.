package com.logistics.vehiclereport

/**
 * Finds vehicle numbers in WhatsApp text.
 *  - Full Indian plates: TS09UB1234, TS 09 UB 1234, ts-9-ub-1234, DL3C1234, 22BH1234AA
 *  - Last-4-digit codes: "1234", "UB1234", "1234 placed" (exactly 4 digits, not part of a longer number)
 */
object VehicleParser {

    private const val STATE_CODES =
        "AN|AP|AR|AS|BR|CH|CG|DD|DL|DN|GA|GJ|HR|HP|JK|JH|KA|KL|LA|LD|MP|MH|MN|ML|MZ|NL|OD|OR|PY|PB|RJ|SK|TN|TS|TG|TR|UP|UK|UA|WB"

    private val PLATE_REGEX = Regex(
        "(?<![A-Z0-9])($STATE_CODES)[\\s.\\-]*(\\d{1,2})[\\s.\\-]*([A-Z]{0,3})[\\s.\\-]*(\\d{4})(?![0-9])",
        RegexOption.IGNORE_CASE
    )

    /** Bharat series: 22 BH 1234 AA */
    private val BH_REGEX = Regex(
        "(?<![A-Z0-9])(\\d{2})[\\s.\\-]*BH[\\s.\\-]*(\\d{4})[\\s.\\-]*([A-Z]{1,2})(?![A-Z0-9])",
        RegexOption.IGNORE_CASE
    )

    private val LAST4_REGEX = Regex("(?<![0-9])(\\d{4})(?![0-9])")

    data class Found(val plates: List<String>, val last4Codes: List<String>)

    /** A vehicle reference inside a text: a full plate or a 4-digit code, with its position. */
    data class Ref(val start: Int, val end: Int, val value: String, val full: Boolean)

    /** All vehicle references in [text], in the order they appear. */
    fun refs(text: String): List<Ref> {
        val out = mutableListOf<Ref>()
        fun free(r: IntRange) = out.none { it.start <= r.last && r.first < it.end }
        PLATE_REGEX.findAll(text).forEach { m ->
            val (state, district, series, number) = m.destructured
            out += Ref(m.range.first, m.range.last + 1, state.uppercase() + district.padStart(2, '0') + series.uppercase() + number, true)
        }
        BH_REGEX.findAll(text).forEach { m ->
            if (free(m.range)) {
                val (year, number, letters) = m.destructured
                out += Ref(m.range.first, m.range.last + 1, year + "BH" + number + letters.uppercase(), true)
            }
        }
        LAST4_REGEX.findAll(text).forEach { m ->
            if (free(m.range)) out += Ref(m.range.first, m.range.last + 1, m.value, false)
        }
        return out.sortedBy { it.start }
    }

    /** Full plates only. */
    fun extract(text: String): List<String> = parse(text).plates

    fun parse(text: String): Found {
        if (text.isBlank()) return Found(emptyList(), emptyList())
        val plates = LinkedHashSet<String>()

        PLATE_REGEX.findAll(text).forEach { m ->
            val (state, district, series, number) = m.destructured
            plates += state.uppercase() + district.padStart(2, '0') + series.uppercase() + number
        }
        var rest = PLATE_REGEX.replace(text, " ")

        BH_REGEX.findAll(rest).forEach { m ->
            val (year, number, letters) = m.destructured
            plates += year + "BH" + number + letters.uppercase()
        }
        rest = BH_REGEX.replace(rest, " ")

        val codes = LAST4_REGEX.findAll(rest).map { it.value }.distinct().toList()
        return Found(plates.toList(), codes)
    }

    /** Last 4 digits of a plate: TS09UB1234 -> 1234, 22BH1234AA -> 1234 */
    fun last4(plate: String): String = plate.filter { it.isDigit() }.takeLast(4)
}
