package eu.elraro.openreader

object PptxConverter {
    private const val PRES = "ppt/presentation.xml"

    /** Maps child-space EMU coordinates of a group to slide space. */
    private class Xf(val sx: Double = 1.0, val sy: Double = 1.0, val dx: Double = 0.0, val dy: Double = 0.0)

    private class Ctx(
        val pkg: OoxmlPackage,
        val rels: Map<String, String>,
        val cx: Double,
        val cy: Double,
    ) {
        val flow = StringBuilder()
        val abs = StringBuilder()
        fun vw(pt: Double) = "%.3fvw".format(pt * 12700.0 / cx * 100.0)
    }

    fun toHtml(pkg: OoxmlPackage, title: String): String {
        val pres = pkg.xml(PRES) ?: throw IllegalArgumentException("Missing $PRES")
        val rels = pkg.rels(PRES)
        val size = pres.child("sldSz")
        val cx = size?.attr("cx")?.toDoubleOrNull() ?: 9144000.0
        val cy = size?.attr("cy")?.toDoubleOrNull() ?: 5143500.0
        val ids = pres.child("sldIdLst")?.all("sldId").orEmpty()

        val body = StringBuilder()
        ids.forEachIndexed { i, id ->
            val part = rels[id.attr("id")] ?: return@forEachIndexed
            val slide = pkg.xml(part) ?: return@forEachIndexed
            val ctx = Ctx(pkg, pkg.rels(part), cx, cy)
            slide.child("cSld")?.child("spTree")?.let { shapes(it, Xf(), ctx) }
            body.append("<div class=\"num\">Slide ${i + 1}</div>")
            body.append("<div class=\"slide\" style=\"padding-top:${"%.3f".format(cy / cx * 100)}%\"><div class=\"in\">")
            body.append(ctx.abs)
            if (ctx.flow.isNotEmpty()) body.append("<div class=\"flow\">${ctx.flow}</div>")
            body.append("</div></div>")
            notes(pkg, part)?.let { body.append("<div class=\"notes\"><b>Notes:</b> ${esc(it)}</div>") }
        }
        if (ids.isEmpty()) body.append("<p>No slides found.</p>")
        val css = """
.num{font-size:12px;color:#666;margin:8px 8px 2px}
.slide{position:relative;width:100%;background:#fff;border-top:1px solid #bbb;border-bottom:1px solid #bbb;page-break-inside:avoid}
.in{position:absolute;left:0;top:0;right:0;bottom:0;overflow:hidden}
.sh{position:absolute;overflow:hidden;box-sizing:border-box}
.flow{position:absolute;left:5%;right:5%;top:5%;bottom:5%;overflow:hidden}
.sh p,.flow p{margin:0 0 .3em}
.notes{font-size:12px;color:#444;margin:4px 8px 0;background:#fffbe6;padding:4px}
table{border-collapse:collapse;width:100%}
td{border:1px solid #999;padding:2px 4px;vertical-align:top}
@media print{.num{page-break-before:always}}
"""
        return htmlPage(title, css, body.toString())
    }

    private fun notes(pkg: OoxmlPackage, slidePart: String): String? {
        val rel = pkg.rels(slidePart).values.firstOrNull { it.contains("notesSlides/") } ?: return null
        val root = pkg.xml(rel) ?: return null
        val text = root.findAll("sp")
            .filter { sp -> sp.find("ph")?.attr("type") == "body" }
            .joinToString(" ") { sp -> sp.findAll("p").joinToString(" ") { paraText(it) } }
            .trim()
        return text.ifEmpty { null }
    }

    private fun paraText(p: Node): String =
        p.children.filter { it.name == "r" || it.name == "fld" }.joinToString("") { it.child("t")?.text?.toString().orEmpty() }

    private fun shapes(parent: Node, xf: Xf, ctx: Ctx) {
        for (n in parent.children) when (n.name) {
            "sp" -> shape(n, xf, ctx)
            "pic" -> picture(n, xf, ctx)
            "graphicFrame" -> frame(n, xf, ctx)
            "grpSp" -> {
                val g = n.child("grpSpPr")?.child("xfrm")
                val off = g?.child("off")
                val ext = g?.child("ext")
                val cOff = g?.child("chOff")
                val cExt = g?.child("chExt")
                var inner = xf
                if (off != null && ext != null && cOff != null && cExt != null) {
                    val ex = ext.attr("cx")?.toDoubleOrNull() ?: 0.0
                    val ey = ext.attr("cy")?.toDoubleOrNull() ?: 0.0
                    val chx = cExt.attr("cx")?.toDoubleOrNull() ?: 0.0
                    val chy = cExt.attr("cy")?.toDoubleOrNull() ?: 0.0
                    if (chx > 0 && chy > 0) {
                        val sx = ex / chx
                        val sy = ey / chy
                        val ox = off.attr("x")?.toDoubleOrNull() ?: 0.0
                        val oy = off.attr("y")?.toDoubleOrNull() ?: 0.0
                        val cx0 = cOff.attr("x")?.toDoubleOrNull() ?: 0.0
                        val cy0 = cOff.attr("y")?.toDoubleOrNull() ?: 0.0
                        inner = Xf(
                            xf.sx * sx, xf.sy * sy,
                            xf.dx + xf.sx * (ox - cx0 * sx), xf.dy + xf.sy * (oy - cy0 * sy),
                        )
                    }
                }
                shapes(n, inner, ctx)
            }
        }
    }

