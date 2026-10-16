package eu.elraro.openreader

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Word 97-2003 (.doc): text with character and paragraph formatting (styles, fonts, sizes, colours, alignment, spacing),
 * inline pictures, tables and the page setup of the first section. Floating shapes, lists and headers are not recovered.
 */
object DocConverter {
    private class CP {
        var bold: Boolean? = null; var italic: Boolean? = null; var strike: Boolean? = null; var caps: Boolean? = null; var smallCaps: Boolean? = null
        var vanish: Boolean? = null; var underline: Int? = null; var size: Int? = null; var color: String? = null; var font: Int? = null; var script: Int? = null
        var picLoc: Int? = null; var special: Boolean = false; var istdChar: Int? = null

        fun copy() = CP().also {
            it.bold = bold; it.italic = italic; it.strike = strike; it.caps = caps; it.smallCaps = smallCaps; it.vanish = vanish
            it.underline = underline; it.size = size; it.color = color; it.font = font; it.script = script
        }
    }

    private class PP {
        var jc: Int? = null; var left: Int? = null; var right: Int? = null; var first: Int? = null
        var before: Int? = null; var after: Int? = null; var lineDya: Int? = null; var lineMult: Int? = null
        var shade: String? = null
        fun copy() = PP().also { it.jc = jc; it.left = left; it.right = right; it.first = first; it.before = before; it.after = after; it.lineDya = lineDya; it.lineMult = lineMult; it.shade = shade }
    }

    private class Style(val base: Int, val paraSprms: ByteArray?, val charSprms: ByteArray?)

