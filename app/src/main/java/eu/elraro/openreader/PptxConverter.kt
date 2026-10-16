package eu.elraro.openreader

import java.util.Locale

object PptxConverter {
    private const val PRES = "ppt/presentation.xml"
    private const val EMU_PX = 9525.0

    private fun px(emu: Double) = emu / EMU_PX
    private fun f(d: Double): String = String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')

    // ---- text property model ----

    private class RP {
        var size: Double? = null; var bold: Boolean? = null; var italic: Boolean? = null; var underline: Boolean? = null
        var strike: Boolean? = null; var caps: Boolean? = null; var baseline: Double? = null; var spc: Double? = null
        var color: String? = null; var font: String? = null
        fun copy() = RP().also { it.merge(this) }
        fun merge(o: RP): RP {
            o.size?.let { size = it }; o.bold?.let { bold = it }; o.italic?.let { italic = it }; o.underline?.let { underline = it }
            o.strike?.let { strike = it }; o.caps?.let { caps = it }; o.baseline?.let { baseline = it }; o.spc?.let { spc = it }
            o.color?.let { color = it }; o.font?.let { font = it }
            return this
        }
    }

    private class PP {
        var marL: Double? = null; var indent: Double? = null; var algn: String? = null
        var lnPct: Double? = null; var lnPts: Double? = null
        var befPct: Double? = null; var befPts: Double? = null; var aftPct: Double? = null; var aftPts: Double? = null
        var buNone: Boolean? = null; var buChar: String? = null; var buAuto: String? = null; var buStart: Int? = null
        var buFont: String? = null; var buColor: String? = null; var buSize: Double? = null
        val r = RP()
        fun merge(o: PP) {
            o.marL?.let { marL = it }; o.indent?.let { indent = it }; o.algn?.let { algn = it }
            o.lnPct?.let { lnPct = it; lnPts = null }; o.lnPts?.let { lnPts = it; lnPct = null }
            o.befPct?.let { befPct = it; befPts = null }; o.befPts?.let { befPts = it; befPct = null }
            o.aftPct?.let { aftPct = it; aftPts = null }; o.aftPts?.let { aftPts = it; aftPct = null }
            o.buNone?.let { buNone = it; if (it) { buChar = null; buAuto = null } }
            o.buChar?.let { buChar = it; buNone = false; buAuto = null }
            o.buAuto?.let { buAuto = it; buNone = false; buChar = null }
            o.buStart?.let { buStart = it }; o.buFont?.let { buFont = it }; o.buColor?.let { buColor = it }; o.buSize?.let { buSize = it }
            r.merge(o.r)
        }
    }

    private class Part(val path: String, val root: Node, val rels: Map<String, String>)

    private class Env(val pkg: OoxmlPackage, val scheme: ColorScheme, val major: String?, val minor: String?, val defaultStyle: Node?)

    private fun readRP(n: Node?, env: Env): RP {
        val r = RP()
        n ?: return r
        n.attr("sz")?.toDoubleOrNull()?.let { r.size = it / 100 }
        n.attr("b")?.let { r.bold = it == "1" || it == "true" }
        n.attr("i")?.let { r.italic = it == "1" || it == "true" }
        n.attr("u")?.let { r.underline = it != "none" }
        n.attr("strike")?.let { r.strike = it != "noStrike" }
        n.attr("cap")?.let { r.caps = it == "all" }
        n.attr("baseline")?.toDoubleOrNull()?.let { r.baseline = it }
        n.attr("spc")?.toDoubleOrNull()?.let { r.spc = it / 100 }
        n.child("solidFill")?.children?.firstOrNull()?.let { r.color = env.scheme.resolve(it) }
        n.child("latin")?.attr("typeface")?.let { r.font = themeFont(it, env) }
        return r
    }

    private fun themeFont(t: String, env: Env): String? = when (t) {
        "+mj-lt" -> env.major; "+mn-lt" -> env.minor; "" -> null
        else -> if (t.startsWith("+")) null else t
    }

    private fun readPP(n: Node?, env: Env): PP {
        val p = PP()
        n ?: return p
        n.attr("marL")?.toDoubleOrNull()?.let { p.marL = px(it) }
        n.attr("indent")?.toDoubleOrNull()?.let { p.indent = px(it) }
        n.attr("algn")?.let { p.algn = it }
        n.child("lnSpc")?.let { s -> s.child("spcPct")?.attr("val")?.toDoubleOrNull()?.let { p.lnPct = it / 100000 }; s.child("spcPts")?.attr("val")?.toDoubleOrNull()?.let { p.lnPts = it / 100 } }
        n.child("spcBef")?.let { s -> s.child("spcPct")?.attr("val")?.toDoubleOrNull()?.let { p.befPct = it / 100000 }; s.child("spcPts")?.attr("val")?.toDoubleOrNull()?.let { p.befPts = it / 100 } }
        n.child("spcAft")?.let { s -> s.child("spcPct")?.attr("val")?.toDoubleOrNull()?.let { p.aftPct = it / 100000 }; s.child("spcPts")?.attr("val")?.toDoubleOrNull()?.let { p.aftPts = it / 100 } }
        if (n.child("buNone") != null) p.buNone = true
        n.child("buChar")?.attr("char")?.let { p.buChar = it }
        n.child("buAutoNum")?.let { p.buAuto = it.attr("type") ?: "arabicPeriod"; p.buStart = it.attr("startAt")?.toIntOrNull() }
        n.child("buFont")?.attr("typeface")?.let { p.buFont = it }
        n.child("buClr")?.children?.firstOrNull()?.let { p.buColor = env.scheme.resolve(it) }
        n.child("buSzPct")?.attr("val")?.toDoubleOrNull()?.let { p.buSize = it / 100000 }
        p.r.merge(readRP(n.child("defRPr"), env))
        return p
    }

