package eu.elraro.openreader

/** Theme fonts from word/theme/theme1.xml. */
class Theme(val major: String?, val minor: String?) {
    companion object {
        fun load(pkg: OoxmlPackage): Theme {
            val root = pkg.xml("word/theme/theme1.xml") ?: return Theme(null, null)
            val fs = root.find("fontScheme")
            return Theme(
                fs?.child("majorFont")?.child("latin")?.attr("typeface"),
                fs?.child("minorFont")?.child("latin")?.attr("typeface"),
            )
        }
    }
}

private fun toggle(rPr: Node, name: String): Boolean? {
    val n = rPr.child(name) ?: return null
    return when (n.attr("val")?.lowercase()) {
        "false", "0", "off" -> false
        else -> true
    }
}

/** Run-level properties; null means "not specified" (inherit). Sizes in points. */
class RunProps {
    var bold: Boolean? = null
    var italic: Boolean? = null
    var strike: Boolean? = null
    var caps: Boolean? = null
    var smallCaps: Boolean? = null
    var vanish: Boolean? = null
    var underline: String? = null
    var color: String? = null
    var size: Double? = null
    var font: String? = null
    var highlight: String? = null
    var shade: String? = null
    var vertAlign: String? = null
    var spacing: Double? = null

    fun copy() = RunProps().also { it.merge(this) }

    fun merge(o: RunProps?): RunProps {
        if (o == null) return this
        o.bold?.let { bold = it }; o.italic?.let { italic = it }; o.strike?.let { strike = it }
        o.caps?.let { caps = it }; o.smallCaps?.let { smallCaps = it }; o.vanish?.let { vanish = it }
        o.underline?.let { underline = it }; o.color?.let { color = it }; o.size?.let { size = it }
        o.font?.let { font = it }; o.highlight?.let { highlight = it }; o.shade?.let { shade = it }
        o.vertAlign?.let { vertAlign = it }; o.spacing?.let { spacing = it }
        return this
    }

    companion object {
        private val HIGHLIGHT = mapOf(
            "yellow" to "#ffff00", "green" to "#00ff00", "cyan" to "#00ffff", "magenta" to "#ff00ff", "blue" to "#0000ff",
            "red" to "#ff0000", "darkBlue" to "#000080", "darkCyan" to "#008080", "darkGreen" to "#008000",
            "darkMagenta" to "#800080", "darkRed" to "#800000", "darkYellow" to "#808000", "darkGray" to "#808080",
            "lightGray" to "#c0c0c0", "black" to "#000000", "white" to "#ffffff",
        )

        fun of(rPr: Node?, theme: Theme): RunProps {
            val p = RunProps()
            if (rPr == null) return p
            p.bold = toggle(rPr, "b"); p.italic = toggle(rPr, "i"); p.strike = toggle(rPr, "strike") ?: toggle(rPr, "dstrike")
            p.caps = toggle(rPr, "caps"); p.smallCaps = toggle(rPr, "smallCaps"); p.vanish = toggle(rPr, "vanish")
            rPr.child("u")?.attr("val")?.let { p.underline = it }
            rPr.child("color")?.attr("val")?.let { p.color = safeColor(it) ?: "" }
            rPr.child("sz")?.attr("val")?.toDoubleOrNull()?.let { p.size = it / 2.0 }
            rPr.child("rFonts")?.let { f ->
                p.font = when (f.attr("asciiTheme") ?: f.attr("hAnsiTheme")) {
                    null -> f.attr("ascii") ?: f.attr("hAnsi")
                    else -> {
                        val t = f.attr("asciiTheme") ?: f.attr("hAnsiTheme")
                        if (t!!.startsWith("major")) theme.major else theme.minor
                    }
                }
            }
            rPr.child("highlight")?.attr("val")?.let { p.highlight = HIGHLIGHT[it] }
            rPr.child("shd")?.attr("fill")?.let { p.shade = safeColor(it) }
            rPr.child("vertAlign")?.attr("val")?.let { p.vertAlign = it }
            rPr.child("spacing")?.attr("val")?.toDoubleOrNull()?.let { p.spacing = it / 20.0 }
            return p
        }
    }
}

