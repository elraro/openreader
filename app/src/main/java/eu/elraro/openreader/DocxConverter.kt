package eu.elraro.openreader

import java.util.Locale

object DocxConverter {
    private const val PART = "word/document.xml"
    private const val PB = "\u0000" // page-break marker used while rendering the body
    private const val TAB = "\u0001" // tab marker used for paragraphs with custom tab stops

    fun toHtml(pkg: OoxmlPackage, title: String): String {
        val doc = pkg.xml(PART) ?: throw IllegalArgumentException("Missing $PART")
        val theme = Theme.load(pkg)
        val shared = Shared(pkg, theme, Styles(pkg.xml("word/styles.xml"), theme), Numbering(pkg.xml("word/numbering.xml"), theme))
        shared.defaultTab = pkg.xml("word/settings.xml")?.child("defaultTabStop")?.attr("val")?.toDoubleOrNull()?.div(20) ?: 36.0
        val body = doc.child("body") ?: throw IllegalArgumentException("Empty document")

        val sect = body.child("sectPr")
        val pg = sect?.child("pgSz")
        val mar = sect?.child("pgMar")
        fun tw(n: Node?, k: String, d: Double) = (n?.attr(k)?.toDoubleOrNull() ?: d) / 20.0
        val page = Page(
            tw(pg, "w", 12240.0), tw(pg, "h", 15840.0),
            tw(mar, "top", 1440.0), tw(mar, "right", 1440.0), tw(mar, "bottom", 1440.0), tw(mar, "left", 1440.0),
        )
        shared.page = page

        val main = Renderer(shared, PART)
        val out = StringBuilder()
        val rels = pkg.rels(PART)
        val titlePg = sect?.child("titlePg") != null
        fun headerFooter(kind: String): String? {
            val refs = sect?.all(kind + "Reference").orEmpty()
            val ref = (if (titlePg) refs.firstOrNull { it.attr("type") == "first" } else null)
                ?: refs.firstOrNull { it.attr("type") == "default" } ?: return null
            val part = rels[ref.attr("id")] ?: return null
            val root = pkg.xml(part) ?: return null
            val sb = StringBuilder()
            Renderer(shared, part).blocks(root, sb, null)
            return sb.toString().takeIf { it.contains("<img") || it.replace(Regex("<[^>]+>"), "").replace("&nbsp;", "").isNotBlank() }
        }
        val header = headerFooter("header")
        val footer = headerFooter("footer")
        main.blocks(body, out, null, true)
        val chunks = out.toString().split(PB).filter { it.isNotEmpty() }.ifEmpty { listOf("") }.toMutableList()
        val foot = StringBuilder()
        main.footnotes(foot)
        chunks[chunks.size - 1] = chunks.last() + foot
        val pages = StringBuilder()
        for (c in chunks) {
            pages.append("<div class=\"page\">")
            header?.let { pages.append("<div class=\"hf\">$it</div>") }
            pages.append("<div class=\"bd\">").append(c).append("</div>")
            footer?.let { pages.append("<div class=\"hf\">$it</div>") }
            pages.append("</div>")
        }

        val css = """
html{-webkit-text-size-adjust:100%}
body{background:#d9d9d9;padding:8px 0}
.page{position:relative;z-index:0;background:#fff;margin:0 auto 8px auto;box-shadow:0 1px 4px rgba(0,0,0,.4);box-sizing:border-box;
  width:${pt(page.w)}pt;min-height:${pt(page.h)}pt;padding:${pt(page.top)}pt ${pt(page.right)}pt ${pt(page.bottom)}pt ${pt(page.left)}pt;
  tab-size:${pt(shared.defaultTab)}pt;-moz-tab-size:${pt(shared.defaultTab)}pt;white-space:pre-wrap;overflow-wrap:break-word}
.page{display:flex;flex-direction:column}
.bd{flex:1}
.page div{white-space:inherit}
.p{margin:0;min-height:1em}
.hf{opacity:.9}
table{border-collapse:collapse;white-space:normal}
td{vertical-align:top;overflow-wrap:break-word;white-space:pre-wrap}
.fn{display:flex;gap:4pt;font-size:.85em}
hr.fnsep{width:30%;margin:12pt 0 4pt 0;border:0;border-top:1px solid #000}
@media print{body{background:#fff;padding:0}.page{box-shadow:none;margin:0;width:auto;min-height:0;page-break-after:always}.page:last-child{page-break-after:auto}}
"""
        val viewport = "width=${Math.round(page.w * 4 / 3) + 16}"
        return htmlPage(title, css, pages.toString(), viewport)
    }