    // ---- geometry helpers ----

    private val CLIP = mapOf(
        "triangle" to "50% 0,100% 100%,0 100%", "rtTriangle" to "0 0,100% 100%,0 100%",
        "diamond" to "50% 0,100% 50%,50% 100%,0 50%", "parallelogram" to "25% 0,100% 0,75% 100%,0 100%",
        "trapezoid" to "20% 0,80% 0,100% 100%,0 100%", "pentagon" to "50% 0,100% 38%,82% 100%,18% 100%,0 38%",
        "hexagon" to "25% 0,75% 0,100% 50%,75% 100%,25% 100%,0 50%", "octagon" to "30% 0,70% 0,100% 30%,100% 70%,70% 100%,30% 100%,0 70%,0 30%",
        "rightArrow" to "0 25%,60% 25%,60% 0,100% 50%,60% 100%,60% 75%,0 75%",
        "leftArrow" to "100% 25%,40% 25%,40% 0,0 50%,40% 100%,40% 75%,100% 75%",
        "upArrow" to "25% 100%,25% 40%,0 40%,50% 0,100% 40%,75% 40%,75% 100%",
        "downArrow" to "25% 0,75% 0,75% 60%,100% 60%,50% 100%,0 60%,25% 60%",
        "chevron" to "0 0,70% 0,100% 50%,70% 100%,0 100%,30% 50%", "homePlate" to "0 0,75% 0,100% 50%,75% 100%,0 100%",
        "star5" to "50% 0,61% 35%,98% 35%,68% 57%,79% 91%,50% 70%,21% 91%,32% 57%,2% 35%,39% 35%",
        "plus" to "35% 0,65% 0,65% 35%,100% 35%,100% 65%,65% 65%,65% 100%,35% 100%,35% 65%,0 65%,0 35%,35% 35%",
    )

    private class Xf(val sx: Double = 1.0, val sy: Double = 1.0, val dx: Double = 0.0, val dy: Double = 0.0)

    private class Box(val x: Double, val y: Double, val w: Double, val h: Double, val rot: Double, val flipH: Boolean, val flipV: Boolean)

    private fun boxOf(xfrm: Node?, xf: Xf): Box? {
        val off = xfrm?.child("off") ?: return null
        val ext = xfrm.child("ext") ?: return null
        val x = px(off.attr("x")?.toDoubleOrNull() ?: return null) * xf.sx + xf.dx
        val y = px(off.attr("y")?.toDoubleOrNull() ?: return null) * xf.sy + xf.dy
        val w = px(ext.attr("cx")?.toDoubleOrNull() ?: return null) * xf.sx
        val h = px(ext.attr("cy")?.toDoubleOrNull() ?: return null) * xf.sy
        return Box(x, y, w, h, (xfrm.attr("rot")?.toDoubleOrNull() ?: 0.0) / 60000, xfrm.attr("flipH") == "1", xfrm.attr("flipV") == "1")
    }

    // ---- rendering ----

    private class Slide(
        val part: Part, val layout: Part?, val master: Part?, val env: Env,
        val txStyles: Node?, val out: StringBuilder = StringBuilder(),
    )