/** Paragraph-level properties. Lengths in points. */
class ParaProps {
    var jc: String? = null
    var left: Double? = null
    var right: Double? = null
    var first: Double? = null // positive = first-line, negative = hanging
    var before: Double? = null
    var after: Double? = null
    var line: Double? = null
    var lineRule: String? = null
    var fill: String? = null
    var borders: Map<String, String>? = null
    var numId: String? = null
    var ilvl: Int? = null
    var pageBreakBefore: Boolean? = null
    var contextual: Boolean? = null

    fun copy() = ParaProps().also { it.merge(this) }

    fun merge(o: ParaProps?): ParaProps {
        if (o == null) return this
        o.jc?.let { jc = it }; o.left?.let { left = it }; o.right?.let { right = it }; o.first?.let { first = it }
        o.before?.let { before = it }; o.after?.let { after = it }; o.line?.let { line = it }; o.lineRule?.let { lineRule = it }
        o.fill?.let { fill = it }; o.numId?.let { numId = it }; o.ilvl?.let { ilvl = it }
        o.pageBreakBefore?.let { pageBreakBefore = it }; o.contextual?.let { contextual = it }
        o.borders?.let { borders = (borders ?: emptyMap()) + it }
        return this
    }

    companion object {
        fun of(pPr: Node?): ParaProps {
            val p = ParaProps()
            if (pPr == null) return p
            pPr.child("jc")?.attr("val")?.let { p.jc = it }
            pPr.child("ind")?.let { i ->
                (i.attr("left") ?: i.attr("start"))?.toDoubleOrNull()?.let { p.left = it / 20 }
                (i.attr("right") ?: i.attr("end"))?.toDoubleOrNull()?.let { p.right = it / 20 }
                i.attr("firstLine")?.toDoubleOrNull()?.let { p.first = it / 20 }
                i.attr("hanging")?.toDoubleOrNull()?.let { p.first = -it / 20 }
            }
            pPr.child("spacing")?.let { s ->
                s.attr("before")?.toDoubleOrNull()?.let { p.before = it / 20 }
                s.attr("after")?.toDoubleOrNull()?.let { p.after = it / 20 }
                if (s.attr("beforeAutospacing") == "1") p.before = 14.0
                if (s.attr("afterAutospacing") == "1") p.after = 14.0
                s.attr("line")?.toDoubleOrNull()?.let { p.line = it; p.lineRule = s.attr("lineRule") ?: "auto" }
            }
            pPr.child("shd")?.attr("fill")?.let { p.fill = safeColor(it) }
            pPr.child("pBdr")?.let { b ->
                val m = HashMap<String, String>()
                for (side in listOf("top", "left", "bottom", "right")) borderCss(b.child(side))?.let { m[side] = it }
                if (m.isNotEmpty()) p.borders = m
            }
            pPr.child("numPr")?.let { n ->
                n.child("numId")?.attr("val")?.let { p.numId = it }
                n.child("ilvl")?.attr("val")?.toIntOrNull()?.let { p.ilvl = it }
            }
            pPr.child("pageBreakBefore")?.let { p.pageBreakBefore = it.attr("val") != "0" && it.attr("val") != "false" }
            pPr.child("contextualSpacing")?.let { p.contextual = it.attr("val") != "0" && it.attr("val") != "false" }
            return p
        }
    }
}

/** CSS border shorthand for a w:top/w:left/... element; "none" when explicitly cleared; null if absent. */
fun borderCss(n: Node?): String? {
    n ?: return null
    val v = n.attr("val") ?: return null
    if (v == "nil" || v == "none") return "none"
    val w = ((n.attr("sz")?.toDoubleOrNull() ?: 4.0) / 8.0).coerceIn(0.5, 12.0)
    val style = when {
        v.startsWith("double") -> "double"
        v.contains("dot", true) -> "dotted"
        v.contains("dash", true) -> "dashed"
        else -> "solid"
    }
    val width = if (style == "double") maxOf(w, 2.25) else w
    val color = safeColor(n.attr("color")) ?: "#000000"
    return "%.2fpt %s %s".format(java.util.Locale.US, width, style, color)
}