    private fun pt(d: Double) = fmt(d)

    internal fun fmt(d: Double): String {
        val s = String.format(Locale.US, "%.2f", d)
        return s.trimEnd('0').trimEnd('.')
    }

    private class Page(val w: Double, val h: Double, val top: Double, val right: Double, val bottom: Double, val left: Double)

    private class Shared(val pkg: OoxmlPackage, val theme: Theme, val styles: Styles, val numbering: Numbering) {
        var defaultTab = 36.0
        var page = Page(612.0, 792.0, 72.0, 72.0, 72.0, 72.0)
        val footnoteOrder = ArrayList<String>()
        val footnoteNodes: Map<String, Node> by lazy {
            pkg.xml("word/footnotes.xml")?.all("footnote")?.filter { it.attr("type").let { t -> t == null || t == "normal" } }
                ?.associateBy { it.attr("id") ?: "" }.orEmpty()
        }
    }

    /** Context for cells of a table: formatting inherited from the table style. */
    private class CellCtx(val p: ParaProps, val r: RunProps)

    private class Renderer(val s: Shared, val part: String) {
        val rels = s.pkg.rels(part)
        val ext = s.pkg.externalRels(part)
        private val skipBreaks = HashSet<Node>()
        private var tabSplit = false

        fun footnotes(out: StringBuilder) {
            if (s.footnoteOrder.isEmpty()) return
            out.append("<hr class=\"fnsep\">")
            val r = Renderer(s, "word/footnotes.xml")
            var i = 0
            while (i < s.footnoteOrder.size) {
                val node = s.footnoteNodes[s.footnoteOrder[i]]
                i++
                if (node == null) continue
                val inner = StringBuilder()
                r.blocks(node, inner, null)
                out.append("<div class=\"fn\"><sup>$i</sup><div>$inner</div></div>")
            }
        }

        fun blocks(parent: Node, out: StringBuilder, cell: CellCtx?, top: Boolean = false) {
            val kids = parent.children.filter { it.name == "p" || it.name == "tbl" || it.name == "sdt" }
            var prev: String? = null
            for ((idx, n) in kids.withIndex()) when (n.name) {
                "p" -> {
                    val next = kids.getOrNull(idx + 1)?.takeIf { it.name == "p" }?.child("pPr")?.child("pStyle")?.attr("val")
                    prev = paragraph(n, out, cell, prev, next, top)
                }
                "tbl" -> { table(n, out, cell); prev = null }
                "sdt" -> { n.child("sdtContent")?.let { blocks(it, out, cell, top) }; prev = null }
            }
        }

        // ---- tables ----

        private class CellInfo(val tc: Node, val col: Int, val span: Int, val restart: Boolean, val cont: Boolean)