    fun toHtml(pkg: OoxmlPackage, title: String): String {
        val pres = pkg.xml(PRES) ?: throw IllegalArgumentException("Missing $PRES")
        val presRels = pkg.rels(PRES)
        val size = pres.child("sldSz")
        val cx = size?.attr("cx")?.toDoubleOrNull() ?: 9144000.0
        val cy = size?.attr("cy")?.toDoubleOrNull() ?: 5143500.0
        val sw = px(cx); val sh = px(cy)
        val ids = pres.child("sldIdLst")?.all("sldId").orEmpty()
        val partCache = HashMap<String, Part?>()
        fun part(path: String?): Part? = path?.let { partCache.getOrPut(it) { pkg.xml(it)?.let { r -> Part(it, r, pkg.rels(it)) } } }

        val body = StringBuilder()
        ids.forEachIndexed { i, id ->
            val slide = part(presRels[id.attr("id")]) ?: return@forEachIndexed
            val layout = part(slide.rels.values.firstOrNull { it.contains("slideLayouts/") })
            val master = part(layout?.rels?.values?.firstOrNull { it.contains("slideMasters/") })
            val themePath = master?.rels?.values?.firstOrNull { it.contains("theme/") }
            val theme = themePath?.let { pkg.xml(it) }
            val fs = theme?.find("fontScheme")
            val env = Env(
                pkg, ColorScheme.parse(theme),
                fs?.child("majorFont")?.child("latin")?.attr("typeface"), fs?.child("minorFont")?.child("latin")?.attr("typeface"),
                pres.child("defaultTextStyle"),
            )
            val s = Slide(slide, layout, master, env, master?.root?.child("txStyles"))
            body.append("<div class=\"num\">Slide ${i + 1}</div>")
            val bg = background(slide.root, s, slide) ?: background(layout?.root, s, layout) ?: background(master?.root, s, master) ?: "#ffffff"
            body.append("<div class=\"slide\" style=\"width:${f(sw)}px;height:${f(sh)}px;background:$bg\">")
            val slideShow = slide.root.attr("showMasterSp") != "0"
            val layoutShow = layout?.root?.attr("showMasterSp") != "0"
            if (slideShow && layoutShow) master?.let { renderTree(it.root.child("cSld")?.child("spTree"), s, it, Xf(), false) }
            if (slideShow) layout?.let { renderTree(it.root.child("cSld")?.child("spTree"), s, it, Xf(), false) }
            renderTree(slide.root.child("cSld")?.child("spTree"), s, slide, Xf(), true)
            body.append(s.out)
            body.append("</div>")
            notes(pkg, slide)?.let { body.append("<div class=\"notes\"><b>Notes:</b> ${esc(it)}</div>") }
        }
        if (ids.isEmpty()) body.append("<p>No slides found.</p>")
        val zoom = f(minOf(1.0, 780.0 / sw))
        val css = """
body{background:#d9d9d9;padding:8px 0}
.num{font-size:12px;color:#555;margin:6px auto 2px;width:${f(sw)}px}
.slide{position:relative;margin:0 auto 6px;overflow:hidden;box-shadow:0 1px 4px rgba(0,0,0,.4);page-break-inside:avoid;white-space:normal}
.sh{position:absolute;box-sizing:border-box;display:flex;flex-direction:column}
.sh div.pp{white-space:pre-wrap;overflow-wrap:break-word}
.notes{font-size:12px;color:#444;margin:0 auto 10px;width:${f(sw)}px;background:#fffbe6;padding:4px;box-sizing:border-box}
table.t{border-collapse:collapse;table-layout:fixed}
table.t td{overflow:hidden;vertical-align:top}
@media print{body{background:#fff;padding:0}.slide{box-shadow:none;margin:0 0 8px 0;zoom:$zoom}.num{page-break-before:always;zoom:$zoom}.notes{zoom:$zoom}}
"""
        return htmlPage(title, css, body.toString(), "width=${Math.round(sw) + 16}")
    }

    private fun notes(pkg: OoxmlPackage, slide: Part): String? {
        val rel = slide.rels.values.firstOrNull { it.contains("notesSlides/") } ?: return null
        val root = pkg.xml(rel) ?: return null
        val text = root.findAll("sp")
            .filter { sp -> sp.find("ph")?.attr("type") == "body" }
            .joinToString(" ") { sp -> sp.findAll("p").joinToString(" ") { p -> p.children.filter { it.name == "r" || it.name == "fld" }.joinToString("") { it.child("t")?.text?.toString().orEmpty() } } }
            .trim()
        return text.ifEmpty { null }
    }

    private fun background(root: Node?, s: Slide, part: Part?): String? {
        val bg = root?.child("cSld")?.child("bg") ?: return null
        bg.child("bgPr")?.let { return fillCss(it, s.env, part?.rels.orEmpty()) }
        bg.child("bgRef")?.children?.firstOrNull()?.let { return s.env.scheme.resolve(it) }
        return null
    }

    /** CSS background value from a node holding a fill (spPr / bgPr); null when it specifies none. */
    private fun fillCss(n: Node?, env: Env, rels: Map<String, String>): String? {
        n ?: return null
        n.child("noFill")?.let { return "transparent" }
        n.child("solidFill")?.let { sf ->
            val c = sf.children.firstOrNull() ?: return null
            val hex = env.scheme.resolve(c) ?: return null
            val alpha = c.child("alpha")?.attr("val")?.toDoubleOrNull()?.div(100000)
            return if (alpha != null && alpha < 1) rgba(hex, alpha) else hex
        }
        n.child("gradFill")?.let { g ->
            val stops = g.child("gsLst")?.all("gs").orEmpty().mapNotNull { gs ->
                val c = env.scheme.resolve(gs.children.firstOrNull()) ?: return@mapNotNull null
                "$c ${f((gs.attr("pos")?.toDoubleOrNull() ?: 0.0) / 1000)}%"
            }
            if (stops.size >= 2) {
                val lin = g.child("lin")
                val css = if (g.child("path") != null) "radial-gradient(circle,${stops.joinToString(",")})"
                else "linear-gradient(${f((lin?.attr("ang")?.toDoubleOrNull() ?: 0.0) / 60000 + 90)}deg,${stops.joinToString(",")})"
                return css
            }
            stops.firstOrNull()?.let { return it.substringBefore(' ') }
        }
        n.child("blipFill")?.let { b ->
            val uri = rels[b.child("blip")?.attr("embed")]?.let { env.pkg.imageDataUri(it) } ?: return null
            return "url('$uri') center/cover no-repeat"
        }
        return null
    }