class CondFormat(val p: ParaProps, val r: RunProps, val fill: String?, val borders: Map<String, String>)

class TableStyle {
    val p = ParaProps()
    val r = RunProps()
    var fill: String? = null
    val borders = HashMap<String, String>()
    val conds = HashMap<String, CondFormat>()
}

class Style(
    val id: String, val type: String, val basedOn: String?, val isDefault: Boolean,
    val p: ParaProps, val r: RunProps, val node: Node,
)

class Styles(root: Node?, private val theme: Theme) {
    private val byId = HashMap<String, Style>()
    val defP = ParaProps()
    val defR = RunProps()
    var defaultParaId: String? = null
        private set
    private val resolvedP = HashMap<String, Pair<ParaProps, RunProps>>()

    init {
        root?.child("docDefaults")?.let { d ->
            defR.merge(RunProps.of(d.child("rPrDefault")?.child("rPr"), theme))
            defP.merge(ParaProps.of(d.child("pPrDefault")?.child("pPr")))
        }
        for (s in root?.all("style").orEmpty()) {
            val id = s.attr("styleId") ?: continue
            val st = Style(
                id, s.attr("type") ?: "paragraph", s.child("basedOn")?.attr("val"), s.attr("default") == "1",
                ParaProps.of(s.child("pPr")), RunProps.of(s.child("rPr"), theme), s,
            )
            byId[id] = st
            if (st.type == "paragraph" && st.isDefault) defaultParaId = id
        }
    }

    /** Fully resolved (inherited) paragraph + run properties of a paragraph or character style. */
    fun resolve(id: String?): Pair<ParaProps, RunProps> {
        val key = id ?: defaultParaId ?: return ParaProps() to RunProps()
        resolvedP[key]?.let { return it }
        val chain = ArrayList<Style>()
        var cur = byId[key]
        while (cur != null && chain.size < 20 && chain.none { it === cur }) {
            chain.add(cur)
            cur = cur.basedOn?.let { byId[it] }
        }
        val p = ParaProps()
        val r = RunProps()
        for (s in chain.asReversed()) { p.merge(s.p); r.merge(s.r) }
        return (p to r).also { resolvedP[key] = it }
    }

    fun table(id: String?): TableStyle {
        val ts = TableStyle()
        if (id == null) return ts
        val chain = ArrayList<Style>()
        var cur = byId[id]
        while (cur != null && chain.size < 20 && chain.none { it === cur }) {
            chain.add(cur)
            cur = cur.basedOn?.let { byId[it] }
        }
        for (s in chain.asReversed()) {
            ts.p.merge(s.p); ts.r.merge(s.r)
            s.node.child("tblPr")?.child("tblBorders")?.let { b ->
                for (side in SIDES) borderCss(b.child(side))?.let { ts.borders[side] = it }
            }
            s.node.child("tcPr")?.child("shd")?.attr("fill")?.let { ts.fill = safeColor(it) }
            for (c in s.node.all("tblStylePr")) {
                val type = c.attr("type") ?: continue
                val b = HashMap<String, String>()
                c.child("tcPr")?.child("tcBorders")?.let { tb ->
                    for (side in SIDES) borderCss(tb.child(side))?.let { b[side] = it }
                }
                val old = ts.conds[type]
                val p = (old?.p ?: ParaProps()).merge(ParaProps.of(c.child("pPr")))
                val r = (old?.r ?: RunProps()).merge(RunProps.of(c.child("rPr"), theme))
                val fill = safeColor(c.child("tcPr")?.child("shd")?.attr("fill")) ?: old?.fill
                ts.conds[type] = CondFormat(p, r, fill, (old?.borders ?: emptyMap()) + b)
            }
        }
        return ts
    }

    companion object {
        val SIDES = listOf("top", "left", "bottom", "right", "insideH", "insideV")
    }
}