        private fun table(tbl: Node, out: StringBuilder, outer: CellCtx?) {
            val tblPr = tbl.child("tblPr")
            val ts = s.styles.table(tblPr?.child("tblStyle")?.attr("val"))
            val grid = tbl.child("tblGrid")?.all("gridCol")?.map { (it.attr("w")?.toDoubleOrNull() ?: 0.0) / 20 }.orEmpty()
            val look = tblPr?.child("tblLook")
            val lookVal = look?.attr("val")?.toIntOrNull(16) ?: 0
            fun flag(name: String, bit: Int) = look?.attr(name)?.let { it == "1" || it == "true" } ?: (lookVal and bit != 0)
            val firstRow = flag("firstRow", 0x20); val lastRow = flag("lastRow", 0x40)
            val firstCol = flag("firstColumn", 0x80); val lastCol = flag("lastColumn", 0x100)
            val noHBand = flag("noHBand", 0x200); val noVBand = flag("noVBand", 0x400)

            // direct table borders override the style's
            val borders = HashMap(ts.borders)
            tblPr?.child("tblBorders")?.let { b -> for (side in Styles.SIDES) borderCss(b.child(side))?.let { borders[side] = it } }

            var padL = 5.4; var padR = 5.4; var padT = 0.0; var padB = 0.0
            tblPr?.child("tblCellMar")?.let { m ->
                (m.child("left") ?: m.child("start"))?.attr("w")?.toDoubleOrNull()?.let { padL = it / 20 }
                (m.child("right") ?: m.child("end"))?.attr("w")?.toDoubleOrNull()?.let { padR = it / 20 }
                m.child("top")?.attr("w")?.toDoubleOrNull()?.let { padT = it / 20 }
                m.child("bottom")?.attr("w")?.toDoubleOrNull()?.let { padB = it / 20 }
            }

            val rows = tbl.all("tr")
            // lay out cells on the grid to resolve vertical merges
            val layout = rows.map { tr ->
                var col = tr.child("trPr")?.child("gridBefore")?.attr("val")?.toIntOrNull() ?: 0
                tr.all("tc").map { tc ->
                    val span = tc.child("tcPr")?.child("gridSpan")?.attr("val")?.toIntOrNull() ?: 1
                    val vm = tc.child("tcPr")?.child("vMerge")
                    val info = CellInfo(tc, col, span, vm != null && vm.attr("val") == "restart", vm != null && vm.attr("val") != "restart")
                    col += span
                    info
                }
            }
            val nCols = maxOf(grid.size, layout.maxOfOrNull { r -> r.maxOfOrNull { it.col + it.span } ?: 0 } ?: 0)

            val sb = StringBuilder("<table style=\"")
            val tw = tblPr?.child("tblW")
            val gridSum = grid.sum()
            when (tw?.attr("type")) {
                "pct" -> tw.attr("w")?.toDoubleOrNull()?.let { sb.append("width:${fmt(it / 50)}%;") }
                "dxa" -> tw.attr("w")?.toDoubleOrNull()?.let { sb.append("width:${fmt(it / 20)}pt;") }
                else -> if (gridSum > 0) sb.append("width:${fmt(gridSum)}pt;")
            }
            if (grid.isNotEmpty()) sb.append("table-layout:fixed;")
            tblPr?.child("tblInd")?.attr("w")?.toDoubleOrNull()?.let { if (it != 0.0) sb.append("margin-left:${fmt(it / 20)}pt;") }
            when (tblPr?.child("jc")?.attr("val")) {
                "center" -> sb.append("margin-left:auto;margin-right:auto;")
                "right", "end" -> sb.append("margin-left:auto;")
            }
            sb.append("\">")
            if (grid.isNotEmpty()) {
                sb.append("<colgroup>")
                grid.forEach { sb.append("<col style=\"width:${fmt(it)}pt\">") }
                sb.append("</colgroup>")
            }

            for ((ri, row) in layout.withIndex()) {
                val tr = rows[ri]
                val h = tr.child("trPr")?.child("trHeight")?.attr("val")?.toDoubleOrNull()
                sb.append(if (h != null) "<tr style=\"height:${fmt(h / 20)}pt\">" else "<tr>")
                val isFirstRow = firstRow && ri == 0
                val isLastRow = lastRow && ri == rows.size - 1 && ri > 0
                val bandIndex = ri - (if (firstRow) 1 else 0)
                for (c in row) {
                    if (c.cont) continue
                    var rowspan = 1
                    if (c.restart) {
                        var k = ri + 1
                        while (k < layout.size && layout[k].any { it.cont && it.col == c.col }) { rowspan++; k++ }
                    }
                    val tcPr = c.tc.child("tcPr")
                    // conditional formatting, lowest to highest priority
                    var fill = ts.fill
                    val p = ParaProps().merge(ts.p)
                    val r = RunProps().merge(ts.r)
                    val cb = HashMap<String, String>()
                    fun apply(type: String) {
                        ts.conds[type]?.let { cf ->
                            p.merge(cf.p); r.merge(cf.r)
                            cf.fill?.let { fill = it }
                            cb.putAll(cf.borders)
                        }
                    }
                    if (!noHBand && !isFirstRow && !isLastRow) apply(if (bandIndex % 2 == 0) "band1Horz" else "band2Horz")
                    val colIndex = c.col - (if (firstCol) 1 else 0)
                    if (!noVBand && !(firstCol && c.col == 0) && !(lastCol && c.col + c.span >= nCols)) apply(if (colIndex % 2 == 0) "band1Vert" else "band2Vert")
                    if (lastCol && c.col + c.span >= nCols) apply("lastCol")
                    if (firstCol && c.col == 0) apply("firstCol")
                    if (isLastRow) apply("lastRow")
                    if (isFirstRow) apply("firstRow")
                    tcPr?.child("shd")?.attr("fill")?.let { f -> safeColor(f)?.let { fill = it } ?: run { if (f == "auto") fill = null } }

                    val css = StringBuilder()
                    fill?.let { css.append("background:$it;") }
                    // borders: outer edges use the table's outer border, inner edges use inside borders
                    val top = if (ri == 0) "top" else "insideH"
                    val bottom = if (ri + rowspan >= rows.size) "bottom" else "insideH"
                    val left = if (c.col == 0) "left" else "insideV"
                    val right = if (c.col + c.span >= nCols) "right" else "insideV"
                    val sides = listOf("top" to top, "bottom" to bottom, "left" to left, "right" to right)
                    val own = tcPr?.child("tcBorders")
                    for ((cssSide, key) in sides) {
                        val v = borderCss(own?.child(cssSide) ?: (if (cssSide == "left") own?.child("start") else if (cssSide == "right") own?.child("end") else null))
                            ?: cb[key] ?: cb[cssSide] ?: borders[key]
                        if (v != null) css.append("border-$cssSide:$v;")
                    }
                    val m = tcPr?.child("tcMar")
                    fun mar(n: String, d: Double) = (m?.child(n)?.attr("w")?.toDoubleOrNull()?.div(20)) ?: d
                    css.append("padding:${fmt(mar("top", padT))}pt ${fmt(mar("right", padR))}pt ${fmt(mar("bottom", padB))}pt ${fmt(mar("left", padL))}pt;")
                    when (tcPr?.child("vAlign")?.attr("val")) {
                        "center" -> css.append("vertical-align:middle;")
                        "bottom" -> css.append("vertical-align:bottom;")
                    }
                    sb.append("<td")
                    if (c.span > 1) sb.append(" colspan=\"${c.span}\"")
                    if (rowspan > 1) sb.append(" rowspan=\"$rowspan\"")
                    sb.append(" style=\"$css\">")
                    blocks(c.tc, sb, CellCtx(p, r))
                    sb.append("</td>")
                }
                sb.append("</tr>")
            }
            sb.append("</table>")
            out.append(sb)
        }

