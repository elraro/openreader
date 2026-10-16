package eu.elraro.openreader

import kotlin.math.max

/** Charts and SmartArt shared by the three OOXML converters. */
object Diagrams {
    private const val CHART = "chart"
    private const val EMU_PX = 9525.0

    /** Chart referenced by a graphicData/drawing element, or null when [chart] has no chart. */
    fun chartSvg(pkg: OoxmlPackage, rels: Map<String, String>, scheme: ColorScheme, frame: Node, wPx: Double, hPx: Double, resolve: ((String) -> List<Any?>?)? = null): String? {
        val ref = frame.find(CHART) ?: return null
        val part = rels[ref.attr("id")] ?: return null
        val root = try { pkg.xml(part) } catch (e: Exception) { null } ?: return null
        if (root.name != "chartSpace") return null
        return try { Charts.svg(root, scheme, wPx, hPx, resolve) } catch (e: Exception) { null }
    }

    /** SmartArt: draws the cached drawing part when present, otherwise lists the text of the data model. */
    fun smartArt(pkg: OoxmlPackage, rels: Map<String, String>, scheme: ColorScheme, frame: Node, wPx: Double, hPx: Double): String? {
        val ids = frame.find("relIds") ?: return null
        val dmPart = rels[ids.attr("dm")] ?: return null
        val dm = try { pkg.xml(dmPart) } catch (e: Exception) { null } ?: return null
        val drawingRel = dm.findAll("dataModelExt").firstOrNull()?.attr("relId")
        val drawingPart = drawingRel?.let { rels[it] }
        val drawing = drawingPart?.let { try { pkg.xml(it) } catch (e: Exception) { null } }
        if (drawing != null) return renderDrawing(drawing, scheme, wPx, hPx)
        val items = dm.findAll("pt").filter { it.attr("type") == null || it.attr("type") == "node" }
            .map { p -> p.findAll("t").joinToString("") { it.text.toString() } }.filter { it.isNotBlank() }
        if (items.isEmpty()) return null
        // no cached drawing: show the nodes as boxes in reading order
        val accent = scheme.get("accent1") ?: "4472C4"
        val boxes = items.joinToString("") {
            "<div style=\"background:#$accent;color:#fff;padding:10px 16px;border-radius:8px;min-width:80px;text-align:center;font-size:14px\">${esc(it)}</div>"
        }
        return "<div style=\"display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:center;width:${f(wPx)}px;min-height:${f(hPx)}px\">$boxes</div>"
    }

    private fun renderDrawing(root: Node, scheme: ColorScheme, wPx: Double, hPx: Double): String {
        val sb = StringBuilder("<div style=\"position:relative;width:${f(wPx)}px;height:${f(hPx)}px\">")
        for (sp in root.findAll("sp")) {
            val spPr = sp.child("spPr") ?: continue
            val xf = spPr.child("xfrm")
            val off = xf?.child("off"); val ext = xf?.child("ext")
            val x = (off?.attr("x")?.toDoubleOrNull() ?: 0.0) / EMU_PX
            val y = (off?.attr("y")?.toDoubleOrNull() ?: 0.0) / EMU_PX
            val w = (ext?.attr("cx")?.toDoubleOrNull() ?: 0.0) / EMU_PX
            val h = (ext?.attr("cy")?.toDoubleOrNull() ?: 0.0) / EMU_PX
            if (w <= 0 || h <= 0) continue
            val style = sp.child("style")
            val fill = if (spPr.child("noFill") != null) null
                else (spPr.child("solidFill")?.children?.firstOrNull() ?: style?.child("fillRef")?.children?.firstOrNull())?.let { scheme.resolve(it) }
            val ln = spPr.child("ln")
            val lc = if (ln?.child("noFill") != null) null
                else (ln?.child("solidFill")?.children?.firstOrNull() ?: style?.child("lnRef")?.children?.firstOrNull())?.let { scheme.resolve(it) }
            val lw = (ln?.attr("w")?.toDoubleOrNull() ?: 12700.0) / EMU_PX
            val geom = spPr.child("prstGeom")?.attr("prst") ?: "rect"
            val radius = when {
                geom == "ellipse" -> "border-radius:50%;"
                geom.startsWith("round") -> "border-radius:${f(minOf(w, h) * 0.16)}px;"
                else -> ""
            }
            val rot = xf?.attr("rot")?.toDoubleOrNull()?.div(60000)
            val tr = if (rot != null && rot != 0.0) "transform:rotate(${f(rot)}deg);" else ""
            sb.append("<div style=\"position:absolute;box-sizing:border-box;left:${f(x)}px;top:${f(y)}px;width:${f(w)}px;height:${f(h)}px;$radius$tr")
            if (fill != null) sb.append("background:$fill;")
            if (lc != null) sb.append("border:${f(max(0.75, lw))}px solid $lc;")
            sb.append("display:flex;flex-direction:column;justify-content:center;overflow:hidden;\">")
            val tx = sp.child("txBody")
            if (tx != null) {
                val color = style?.child("fontRef")?.children?.firstOrNull()?.let { scheme.resolve(it) } ?: "#ffffff"
                for (p in tx.all("p")) {
                    val algn = p.child("pPr")?.attr("algn")
                    val txt = StringBuilder()
                    for (r in p.all("r")) {
                        val t = r.child("t")?.text?.toString() ?: continue
                        val rp = r.child("rPr")
                        val sz = rp?.attr("sz")?.toDoubleOrNull()?.div(100)?.times(4.0 / 3) ?: 16.0
                        val c = rp?.child("solidFill")?.children?.firstOrNull()?.let { scheme.resolve(it) } ?: color
                        val b = if (rp?.attr("b") == "1") "font-weight:bold;" else ""
                        txt.append("<span style=\"font-size:${f(sz)}px;color:$c;$b\">${esc(t)}</span>")
                    }
                    if (txt.isEmpty()) continue
                    val al = when (algn) { "l" -> "left"; "r" -> "right"; else -> "center" }
                    sb.append("<div style=\"text-align:$al;padding:0 4px;line-height:1.15\">$txt</div>")
                }
            }
            sb.append("</div>")
        }
        return sb.append("</div>").toString()
    }

    private fun f(d: Double): String = String.format(java.util.Locale.US, "%.2f", d).trimEnd('0').trimEnd('.').ifEmpty { "0" }
}
