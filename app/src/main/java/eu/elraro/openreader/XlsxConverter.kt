package eu.elraro.openreader

object XlsxConverter {
    private const val WORKBOOK = "xl/workbook.xml"
    internal const val MAX_ROWS = 3000
    internal const val MAX_COLS = 100

    /** A rendered cell: text, kind (0 text, 1 number, 2 centred bool/error) and style index. */
    internal class XCell(val text: String, val kind: Int, val xf: Int)

    fun toHtml(pkg: OoxmlPackage, title: String): String {
        val wb = pkg.xml(WORKBOOK) ?: throw IllegalArgumentException("Missing $WORKBOOK")
        val rels = pkg.rels(WORKBOOK)
        val shared = loadShared(pkg)
        val scheme = ColorScheme.parse(pkg.xml("xl/theme/theme1.xml"))
        val styles = XStyles(pkg.xml("xl/styles.xml"), scheme)
        val sheets = wb.child("sheets")?.all("sheet").orEmpty().filter { it.attr("state") == null || it.attr("state") == "visible" }

        val tabs = StringBuilder()
        val body = StringBuilder()
        val used = HashSet<Int>()
        val names = sheets.mapIndexed { i, s -> s.attr("name") ?: "Sheet${i + 1}" }
        val paths = sheets.map { rels[it.attr("id")] }
        val roots = paths.map { path -> path?.let { try { pkg.xml(it) } catch (e: Exception) { null } } }
        val book = buildBook(names, roots, shared)
        sheets.forEachIndexed { i, _ ->
            val name = names[i]
            tabs.append("<a href=\"#s$i\">${esc(name)}</a>")
            body.append("<h2 id=\"s$i\">${esc(name)}</h2>")
            val sheet = roots[i]
            if (sheet == null) body.append("<p class=\"note\">Sheet could not be read.</p>")
            else sheet(sheet, shared, styles, used, body, Ctx(pkg, scheme, book, name, paths[i]))
        }
        val css = StringBuilder()
        for (xf in used.sorted()) css.append(".x$xf{${styles.css(xf)}}\n")
        return page(title, tabs, body, css.toString())
    }

    private class Ctx(val pkg: OoxmlPackage, val scheme: ColorScheme, val book: Workbook, val name: String, val path: String?)

    /** Raw values and formulas of every sheet, so formulas without a cached result can be calculated. */
    private fun buildBook(names: List<String>, roots: List<Node?>, shared: List<String>): Workbook {
        val book = Workbook()
        roots.forEachIndexed { i, root ->
            root ?: return@forEachIndexed
            val masters = HashMap<String, Triple<Int, Int, String>>()
            var rowNo = -1
            for (row in root.child("sheetData")?.all("row").orEmpty()) {
                rowNo = row.attr("r")?.toIntOrNull()?.minus(1) ?: (rowNo + 1)
                if (rowNo >= 100000) break
                var colNo = -1
                for (c in row.all("c")) {
                    colNo = c.attr("r")?.let { colIndex(it) } ?: (colNo + 1)
                    if (colNo < 0 || colNo > 16383) continue
                    val v = c.child("v")?.text?.toString()
                    val value: Any? = when (c.attr("t")) {
                        "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) }
                        "inlineStr" -> c.child("is")?.let { richText(it) }
                        "b" -> v == "1"
                        "e" -> v?.let { XlError(it) }
                        "str", "d" -> v
                        else -> v?.toDoubleOrNull() ?: v
                    }
                    val f = c.child("f")
                    var formula: String? = null; var dr = 0; var dc = 0
                    if (f != null && v.isNullOrEmpty()) {
                        val text = f.text.toString()
                        if (f.attr("t") == "shared") {
                            val si = f.attr("si") ?: ""
                            if (text.isNotBlank()) { masters[si] = Triple(rowNo, colNo, text); formula = text }
                            else masters[si]?.let { (mr, mc, mt) -> formula = mt; dr = rowNo - mr; dc = colNo - mc }
                        } else if (text.isNotBlank()) formula = text
                    } else if (f != null && f.attr("t") == "shared" && f.text.isNotBlank()) {
                        masters[f.attr("si") ?: ""] = Triple(rowNo, colNo, f.text.toString())
                    }
                    book.put(names[i], rowNo, colNo, Workbook.Cell(value, formula, dr, dc))
                }
            }
        }
        return book
    }

