package eu.elraro.openreader

object DocxConverter {
    private const val PART = "word/document.xml"

    fun toHtml(pkg: OoxmlPackage, title: String): String {
        val doc = pkg.xml(PART) ?: throw IllegalArgumentException("Missing $PART")
        val ctx = Ctx(pkg, pkg.rels(PART), loadNumbering(pkg))
        val body = StringBuilder()
        doc.child("body")?.let { blocks(it, ctx, body) }
        val css = """
.page{padding:16px;max-width:900px;margin:0 auto;line-height:1.45}
p{margin:0 0 .6em}
table{border-collapse:collapse;margin:.6em 0;max-width:100%}
td,th{border:1px solid #999;padding:4px 6px;vertical-align:top}
li{margin:0}
"""
        return htmlPage(title, css, "<div class=\"page\">$body</div>")
    }

    private class Ctx(
        val pkg: OoxmlPackage,
        val rels: Map<String, String>,
        val numbering: Map<String, Map<Int, Boolean>>, // numId -> level -> isBullet
    ) {
        val counters = HashMap<String, IntArray>()
    }

    private fun loadNumbering(pkg: OoxmlPackage): Map<String, Map<Int, Boolean>> {
        val root = pkg.xml("word/numbering.xml") ?: return emptyMap()
        val abstract = HashMap<String, Map<Int, Boolean>>()
        for (a in root.all("abstractNum")) {
            val id = a.attr("abstractNumId") ?: continue
            abstract[id] = a.all("lvl").associate { l ->
                (l.attr("ilvl")?.toIntOrNull() ?: 0) to (l.child("numFmt")?.attr("val") == "bullet")
            }
        }
        val out = HashMap<String, Map<Int, Boolean>>()
        for (n in root.all("num")) {
            val id = n.attr("numId") ?: continue
            val abs = n.child("abstractNumId")?.attr("val") ?: continue
            abstract[abs]?.let { out[id] = it }
        }
        return out
    }

    private fun blocks(parent: Node, ctx: Ctx, out: StringBuilder) {
        for (n in parent.children) when (n.name) {
            "p" -> paragraph(n, ctx, out)
            "tbl" -> table(n, ctx, out)
            "sdt" -> n.child("sdtContent")?.let { blocks(it, ctx, out) }
        }
    }

    private fun table(tbl: Node, ctx: Ctx, out: StringBuilder) {
        out.append("<table>")
        for (tr in tbl.all("tr")) {
            out.append("<tr>")
            for (tc in tr.all("tc")) {
                val span = tc.child("tcPr")?.child("gridSpan")?.attr("val")?.toIntOrNull() ?: 1
                // vMerge continuation cells are skipped
                val vm = tc.child("tcPr")?.child("vMerge")
                if (vm != null && vm.attr("val") != "restart") continue
                out.append(if (span > 1) "<td colspan=\"$span\">" else "<td>")
                blocks(tc, ctx, out)
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table>")
    }

    private fun paragraph(p: Node, ctx: Ctx, out: StringBuilder) {
        val pPr = p.child("pPr")
        val style = pPr?.child("pStyle")?.attr("val")?.lowercase().orEmpty()
        var tag = "p"
        when {
            style == "title" -> tag = "h1"
            style.startsWith("heading") -> {
                val lvl = style.filter { it.isDigit() }.toIntOrNull() ?: 1
                tag = "h${lvl.coerceIn(1, 6)}"
            }
        }
        val css = StringBuilder()
        when (pPr?.child("jc")?.attr("val")) {
            "center" -> css.append("text-align:center;")
            "right", "end" -> css.append("text-align:right;")
            "both" -> css.append("text-align:justify;")
        }
        var prefix = ""
        val numPr = pPr?.child("numPr")
        val numId = numPr?.child("numId")?.attr("val")
        if (numId != null && numId != "0") {
            val lvl = numPr.child("ilvl")?.attr("val")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
            css.append("margin-left:${(lvl + 1) * 24}px;")
            val bullet = ctx.numbering[numId]?.get(lvl) ?: true
            prefix = if (bullet) "•&nbsp;" else {
                val c = ctx.counters.getOrPut(numId) { IntArray(9) }
                c[lvl]++
                for (i in lvl + 1 until 9) c[i] = 0
                "${c[lvl]}.&nbsp;"
            }
        }
        val inner = StringBuilder()
        runs(p, ctx, inner)
        out.append("<$tag")
        if (css.isNotEmpty()) out.append(" style=\"$css\"")
        out.append(">").append(prefix)
        out.append(if (inner.isEmpty()) "&nbsp;" else inner)
        out.append("</$tag>")
    }

    private fun runs(parent: Node, ctx: Ctx, out: StringBuilder) {
        for (n in parent.children) when (n.name) {
            "r" -> run(n, ctx, out)
            "hyperlink", "ins", "smartTag", "sdt", "sdtContent", "fldSimple" -> runs(n, ctx, out)
        }
    }

    private fun run(r: Node, ctx: Ctx, out: StringBuilder) {
        val rPr = r.child("rPr")
        val content = StringBuilder()
        for (c in r.children) when (c.name) {
            "t" -> content.append(esc(c.text.toString()).replace("  ", "&nbsp; "))
            "tab" -> content.append("&emsp;")
            "br", "cr" -> content.append(if (c.attr("type") == "page") "<hr>" else "<br>")
            "drawing", "pict" -> image(c, ctx)?.let { content.append(it) }
        }
        if (content.isEmpty()) return
        var s = content.toString()
        fun on(tag: String) = rPr?.child(tag)?.let { it.attr("val") != "false" && it.attr("val") != "0" } == true
        if (on("b")) s = "<b>$s</b>"
        if (on("i")) s = "<i>$s</i>"
        if (rPr?.child("u")?.attr("val").let { it != null && it != "none" }) s = "<u>$s</u>"
        if (on("strike")) s = "<s>$s</s>"
        when (rPr?.child("vertAlign")?.attr("val")) {
            "superscript" -> s = "<sup>$s</sup>"
            "subscript" -> s = "<sub>$s</sub>"
        }
        val style = StringBuilder()
        safeColor(rPr?.child("color")?.attr("val"))?.let { style.append("color:$it;") }
        rPr?.child("sz")?.attr("val")?.toIntOrNull()?.let { style.append("font-size:${it / 2.0}pt;") }
        if (style.isNotEmpty()) s = "<span style=\"$style\">$s</span>"
        out.append(s)
    }

    private fun image(drawing: Node, ctx: Ctx): String? {
        val blip = drawing.find("blip") ?: return null
        val part = ctx.rels[blip.attr("embed")] ?: return null
        val uri = ctx.pkg.imageDataUri(part) ?: return "<i>[image]</i>"
        val extent = drawing.find("extent")
        val w = extent?.attr("cx")?.toLongOrNull()?.let { it / 9525 }
        return "<img src=\"$uri\"" + (if (w != null && w > 0) " style=\"width:${w}px\"" else "") + ">"
    }
}