    /** FC ranges with their property bytes (CHPX or PAPX data), sorted by start. */
    private class Ranges(val start: IntArray, val end: IntArray, val data: Array<ByteArray?>) {
        var hint = 0
        fun find(fc: Int): Int {
            if (hint < start.size && fc >= start[hint] && fc < end[hint]) return hint
            var lo = 0; var hi = start.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when { fc < start[mid] -> hi = mid - 1; fc >= end[mid] -> lo = mid + 1; else -> { hint = mid; return mid } }
            }
            return -1
        }
    }

    private val ICO = listOf("#000000", "#000000", "#0000ff", "#00ffff", "#00ff00", "#ff00ff", "#ff0000", "#ffff00", "#ffffff",
        "#000080", "#008080", "#008000", "#800080", "#800000", "#808000", "#808080", "#c0c0c0")

    fun toHtml(cfb: Cfb, title: String): String {
        val wd = cfb.stream("WordDocument") ?: throw IllegalArgumentException("Missing WordDocument stream")
        val b = ByteBuffer.wrap(wd).order(ByteOrder.LITTLE_ENDIAN)
        if (wd.size < 0x1AA || (b.getShort(0).toInt() and 0xFFFF) != 0xA5EC) throw IllegalArgumentException("Not a Word 97+ document")
        val flags = b.getShort(0x0A).toInt()
        if (flags and 0x100 != 0) throw IllegalArgumentException("Encrypted documents are not supported")
        val tableBytes = cfb.stream(if (flags and 0x200 != 0) "1Table" else "0Table")
            ?: throw IllegalArgumentException("Missing table stream")
        val t = ByteBuffer.wrap(tableBytes).order(ByteOrder.LITTLE_ENDIAN)
        val ccpText = b.getInt(0x4C)
        fun fc(i: Int) = b.getInt(0x9A + 8 * i)
        fun lcb(i: Int) = b.getInt(0x9A + 8 * i + 4)
        val fcClx = fc(33); val lcbClx = lcb(33)
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > tableBytes.size) throw IllegalArgumentException("Bad piece table")

        // text and the file position (FC) of every character
        val text = StringBuilder(); var fcs = IntArray(maxOf(ccpText, 0) + 16)
        readText(wd, tableBytes, fcClx, lcbClx, ccpText, text) { cp, fcv -> if (cp < fcs.size) fcs[cp] = fcv }

        val chpx = readBte(wd, tableBytes, fc(12), lcb(12), false)
        val papx = readBte(wd, tableBytes, fc(13), lcb(13), true)
        val styles = try { readStyles(tableBytes, fc(1), lcb(1)) } catch (e: RuntimeException) { emptyMap() }
        val fonts = try { readFonts(tableBytes, fc(15), lcb(15)) } catch (e: RuntimeException) { emptyList() }
        val data = cfb.stream("Data")
        val defaultFont = if (tableBytes.size > fc(1) + 16 && lcb(1) > 16) fonts.getOrNull(t.getShort(fc(1) + 2 + 14).toInt() and 0xFFFF) else null
        val page = try { readSection(wd, tableBytes, fc(6), lcb(6)) } catch (e: RuntimeException) { doubleArrayOf(612.0, 792.0, 72.0, 90.0, 72.0, 90.0) }

        val ctx = Ctx(wd, data, chpx, papx, styles, fonts, defaultFont)
        val body = render(text, fcs, ctx)
        val pw = page[0]; val ph = page[1]
        val css = """
html{-webkit-text-size-adjust:100%}
body{background:#d9d9d9;padding:8px 0}
.page{position:relative;background:#fff;margin:0 auto 8px auto;box-shadow:0 1px 4px rgba(0,0,0,.4);box-sizing:border-box;
  width:${fmt(pw)}pt;min-height:${fmt(ph)}pt;padding:${fmt(page[2])}pt ${fmt(page[3])}pt ${fmt(page[4])}pt ${fmt(page[5])}pt;overflow-wrap:break-word;
  font-family:${fontCss(defaultFont ?: "Times New Roman")};font-size:10pt}
.bd{min-height:1em}
p{margin:0;min-height:1em;white-space:pre-wrap}
table{border-collapse:collapse;white-space:normal}
td{vertical-align:top;border:1px solid #999;padding:2pt 4pt;white-space:pre-wrap}
@media print{body{background:#fff;padding:0}.page{box-shadow:none;margin:0;width:auto;min-height:0;page-break-after:always}.page:last-child{page-break-after:auto}}
"""
        return htmlPage(title, css, body, "width=${Math.round(pw * 4 / 3) + 16}", Paginate.JS)
    }

    private fun fmt(d: Double) = DocxConverter.fmt(d)

    /** Font stack with a generic family guessed from the name, so unavailable fonts still look similar. */
    private fun fontCss(name: String): String {
        val n = name.replace("'", "")
        val generic = when {
            Regex("courier|mono|consolas|lucida console", RegexOption.IGNORE_CASE).containsMatchIn(n) -> "monospace"
            Regex("arial|calibri|verdana|tahoma|helvetica|segoe|trebuchet|sans|gothic|century gothic|impact|candara", RegexOption.IGNORE_CASE).containsMatchIn(n) -> "sans-serif"
            else -> "serif"
        }
        return "'$n',$generic"
    }

    private class Ctx(
        val wd: ByteArray, val data: ByteArray?, val chpx: Ranges, val papx: Ranges, val styles: Map<Int, Style>,
        val fonts: List<String>, val defaultFont: String?,
    )

    // ------------------------------------------------------------------ file structures

    private inline fun readText(wd: ByteArray, tbl: ByteArray, fcClx: Int, lcbClx: Int, ccpText: Int, out: StringBuilder, fcOf: (Int, Int) -> Unit) {
        val t = ByteBuffer.wrap(tbl).order(ByteOrder.LITTLE_ENDIAN)
        var p = fcClx
        val end = fcClx + lcbClx
        while (p < end && (tbl[p].toInt() and 0xFF) == 1) p += 3 + (t.getShort(p + 1).toInt() and 0xFFFF)
        if (p >= end || (tbl[p].toInt() and 0xFF) != 2) throw IllegalArgumentException("Bad piece table")
        val lcb = t.getInt(p + 1)
        val plc = p + 5
        val n = (lcb - 4) / 12
        for (i in 0 until n) {
            val cpStart = t.getInt(plc + i * 4)
            val cpEnd = minOf(t.getInt(plc + (i + 1) * 4), ccpText)
            if (cpEnd <= cpStart) continue
            val fcRaw = t.getInt(plc + (n + 1) * 4 + i * 8 + 2)
            val count = cpEnd - cpStart
            if (fcRaw and 0x40000000 != 0) {
                val off = (fcRaw and 0x3FFFFFFF) / 2
                if (off + count <= wd.size) {
                    out.append(String(wd, off, count, charset("windows-1252")))
                    for (k in 0 until count) fcOf(cpStart + k, off + k)
                }
            } else if (fcRaw >= 0 && fcRaw + count * 2 <= wd.size) {
                out.append(String(wd, fcRaw, count * 2, Charsets.UTF_16LE))
                for (k in 0 until count) fcOf(cpStart + k, fcRaw + 2 * k)
            }
        }
    }

    /** PlcBteChpx / PlcBtePapx: FKP pages holding the formatting of FC ranges. */
    private fun readBte(wd: ByteArray, tbl: ByteArray, fcPlc: Int, lcbPlc: Int, para: Boolean): Ranges {
        val starts = ArrayList<Int>(); val ends = ArrayList<Int>(); val datas = ArrayList<ByteArray?>()
        if (lcbPlc >= 12 && fcPlc >= 0 && fcPlc + lcbPlc <= tbl.size) {
            val t = ByteBuffer.wrap(tbl).order(ByteOrder.LITTLE_ENDIAN)
            val n = (lcbPlc - 4) / 8
            for (i in 0 until n) {
                val pn = t.getInt(fcPlc + (n + 1) * 4 + i * 4) and 0x3FFFFF
                val page = pn.toLong() * 512
                if (page + 512 > wd.size) continue
                val pg = page.toInt()
                val crun = wd[pg + 511].toInt() and 0xFF
                val f = ByteBuffer.wrap(wd, pg, 512).slice().order(ByteOrder.LITTLE_ENDIAN)
                for (r in 0 until crun) {
                    val s = f.getInt(r * 4); val e = f.getInt((r + 1) * 4)
                    val off = if (para) (wd[pg + (crun + 1) * 4 + r * 13].toInt() and 0xFF) * 2 else (wd[pg + (crun + 1) * 4 + r].toInt() and 0xFF) * 2
                    var bytes: ByteArray? = null
                    if (off in 1 until 510) {
                        var cb = wd[pg + off].toInt() and 0xFF
                        var from = off + 1
                        if (para) {
                            if (cb == 0) { cb = (wd[pg + off + 1].toInt() and 0xFF) * 2; from = off + 2 } else cb = cb * 2 - 1
                        }
                        if (from + cb <= 512 && cb >= 0) bytes = wd.copyOfRange(pg + from, pg + from + cb)
                    }
                    starts.add(s); ends.add(e); datas.add(bytes)
                }
            }
        }
        return Ranges(starts.toIntArray(), ends.toIntArray(), datas.toTypedArray())
    }

    private fun readStyles(tbl: ByteArray, fc: Int, lcb: Int): Map<Int, Style> {
        val out = HashMap<Int, Style>()
        if (lcb < 20 || fc < 0 || fc + lcb > tbl.size) return out
        val t = ByteBuffer.wrap(tbl).order(ByteOrder.LITTLE_ENDIAN)
        val cbStshi = t.getShort(fc).toInt() and 0xFFFF
        val cstd = t.getShort(fc + 2).toInt() and 0xFFFF
        val baseSize = t.getShort(fc + 4).toInt() and 0xFFFF
        var p = fc + 2 + cbStshi
        val end = fc + lcb
        for (i in 0 until cstd) {
            if (p + 2 > end) break
            val cb = t.getShort(p).toInt() and 0xFFFF
            val s = p + 2
            p = s + cb
            if (cb < baseSize || cb == 0 || p > end) continue
            val w2 = t.getShort(s + 2).toInt() and 0xFFFF
            val w3 = t.getShort(s + 4).toInt() and 0xFFFF
            val stk = w2 and 0xF; val base = w2 shr 4; val cupx = w3 and 0xF
            val cch = t.getShort(s + baseSize).toInt() and 0xFFFF
            var q = s + baseSize + 2 + (cch + 1) * 2
            if ((q - s) and 1 == 1) q++
            var para: ByteArray? = null; var chr: ByteArray? = null
            fun upx(): ByteArray? {
                if (q + 2 > p) return null
                val l = t.getShort(q).toInt() and 0xFFFF
                val bytes = if (q + 2 + l <= p) tbl.copyOfRange(q + 2, q + 2 + l) else null
                q += 2 + l; if (l and 1 == 1) q++
                return bytes
            }
            if (stk == 1 && cupx >= 1) { para = upx()?.let { if (it.size >= 2) it.copyOfRange(2, it.size) else null }; if (cupx >= 2) chr = upx() }
            else if (stk == 2 && cupx >= 1) chr = upx()
            out[i] = Style(if (base == 0xFFF) -1 else base, para, chr)
        }
        return out
    }

    private fun readFonts(tbl: ByteArray, fc: Int, lcb: Int): List<String> {
        val out = ArrayList<String>()
        if (lcb < 4 || fc < 0 || fc + lcb > tbl.size) return out
        val t = ByteBuffer.wrap(tbl).order(ByteOrder.LITTLE_ENDIAN)
        val n = t.getShort(fc).toInt() and 0xFFFF
        var p = fc + 4
        for (i in 0 until n) {
            if (p >= fc + lcb) break
            val cb = tbl[p].toInt() and 0xFF
            val nameAt = p + 1 + 39
            var e = nameAt
            while (e + 1 < p + 1 + cb && (tbl[e].toInt() != 0 || tbl[e + 1].toInt() != 0)) e += 2
            out.add(if (nameAt < e) String(tbl, nameAt, e - nameAt, Charsets.UTF_16LE) else "")
            p += cb + 1
        }
        return out
    }

    /** Page width, height and margins (pt) of the first section: [w, h, top, right, bottom, left]. */
    private fun readSection(wd: ByteArray, tbl: ByteArray, fc: Int, lcb: Int): DoubleArray {
        val r = doubleArrayOf(612.0, 792.0, 72.0, 90.0, 72.0, 90.0)
        if (lcb < 16 || fc < 0 || fc + lcb > tbl.size) return r
        val t = ByteBuffer.wrap(tbl).order(ByteOrder.LITTLE_ENDIAN)
        val n = (lcb - 4) / 16
        val sed = fc + (n + 1) * 4
        val fcSepx = t.getInt(sed + 2)
        if (fcSepx < 0 || fcSepx + 2 > wd.size) return r
        val b = ByteBuffer.wrap(wd).order(ByteOrder.LITTLE_ENDIAN)
        val cb = b.getShort(fcSepx).toInt() and 0xFFFF
        if (fcSepx + 2 + cb > wd.size) return r
        sprms(wd, fcSepx + 2, fcSepx + 2 + cb) { op, off, size ->
            if (size < 2) return@sprms
            val v = b.getShort(off).toInt().toDouble() / 20
            when (op) { 0xB01F -> r[0] = v; 0xB020 -> r[1] = v; 0x9023 -> r[2] = v; 0xB022 -> r[3] = v; 0x9024 -> r[4] = v; 0xB021 -> r[5] = v }
        }
        if (r[0] < 100 || r[1] < 100) { r[0] = 612.0; r[1] = 792.0 }
        return r
    }

    // ------------------------------------------------------------------ sprms

    private inline fun sprms(g: ByteArray, from: Int, to: Int, f: (op: Int, off: Int, size: Int) -> Unit) {
        var p = from
        while (p + 2 <= to) {
            val op = (g[p].toInt() and 255) or ((g[p + 1].toInt() and 255) shl 8)
            p += 2
            val size = when (op shr 13) {
                0, 1 -> 1; 2, 4, 5 -> 2; 3 -> 4; 7 -> 3
                else -> if (op == 0xD608 || op == 0xC615) (if (p + 2 <= to) ((g[p].toInt() and 255) or ((g[p + 1].toInt() and 255) shl 8)) + 1 else 0)
                        else if (p < to) (g[p].toInt() and 255) + 1 else 0
            }
            if (size <= 0 || p + size > to) break
            f(op, p, size)
            p += size
        }
    }

    private fun toggle(cur: Boolean?, v: Int): Boolean? = when (v and 0xFF) { 0 -> false; 1 -> true; 0x81 -> !(cur ?: false); else -> cur }

    private fun applyChar(cp: CP, g: ByteArray?) {
        g ?: return
        val b = ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN)
        sprms(g, 0, g.size) { op, o, _ ->
            val v1 = g[o].toInt() and 0xFF
            when (op) {
                0x0835 -> cp.bold = toggle(cp.bold, v1)
                0x0836 -> cp.italic = toggle(cp.italic, v1)
                0x0837 -> cp.strike = toggle(cp.strike, v1)
                0x0838 -> cp.vanish = toggle(cp.vanish, v1)
                0x083B -> cp.smallCaps = toggle(cp.smallCaps, v1)
                0x083C -> cp.caps = toggle(cp.caps, v1)
                0x2A3E -> cp.underline = v1
                0x2A42 -> cp.color = ICO.getOrNull(v1)
                0x6870 -> if ((g[o + 3].toInt() and 0xFF) != 0xFF) cp.color = "#%02x%02x%02x".format(g[o].toInt() and 255, g[o + 1].toInt() and 255, g[o + 2].toInt() and 255)
                0x4A43 -> cp.size = b.getShort(o).toInt() and 0xFFFF
                0x4A4F -> cp.font = b.getShort(o).toInt() and 0xFFFF
                0x2A48 -> cp.script = v1
                0x4A30 -> cp.istdChar = b.getShort(o).toInt() and 0xFFFF
                0x6A03 -> cp.picLoc = b.getInt(o)
                0x0855 -> cp.special = v1 == 1
            }
        }
    }

    private fun applyPara(pp: PP, g: ByteArray?, from: Int = 0) {
        g ?: return
        val b = ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN)
        sprms(g, from, g.size) { op, o, _ ->
            val v1 = g[o].toInt() and 0xFF
            when (op) {
                0x2461, 0x2403 -> pp.jc = v1
                0x845E, 0x840F -> pp.left = b.getShort(o).toInt()
                0x845D, 0x840E -> pp.right = b.getShort(o).toInt()
                0x8460, 0x8411 -> pp.first = b.getShort(o).toInt()
                0xA413, 0xA414 -> if (op == 0xA413) pp.before = b.getShort(o).toInt() and 0xFFFF else pp.after = b.getShort(o).toInt() and 0xFFFF
                0x6412 -> { pp.lineDya = b.getShort(o).toInt(); pp.lineMult = b.getShort(o + 2).toInt() and 0xFFFF }
            }
        }
    }

    private fun styleChain(styles: Map<Int, Style>, istd: Int): List<Style> {
        val out = ArrayList<Style>()
        var i = istd; var guard = 0
        while (guard++ < 20) { val s = styles[i] ?: break; out.add(s); i = s.base; if (i < 0) break }
        return out.reversed()
    }

    // ------------------------------------------------------------------ rendering

    private fun css(cp: CP, ctx: Ctx, base: CP): String {
        val sb = StringBuilder()
        cp.font?.let { f -> ctx.fonts.getOrNull(f)?.takeIf { it.isNotEmpty() && it != base.font?.let { bf -> ctx.fonts.getOrNull(bf) } }?.let { sb.append("font-family:${fontCss(it)};") } }
        cp.size?.let { if (it != base.size) sb.append("font-size:${fmt(it / 2.0)}pt;") }
        if (cp.bold == true) sb.append("font-weight:bold;")
        if (cp.italic == true) sb.append("font-style:italic;")
        val deco = ArrayList<String>()
        if ((cp.underline ?: 0) != 0) deco.add("underline")
        if (cp.strike == true) deco.add("line-through")
        if (deco.isNotEmpty()) sb.append("text-decoration:${deco.joinToString(" ")};")
        cp.color?.let { if (it != "#000000") sb.append("color:$it;") }
        if (cp.caps == true) sb.append("text-transform:uppercase;")
        if (cp.smallCaps == true) sb.append("font-variant:small-caps;")
        when (cp.script) { 1 -> sb.append("vertical-align:super;font-size:smaller;"); 2 -> sb.append("vertical-align:sub;font-size:smaller;") }
        return sb.toString()
    }

    private fun render(raw: StringBuilder, fcs: IntArray, ctx: Ctx): String {
        val defaultCp = CP().also { it.size = 20 }
        val normalChain = styleChain(ctx.styles, 0)
        val pages = ArrayList<StringBuilder>().also { it.add(StringBuilder()) }
        var out = pages[0]
        val cur = StringBuilder()
        var openStyle: String? = null
        val rows = ArrayList<ArrayList<String>>()
        var cells = ArrayList<String>()
        var depth = 0
        val inInstr = BooleanArray(16)
        var paraCp = defaultCp
        var paraStart = true
        var curPP = PP(); var curIstd = 0
        var paraCpResolved = false

        fun paraSetup(fc: Int) {
            // paragraph properties are stored by FC of the paragraph; the first character of a paragraph decides
            val i = ctx.papx.find(fc)
            val data = if (i >= 0) ctx.papx.data[i] else null
            val istd = if (data != null && data.size >= 2) (data[0].toInt() and 255) or ((data[1].toInt() and 255) shl 8) else 0
            val pp = PP(); val cp = defaultCp.copy()
            for (s in styleChain(ctx.styles, istd)) { applyPara(pp, s.paraSprms); applyChar(cp, s.charSprms) }
            if (data != null && data.size > 2) applyPara(pp, data, 2)
            curPP = pp; curIstd = istd; paraCp = cp
            paraCpResolved = true
        }

        fun closeSpan() { if (openStyle != null) { cur.append("</span>"); openStyle = null } }
        fun charProps(fc: Int): CP {
            val cp = paraCp.copy()
            val i = ctx.chpx.find(fc)
            val g = if (i >= 0) ctx.chpx.data[i] else null
            // character style referenced from the run
            val probe = CP().also { applyChar(it, g) }
            probe.istdChar?.let { for (s in styleChain(ctx.styles, it)) applyChar(cp, s.charSprms) }
            applyChar(cp, g)
            cp.picLoc = probe.picLoc; cp.special = probe.special
            return cp
        }

        fun paraCss(pp: PP): String {
            val sb = StringBuilder()
            when (pp.jc) { 1 -> sb.append("text-align:center;"); 2 -> sb.append("text-align:right;"); 3 -> sb.append("text-align:justify;") }
            pp.left?.let { if (it != 0) sb.append("margin-left:${fmt(it / 20.0)}pt;") }
            pp.right?.let { if (it != 0) sb.append("margin-right:${fmt(it / 20.0)}pt;") }
            pp.first?.let { if (it != 0) sb.append("text-indent:${fmt(it / 20.0)}pt;") }
            pp.before?.let { if (it != 0) sb.append("margin-top:${fmt(it / 20.0)}pt;") }
            pp.after?.let { if (it != 0) sb.append("margin-bottom:${fmt(it / 20.0)}pt;") }
            val d = pp.lineDya
            if (d != null && d != 0) {
                if (pp.lineMult == 1) sb.append("line-height:${fmt(d / 240.0)};") else sb.append("line-height:${fmt(Math.abs(d) / 20.0)}pt;")
            }
            return sb.toString()
        }

        fun paragraphHtml(inner: CharSequence, pp: PP, cp: CP): String {
            val extra = StringBuilder(paraCss(pp))
            cp.size?.let { if (it != 20) extra.append("font-size:${fmt(it / 2.0)}pt;") }
            return "<p style=\"$extra\">${if (inner.isEmpty()) "&nbsp;" else inner}</p>"
        }

        fun flushTable() {
            if (rows.isEmpty()) return
            out.append("<table>")
            for (r in rows) { out.append("<tr>"); r.forEach { out.append("<td>").append(it).append("</td>") }; out.append("</tr>") }
            out.append("</table>")
            rows.clear()
        }

        var i = 0
        val n = raw.length
        var baseForSpan = defaultCp
        while (i < n) {
            val ch = raw[i]
            val fc = if (i < fcs.size) fcs[i] else 0
            if (paraStart) { paraSetup(fc); paraStart = false }
            when (ch) {
                '\u0013' -> { if (depth < 15) depth++; inInstr[depth] = true }
                '\u0014' -> if (depth > 0) inInstr[depth] = false
                '\u0015' -> if (depth > 0) depth--
                else -> if (depth == 0 || !inInstr[depth]) {
                    val cp = charProps(fc)
                    when {
                        ch == '\u0001' && cp.special && cp.picLoc != null -> picture(ctx, cp.picLoc!!)?.let { closeSpan(); cur.append(it) }
                        cp.vanish == true -> {}
                        ch == '\u0007' -> {
                            closeSpan()
                            if (cur.isEmpty() && cells.isNotEmpty()) { rows.add(cells); cells = ArrayList() }
                            else { cells.add(cur.toString().trim()); cur.setLength(0) }
                            paraStart = true
                        }
                        ch == '\r' -> {
                            closeSpan()
                            if (cells.isEmpty()) {
                                flushTable()
                                out.append(paragraphHtml(cur, curPP, paraCp))
                                cur.setLength(0)
                            } else cur.append("<br>")
                            paraStart = true
                        }
                        ch == '\u000c' -> {
                            closeSpan(); flushTable()
                            if (cur.isNotEmpty()) { out.append(paragraphHtml(cur, curPP, paraCp)); cur.setLength(0) }
                            out = StringBuilder().also { pages.add(it) }
                        }
                        ch == '\u0008' || ch == '\u0002' -> {}
                        else -> {
                            val style = css(cp, ctx, paraCp)
                            if (style != (openStyle ?: "")) {
                                closeSpan()
                                if (style.isNotEmpty()) { cur.append("<span style=\"$style\">"); openStyle = style }
                            }
                            when (ch) {
                                '\u000b' -> cur.append("<br>")
                                '\t' -> cur.append("&emsp;")
                                '&' -> cur.append("&amp;")
                                '<' -> cur.append("&lt;")
                                '>' -> cur.append("&gt;")
                                else -> if (ch >= ' ') cur.append(ch)
                            }
                        }
                    }
                }
            }
            i++
        }
        closeSpan(); flushTable()
        if (cur.isNotEmpty()) out.append(paragraphHtml(cur, curPP, paraCp))
        return pages.filter { it.isNotEmpty() }.ifEmpty { listOf(StringBuilder()) }
            .joinToString("") { "<div class=\"page\"><div class=\"bd\">$it</div></div>" }
    }

    /** Inline picture stored in the Data stream at [loc] (PICFAndOfficeArtData). */
    private fun picture(ctx: Ctx, loc: Int): String? {
        val d = ctx.data ?: return null
        if (loc < 0 || loc + 68 > d.size) return null
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val lcb = b.getInt(loc); val cbHeader = b.getShort(loc + 4).toInt() and 0xFFFF
        if (lcb <= cbHeader || loc.toLong() + lcb > d.size) return null
        val mm = b.getShort(loc + 6).toInt()
        var start = loc + cbHeader
        if (mm == 0x66 && start < d.size) start += 1 + (d[start].toInt() and 0xFF)
        val blip = OfficeArt.firstBlip(d, start, loc + lcb) ?: return null
        val w = (b.getShort(loc + 28).toInt() * (b.getShort(loc + 32).toInt() and 0xFFFF) / 1000.0) / 20
        val h = (b.getShort(loc + 30).toInt() * (b.getShort(loc + 34).toInt() and 0xFFFF) / 1000.0) / 20
        val dim = if (w > 1 && h > 1) "width:${fmt(w)}pt;height:${fmt(h)}pt;" else ""
        return "<img src=\"${blip.dataUri}\" style=\"max-width:none;$dim\">"
    }
}