        // ---- paragraphs ----

        private fun paragraph(pNode: Node, out: StringBuilder, cell: CellCtx?, prevStyle: String?, nextStyle: String?, top: Boolean): String? {
            val pPr = pNode.child("pPr")
            val styleId = pPr?.child("pStyle")?.attr("val")
            val (stP, stR) = s.styles.resolve(styleId)
            val isDefault = styleId == null || styleId == s.styles.defaultParaId

            val p = ParaProps().merge(s.styles.defP)
            val base = RunProps().merge(s.styles.defR)
            if (cell != null && isDefault) { p.merge(stP); base.merge(stR); p.merge(cell.p); base.merge(cell.r) }
            else { if (cell != null) { p.merge(cell.p); base.merge(cell.r) }; p.merge(stP); base.merge(stR) }

            val direct = ParaProps.of(pPr)
            val numId = direct.numId ?: p.numId
            val ilvl = direct.ilvl ?: p.ilvl ?: 0
            var marker: String? = null
            var markerProps: RunProps? = null
            var suffix = "tab"
            if (numId != null && numId != "0") {
                s.numbering.level(numId, ilvl)?.let { lvl -> p.merge(lvl.p); markerProps = lvl.r; suffix = lvl.suff }
                marker = s.numbering.next(numId, ilvl)
            }
            p.merge(direct)
            // paragraph-mark run properties are used for empty paragraphs
            val markRun = base.copy().merge(RunProps.of(pPr?.child("rPr"), s.theme))

            // explicit page breaks only matter for body-level paragraphs
            var breakBefore = false
            var breakAfter = false
            if (top) {
                if (p.pageBreakBefore == true) breakBefore = true
                val items = pNode.all("r").flatMap { r -> r.children.filter { it.name != "rPr" } }
                val first = items.firstOrNull { it.name != "lastRenderedPageBreak" }
                if (items.firstOrNull()?.name == "lastRenderedPageBreak") breakBefore = true
                if (first != null && first.name == "br" && first.attr("type") == "page") { breakBefore = true; skipBreaks.add(first) }
                val last = items.lastOrNull()
                if (last != null && last !== first && last.name == "br" && last.attr("type") == "page") { breakAfter = true; skipBreaks.add(last) }
                val sect = pPr?.child("sectPr")
                if (sect != null && sect.child("type")?.attr("val") != "continuous") breakAfter = true
            }
            val tabStops = p.tabs?.filter { it.kind != "clear" }?.sortedBy { it.pos }.orEmpty()
            tabSplit = tabStops.isNotEmpty()
            val inner = StringBuilder()
            runs(pNode, inner, base)
            tabSplit = false
            var content = inner.toString()
            var positioned = false
            if (tabStops.isNotEmpty() && content.contains(TAB)) {
                val segs = content.split(TAB)
                val marL = p.left ?: 0.0
                val sbT = StringBuilder()
                val allRight = tabStops.all { it.kind == "right" || it.kind == "decimal" }
                if (segs.size == 2 && tabStops.size == 1 && allRight) {
                    val ts = tabStops[0]
                    val leader = when (ts.leader) { "dot", "middleDot" -> "dotted"; "hyphen" -> "dashed"; "underscore" -> "solid"; else -> null }
                    sbT.append("<span style=\"display:flex;width:${fmt(ts.pos - marL)}pt\"><span>${segs[0]}</span><span style=\"flex:1;margin:0 2pt")
                    if (leader != null) sbT.append(";border-bottom:1px $leader;align-self:flex-end;margin-bottom:.3em")
                    sbT.append("\"></span><span>${segs[1]}</span></span>")
                } else {
                    sbT.append(segs[0])
                    var tail = StringBuilder()
                    for (i in 1 until segs.size) {
                        val ts = tabStops.getOrNull(i - 1)
                        if (ts == null) { sbT.append('\t').append(segs[i]); continue }
                        val tr = when (ts.kind) { "center" -> "transform:translateX(-50%);"; "right", "decimal" -> "transform:translateX(-100%);"; else -> "" }
                        sbT.append("<span style=\"position:absolute;white-space:nowrap;left:${fmt(ts.pos - marL)}pt;$tr\">${segs[i]}</span>")
                    }
                    positioned = true
                }
                content = sbT.toString()
            }

            val css = StringBuilder()
            p.jc?.let {
                when (it) {
                    "center" -> css.append("text-align:center;")
                    "right", "end" -> css.append("text-align:right;")
                    "both", "distribute" -> css.append("text-align:justify;")
                }
            }
            val contextual = p.contextual == true
            val before = if (contextual && prevStyle == styleId && prevStyle != null) 0.0 else p.before ?: 0.0
            val after = if (contextual && nextStyle == styleId && nextStyle != null) 0.0 else p.after ?: 0.0
            css.append("margin:${fmt(before)}pt ${fmt(p.right ?: 0.0)}pt ${fmt(after)}pt ${fmt(p.left ?: 0.0)}pt;")
            p.first?.let { if (it != 0.0) css.append("text-indent:${fmt(it)}pt;") }
            val factor = Fonts.lineFactor(base.font)
            p.line?.let { l ->
                when (p.lineRule) {
                    "exact", "atLeast" -> css.append("line-height:${fmt(l / 20)}pt;")
                    else -> css.append("line-height:${fmt(l / 240 * factor)};")
                }
            } ?: css.append("line-height:${fmt(factor)};")
            p.fill?.let { css.append("background:$it;") }
            p.borders?.forEach { (side, v) -> css.append("border-$side:$v;") }
            if (p.borders != null) css.append("padding:1pt 4pt;")
            if (p.pageBreakBefore == true) css.append("page-break-before:always;")
            css.append(runCss(base, null))
            if (positioned) css.append("position:relative;")

            val sb = StringBuilder()
            if (breakBefore) sb.append(PB)
            sb.append("<div class=\"p\" style=\"$css\">")
            if (marker != null && marker.isNotEmpty()) {
                val ms = StringBuilder()
                markerProps?.let { ms.append(runCss(base.copy().merge(it), base)) }
                val hang = if ((p.first ?: 0.0) < 0) -(p.first!!) else 0.0
                if (suffix == "tab" && hang > 0) {
                    sb.append("<span style=\"display:inline-block;min-width:${fmt(hang)}pt;text-indent:0;$ms\">${esc(marker)}</span>")
                } else if (suffix == "nothing") {
                    sb.append("<span style=\"$ms\">${esc(marker)}</span>")
                } else {
                    sb.append("<span style=\"$ms\">${esc(marker)}${if (suffix == "tab") "\t" else " "}</span>")
                }
            }
            if (content.isEmpty() && (marker == null || marker.isEmpty())) {
                sb.append("<span style=\"${runCss(markRun, base)}\">&nbsp;</span>")
            } else sb.append(content)
            sb.append("</div>")
            if (breakAfter) sb.append(PB)
            out.append(sb)
            return styleId
        }