    private fun rgba(hex: String, a: Double): String {
        val r = hex.substring(1, 3).toInt(16); val g = hex.substring(3, 5).toInt(16); val b = hex.substring(5, 7).toInt(16)
        return "rgba($r,$g,$b,${f(a)})"
    }

    private fun renderTree(tree: Node?, s: Slide, part: Part, xf: Xf, isSlide: Boolean) {
        tree ?: return
        for (n in tree.children) when (n.name) {
            "sp" -> if (isSlide || n.find("ph") == null) shape(n, s, part, xf, isSlide)
            "pic" -> picture(n, s, part, xf)
            "cxnSp" -> connector(n, s, part, xf)
            "graphicFrame" -> frame(n, s, part, xf)
            "grpSp" -> {
                val g = n.child("grpSpPr")?.child("xfrm")
                val off = g?.child("off"); val ext = g?.child("ext"); val cOff = g?.child("chOff"); val cExt = g?.child("chExt")
                var inner = xf
                if (off != null && ext != null && cOff != null && cExt != null) {
                    val chx = px(cExt.attr("cx")?.toDoubleOrNull() ?: 0.0); val chy = px(cExt.attr("cy")?.toDoubleOrNull() ?: 0.0)
                    if (chx > 0 && chy > 0) {
                        val sx = px(ext.attr("cx")?.toDoubleOrNull() ?: 0.0) / chx
                        val sy = px(ext.attr("cy")?.toDoubleOrNull() ?: 0.0) / chy
                        val ox = px(off.attr("x")?.toDoubleOrNull() ?: 0.0); val oy = px(off.attr("y")?.toDoubleOrNull() ?: 0.0)
                        val cx0 = px(cOff.attr("x")?.toDoubleOrNull() ?: 0.0); val cy0 = px(cOff.attr("y")?.toDoubleOrNull() ?: 0.0)
                        inner = Xf(xf.sx * sx, xf.sy * sy, xf.dx + xf.sx * (ox - cx0 * sx), xf.dy + xf.sy * (oy - cy0 * sy))
                    }
                }
                renderTree(n, s, part, inner, isSlide)
            }
        }
    }

    // ---- placeholders ----

    private fun phOf(sp: Node): Node? = sp.child("nvSpPr")?.child("nvPr")?.child("ph")

    private fun normType(t: String?): String = when (t) {
        null, "body", "obj", "subTitle", "tbl", "chart", "pic", "clipArt", "dgm", "media" -> "body"
        "title", "ctrTitle" -> "title"
        else -> t
    }

    private fun findPh(tree: Node?, type: String?, idx: String?, typeOnly: Boolean): Node? {
        tree ?: return null
        val shapes = tree.children.filter { it.name == "sp" && phOf(it) != null }
        if (!typeOnly && idx != null) shapes.firstOrNull { phOf(it)?.attr("idx") == idx }?.let { return it }
        return shapes.firstOrNull { normType(phOf(it)?.attr("type")) == normType(type) }
    }

