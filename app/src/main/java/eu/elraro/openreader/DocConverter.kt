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
        var shade: String? = null; var ilfo: Int? = null; var ilvl: Int? = null
        fun copy() = PP().also { it.ilfo = ilfo; it.ilvl = ilvl; it.jc = jc; it.left = left; it.right = right; it.first = first; it.before = before; it.after = after; it.lineDya = lineDya; it.lineMult = lineMult; it.shade = shade }
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
        val ccpFtn = maxOf(b.getInt(0x50), 0); val ccpHdd = maxOf(b.getInt(0x54), 0)
        val ccpAtn = maxOf(b.getInt(0x5C), 0); val ccpEdn = maxOf(b.getInt(0x60), 0); val ccpTxbx = maxOf(b.getInt(0x64), 0)
        val total = ccpText + ccpFtn + ccpHdd + ccpAtn + ccpEdn + ccpTxbx
        val txbxStart = ccpText + ccpFtn + ccpHdd + ccpAtn + ccpEdn
        val text = StringBuilder(); var fcs = IntArray(maxOf(total, 0) + 16)
        readText(wd, tableBytes, fcClx, lcbClx, total, text) { cp, fcv -> if (cp < fcs.size) fcs[cp] = fcv }

        val chpx = readBte(wd, tableBytes, fc(12), lcb(12), false)
        val papx = readBte(wd, tableBytes, fc(13), lcb(13), true)
        val styles = try { readStyles(tableBytes, fc(1), lcb(1)) } catch (e: RuntimeException) { emptyMap() }
        val fonts = try { readFonts(tableBytes, fc(15), lcb(15)) } catch (e: RuntimeException) { emptyList() }
        val data = cfb.stream("Data")
        val defaultFont = if (tableBytes.size > fc(1) + 16 && lcb(1) > 16) fonts.getOrNull(t.getShort(fc(1) + 2 + 14).toInt() and 0xFFFF) else null
        val page = try { readSection(wd, tableBytes, fc(6), lcb(6)) } catch (e: RuntimeException) { doubleArrayOf(612.0, 792.0, 72.0, 90.0, 72.0, 90.0) }

        val (lists, lfos) = try { readLists(tableBytes, fc(73), lcb(73), fc(74), lcb(74)) } catch (e: RuntimeException) { emptyMap<Int, List<Lvl>>() to emptyList() }

        // footnotes: reference positions in the main text and the text ranges of the notes
        val fnRefCps = plcCps(tableBytes, fc(2), lcb(2), 2)
        val fnTxtCps = plcCps(tableBytes, fc(3), lcb(3), 0)
        val refMap = HashMap<Int, Int>().also { m -> fnRefCps.dropLast(1).forEachIndexed { i, cp -> m[cp] = i } }
        val txbxCps = plcCps(tableBytes, fc(56), lcb(56), 22)
        var ctxRef: Ctx? = null
        val floats = try {
            buildFloats(wd, tableBytes, fc(40), lcb(40), fc(50), lcb(50), page) { idx ->
                val a = txbxCps.getOrNull(idx); val e = txbxCps.getOrNull(idx + 1)
                if (a == null || e == null || ctxRef == null) null
                else renderRange(text, fcs, ctxRef!!, txbxStart + a, minOf(txbxStart + e, text.length), -1).joinToString("")
            }
        } catch (e: RuntimeException) { emptyMap() }
        val ctx = Ctx(wd, data, chpx, papx, styles, fonts, defaultFont, lists, lfos, refMap, floats)
        ctxRef = ctx
        val mainPages = renderRange(text, fcs, ctx, 0, minOf(ccpText, text.length), -1)

        // headers and footers of the first section: stories after the six footnote/endnote separators
        val hddCps = plcCps(tableBytes, fc(11), lcb(11), 0)
        fun story(idx: Int): String {
            val a = hddCps.getOrNull(idx) ?: return ""; val e = hddCps.getOrNull(idx + 1) ?: return ""
            val from = ccpText + ccpFtn + a; val to = minOf(ccpText + ccpFtn + e, text.length)
            if (to - from <= 1) return ""
            val html = renderRange(text, fcs, ctx, from, to, -1).joinToString("")
            return if (html.replace(Regex("<[^>]+>"), "").replace("&nbsp;", "").isBlank() && !html.contains("<img")) "" else html
        }
        val header = story(7); val footer = story(9)
        val fnHtml = StringBuilder()
        if (fnRefCps.size > 1 && fnTxtCps.size > 1) {
            fnHtml.append("<hr style=\"width:30%;margin:12pt 0 4pt 0;border:0;border-top:1px solid #000\">")
            for (k in 0 until minOf(fnRefCps.size - 1, fnTxtCps.size - 1)) {
                val from = ccpText + fnTxtCps[k]; val to = minOf(ccpText + fnTxtCps[k + 1], text.length)
                if (to - from > 1) fnHtml.append("<div style=\"font-size:.85em\">").append(renderRange(text, fcs, ctx, from, to, k + 1).joinToString("")).append("</div>")
            }
        }
        val bodyPages = ArrayList(mainPages)
        if (fnHtml.isNotEmpty() && bodyPages.isNotEmpty()) bodyPages[bodyPages.size - 1] = bodyPages.last() + fnHtml
        val body = bodyPages.joinToString("") { c ->
            "<div class=\"page\">" + (if (header.isNotEmpty()) "<div class=\"hf\">$header</div>" else "") + "<div class=\"bd\">$c</div>" +
                (if (footer.isNotEmpty()) "<div class=\"hf\">$footer</div>" else "") + "</div>"
        }
        val pw = page[0]; val ph = page[1]
        val css = """
html{-webkit-text-size-adjust:100%}
body{background:#d9d9d9;padding:8px 0}
.page{position:relative;background:#fff;margin:0 auto 8px auto;box-shadow:0 1px 4px rgba(0,0,0,.4);box-sizing:border-box;
  width:${fmt(pw)}pt;min-height:${fmt(ph)}pt;padding:${fmt(page[2])}pt ${fmt(page[3])}pt ${fmt(page[4])}pt ${fmt(page[5])}pt;overflow-wrap:break-word;
  font-family:${fontCss(defaultFont ?: "Times New Roman")};font-size:10pt}
.bd{min-height:1em;flex:1}
.page{display:flex;flex-direction:column}
.hf{opacity:.9}
p{margin:0;min-height:1em;white-space:pre-wrap}
table{border-collapse:collapse;white-space:normal}
td{vertical-align:top;border:1px solid #999;padding:2pt 4pt;white-space:pre-wrap}
@media print{body{background:#fff;padding:0}.page{box-shadow:none;margin:0 auto;width:${Math.round(pw * 4 / 3)}px;min-height:0;zoom:${fmt(minOf(0.97, 790.0 / (pw * 4 / 3)))};page-break-after:always}.page:last-child{page-break-after:auto}}
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

    private class Lvl(val start: Int, val nfc: Int, val papx: ByteArray?, val text: String)

    private class Ctx(
        val wd: ByteArray, val data: ByteArray?, val chpx: Ranges, val papx: Ranges, val styles: Map<Int, Style>,
        val fonts: List<String>, val defaultFont: String?,
        val lists: Map<Int, List<Lvl>> = emptyMap(), val lfos: List<Int> = emptyList(),
        val footnoteRefs: Map<Int, Int> = emptyMap(), val floats: Map<Int, String> = emptyMap(),
    ) {
        val counters = HashMap<Int, IntArray>()
    }

    /** PlfLst (list definitions with their levels) and PlfLfo (list id of each numbered paragraph's ilfo). */
    private fun readLists(tbl: ByteArray, fcLst: Int, lcbLst: Int, fcLfo: Int, lcbLfo: Int): Pair<Map<Int, List<Lvl>>, List<Int>> {
        val lists = LinkedHashMap<Int, List<Lvl>>()
        val lfos = ArrayList<Int>()
        if (lcbLst < 2 || fcLst < 0 || fcLst + lcbLst > tbl.size) return lists to lfos
        val t = ByteBuffer.wrap(tbl).order(ByteOrder.LITTLE_ENDIAN)
        val n = t.getShort(fcLst).toInt() and 0xFFFF
        var lv = fcLst + 2 + n * 28
        for (i in 0 until n) {
            val o = fcLst + 2 + i * 28
            val lsid = t.getInt(o)
            val simple = tbl[o + 26].toInt() and 1 != 0
            val levels = ArrayList<Lvl>()
            for (k in 0 until (if (simple) 1 else 9)) {
                if (lv + 28 > tbl.size) break
                val start = t.getInt(lv); val nfc = tbl[lv + 4].toInt() and 0xFF
                val cbPapx = tbl[lv + 24].toInt() and 0xFF; val cbChpx = tbl[lv + 25].toInt() and 0xFF
                var q = lv + 28
                val papx = if (q + cbPapx <= tbl.size) tbl.copyOfRange(q, q + cbPapx) else null
                q += cbPapx + cbChpx
                if (q + 2 > tbl.size) break
                val cch = t.getShort(q).toInt() and 0xFFFF
                q += 2
                val text = if (q + cch * 2 <= tbl.size) String(tbl, q, cch * 2, Charsets.UTF_16LE) else ""
                q += cch * 2
                levels.add(Lvl(start, nfc, papx, text))
                lv = q
            }
            lists[lsid] = levels
        }
        if (lcbLfo >= 4 && fcLfo >= 0 && fcLfo + lcbLfo <= tbl.size) {
            val m = t.getInt(fcLfo)
            for (i in 0 until m.coerceIn(0, 5000)) {
                val o = fcLfo + 4 + i * 16
                if (o + 4 > tbl.size) break
                lfos.add(t.getInt(o))
            }
        }
        return lists to lfos
    }

    private fun numberText(lvl: Lvl, levels: List<Lvl>, counters: IntArray): String {
        val sb = StringBuilder()
        for (ch in lvl.text) {
            if (ch.code < 9) {
                val l = levels.getOrNull(ch.code)
                val v = if (counters[ch.code] < 0) (l?.start ?: 1) else counters[ch.code]
                sb.append(formatNumber(v, l?.nfc ?: 0))
            } else sb.append(bulletChar(ch))
        }
        return sb.toString()
    }

    private fun bulletChar(ch: Char): Char = when (ch.code) {
        0xF0B7, 0x00B7 -> '•'; 0xF0A7 -> '▪'; 0xF076 -> '❖'; 0xF0D8 -> '➢'; 0xF0FC -> '✓'; 0xF0A8 -> '○'; 0xF06F -> '○'
        in 0xF000..0xF0FF -> '•'; else -> ch
    }

    private fun formatNumber(v: Int, nfc: Int): String = when (nfc) {
        1 -> roman(v).uppercase(); 2 -> roman(v)
        3 -> letters(v).uppercase(); 4 -> letters(v)
        5 -> v.toString() + (if (v % 100 in 11..13) "th" else when (v % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" })
        22 -> "%02d".format(v)
        else -> v.toString()
    }

    private fun roman(n: Int): String {
        var x = n; val sb = StringBuilder()
        for ((v, s) in listOf(1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc", 50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i"))
            while (x >= v) { sb.append(s); x -= v }
        return sb.toString()
    }

    private fun letters(n: Int): String { var x = n - 1; val sb = StringBuilder(); do { sb.insert(0, 'a' + x % 26); x = x / 26 - 1 } while (x >= 0); return sb.toString() }

    // ------------------------------------------------------------------ file structures

    /** CP array of a Plc whose entries (each [dataSize] bytes) follow the n+1 CPs; empty if absent. */
    private fun plcCps(tbl: ByteArray, fc: Int, lcb: Int, dataSize: Int): List<Int> {
        if (lcb < 8 || fc < 0 || fc + lcb > tbl.size) return emptyList()
        val n = (lcb - 4) / (4 + dataSize)
        val t = ByteBuffer.wrap(tbl).order(ByteOrder.LITTLE_ENDIAN)
        return (0..n).map { t.getInt(fc + it * 4) }
    }

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
                0x460B -> pp.ilfo = b.getShort(o).toInt() and 0xFFFF
                0x260A -> pp.ilvl = v1
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

    /** Renders characters [from, to) of the document text; returns one HTML string per page (forced page breaks). */
    private fun renderRange(raw: StringBuilder, fcs: IntArray, ctx: Ctx, from: Int, to: Int, footnoteNo: Int): List<String> {
        val defaultCp = CP().also { it.size = 20 }
        val pages = ArrayList<StringBuilder>().also { it.add(StringBuilder()) }
        var out = pages[0]
        val cur = StringBuilder()
        var openStyle: String? = null
        val rows = ArrayList<ArrayList<String>>()
        var cells = ArrayList<String>()
        var depth = 0
        val inInstr = BooleanArray(16)
        val instr = Array(16) { StringBuilder() }
        val fieldClose = arrayOfNulls<String>(16)
        var paraCp = defaultCp
        var paraStart = true
        var curPP = PP()
        var marker: String? = null

        fun paraSetup(fc: Int) {
            // paragraph properties are stored by FC of the paragraph; the first character of a paragraph decides
            val i = ctx.papx.find(fc)
            val data = if (i >= 0) ctx.papx.data[i] else null
            val istd = if (data != null && data.size >= 2) (data[0].toInt() and 255) or ((data[1].toInt() and 255) shl 8) else 0
            val pp = PP(); val cp = defaultCp.copy()
            for (st in styleChain(ctx.styles, istd)) { applyPara(pp, st.paraSprms); applyChar(cp, st.charSprms) }
            val direct = PP().also { if (data != null && data.size > 2) applyPara(it, data, 2) }
            val ilfo = direct.ilfo ?: pp.ilfo
            val ilvl = (direct.ilvl ?: pp.ilvl ?: 0).coerceIn(0, 8)
            marker = null
            val lsid = ilfo?.let { ctx.lfos.getOrNull(it - 1) }
            val levels = lsid?.let { ctx.lists[it] }
            val lvl = levels?.getOrNull(ilvl)
            if (lvl != null) {
                applyPara(pp, lvl.papx)
                val counters = ctx.counters.getOrPut(lsid) { IntArray(9) { -1 } }
                if (lvl.nfc != 255) {
                    counters[ilvl] = if (counters[ilvl] < 0) lvl.start else counters[ilvl] + 1
                    for (k in ilvl + 1 until 9) counters[k] = -1
                    marker = if (lvl.nfc == 23) numberText(lvl, levels, counters).ifEmpty { "•" } else numberText(lvl, levels, counters)
                }
            }
            if (data != null && data.size > 2) applyPara(pp, data, 2)
            curPP = pp; paraCp = cp
        }

        fun closeSpan() { if (openStyle != null) { cur.append("</span>"); openStyle = null } }
        fun charProps(fc: Int): CP {
            val cp = paraCp.copy()
            val i = ctx.chpx.find(fc)
            val g = if (i >= 0) ctx.chpx.data[i] else null
            // character style referenced from the run
            val probe = CP().also { applyChar(it, g) }
            probe.istdChar?.let { for (st in styleChain(ctx.styles, it)) applyChar(cp, st.charSprms) }
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

        fun paragraphHtml(inner: CharSequence, pp: PP, cp: CP, mk: String?): String {
            val extra = StringBuilder(paraCss(pp))
            cp.size?.let { if (it != 20) extra.append("font-size:${fmt(it / 2.0)}pt;") }
            val hang = pp.first?.let { if (it < 0) -it / 20.0 else 14.0 } ?: 14.0
            val m = if (mk != null) "<span style=\"display:inline-block;min-width:${fmt(hang)}pt;text-indent:0;padding-right:4pt;box-sizing:content-box\">${esc(mk)}</span>" else ""
            return "<p style=\"$extra\">${if (inner.isEmpty() && m.isEmpty()) "&nbsp;" else m + inner}</p>"
        }

        fun flushTable() {
            if (rows.isEmpty()) return
            out.append("<table>")
            for (r in rows) { out.append("<tr>"); r.forEach { out.append("<td>").append(it).append("</td>") }; out.append("</tr>") }
            out.append("</table>")
            rows.clear()
        }

        var i = from
        val n = minOf(to, raw.length)
        while (i < n) {
            val ch = raw[i]
            val fc = if (i < fcs.size) fcs[i] else 0
            if (paraStart) { paraSetup(fc); paraStart = false }
            when (ch) {
                '\u0013' -> { if (depth < 15) depth++; inInstr[depth] = true; instr[depth].setLength(0); fieldClose[depth] = null }
                '\u0014' -> if (depth > 0) {
                    inInstr[depth] = false
                    val ins = instr[depth].toString().trim()
                    val up = ins.uppercase()
                    when {
                        up.startsWith("PAGE") && !up.startsWith("PAGEREF") -> { closeSpan(); cur.append("<span class=\"fpg\">"); fieldClose[depth] = "</span>" }
                        up.startsWith("NUMPAGES") -> { closeSpan(); cur.append("<span class=\"fnp\">"); fieldClose[depth] = "</span>" }
                        up.startsWith("HYPERLINK") -> {
                            val url = Regex("\"([^\"]+)\"").find(ins)?.groupValues?.get(1)
                            if (url != null && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:"))) {
                                closeSpan(); cur.append("<a href=\"${esc(url)}\">"); fieldClose[depth] = "</a>"
                            }
                        }
                    }
                }
                '\u0015' -> if (depth > 0) { fieldClose[depth]?.let { closeSpan(); cur.append(it) }; fieldClose[depth] = null; depth-- }
                else -> if (depth > 0 && inInstr[depth]) instr[depth].append(ch)
                else {
                    val cp = charProps(fc)
                    when {
                        ch == '\u0001' && cp.special && cp.picLoc != null -> picture(ctx, cp.picLoc!!)?.let { closeSpan(); cur.append(it) }
                        cp.vanish == true -> {}
                        ch == '\u0002' -> if (cp.special || true) {
                            closeSpan()
                            val no = if (footnoteNo > 0) footnoteNo else ctx.footnoteRefs[i]?.plus(1)
                            if (no != null) cur.append("<sup>$no</sup>")
                        }
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
                                out.append(paragraphHtml(cur, curPP, paraCp, marker))
                                cur.setLength(0)
                            } else cur.append("<br>")
                            paraStart = true
                        }
                        ch == '\u000c' -> {
                            closeSpan(); flushTable()
                            if (cur.isNotEmpty()) { out.append(paragraphHtml(cur, curPP, paraCp, marker)); cur.setLength(0) }
                            out = StringBuilder().also { pages.add(it) }
                        }
                        ch == '\u0008' -> ctx.floats[i]?.let { closeSpan(); cur.append(it) }
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
        if (cur.isNotEmpty()) out.append(paragraphHtml(cur, curPP, paraCp, marker))
        return pages.filter { it.isNotEmpty() }.map { it.toString() }.ifEmpty { listOf("") }
    }

    // ------------------------------------------------------------------ floating shapes (pictures, WordArt, boxes)

    private class Shp(val spid: Int, val type: Int, val props: Map<Int, Int>, val complex: Map<Int, ByteArray>)

    private fun readOptFull(b: ByteBuffer, body: Int, count: Int, end: Int): Pair<Map<Int, Int>, Map<Int, ByteArray>> {
        val props = HashMap<Int, Int>(); val complex = HashMap<Int, ByteArray>()
        var cp = body + count * 6
        for (k in 0 until count) {
            val o = body + k * 6
            if (o + 6 > end) break
            val idRaw = b.getShort(o).toInt() and 0xFFFF
            val id = idRaw and 0x3FFF; val v = b.getInt(o + 2)
            props[id] = v
            if (idRaw and 0x8000 != 0 && v > 0 && cp + v <= end) { complex[id] = b.array().copyOfRange(cp, cp + v); cp += v }
        }
        return props to complex
    }

    private fun collectShapes(b: ByteBuffer, start: Int, end: Int, out: MutableMap<Int, Shp>) {
        var p = start
        while (p + 8 <= end) {
            val vi = b.getShort(p).toInt() and 0xFFFF; val type = b.getShort(p + 2).toInt() and 0xFFFF; val len = b.getInt(p + 4)
            if (len < 0 || p + 8 + len > end) return
            val body = p + 8
            if (type == 0xF004) { // SpContainer
                var spid = 0; var st = 0; var opt: Pair<Map<Int, Int>, Map<Int, ByteArray>>? = null
                var q = body
                while (q + 8 <= body + len) {
                    val v2 = b.getShort(q).toInt() and 0xFFFF; val t2 = b.getShort(q + 2).toInt() and 0xFFFF; val l2 = b.getInt(q + 4)
                    if (l2 < 0 || q + 8 + l2 > body + len) break
                    if (t2 == 0xF00A) { spid = b.getInt(q + 8); st = v2 shr 4 }
                    if (t2 == 0xF00B) opt = readOptFull(b, q + 8, v2 shr 4, q + 8 + l2)
                    q += 8 + l2
                }
                if (spid != 0) out[spid] = Shp(spid, st, opt?.first.orEmpty(), opt?.second.orEmpty())
            } else if (vi and 0xF == 0xF) collectShapes(b, body, body + len, out)
            p = body + len
        }
    }

    private fun buildFloats(wd: ByteArray, tbl: ByteArray, fcSpa: Int, lcbSpa: Int, fcDgg: Int, lcbDgg: Int, page: DoubleArray, textbox: (Int) -> String?): Map<Int, String> {
        if (lcbSpa < 30 || fcSpa < 0 || fcSpa + lcbSpa > tbl.size || lcbDgg < 16 || fcDgg < 0 || fcDgg + lcbDgg > tbl.size) return emptyMap()
        val t = ByteBuffer.wrap(tbl).order(ByteOrder.LITTLE_ENDIAN)
        // the drawing group: a DggContainer with the picture store, then the main document's DgContainer
        val dggEnd = fcDgg + lcbDgg
        if ((t.getShort(fcDgg + 2).toInt() and 0xFFFF) != 0xF000) return emptyMap()
        val dggLen = t.getInt(fcDgg + 4)
        val bse = ArrayList<Int>() // offsets of BSE record bodies
        var q = fcDgg + 8
        while (q + 8 <= fcDgg + 8 + dggLen) {
            val ty = t.getShort(q + 2).toInt() and 0xFFFF; val l = t.getInt(q + 4)
            if (l < 0) break
            if (ty == 0xF001) {
                var r = q + 8
                while (r + 8 <= q + 8 + l) { val l2 = t.getInt(r + 4); if ((t.getShort(r + 2).toInt() and 0xFFFF) == 0xF007) bse.add(r); if (l2 < 0) break; r += 8 + l2 }
            }
            q += 8 + l
        }
        val shapes = HashMap<Int, Shp>()
        var p = fcDgg + 8 + dggLen + 1 // skip dgglbl
        if (p + 8 <= dggEnd && (t.getShort(p + 2).toInt() and 0xFFFF) == 0xF002) collectShapes(t, p + 8, minOf(dggEnd, p + 8 + t.getInt(p + 4)), shapes)

        fun blip(index: Int): String? {
            val off = bse.getOrNull(index - 1) ?: return null
            val len = t.getInt(off + 4)
            if (len > 36 + 8) OfficeArt.firstBlip(tbl, off + 8 + 36, off + 8 + len)?.dataUri?.let { return it }
            val fo = t.getInt(off + 8 + 28)
            if (fo < 0 || fo >= wd.size) return null
            return OfficeArt.firstBlip(wd, fo, wd.size)?.dataUri
        }

        val n = (lcbSpa - 4) / 30
        val out = HashMap<Int, String>()
        for (k in 0 until n) {
            val cp = t.getInt(fcSpa + k * 4)
            val o = fcSpa + (n + 1) * 4 + k * 26
            val shp = shapes[t.getInt(o)] ?: continue
            val xl = t.getInt(o + 4) / 20.0; val yt = t.getInt(o + 8) / 20.0
            val w = (t.getInt(o + 12) - t.getInt(o + 4)) / 20.0; val h = (t.getInt(o + 16) - t.getInt(o + 8)) / 20.0
            if (w <= 0 || h <= 0) continue
            val flags = t.getShort(o + 20).toInt() and 0xFFFF
            val bx = (flags shr 1) and 3; val by = (flags shr 3) and 3; val wr = (flags shr 5) and 0xF; val below = flags and 0x4000 != 0
            val inner: String? = shp.props[0x0104]?.let { pib -> blip(pib)?.let { "<img src=\"$it\" style=\"width:${fmt(w)}pt;height:${fmt(h)}pt;max-width:none;display:block\">" } }
                ?: shp.complex[0x00C0]?.let { raw -> // WordArt text
                    val text = String(raw, Charsets.UTF_16LE).trimEnd('\u0000')
                    val col = shp.props[0x0181]?.let { "#%02x%02x%02x".format(it and 255, (it shr 8) and 255, (it shr 16) and 255) } ?: "#000000"
                    if (text.isBlank()) null else "<svg width=\"${fmt(w)}pt\" height=\"${fmt(h)}pt\" viewBox=\"0 0 ${fmt(w)} ${fmt(h)}\"><text x=\"0\" y=\"${fmt(h * 0.8)}\" font-size=\"${fmt(h * 0.9)}\" textLength=\"${fmt(w)}\" lengthAdjust=\"spacingAndGlyphs\" fill=\"$col\" font-family=\"serif\">${esc(text)}</text></svg>"
                }
                ?: shp.props[0x0080]?.takeIf { it != 0 }?.let { tx -> textbox((tx ushr 16) - 1) }?.let { html ->
                    val fillOn = shp.props[0x01BF]?.let { it and 0x100010 == 0x100010 } == true
                    val fill = if (fillOn) shp.props[0x0181]?.let { "background:#%02x%02x%02x;".format(it and 255, (it shr 8) and 255, (it shr 16) and 255) }.orEmpty() else ""
                    val line = if (shp.props[0x01FF]?.let { it and 0x80008 == 0x80008 } == true) "border:1px solid #000;" else ""
                    "<div style=\"width:${fmt(w)}pt;min-height:${fmt(h)}pt;box-sizing:border-box;overflow:hidden;line-height:normal;text-align:left;text-indent:0;$fill$line\">$html</div>"
                }
                ?: if (shp.props[0x01BF]?.let { it and 0x100010 == 0x100010 } == true || shp.props.containsKey(0x0181)) {
                    val fill = shp.props[0x0181]?.let { "#%02x%02x%02x".format(it and 255, (it shr 8) and 255, (it shr 16) and 255) } ?: "#ffffff"
                    "<div style=\"width:${fmt(w)}pt;height:${fmt(h)}pt;background:$fill\"></div>"
                } else null
            if (inner == null) continue
            val left = xl + (if (bx == 2) 0.0 else page[5]); val top = yt + (if (by == 2) 0.0 else if (by == 1) page[2] else 0.0)
            out[cp] = when {
                wr == 3 && by != 0 -> "<span style=\"position:absolute;left:${fmt(left)}pt;top:${fmt(top)}pt;z-index:${if (below) -1 else 1};line-height:0\">$inner</span>"
                wr == 3 -> "<span style=\"position:relative;display:inline-block;width:0;height:0\"><span style=\"position:absolute;left:${fmt(xl)}pt;top:${fmt(yt)}pt;z-index:${if (below) -1 else 1};line-height:0\">$inner</span></span>"
                wr == 1 -> "<span style=\"display:block;line-height:0;margin:4pt auto;width:${fmt(w)}pt\">$inner</span>"
                else -> "<span style=\"float:${if (xl + w / 2 > (page[0] - page[3] - page[5]) / 2 + (if (bx == 2) 0.0 else 0.0) && bx != 2 || (bx == 2 && xl + w / 2 > page[0] / 2)) "right" else "left"};line-height:0;margin:2pt 6pt 2pt 6pt\">$inner</span>"
            }
        }
        return out
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