        /** CSS for run properties; when [base] is given only the differences are emitted. */
        private fun runCss(r: RunProps, base: RunProps?): String {
            val sb = StringBuilder()
            fun <T> diff(a: T?, b: T?) = a != null && a != b
            if (diff(r.font, base?.font)) sb.append("font-family:${Fonts.css(r.font!!)};")
            val size = r.size
            val sup = r.vertAlign == "superscript" || r.vertAlign == "subscript"
            if (size != null && (base == null || size != base.size || sup)) {
                sb.append("font-size:${fmt(if (sup) size * 0.65 else size)}pt;")
            }
            if (diff(r.bold, base?.bold)) sb.append(if (r.bold == true) "font-weight:bold;" else "font-weight:normal;")
            if (diff(r.italic, base?.italic)) sb.append(if (r.italic == true) "font-style:italic;" else "font-style:normal;")
            if (diff(r.color, base?.color) && !r.color.isNullOrEmpty()) sb.append("color:${r.color};")
            val deco = ArrayList<String>()
            if (r.underline != null && r.underline != "none") deco.add("underline")
            if (r.strike == true) deco.add("line-through")
            if (deco.isNotEmpty() && (base == null || r.underline != base.underline || r.strike != base.strike)) {
                sb.append("text-decoration:${deco.joinToString(" ")};")
                when (r.underline) {
                    "double" -> sb.append("text-decoration-style:double;")
                    "dotted", "dottedHeavy" -> sb.append("text-decoration-style:dotted;")
                    "dash", "dashedHeavy", "dashLong" -> sb.append("text-decoration-style:dashed;")
                    "wave", "wavyHeavy", "wavyDouble" -> sb.append("text-decoration-style:wavy;")
                }
            }
            if (diff(r.caps, base?.caps) && r.caps == true) sb.append("text-transform:uppercase;")
            if (diff(r.smallCaps, base?.smallCaps) && r.smallCaps == true) sb.append("font-variant:small-caps;")
            (r.highlight ?: r.shade)?.let { if (it != base?.highlight && it != base?.shade) sb.append("background:$it;") }
            when (r.vertAlign) {
                "superscript" -> sb.append("vertical-align:super;line-height:0;")
                "subscript" -> sb.append("vertical-align:sub;line-height:0;")
            }
            if (diff(r.spacing, base?.spacing)) sb.append("letter-spacing:${fmt(r.spacing!!)}pt;")
            return sb.toString()
        }