    private fun shape(sp: Node, s: Slide, part: Part, xf: Xf, isSlide: Boolean) {
        val spPr = sp.child("spPr")
        val ph = phOf(sp)
        var layoutSp: Node? = null
        var masterSp: Node? = null
        if (ph != null && isSlide) {
            layoutSp = findPh(s.layout?.root?.child("cSld")?.child("spTree"), ph.attr("type"), ph.attr("idx"), false)
            masterSp = findPh(s.master?.root?.child("cSld")?.child("spTree"), layoutSp?.let { phOf(it)?.attr("type") } ?: ph.attr("type"), null, true)
        } else if (ph != null && part === s.layout) {
            masterSp = findPh(s.master?.root?.child("cSld")?.child("spTree"), ph.attr("type"), null, true)
        }
        val chain = listOfNotNull(masterSp, layoutSp, sp) // low -> high priority

        val box = boxOf(spPr?.child("xfrm"), xf)
            ?: chain.asReversed().firstNotNullOfOrNull { boxOf(it.child("spPr")?.child("xfrm"), Xf()) }
            ?: return

        val css = StringBuilder("left:${f(box.x)}px;top:${f(box.y)}px;width:${f(box.w)}px;height:${f(box.h)}px;")
        val transforms = ArrayList<String>()
        if (box.rot != 0.0) transforms.add("rotate(${f(box.rot)}deg)")
        if (box.flipH && spPr?.child("custGeom") == null && ph == null) transforms.add("scaleX(-1)")
        if (box.flipV && ph == null) transforms.add("scaleY(-1)")
        if (transforms.isNotEmpty()) css.append("transform:${transforms.joinToString(" ")};")

        // fill and line: own, else inherited from placeholder chain, else theme style reference
        val style = sp.child("style")
        var fill = chain.asReversed().firstNotNullOfOrNull { fillCss(it.child("spPr"), s.env, part.rels) }
        if (fill == null && style != null && style.child("fillRef")?.attr("idx") != "0") fill = s.env.scheme.resolve(style.child("fillRef")?.children?.firstOrNull())
        if (fill != null && fill != "transparent") css.append("background:$fill;")
        val ln = chain.asReversed().firstNotNullOfOrNull { it.child("spPr")?.child("ln") }
        var lineColor: String? = null
        var lineW = 1.0
        if (ln != null && ln.child("noFill") == null) {
            lineColor = ln.child("solidFill")?.children?.firstOrNull()?.let { s.env.scheme.resolve(it) }
            ln.attr("w")?.toDoubleOrNull()?.let { lineW = px(it).coerceAtLeast(1.0) }
        }
        if (lineColor == null && ln == null && style != null && style.child("lnRef")?.attr("idx").let { it != null && it != "0" }) {
            lineColor = s.env.scheme.resolve(style.child("lnRef")?.children?.firstOrNull())
        }
        if (lineColor != null) css.append("border:${f(lineW)}px solid $lineColor;")
        val prst = spPr?.child("prstGeom")?.attr("prst") ?: chain.asReversed().firstNotNullOfOrNull { it.child("spPr")?.child("prstGeom")?.attr("prst") }
        when (prst) {
            "ellipse", "flowChartConnector" -> css.append("border-radius:50%;")
            "roundRect", "flowChartAlternateProcess" -> css.append("border-radius:${f(minOf(box.w, box.h) * 0.1667)}px;")
            "flowChartTerminator" -> css.append("border-radius:${f(box.h / 2)}px;")
            else -> CLIP[prst]?.let { css.append("clip-path:polygon($it);") }
        }

        // text
        val txBody = sp.child("txBody")
        val html = StringBuilder()
        if (txBody != null) {
            val isTitle = normType(ph?.attr("type")) == "title"
            val styleNode = when {
                ph == null -> s.txStyles?.child("otherStyle")
                isTitle -> s.txStyles?.child("titleStyle")
                normType(ph.attr("type")) == "body" -> s.txStyles?.child("bodyStyle")
                else -> s.txStyles?.child("otherStyle")
            }
            val lstChain = listOfNotNull(s.env.defaultStyle, styleNode) + chain.map { it.child("txBody")?.child("lstStyle") }
            val bodyPr = (listOfNotNull(masterSp, layoutSp, sp).map { it.child("txBody")?.child("bodyPr") })
            fun bp(attr: String) = bodyPr.asReversed().firstNotNullOfOrNull { it?.attr(attr) }
            val fontRefColor = style?.child("fontRef")?.children?.firstOrNull()?.let { s.env.scheme.resolve(it) }
            val fontScale = bodyPr.asReversed().firstNotNullOfOrNull { it?.child("normAutofit")?.attr("fontScale")?.toDoubleOrNull() }?.div(100000) ?: 1.0
            val anchor = bp("anchor")
            css.append(when (anchor) { "ctr" -> "justify-content:center;"; "b" -> "justify-content:flex-end;"; else -> "justify-content:flex-start;" })
            val l = bp("lIns")?.toDoubleOrNull()?.let { px(it) } ?: 9.6
            val t = bp("tIns")?.toDoubleOrNull()?.let { px(it) } ?: 4.8
            val r = bp("rIns")?.toDoubleOrNull()?.let { px(it) } ?: 9.6
            val b = bp("bIns")?.toDoubleOrNull()?.let { px(it) } ?: 4.8
            css.append("padding:${f(t)}px ${f(r)}px ${f(b)}px ${f(l)}px;")
            if (bp("wrap") == "none") css.append("white-space:nowrap;")
            val counters = IntArray(9)
            val generic = (if (s.env.defaultStyle != null) 1 else 0) + (if (styleNode != null) 1 else 0)
            for (p in txBody.all("p")) paragraph(p, lstChain, s.env, fontRefColor, fontScale, counters, html, null, generic)
        }
        if (html.isEmpty() && fill == null && lineColor == null) return
        s.out.append("<div class=\"sh\" style=\"$css\">$html</div>")
    }