    private fun sheet(root: Node, shared: List<String>, styles: XStyles, used: MutableSet<Int>, out: StringBuilder, ctx: Ctx) {
        val fmtPr = root.child("sheetFormatPr")
        val defRowH = fmtPr?.attr("defaultRowHeight")?.toDoubleOrNull() ?: 15.0
        val defColPx = fmtPr?.attr("defaultColWidth")?.toDoubleOrNull()?.let { Math.round(it * 7).toInt() } ?: 64
        val showGrid = root.child("sheetViews")?.child("sheetView")?.attr("showGridLines").let { it != "0" && it != "false" }

        val colW = HashMap<Int, Int>()
        val colHidden = HashSet<Int>()
        for (c in root.child("cols")?.all("col").orEmpty()) {
            val min = c.attr("min")?.toIntOrNull() ?: continue
            val max = minOf(c.attr("max")?.toIntOrNull() ?: min, MAX_COLS)
            val w = c.attr("width")?.toDoubleOrNull()?.let { Math.round(it * 7).toInt() }
            for (i in min..max) {
                if (w != null) colW[i - 1] = w
                if (c.attr("hidden") == "1") colHidden.add(i - 1)
            }
        }

        // merged ranges: anchor -> (rowspan, colspan); covered cells are skipped
        val mergeAnchor = HashMap<Long, Pair<Int, Int>>()
        val covered = HashSet<Long>()
        fun key(r: Int, c: Int) = r.toLong() * 1000 + c
        for (m in root.child("mergeCells")?.all("mergeCell").orEmpty()) {
            val ref = m.attr("ref") ?: continue
            val (a, b) = if (':' in ref) ref.split(':').let { it[0] to it[1] } else ref to ref
            val r1 = rowOf(a) ; val c1 = colIndex(a); val r2 = rowOf(b); val c2 = colIndex(b)
            if (r1 < 0 || c1 < 0 || r2 < r1 || c2 < c1) continue
            mergeAnchor[key(r1, c1)] = (r2 - r1 + 1) to (c2 - c1 + 1)
            for (r in r1..r2) for (c in c1..c2) if (r != r1 || c != c1) covered.add(key(r, c))
        }

        class Row(val ht: Double?, val hidden: Boolean, val cells: HashMap<Int, XCell>)
        val rows = HashMap<Int, Row>()
        var maxRow = -1
        var maxCol = -1
        var rowNo = -1
        for (row in root.child("sheetData")?.all("row").orEmpty()) {
            rowNo = row.attr("r")?.toIntOrNull()?.minus(1) ?: (rowNo + 1)
            if (rowNo >= MAX_ROWS) break
            val cells = HashMap<Int, XCell>()
            var colNo = -1
            for (c in row.all("c")) {
                colNo = c.attr("r")?.let { colIndex(it) } ?: (colNo + 1)
                if (colNo < 0 || colNo >= MAX_COLS) continue
                val xf = c.attr("s")?.toIntOrNull() ?: 0
                cells[colNo] = if (c.child("f") != null && c.child("v")?.text.isNullOrEmpty()) {
                    val (text, kind) = Workbook.format(ctx.book.eval(ctx.name, rowNo, colNo), styles.formatCode(xf)) ?: ("" to 0)
                    XCell(text, kind, xf)
                } else cellValue(c, shared, styles, xf)
                if (colNo > maxCol) maxCol = colNo
            }
            rows[rowNo] = Row(
                row.attr("ht")?.toDoubleOrNull()?.takeIf { row.attr("customHeight") != "0" },
                row.attr("hidden") == "1", cells,
            )
            if (rowNo > maxRow) maxRow = rowNo
        }
        // columns needed so long text at the right edge can spill over
        var spill = maxCol
        for (row in rows.values) for ((c, cell) in row.cells) {
            if (cell.kind != 0 || cell.text.isEmpty() || styles.wraps(cell.xf)) continue
            val need = cell.text.length * 7.0 * (styles.fontSize(cell.xf) / 11.0)
            var have = (colW[c] ?: defColPx).toDouble()
            var k = c
            while (have < need && k < maxCol + 12 && k < MAX_COLS - 1) { k++; have += colW[k] ?: defColPx }
            if (k > spill) spill = k
        }
        maxCol = maxOf(maxCol, spill)
        for ((k, v) in mergeAnchor) {
            maxRow = maxOf(maxRow, (k / 1000).toInt() + v.first - 1).coerceAtMost(MAX_ROWS - 1)
            maxCol = maxOf(maxCol, (k % 1000).toInt() + v.second - 1).coerceAtMost(MAX_COLS - 1)
        }
        if (maxRow < 0 || maxCol < 0) {
            out.append("<p class=\"note\">Empty sheet.</p>")
            return
        }

        fun px(c: Int) = colW[c] ?: defColPx
        var total = 40
        for (c in 0..maxCol) if (c !in colHidden) total += px(c)
        out.append("<div class=\"wrap\"><table class=\"sh${if (showGrid) " grid" else ""}\" style=\"width:${total}px\"><colgroup><col style=\"width:40px\">")
        for (c in 0..maxCol) if (c !in colHidden) out.append("<col style=\"width:${px(c)}px\">")
        out.append("</colgroup><tr class=\"hd\"><th></th>")
        for (c in 0..maxCol) if (c !in colHidden) out.append("<th>${colName(c)}</th>")
        out.append("</tr>")
        for (r in 0..maxRow) {
            val row = rows[r]
            if (row?.hidden == true) continue
            out.append("<tr style=\"height:${fmtPt(row?.ht ?: defRowH)}pt\"><th>${r + 1}</th>")
            var c = 0
            while (c <= maxCol) {
                if (c in colHidden || key(r, c) in covered) { c++; continue }
                val cell = row?.cells?.get(c)
                val merge = mergeAnchor[key(r, c)]
                var colspan = merge?.second ?: 1
                val rowspan = merge?.first ?: 1
                // let wide left-aligned text spill over empty neighbours, like Excel does
                if (cell != null && cell.kind == 0 && merge == null && cell.text.isNotEmpty() && !styles.wraps(cell.xf)) {
                    val need = cell.text.length * 7.0 * (styles.fontSize(cell.xf) / 11.0)
                    var have = px(c).toDouble()
                    var k = c + 1
                    while (have < need && k <= maxCol && key(r, k) !in covered && k !in colHidden) {
                        val nb = row?.cells?.get(k)
                        if (nb != null && (nb.text.isNotEmpty() || nb.xf != 0)) break
                        have += px(k); colspan++; k++
                    }
                }
                out.append("<td")
                if (cell != null) {
                    used.add(cell.xf)
                    out.append(" class=\"${if (cell.kind == 1) "n " else if (cell.kind == 2) "c " else ""}x${cell.xf}\"")
                }
                if (colspan > 1) out.append(" colspan=\"$colspan\"")
                if (rowspan > 1) out.append(" rowspan=\"$rowspan\"")
                out.append(">").append(esc(cell?.text.orEmpty())).append("</td>")
                c += colspan
            }
            out.append("</tr>")
        }
        out.append("</table></div>")
        if (rowNo >= MAX_ROWS) out.append("<p class=\"note\">Showing the first $MAX_ROWS rows.</p>")
        root.child("drawing")?.let { d -> drawings(ctx, d.attr("id"), ::px, colHidden, defRowH, rows.mapValues { it.value.ht }, out) }
    }