        // ---- runs ----

        private fun runs(parent: Node, out: StringBuilder, base: RunProps) {
            for (n in parent.children) when (n.name) {
                "r" -> run(n, out, base)
                "hyperlink" -> {
                    val href = n.attr("id")?.let { ext[it] }?.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("mailto:") }
                    if (href != null) {
                        out.append("<a href=\"${esc(href)}\">")
                        runs(n, out, base)
                        out.append("</a>")
                    } else runs(n, out, base)
                }
                "smartTag", "sdt", "sdtContent", "fldSimple", "customXml" -> runs(n, out, base)
                "ins", "moveTo", "del", "moveFrom" -> {
                    // tracked changes are shown like Word's "All Markup": insertions underlined, deletions struck through
                    val saved = revision
                    revision = if (n.name == "ins" || n.name == "moveTo") "color:#1a7f37;text-decoration:underline;" else "color:#cf222e;text-decoration:line-through;"
                    runs(n, out, base)
                    revision = saved
                }
            }
        }

        private var revision: String? = null

        private fun run(r: Node, out: StringBuilder, base: RunProps) {
            val rPr = r.child("rPr")
            val props = base.copy()
            rPr?.child("rStyle")?.attr("val")?.let { props.merge(s.styles.resolve(it).second) }
            props.merge(RunProps.of(rPr, s.theme))
            if (props.vanish == true) return
            val content = StringBuilder()
            runContent(r, content)
            if (content.isEmpty()) return
            val css = runCss(props, base) + (revision ?: "")
            fun wrap(x: CharSequence) = if (css.isEmpty() || x.isEmpty()) x else "<span style=\"$css\">$x</span>"
            if (tabSplit && content.contains(TAB)) {
                // tabs go outside the span so the paragraph can be split at them
                out.append(content.split(TAB).joinToString(TAB) { wrap(it) })
            } else out.append(wrap(content))
        }