    private fun bulletText(pp: PP, counters: IntArray, lvl: Int): String? {
        if (pp.buNone == true) return null
        pp.buChar?.let { c ->
            val wing = pp.buFont?.lowercase()?.contains("wingdings") == true
            return when {
                wing && c == "§" -> "▪"; wing && c == "v" -> "❖"; wing && c == "Ø" -> "➢"; wing && c == "ü" -> "✓"
                pp.buFont?.lowercase() == "symbol" && c == "·" -> "•"
                c.isNotEmpty() && c[0].code in 0xF000..0xF0FF -> "•"
                else -> c
            }
        }
        pp.buAuto?.let { t ->
            val n = (pp.buStart ?: 1) + counters[lvl] - 1
            return when {
                t.startsWith("alphaLc") -> Numbering.format(n, "lowerLetter")
                t.startsWith("alphaUc") -> Numbering.format(n, "upperLetter")
                t.startsWith("romanLc") -> Numbering.format(n, "lowerRoman")
                t.startsWith("romanUc") -> Numbering.format(n, "upperRoman")
                else -> n.toString()
            } + if (t.endsWith("ParenBoth")) ")" else if (t.endsWith("ParenR")) ")" else if (t.endsWith("Period")) "." else ""
        }
        return null
    }

    private fun paragraph(p: Node, lstChain: List<Node?>, env: Env, baseColor: String?, fontScale: Double, counters: IntArray, out: StringBuilder, forceColor: String? = null, genericCount: Int = lstChain.size) {
        val pPr = p.child("pPr")
        val lvl = (pPr?.attr("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8)
        val pp = PP()
        for ((i, l) in lstChain.withIndex()) {
            if (i == genericCount && baseColor != null) pp.r.color = baseColor // theme font colour sits above generic styles
            pp.merge(readPP(l?.child("lvl${lvl + 1}pPr"), env))
        }
        if (genericCount >= lstChain.size && baseColor != null) pp.r.color = baseColor
        pp.merge(readPP(pPr, env))
        val base = RP().also { it.size = 18.0; it.color = baseColor ?: "#000000" }.merge(pp.r)
        if (forceColor != null) base.color = forceColor

        // numbering state
        if (pp.buAuto != null) { counters[lvl]++ ; for (i in lvl + 1 until 9) counters[i] = 0 } else if (pp.buChar == null) counters.fill(0, lvl, 9)
        val bullet = bulletText(pp, counters, lvl)

        val size0 = (base.size ?: 18.0) * fontScale
        val factor = Fonts.lineFactor(base.font ?: env.minor)
        val css = StringBuilder()
        pp.algn?.let { css.append(when (it) { "ctr" -> "text-align:center;"; "r" -> "text-align:right;"; "just" -> "text-align:justify;"; else -> "" }) }
        val marL = pp.marL ?: 0.0
        val indent = pp.indent ?: 0.0
        css.append("margin-left:${f(marL)}px;")
        if (indent != 0.0 && bullet == null) css.append("text-indent:${f(indent)}px;")
        pp.lnPts?.let { css.append("line-height:${f(it)}pt;") } ?: css.append("line-height:${f((pp.lnPct ?: 1.0) * factor)};")
        val bef = pp.befPts ?: pp.befPct?.let { it * size0 * 1.2 } ?: 0.0
        val aft = pp.aftPts ?: pp.aftPct?.let { it * size0 * 1.2 } ?: 0.0
        css.append("margin-top:${f(bef * 4 / 3)}px;margin-bottom:${f(aft * 4 / 3)}px;")
        css.append("font-size:${f(size0)}pt;color:${base.color};")
        (base.font ?: env.minor)?.let { css.append("font-family:${Fonts.css(it)};") }
        if (base.bold == true) css.append("font-weight:bold;")
        if (base.italic == true) css.append("font-style:italic;")

        val sb = StringBuilder()
        if (bullet != null) {
            val w = if (indent < 0) -indent else 18.0
            val bs = StringBuilder("display:inline-block;width:${f(w)}px;margin-left:${f(if (indent < 0) indent else 0.0)}px;text-indent:0;")
            pp.buColor?.let { bs.append("color:$it;") }
            pp.buSize?.let { bs.append("font-size:${f(it * 100)}%;") }
            sb.append("<span style=\"$bs\">${esc(bullet)}</span>")
        }
        var any = false
        for (r in p.children) when (r.name) {
            "r", "fld" -> {
                val t = r.child("t")?.text?.toString().orEmpty()
                if (t.isEmpty()) continue
                any = true
                val rp = base.copy().merge(readRP(r.child("rPr"), env))
                val rs = StringBuilder()
                val sz = (rp.size ?: 18.0) * fontScale
                val sup = rp.baseline != null && rp.baseline != 0.0
                if (sz != size0 || sup) rs.append("font-size:${f(if (sup) sz * 0.65 else sz)}pt;")
                if (rp.color != base.color) rs.append("color:${rp.color};")
                if (rp.font != base.font && rp.font != null) rs.append("font-family:${Fonts.css(rp.font!!)};")
                if (rp.bold != base.bold) rs.append(if (rp.bold == true) "font-weight:bold;" else "font-weight:normal;")
                if (rp.italic != base.italic) rs.append(if (rp.italic == true) "font-style:italic;" else "font-style:normal;")
                val deco = listOfNotNull(if (rp.underline == true) "underline" else null, if (rp.strike == true) "line-through" else null)
                if (deco.isNotEmpty()) rs.append("text-decoration:${deco.joinToString(" ")};")
                if (rp.caps == true) rs.append("text-transform:uppercase;")
                if (sup) rs.append("vertical-align:${if ((rp.baseline ?: 0.0) > 0) "super" else "sub"};line-height:0;")
                rp.spc?.let { if (it != 0.0) rs.append("letter-spacing:${f(it)}pt;") }
                val text = esc(t)
                if (rs.isEmpty()) sb.append(text) else sb.append("<span style=\"$rs\">$text</span>")
            }
            "br" -> { sb.append("<br>"); any = true }
        }
        if (!any && bullet == null) {
            val end = readRP(p.child("endParaRPr"), env)
            val sz = (end.size ?: base.size ?: 18.0) * fontScale
            sb.append("<span style=\"font-size:${f(sz)}pt\">&nbsp;</span>")
        }
        out.append("<div class=\"pp\" style=\"$css\">$sb</div>")
    }

    private fun picture(pic: Node, s: Slide, part: Part, xf: Xf) {
        val box = boxOf(pic.child("spPr")?.child("xfrm"), xf) ?: return
        val blipPart = part.rels[pic.find("blip")?.attr("embed")] ?: return
        val uri = s.env.pkg.imageDataUri(blipPart)
        val css = StringBuilder("left:${f(box.x)}px;top:${f(box.y)}px;width:${f(box.w)}px;height:${f(box.h)}px;overflow:hidden;")
        val tr = ArrayList<String>()
        if (box.rot != 0.0) tr.add("rotate(${f(box.rot)}deg)")
        if (box.flipH) tr.add("scaleX(-1)")
        if (box.flipV) tr.add("scaleY(-1)")
        if (tr.isNotEmpty()) css.append("transform:${tr.joinToString(" ")};")
        val prst = pic.child("spPr")?.child("prstGeom")?.attr("prst")
        if (prst == "ellipse") css.append("border-radius:50%;") else if (prst == "roundRect") css.append("border-radius:${f(minOf(box.w, box.h) * 0.1667)}px;")
        val inner = if (uri == null) {
            "<div style=\"width:100%;height:100%;border:1px dashed #999;box-sizing:border-box\"></div>"
        } else {
            val crop = pic.find("srcRect")
            val l = (crop?.attr("l")?.toDoubleOrNull() ?: 0.0) / 100000; val t = (crop?.attr("t")?.toDoubleOrNull() ?: 0.0) / 100000
            val r = (crop?.attr("r")?.toDoubleOrNull() ?: 0.0) / 100000; val b = (crop?.attr("b")?.toDoubleOrNull() ?: 0.0) / 100000
            if (l + r + t + b > 0 && l + r < 1 && t + b < 1) {
                val iw = box.w / (1 - l - r); val ih = box.h / (1 - t - b)
                "<img src=\"$uri\" style=\"position:absolute;max-width:none;width:${f(iw)}px;height:${f(ih)}px;left:${f(-l * iw)}px;top:${f(-t * ih)}px\">"
            } else "<img src=\"$uri\" style=\"width:100%;height:100%;display:block\">"
        }
        s.out.append("<div class=\"sh\" style=\"$css\">$inner</div>")
    }

    private fun connector(c: Node, s: Slide, part: Part, xf: Xf) {
        val box = boxOf(c.child("spPr")?.child("xfrm"), xf) ?: return
        val ln = c.child("spPr")?.child("ln")
        val color = ln?.child("solidFill")?.children?.firstOrNull()?.let { s.env.scheme.resolve(it) }
            ?: c.child("style")?.child("lnRef")?.children?.firstOrNull()?.let { s.env.scheme.resolve(it) } ?: "#000000"
        val w = ln?.attr("w")?.toDoubleOrNull()?.let { px(it) } ?: 1.0
        val pad = w + 1
        val x1 = if (box.flipH) box.w else 0.0; val x2 = if (box.flipH) 0.0 else box.w
        val y1 = if (box.flipV) box.h else 0.0; val y2 = if (box.flipV) 0.0 else box.h
        val dash = when (ln?.child("prstDash")?.attr("val")) { "dash", "lgDash", "sysDash" -> " stroke-dasharray=\"${f(w * 4)} ${f(w * 3)}\""; "sysDot", "dot" -> " stroke-dasharray=\"${f(w)} ${f(w * 2)}\""; else -> "" }
        s.out.append("<svg style=\"position:absolute;left:${f(box.x - pad)}px;top:${f(box.y - pad)}px;overflow:visible\" width=\"${f(box.w + pad * 2)}\" height=\"${f(box.h + pad * 2)}\">" +
            "<line x1=\"${f(x1 + pad)}\" y1=\"${f(y1 + pad)}\" x2=\"${f(x2 + pad)}\" y2=\"${f(y2 + pad)}\" stroke=\"$color\" stroke-width=\"${f(w)}\"$dash/></svg>")
    }

    private fun frame(gf: Node, s: Slide, part: Part, xf: Xf) {
        val box = boxOf(gf.child("xfrm"), xf) ?: return
        val tbl = gf.find("tbl")
        if (tbl == null) {
            val inner = Diagrams.chartSvg(s.env.pkg, part.rels, s.env.scheme, gf, box.w, box.h)
                ?: Diagrams.smartArt(s.env.pkg, part.rels, s.env.scheme, gf, box.w, box.h)
                ?: "<div style=\"width:100%;height:100%;border:1px dashed #999;box-sizing:border-box\"></div>"
            s.out.append("<div class=\"sh\" style=\"left:${f(box.x)}px;top:${f(box.y)}px;width:${f(box.w)}px;height:${f(box.h)}px\">$inner</div>")
            return
        }
        val tblPr = tbl.child("tblPr")
        val firstRow = tblPr?.attr("firstRow") == "1"; val bandRow = tblPr?.attr("bandRow") == "1"
        val hasStyle = tblPr?.child("tableStyleId") != null
        val accent = s.env.scheme.get("accent1") ?: "4472C4"
        val sb = StringBuilder("<table class=\"t\" style=\"width:${f(box.w)}px\"><colgroup>")
        for (g in tbl.child("tblGrid")?.all("gridCol").orEmpty()) sb.append("<col style=\"width:${f(px(g.attr("w")?.toDoubleOrNull() ?: 0.0))}px\">")
        sb.append("</colgroup>")
        val rows = tbl.all("tr")
        for ((ri, tr) in rows.withIndex()) {
            sb.append("<tr style=\"height:${f(px(tr.attr("h")?.toDoubleOrNull() ?: 0.0))}px\">")
            for (tc in tr.all("tc")) {
                if (tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1") continue
                val tcPr = tc.child("tcPr")
                var bg = fillCss(tcPr, s.env, part.rels)
                var color: String? = null
                var bold = false
                if (bg == null && hasStyle) {
                    when {
                        firstRow && ri == 0 -> { bg = "#" + accent; color = "#ffffff"; bold = true }
                        bandRow -> bg = "#" + ColorScheme.tint(accent, if ((ri - (if (firstRow) 1 else 0)) % 2 == 0) 0.6 else 0.8)
                        else -> bg = "#" + ColorScheme.tint(accent, 0.8)
                    }
                }
                val css = StringBuilder()
                bg?.let { css.append("background:$it;") }
                val m = fun(a: String, d: Double) = px(tcPr?.attr(a)?.toDoubleOrNull() ?: d)
                css.append("padding:${f(m("marT", 45720.0))}px ${f(m("marR", 91440.0))}px ${f(m("marB", 45720.0))}px ${f(m("marL", 91440.0))}px;")
                for ((attr, side) in listOf("lnL" to "left", "lnR" to "right", "lnT" to "top", "lnB" to "bottom")) {
                    val l = tcPr?.child(attr)
                    val lc = l?.child("solidFill")?.children?.firstOrNull()?.let { s.env.scheme.resolve(it) }
                    if (lc != null && l.child("noFill") == null) css.append("border-$side:${f(px(l.attr("w")?.toDoubleOrNull() ?: 12700.0).coerceAtLeast(1.0))}px solid $lc;")
                    else if (hasStyle && l == null) css.append("border-$side:1px solid #ffffff;")
                }
                when (tcPr?.attr("anchor")) { "ctr" -> css.append("vertical-align:middle;"); "b" -> css.append("vertical-align:bottom;") }
                val span = tc.attr("gridSpan")?.toIntOrNull() ?: 1
                val rowSpan = tc.attr("rowSpan")?.toIntOrNull() ?: 1
                sb.append("<td style=\"$css\"")
                if (span > 1) sb.append(" colspan=\"$span\"")
                if (rowSpan > 1) sb.append(" rowspan=\"$rowSpan\"")
                sb.append(">")
                val chain = listOfNotNull(s.env.defaultStyle, s.txStyles?.child("otherStyle"))
                val counters = IntArray(9)
                val inner = StringBuilder()
                tc.child("txBody")?.all("p")?.forEach { paragraph(it, chain, s.env, null, 1.0, counters, inner, color) }
                sb.append(if (bold) inner.toString().replace("class=\"pp\" style=\"", "class=\"pp\" style=\"font-weight:bold;") else inner)
                sb.append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        s.out.append("<div class=\"sh\" style=\"left:${f(box.x)}px;top:${f(box.y)}px;width:${f(box.w)}px;\">$sb</div>")
    }
}