    /** CSS box for an xfrm node, or null if absent. */
    private fun box(xfrm: Node?, xf: Xf, ctx: Ctx): String? {
        val off = xfrm?.child("off") ?: return null
        val ext = xfrm.child("ext") ?: return null
        val x = (off.attr("x")?.toDoubleOrNull() ?: return null) * xf.sx + xf.dx
        val y = (off.attr("y")?.toDoubleOrNull() ?: return null) * xf.sy + xf.dy
        val w = (ext.attr("cx")?.toDoubleOrNull() ?: return null) * xf.sx
        val h = (ext.attr("cy")?.toDoubleOrNull() ?: return null) * xf.sy
        return "left:%.3f%%;top:%.3f%%;width:%.3f%%;height:%.3f%%;".format(
            x / ctx.cx * 100, y / ctx.cy * 100, w / ctx.cx * 100, h / ctx.cy * 100,
        )
    }

    private fun shape(sp: Node, xf: Xf, ctx: Ctx) {
        val spPr = sp.child("spPr")
        val style = StringBuilder()
        val fill = safeColor(spPr?.child("solidFill")?.child("srgbClr")?.attr("val"))
        if (fill != null) style.append("background:$fill;")
        val txt = StringBuilder()
        sp.child("txBody")?.let { body ->
            val isTitle = sp.find("ph")?.attr("type").let { it == "title" || it == "ctrTitle" }
            for (p in body.all("p")) txt.append(paragraph(p, ctx, isTitle))
        }
        if (txt.isEmpty() && fill == null) return
        val b = box(spPr?.child("xfrm"), xf, ctx)
        if (b != null) ctx.abs.append("<div class=\"sh\" style=\"$b$style\">$txt</div>")
        else if (txt.isNotEmpty()) ctx.flow.append(txt)
    }

    private fun paragraph(p: Node, ctx: Ctx, title: Boolean): String {
        val pPr = p.child("pPr")
        val css = StringBuilder()
        when (pPr?.attr("algn")) {
            "ctr" -> css.append("text-align:center;")
            "r" -> css.append("text-align:right;")
        }
        val bulleted = pPr?.child("buChar") != null || pPr?.child("buAutoNum") != null
        val lvl = pPr?.attr("lvl")?.toIntOrNull() ?: 0
        if (lvl > 0) css.append("margin-left:${lvl * 3}%;")
        val sb = StringBuilder()
        if (bulleted) sb.append("•&nbsp;")
        for (r in p.children) when (r.name) {
            "r", "fld" -> {
                val t = r.child("t")?.text?.toString().orEmpty()
                if (t.isEmpty()) continue
                val rPr = r.child("rPr")
                val rs = StringBuilder()
                val sz = rPr?.attr("sz")?.toDoubleOrNull()
                rs.append("font-size:${ctx.vw(sz?.div(100.0) ?: if (title) 32.0 else 18.0)};")
                safeColor(rPr?.child("solidFill")?.child("srgbClr")?.attr("val"))?.let { rs.append("color:$it;") }
                var s = esc(t)
                if (rPr?.attr("b") == "1" || (title && rPr?.attr("b") == null)) s = "<b>$s</b>"
                if (rPr?.attr("i") == "1") s = "<i>$s</i>"
                if (rPr?.attr("u").let { it != null && it != "none" }) s = "<u>$s</u>"
                sb.append("<span style=\"$rs\">$s</span>")
            }
            "br" -> sb.append("<br>")
        }
        if (sb.isEmpty() || sb.toString() == "•&nbsp;") return ""
        return "<p style=\"$css\">$sb</p>"
    }

    private fun picture(pic: Node, xf: Xf, ctx: Ctx) {
        val part = ctx.rels[pic.find("blip")?.attr("embed")] ?: return
        val b = box(pic.child("spPr")?.child("xfrm"), xf, ctx) ?: return
        val uri = ctx.pkg.imageDataUri(part) ?: return
        ctx.abs.append("<div class=\"sh\" style=\"$b\"><img src=\"$uri\" style=\"width:100%;height:100%;object-fit:contain\"></div>")
    }

    private fun frame(gf: Node, xf: Xf, ctx: Ctx) {
        val tbl = gf.find("tbl") ?: return
        val html = StringBuilder("<table>")
        for (tr in tbl.all("tr")) {
            html.append("<tr>")
            for (tc in tr.all("tc")) {
                if (tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1") continue
                val span = tc.attr("gridSpan")?.toIntOrNull() ?: 1
                html.append(if (span > 1) "<td colspan=\"$span\">" else "<td>")
                tc.child("txBody")?.all("p")?.forEach { html.append(paragraph(it, ctx, false)) }
                html.append("</td>")
            }
            html.append("</tr>")
        }
        html.append("</table>")
        val b = box(gf.child("xfrm"), xf, ctx)
        if (b != null) ctx.abs.append("<div class=\"sh\" style=\"$b\">$html</div>") else ctx.flow.append(html)
    }
}