        private fun runContent(r: Node, out: StringBuilder) {
            for (c in r.children) when (c.name) {
                "t", "delText" -> out.append(esc(c.text.toString()))
                "tab", "ptab" -> out.append(if (tabSplit) TAB else "\t")
                "br" -> if (c in skipBreaks) {} else when (c.attr("type")) {
                    "page" -> out.append("<span style=\"display:block;page-break-after:always;height:0\"></span>")
                    "column" -> {}
                    else -> out.append("<br>")
                }
                "cr" -> out.append("<br>")
                "noBreakHyphen" -> out.append('‑')
                "softHyphen" -> out.append("&shy;")
                "sym" -> out.append(esc(Numbering.bullet(String(Character.toChars((c.attr("char")?.toIntOrNull(16) ?: 0x2022))))))
                "footnoteReference" -> c.attr("id")?.let { id ->
                    s.footnoteOrder.add(id)
                    out.append("<sup>${s.footnoteOrder.size}</sup>")
                }
                "drawing" -> drawing(c)?.let { out.append(it) }
                "pict", "object" -> pict(c)?.let { out.append(it) }
                "AlternateContent" -> (c.child("Choice") ?: c.child("Fallback"))?.let { runContent(it, out) }
            }
        }

        // ---- images, text boxes ----

        private fun emu(v: String?) = v?.toDoubleOrNull()?.div(12700)

        private val scheme: ColorScheme by lazy { ColorScheme.parse(s.pkg.xml("word/theme/theme1.xml")) }

        private fun drawing(d: Node): String? {
            val extent = d.find("extent")
            val w = emu(extent?.attr("cx"))
            val h = emu(extent?.attr("cy"))
            val blip = d.find("blip")
            val inner: String
            val diagram = if (blip == null && w != null && h != null) d.find("graphicData")?.let { gd ->
                val wpx = w * 4 / 3; val hpx = h * 4 / 3
                (Diagrams.chartSvg(s.pkg, rels, scheme, gd, wpx, hpx) ?: Diagrams.smartArt(s.pkg, rels, scheme, gd, wpx, hpx))
                    ?.let { "<span style=\"display:block;width:${fmt(w)}pt;height:${fmt(h)}pt;overflow:hidden\">$it</span>" }
            } else null
            if (diagram != null) {
                inner = diagram
            } else if (blip != null) {
                val part = rels[blip.attr("embed")] ?: return null
                val uri = s.pkg.imageDataUri(part)
                    ?: return "<span style=\"display:inline-block;width:${fmt(w ?: 40.0)}pt;height:${fmt(h ?: 20.0)}pt;border:1px dashed #999\"></span>"
                val crop = d.find("srcRect")
                val l = (crop?.attr("l")?.toDoubleOrNull() ?: 0.0) / 100000
                val t = (crop?.attr("t")?.toDoubleOrNull() ?: 0.0) / 100000
                val rr = (crop?.attr("r")?.toDoubleOrNull() ?: 0.0) / 100000
                val b = (crop?.attr("b")?.toDoubleOrNull() ?: 0.0) / 100000
                inner = if (w != null && h != null && (l + t + rr + b) > 0 && l + rr < 1 && t + b < 1) {
                    val iw = w / (1 - l - rr); val ih = h / (1 - t - b)
                    "<span style=\"display:block;position:relative;overflow:hidden;width:${fmt(w)}pt;height:${fmt(h)}pt\">" +
                        "<img src=\"$uri\" style=\"position:absolute;max-width:none;width:${fmt(iw)}pt;height:${fmt(ih)}pt;left:${fmt(-l * iw)}pt;top:${fmt(-t * ih)}pt\"></span>"
                } else {
                    "<img src=\"$uri\" style=\"max-width:none;" + (if (w != null && h != null) "width:${fmt(w)}pt;height:${fmt(h)}pt;" else "") + "\">"
                }
            } else {
                val tx = d.find("txbxContent") ?: return null
                val sb = StringBuilder()
                blocks(tx, sb, null)
                inner = "<div style=\"box-sizing:border-box;" + (if (w != null) "width:${fmt(w)}pt;" else "") + "\">$sb</div>"
            }
            val anchor = d.child("anchor") ?: return "<span style=\"display:inline-block;vertical-align:bottom;line-height:0\">$inner</span>"
            return placeAnchor(anchor, inner, w ?: 0.0, h ?: 0.0)
        }

