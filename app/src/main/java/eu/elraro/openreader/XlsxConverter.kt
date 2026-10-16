package eu.elraro.openreader

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object XlsxConverter {
    private const val WORKBOOK = "xl/workbook.xml"
    private const val MAX_ROWS = 2000
    private const val MAX_COLS = 100

    fun toHtml(pkg: OoxmlPackage, title: String): String {
        val wb = pkg.xml(WORKBOOK) ?: throw IllegalArgumentException("Missing $WORKBOOK")
        val rels = pkg.rels(WORKBOOK)
        val shared = loadShared(pkg)
        val dateStyles = loadDateStyles(pkg)
        val sheets = wb.child("sheets")?.all("sheet").orEmpty()

        val tabs = StringBuilder()
        val body = StringBuilder()
        sheets.forEachIndexed { i, s ->
            val name = s.attr("name") ?: "Sheet${i + 1}"
            tabs.append("<a href=\"#s$i\">${esc(name)}</a>")
            body.append("<h2 id=\"s$i\">${esc(name)}</h2>")
            val part = rels[s.attr("id")]
            val sheet = part?.let { pkg.xml(it) }
            if (sheet == null) body.append("<p><i>Sheet could not be read.</i></p>")
            else sheet(sheet, shared, dateStyles, body)
        }
        val css = """
.tabs{position:sticky;top:0;background:#e8f0fe;padding:6px;white-space:nowrap;overflow-x:auto}
.tabs a{margin-right:12px;color:#1565c0;text-decoration:none;font-weight:bold}
h2{font-size:1.1em;margin:14px 8px 6px}
.wrap{overflow-x:auto;padding:0 8px}
table{border-collapse:collapse;font-size:.85em}
td,th{border:1px solid #bbb;padding:2px 6px;white-space:pre}
th{background:#f1f3f4;font-weight:normal;color:#555}
td.n{text-align:right}
"""
        return htmlPage(title, css, "<div class=\"tabs\">$tabs</div>$body")
    }

    private fun sheet(root: Node, shared: List<String>, dateStyles: Set<Int>, out: StringBuilder) {
        val rows = root.child("sheetData")?.all("row").orEmpty()
        if (rows.isEmpty()) {
            out.append("<p><i>Empty sheet.</i></p>")
            return
        }
        var maxCol = 0
        val grid = ArrayList<Pair<Int, Map<Int, Pair<String, Boolean>>>>()
        var rowNo = 0
        for (row in rows) {
            rowNo = row.attr("r")?.toIntOrNull() ?: (rowNo + 1)
            if (grid.size >= MAX_ROWS) break
            val cells = HashMap<Int, Pair<String, Boolean>>()
            var colNo = -1
            for (c in row.all("c")) {
                colNo = c.attr("r")?.let { colIndex(it) } ?: (colNo + 1)
                if (colNo >= MAX_COLS) continue
                val v = cellValue(c, shared, dateStyles)
                if (v.first.isNotEmpty()) {
                    cells[colNo] = v
                    if (colNo > maxCol) maxCol = colNo
                }
            }
            grid.add(rowNo to cells)
        }
        out.append("<div class=\"wrap\"><table><tr><th></th>")
        for (c in 0..maxCol) out.append("<th>${colName(c)}</th>")
        out.append("</tr>")
        for ((r, cells) in grid) {
            out.append("<tr><th>$r</th>")
            for (c in 0..maxCol) {
                val v = cells[c]
                if (v == null) out.append("<td></td>")
                else out.append(if (v.second) "<td class=\"n\">" else "<td>").append(esc(v.first)).append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table></div>")
        if (rows.size > MAX_ROWS) out.append("<p><i>Showing first $MAX_ROWS rows.</i></p>")
    }

    /** Returns display text and whether it is numeric (right-aligned). */
    private fun cellValue(c: Node, shared: List<String>, dateStyles: Set<Int>): Pair<String, Boolean> {
        val t = c.attr("t")
        val v = c.child("v")?.text?.toString().orEmpty()
        return when (t) {
            "s" -> (v.toIntOrNull()?.let { shared.getOrNull(it) } ?: "") to false
            "inlineStr" -> (c.child("is")?.let { richText(it) } ?: "") to false
            "b" -> (if (v == "1") "TRUE" else "FALSE") to false
            "str", "e" -> v to false
            else -> {
                val d = v.toDoubleOrNull() ?: return v to false
                val style = c.attr("s")?.toIntOrNull()
                if (style != null && style in dateStyles) formatDate(d) to false
                else formatNumber(d) to true
            }
        }
    }

    private fun formatNumber(d: Double): String =
        if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString()
        else java.math.BigDecimal(d).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()

    private fun formatDate(serial: Double): String {
        val ms = Math.round((serial - 25569.0) * 86400000.0)
        val hasTime = serial != Math.floor(serial)
        val f = SimpleDateFormat(if (hasTime) "yyyy-MM-dd HH:mm" else "yyyy-MM-dd", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    private fun loadShared(pkg: OoxmlPackage): List<String> =
        pkg.xml("xl/sharedStrings.xml")?.all("si")?.map { richText(it) }.orEmpty()

    /** Concatenate <t> in <si>/<is>, ignoring phonetic runs. */
    private fun richText(n: Node): String {
        val sb = StringBuilder()
        for (c in n.children) when (c.name) {
            "t" -> sb.append(c.text)
            "r" -> c.child("t")?.let { sb.append(it.text) }
        }
        return sb.toString()
    }

    private fun loadDateStyles(pkg: OoxmlPackage): Set<Int> {
        val root = pkg.xml("xl/styles.xml") ?: return emptySet()
        val custom = HashMap<Int, String>()
        root.child("numFmts")?.all("numFmt")?.forEach { f ->
            val id = f.attr("numFmtId")?.toIntOrNull() ?: return@forEach
            custom[id] = f.attr("formatCode").orEmpty()
        }
        val out = HashSet<Int>()
        root.child("cellXfs")?.all("xf")?.forEachIndexed { i, xf ->
            val id = xf.attr("numFmtId")?.toIntOrNull() ?: return@forEachIndexed
            val isDate = if (id in custom) looksLikeDate(custom.getValue(id)) else id in 14..22 || id in 45..47
            if (isDate) out.add(i)
        }
        return out
    }

    private fun looksLikeDate(code: String): Boolean {
        val stripped = code.replace(Regex("\"[^\"]*\"|\\[[^]]*]|\\\\."), "").lowercase()
        return stripped.any { it in "ymdhs" }
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1)
            else break
        }
        return n - 1
    }

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.insert(0, 'A' + r)
            n = (n - 1) / 26
        }
        return sb.toString()
    }
}