class NumLevel(val fmt: String, val text: String, val start: Int, val p: ParaProps, val r: RunProps, val suff: String)

class Numbering(root: Node?, theme: Theme) {
    private val abstract = HashMap<String, Map<Int, NumLevel>>()
    private val numToAbs = HashMap<String, String>()
    private val overrides = HashMap<String, Map<Int, Int>>()
    private val values = HashMap<String, Array<Int?>>()
    private val seen = HashSet<String>()

    init {
        for (a in root?.all("abstractNum").orEmpty()) {
            val id = a.attr("abstractNumId") ?: continue
            val levels = HashMap<Int, NumLevel>()
            for (l in a.all("lvl")) {
                val i = l.attr("ilvl")?.toIntOrNull() ?: continue
                levels[i] = NumLevel(
                    l.child("numFmt")?.attr("val") ?: "decimal",
                    l.child("lvlText")?.attr("val") ?: "",
                    l.child("start")?.attr("val")?.toIntOrNull() ?: 1,
                    ParaProps.of(l.child("pPr")),
                    RunProps.of(l.child("rPr"), theme).also { it.font = null },
                    l.child("suff")?.attr("val") ?: "tab",
                )
            }
            abstract[id] = levels
        }
        for (n in root?.all("num").orEmpty()) {
            val id = n.attr("numId") ?: continue
            n.child("abstractNumId")?.attr("val")?.let { numToAbs[id] = it }
            val ov = HashMap<Int, Int>()
            for (o in n.all("lvlOverride")) {
                val lvl = o.attr("ilvl")?.toIntOrNull() ?: continue
                o.child("startOverride")?.attr("val")?.toIntOrNull()?.let { ov[lvl] = it }
            }
            if (ov.isNotEmpty()) overrides[id] = ov
        }
    }

    fun level(numId: String, ilvl: Int): NumLevel? = abstract[numToAbs[numId]]?.get(ilvl)

    /** Advances the counters for a list paragraph and returns the marker text. */
    fun next(numId: String, ilvl: Int): String? {
        val absId = numToAbs[numId] ?: return null
        val levels = abstract[absId] ?: return null
        val lvl = levels[ilvl] ?: return null
        val v = values.getOrPut(absId) { arrayOfNulls(9) }
        if (seen.add(numId)) overrides[numId]?.forEach { (l, s) -> if (l in 0..8) v[l] = s - 1 }
        v[ilvl] = (v[ilvl]?.plus(1)) ?: lvl.start
        for (l in ilvl + 1 until 9) v[l] = null
        if (lvl.fmt == "bullet") return bullet(lvl.text)
        if (lvl.fmt == "none") return ""
        return Regex("%([1-9])").replace(lvl.text) { m ->
            val n = m.groupValues[1].toInt() - 1
            val lv = levels[n]
            format(v[n] ?: lv?.start ?: 1, lv?.fmt ?: "decimal")
        }
    }

    companion object {
        fun bullet(t: String): String {
            if (t.isEmpty()) return "•"
            val c = t[0]
            return when {
                c == '' -> "•"
                c == '' -> "▪"
                c == '' -> "➢"
                c == '' -> "✓"
                c == '' -> "❖"
                c == 'o' -> "◦"
                c.code in 0xF000..0xF0FF -> "•"
                else -> t
            }
        }

        fun format(n: Int, fmt: String): String = when (fmt) {
            "decimalZero" -> "%02d".format(n)
            "lowerLetter" -> letters(n).lowercase()
            "upperLetter" -> letters(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            "ordinal" -> n.toString() + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }
            else -> n.toString()
        }

        private fun letters(n: Int): String {
            var x = n
            val sb = StringBuilder()
            while (x > 0) { sb.insert(0, 'A' + (x - 1) % 26); x = (x - 1) / 26 }
            return sb.toString()
        }

        private fun roman(n: Int): String {
            val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var x = n
            val sb = StringBuilder()
            for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
            return sb.toString()
        }
    }
}