        private fun placeAnchor(a: Node, inner: String, w: Double, h: Double): String {
            val ph = a.child("positionH"); val pv = a.child("positionV")
            val hOff = emu(ph?.child("posOffset")?.text?.toString()?.trim())
            val vOff = emu(pv?.child("posOffset")?.text?.toString()?.trim())
            val hAlign = ph?.child("align")?.text?.toString()?.trim()
            val hRel = ph?.attr("relativeFrom"); val vRel = pv?.attr("relativeFrom")
            val wrapNone = a.child("wrapNone") != null
            val pg = s.page
            if (wrapNone && (hRel == "page" || hRel == "margin") && (vRel == "page" || vRel == "margin")) {
                val css = StringBuilder("position:absolute;line-height:0;")
                val ox = if (hRel == "margin") pg.left else 0.0
                val oy = if (vRel == "margin") pg.top else 0.0
                when {
                    hAlign == "center" -> css.append("left:50%;margin-left:${fmt(-w / 2)}pt;")
                    hAlign == "right" -> css.append("right:${if (hRel == "margin") fmt(pg.right) else "0"}pt;")
                    else -> css.append("left:${fmt(ox + (hOff ?: 0.0))}pt;")
                }
                css.append("top:${fmt(oy + (vOff ?: 0.0))}pt;")
                css.append("z-index:${if (a.attr("behindDoc") == "1") -1 else 1};")
                return "<span style=\"$css\">$inner</span>"
            }
            if (wrapNone) return "<span style=\"display:inline-block;vertical-align:bottom;line-height:0\">$inner</span>"
            if (a.child("wrapTopAndBottom") != null) {
                return "<span style=\"display:block;clear:both;line-height:0;margin:4pt auto;width:${fmt(w)}pt\">$inner</span>"
            }
            val right = hAlign == "right" || (hAlign == null && hOff != null && hOff > pg.w / 2)
            val side = if (right) "right" else "left"
            val dist = emu(a.attr(if (right) "distL" else "distR")) ?: 8.0
            return "<span style=\"float:$side;line-height:0;margin:2pt ${if (right) 0 else 0}pt 2pt 0;margin-${if (right) "left" else "right"}:${fmt(dist)}pt\">$inner</span>"
        }

        private val sizeRe = Regex("(width|height)\\s*:\\s*([0-9.]+)\\s*(pt|in|px|cm|mm)?")

        private fun pict(c: Node): String? {
            val shape = c.find("shape") ?: c
            val style = shape.attr("style").orEmpty()
            val dims = HashMap<String, Double>()
            for (m in sizeRe.findAll(style)) {
                val v = m.groupValues[2].toDoubleOrNull() ?: continue
                dims[m.groupValues[1]] = v * when (m.groupValues[3]) { "in" -> 72.0; "px" -> 0.75; "cm" -> 28.35; "mm" -> 2.835; else -> 1.0 }
            }
            val img = c.find("imagedata")
            if (img != null) {
                val part = rels[img.attr("id")] ?: return null
                val uri = s.pkg.imageDataUri(part) ?: return "<i>[image]</i>"
                val sz = if (dims["width"] != null && dims["height"] != null) "width:${fmt(dims["width"]!!)}pt;height:${fmt(dims["height"]!!)}pt;" else ""
                return "<img src=\"$uri\" style=\"max-width:none;$sz\">"
            }
            val tx = c.find("txbxContent") ?: return null
            val sb = StringBuilder()
            blocks(tx, sb, null)
            return "<div style=\"display:inline-block;vertical-align:top;" + (dims["width"]?.let { "width:${fmt(it)}pt;" } ?: "") + "\">$sb</div>"
        }
    }
}