    /** Charts and pictures anchored on the sheet, shown below the table at their anchored size. */
    private fun drawings(ctx: Ctx, relId: String?, px: (Int) -> Int, hidden: Set<Int>, defRowH: Double, rowH: Map<Int, Double?>, out: StringBuilder) {
        val pkg = ctx.pkg
        val part = ctx.path?.let { pkg.rels(it)[relId] } ?: return
        val drawing = try { pkg.xml(part) } catch (e: Exception) { null } ?: return
        val rels = pkg.rels(part)
        val sb = StringBuilder()
        fun num(n: Node?, k: String) = n?.child(k)?.text?.toString()?.toDoubleOrNull() ?: 0.0
        for (a in drawing.children) {
            if (a.name != "twoCellAnchor" && a.name != "oneCellAnchor" && a.name != "absoluteAnchor") continue
            var w = 480.0; var h = 288.0
            val ext = a.child("ext")
            if (ext != null) { w = (ext.attr("cx")?.toDoubleOrNull() ?: 0.0) / 9525; h = (ext.attr("cy")?.toDoubleOrNull() ?: 0.0) / 9525 }
            else if (a.name == "twoCellAnchor") {
                val f = a.child("from"); val t = a.child("to")
                val c0 = num(f, "col").toInt(); val c1 = num(t, "col").toInt(); val r0 = num(f, "row").toInt(); val r1 = num(t, "row").toInt()
                w = (c0 until c1).filter { it !in hidden }.sumOf { px(it).toDouble() } + (num(t, "colOff") - num(f, "colOff")) / 9525
                h = (r0 until r1).sumOf { (rowH[it] ?: defRowH) * 4 / 3 } + (num(t, "rowOff") - num(f, "rowOff")) / 9525
            }
            if (w < 20 || h < 20 || w > 4000 || h > 4000) continue
            val frame = a.child("graphicFrame")
            val pic = a.child("pic")
            if (frame != null) {
                val svg = Diagrams.chartSvg(pkg, rels, ctx.scheme, frame, w, h) { ctx.book.rangeValues(it) }
                    ?: Diagrams.smartArt(pkg, rels, ctx.scheme, frame, w, h)
                if (svg != null) sb.append("<div class=\"fig\">$svg</div>")
            } else if (pic != null) {
                val uri = rels[pic.find("blip")?.attr("embed")]?.let { pkg.imageDataUri(it) }
                if (uri != null) sb.append("<div class=\"fig\"><img src=\"$uri\" style=\"width:${w.toInt()}px;height:${h.toInt()}px\"></div>")
            }
        }
        out.append(sb)
    }

