package com.logistics.vehiclereport

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes a simple .xlsx, one sheet or several (opens in Excel, Google Sheets, WPS). No external library needed. */
object XlsxWriter {

    /** One sheet of a workbook. */
    class Sheet(val name: String, val rows: List<List<String>>, val widths: List<Int>)

    /** Plain numbers (no leading zero, up to 9 digits before the point) written as numbers when [numbers] is on. */
    private val PLAIN_NUMBER = Regex("^-?(0|[1-9]\\d{0,8})(\\.\\d+)?$")

    fun write(out: OutputStream, sheetName: String, rows: List<List<String>>, columnWidths: List<Int>, numbers: Boolean = false) =
        writeSheets(out, listOf(Sheet(sheetName, rows, columnWidths)), numbers)

    /** A workbook with every sheet in [sheets], in that order. */
    fun writeSheets(out: OutputStream, sheets: List<Sheet>, numbers: Boolean = false) {
        ZipOutputStream(out).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes(sheets.size))
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", workbook(sheets.map { it.name }))
            put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, sh -> put("xl/worksheets/sheet${i + 1}.xml", sheet(sh.rows, sh.widths, numbers)) }
        }
    }

    private fun sheet(rows: List<List<String>>, widths: List<Int>, numbers: Boolean): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        if (widths.isNotEmpty()) {
            sb.append("<cols>")
            widths.forEachIndexed { i, w ->
                sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        rows.forEachIndexed { r, row ->
            val rowNum = r + 1
            sb.append("""<row r="$rowNum">""")
            row.forEachIndexed { c, value ->
                val style = if (r == 0) """ s="1"""" else ""
                if (numbers && r > 0 && PLAIN_NUMBER.matches(value)) {
                    sb.append("""<c r="${colName(c)}$rowNum"><v>$value</v></c>""")
                } else {
                    sb.append("""<c r="${colName(c)}$rowNum" t="inlineStr"$style><is><t xml:space="preserve">""")
                    sb.append(escape(value))
                    sb.append("</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun colName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
                ch < ' ' || ch == '￾' || ch == '￿' -> Unit // not allowed in XML
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun workbook(names: List<String>): String {
        val used = HashSet<String>()
        val sheets = names.mapIndexed { i, n ->
            var safe = n.replace(Regex("[\\\\/?*\\[\\]:]"), " ").take(31).ifBlank { "Sheet${i + 1}" }
            while (!used.add(safe.lowercase())) safe = safe.take(28) + " ${i + 1}"
            """<sheet name="${escape(safe)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>"""
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<sheets>$sheets</sheets></workbook>"""
    }

    private fun contentTypes(n: Int) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            (1..n).joinToString("") { """<Override PartName="/xl/worksheets/sheet$it.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } +
            """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
            """</Types>"""

    private fun workbookRels(n: Int) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            (1..n).joinToString("") { """<Relationship Id="rId$it" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$it.xml"/>""" } +
            """<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            """</Relationships>"""

    private const val ROOT_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private const val STYLES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>""" +
            """<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF0B8574"/><bgColor indexed="64"/></patternFill></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>""" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            """</styleSheet>"""
}