    private fun fmtPt(d: Double) = String.format(java.util.Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')

    private fun cellValue(c: Node, shared: List<String>, styles: XStyles, xf: Int): XCell {
        val t = c.attr("t")
        val v = c.child("v")?.text?.toString().orEmpty()
        return when (t) {
            "s" -> XCell(v.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty(), 0, xf)
            "inlineStr" -> XCell(c.child("is")?.let { richText(it) }.orEmpty(), 0, xf)
            "b" -> XCell(if (v == "1") "TRUE" else "FALSE", 2, xf)
            "e" -> XCell(v, 2, xf)
            "str", "d" -> XCell(v, 0, xf)
            else -> {
                val d = v.toDoubleOrNull() ?: return XCell(v, 0, xf)
                XCell(NumFmt.format(d, styles.formatCode(xf)), if (NumFmt.isDate(styles.formatCode(xf))) 0 else 1, xf)
            }
        }
    }

    internal fun page(title: String, tabs: CharSequence, body: CharSequence, extraCss: String = ""): String {
        val css = """
.tabs{position:sticky;top:0;background:#e8f0fe;padding:6px;white-space:nowrap;overflow-x:auto;z-index:2}
.tabs a{margin-right:12px;color:#1565c0;text-decoration:none;font-weight:bold}
h2{font-size:1.1em;margin:14px 8px 6px}
.wrap{overflow-x:auto;padding:0 8px}
.fig{margin:10px 8px}
.note{margin:6px 8px;color:#666;font-style:italic;font-size:.9em}
table.sh{border-collapse:collapse;table-layout:fixed;font-family:'Calibri',sans-serif;font-size:11pt}
table.sh td,table.sh th{padding:0 3px;white-space:pre;overflow:hidden;vertical-align:bottom;box-sizing:border-box;border:0 solid transparent}
table.grid td{border:1px solid #d9d9d9}
table.sh th{background:#f1f3f4;font-weight:normal;color:#555;font-size:9pt;text-align:center;border:1px solid #c8c8c8;vertical-align:middle}
table.sh td.n{text-align:right}
table.sh td.c{text-align:center}
$extraCss
"""
        return htmlPage(title, css, "<div class=\"tabs\">$tabs</div>$body")
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

    internal fun formatDate(serial: Double): String = NumFmt.format(serial, if (serial != Math.floor(serial)) "yyyy-mm-dd hh:mm" else "yyyy-mm-dd")
    internal fun formatNumber(d: Double): String = NumFmt.general(d)
    internal fun looksLikeDate(code: String): Boolean = NumFmt.isDate(code)

    private fun rowOf(ref: String): Int = ref.dropWhile { it.isLetter() }.toIntOrNull()?.minus(1) ?: -1

    internal fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1)
            else break
        }
        return n - 1
    }

    internal fun colName(i: Int): String {
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

/** Cell formats from xl/styles.xml turned into CSS. */
internal class XStyles(root: Node?, private val scheme: ColorScheme) {
    private class Font(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val size: Double, val color: String?, val name: String?)
    private class Xf(val numFmt: Int, val font: Int, val fill: Int, val border: Int, val h: String?, val v: String?, val wrap: Boolean, val indent: Int)

    private val custom = HashMap<Int, String>()
    private val fonts = ArrayList<Font>()
    private val fills = ArrayList<String?>()
    private val borders = ArrayList<Map<String, String>>()
    private val xfs = ArrayList<Xf>()
    private val cache = HashMap<Int, String>()

    init {
        root?.child("numFmts")?.all("numFmt")?.forEach { f ->
            f.attr("numFmtId")?.toIntOrNull()?.let { custom[it] = f.attr("formatCode").orEmpty() }
        }
        for (f in root?.child("fonts")?.all("font").orEmpty()) {
            fonts.add(Font(
                f.child("b") != null && f.child("b")?.attr("val") != "0", f.child("i") != null && f.child("i")?.attr("val") != "0",
                f.child("u") != null && f.child("u")?.attr("val") != "none", f.child("strike") != null,
                f.child("sz")?.attr("val")?.toDoubleOrNull() ?: 11.0, color(f.child("color")), f.child("name")?.attr("val"),
            ))
        }
        for (f in root?.child("fills")?.all("fill").orEmpty()) {
            val pf = f.child("patternFill")
            fills.add(if (pf?.attr("patternType") == "solid") color(pf.child("fgColor")) else null)
        }
        for (b in root?.child("borders")?.all("border").orEmpty()) {
            val m = HashMap<String, String>()
            for (side in listOf("left", "right", "top", "bottom")) {
                val s = b.child(side) ?: continue
                val style = s.attr("style") ?: continue
                val (w, st) = when (style) {
                    "hair" -> 1 to "solid"; "thin" -> 1 to "solid"; "medium" -> 2 to "solid"; "thick" -> 3 to "solid"
                    "dashed", "mediumDashed", "dashDot", "mediumDashDot", "dashDotDot" -> 1 to "dashed"
                    "dotted" -> 1 to "dotted"; "double" -> 3 to "double"; else -> 1 to "solid"
                }
                m[side] = "${w}px $st ${color(s.child("color")) ?: "#000000"}"
            }
            borders.add(m)
        }
        for (x in root?.child("cellXfs")?.all("xf").orEmpty()) {
            val al = x.child("alignment")
            xfs.add(Xf(
                x.attr("numFmtId")?.toIntOrNull() ?: 0, x.attr("fontId")?.toIntOrNull() ?: 0, x.attr("fillId")?.toIntOrNull() ?: 0,
                x.attr("borderId")?.toIntOrNull() ?: 0, al?.attr("horizontal"), al?.attr("vertical"),
                al?.attr("wrapText") == "1" || al?.attr("wrapText") == "true", al?.attr("indent")?.toIntOrNull() ?: 0,
            ))
        }
    }

    val INDEXED = listOf(
        "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF", "000000", "FFFFFF", "FF0000", "00FF00", "0000FF",
        "FFFF00", "FF00FF", "00FFFF", "800000", "008000", "000080", "808000", "800080", "008080", "C0C0C0", "808080", "9999FF", "993366",
        "FFFFCC", "CCFFFF", "660066", "FF8080", "0066CC", "CCCCFF", "000080", "FF00FF", "FFFF00", "00FFFF", "800080", "800000", "008080",
        "0000FF", "00CCFF", "CCFFFF", "CCFFCC", "FFFF99", "99CCFF", "FF99CC", "CC99FF", "FFCC99", "3366FF", "33CCCC", "99CC00", "FFCC00",
        "FF9900", "FF6600", "666699", "969696", "003366", "339966", "003300", "333300", "993300", "993366", "333399", "333333",
    )

    private fun color(n: Node?): String? {
        n ?: return null
        var hex: String? = n.attr("rgb")?.takeLast(6)
        if (hex == null) n.attr("theme")?.toIntOrNull()?.let { i ->
            val name = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink").getOrNull(i)
            hex = name?.let { scheme.get(it) }
        }
        if (hex == null) n.attr("indexed")?.toIntOrNull()?.let { hex = INDEXED.getOrNull(it) }
        val h = hex?.takeIf { it.length == 6 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } } ?: return null
        val t = n.attr("tint")?.toDoubleOrNull()
        return "#" + (if (t != null && t != 0.0) ColorScheme.tint(h, t) else h)
    }

    fun formatCode(xf: Int): String = NumFmt.code(xfs.getOrNull(xf)?.numFmt ?: 0, custom)
    fun wraps(xf: Int) = xfs.getOrNull(xf)?.wrap == true
    fun fontSize(xf: Int): Double = fonts.getOrNull(xfs.getOrNull(xf)?.font ?: 0)?.size ?: 11.0

    fun css(i: Int): String = cache.getOrPut(i) {
        val x = xfs.getOrNull(i) ?: return@getOrPut ""
        val sb = StringBuilder()
        fonts.getOrNull(x.font)?.let { f ->
            f.name?.let { sb.append("font-family:${Fonts.css(it)};") }
            sb.append("font-size:${f.size}pt;")
            if (f.bold) sb.append("font-weight:bold;")
            if (f.italic) sb.append("font-style:italic;")
            val deco = listOfNotNull(if (f.underline) "underline" else null, if (f.strike) "line-through" else null)
            if (deco.isNotEmpty()) sb.append("text-decoration:${deco.joinToString(" ")};")
            f.color?.let { sb.append("color:$it;") }
        }
        fills.getOrNull(x.fill)?.let { sb.append("background:$it;") }
        borders.getOrNull(x.border)?.forEach { (side, v) -> sb.append("border-$side:$v;") }
        when (x.h) {
            "center", "centerContinuous" -> sb.append("text-align:center;")
            "right" -> sb.append("text-align:right;")
            "left" -> sb.append("text-align:left;")
        }
        when (x.v) {
            "center" -> sb.append("vertical-align:middle;")
            "top" -> sb.append("vertical-align:top;")
        }
        if (x.wrap) sb.append("white-space:pre-wrap;overflow-wrap:anywhere;")
        if (x.indent > 0) sb.append("padding-left:${3 + x.indent * 9}px;")
        sb.toString()
    }
}
