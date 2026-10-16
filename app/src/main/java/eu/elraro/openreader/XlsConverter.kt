package eu.elraro.openreader

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Cell values and formatting (fonts, fills, borders, alignment, widths, merges) from Excel 97-2003 (.xls, BIFF8). */
object XlsConverter {
    private class XlCell(val text: String, val numeric: Boolean, val xf: Int)
    private class Sheet(val name: String) {
        val cells = HashMap<Int, HashMap<Int, XlCell>>()
        val colW = HashMap<Int, Int>()
        val colHidden = HashSet<Int>()
        val rowH = HashMap<Int, Double>()
        val rowHidden = HashSet<Int>()
        val merges = ArrayList<IntArray>()
    }
    private class XlFont(val size: Double, val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val color: Int, val name: String)
    private class XlXf(val font: Int, val h: Int, val wrap: Boolean, val v: Int, val fill: Int, val styles: IntArray, val colors: IntArray, val indent: Int)

    fun toHtml(cfb: Cfb, title: String): String {
        val wb = cfb.stream("Workbook") ?: cfb.stream("Book") ?: throw IllegalArgumentException("Missing Workbook stream")
        val b = ByteBuffer.wrap(wb).order(ByteOrder.LITTLE_ENDIAN)

        val names = ArrayList<String>()
        val sst = ArrayList<String>()
        val customFmt = HashMap<Int, String>()
        val xfFmt = ArrayList<Int>()
        val sheets = ArrayList<Sheet>()
        var cur: Sheet? = null
        var sheetIdx = -1
        var pendingFormula: Triple<Int, Int, Int>? = null
        val fonts = ArrayList<XlFont>()
        val xfs = ArrayList<XlXf>()
        val palette = HashMap<Int, String>()
        var curXf = 0

        fun put(r: Int, c: Int, text: String, numeric: Boolean, xf: Int = curXf) {
            val s = cur ?: return
            if (r >= XlsxConverter.MAX_ROWS || c >= XlsxConverter.MAX_COLS) return
            if (text.isEmpty() && xf == 0) return
            s.cells.getOrPut(r) { HashMap() }[c] = XlCell(text, numeric, xf)
        }
        fun number(r: Int, c: Int, xf: Int, d: Double) {
            val code = NumFmt.code(xfFmt.getOrNull(xf) ?: 0, customFmt)
            put(r, c, NumFmt.format(d, code), !NumFmt.isDate(code), xf)
        }

        var pos = 0
        while (pos + 4 <= wb.size) {
            val id = b.getShort(pos).toInt() and 0xFFFF
            val len = b.getShort(pos + 2).toInt() and 0xFFFF
            val d = pos + 4
            if (d + len > wb.size) break
            when (id) {
                0x0809 -> if (len >= 4 && (b.getShort(d + 2).toInt() and 0xFFFF) == 0x0010) {
                    sheetIdx++
                    cur = Sheet(names.getOrNull(sheetIdx) ?: "Sheet${sheetIdx + 1}").also { sheets.add(it) }
                }
                0x0085 -> if (wb[d + 5].toInt() == 0) names.add(readShortString(wb, d + 6))
                0x00FC -> {
                    // SST plus following CONTINUE records
                    val chunks = ArrayList<IntRange>()
                    chunks.add(d until d + len)
                    var q = d + len
                    while (q + 4 <= wb.size && (b.getShort(q).toInt() and 0xFFFF) == 0x003C) {
                        val l = b.getShort(q + 2).toInt() and 0xFFFF
                        chunks.add(q + 4 until q + 4 + l)
                        q += 4 + l
                    }
                    readSst(wb, chunks, sst)
                    pos = q
                    continue
                }
                0x041E -> if (len >= 4) customFmt[b.getShort(d).toInt() and 0xFFFF] = readUnicode(wb, d + 2, b.getShort(d + 2).toInt() and 0xFFFF, 2)
                0x00E0 -> if (len >= 4) {
                    xfFmt.add(b.getShort(d + 2).toInt() and 0xFFFF)
                    if (len >= 20) {
                        val brd = u16(b, d + 10); val c1 = u16(b, d + 12); val c2 = b.getInt(d + 14); val c3 = u16(b, d + 18)
                        xfs.add(XlXf(
                            u16(b, d), wb[d + 6].toInt() and 7, wb[d + 6].toInt() and 8 != 0, (wb[d + 6].toInt() shr 4) and 7,
                            if ((c2 ushr 26) == 1) c3 and 0x7F else -1,
                            intArrayOf(brd and 15, (brd shr 4) and 15, (brd shr 8) and 15, (brd shr 12) and 15),
                            intArrayOf(c1 and 0x7F, (c1 shr 7) and 0x7F, c2 and 0x7F, (c2 shr 7) and 0x7F), wb[d + 8].toInt() and 15,
                        ))
                    } else xfs.add(XlXf(0, 0, false, 2, -1, IntArray(4), IntArray(4), 0))
                }
                0x0031 -> if (len >= 16) {
                    val flags = u16(b, d + 2)
                    val cch = wb[d + 14].toInt() and 0xFF
                    fonts.add(XlFont(u16(b, d) / 20.0, u16(b, d + 6) >= 700, flags and 2 != 0, wb[d + 10].toInt() != 0, flags and 8 != 0,
                        u16(b, d + 4), decode(wb, d + 16, cch, wb[d + 15].toInt() and 1 != 0)))
                    if (fonts.size == 4) fonts.add(fonts[3]) // BIFF skips font index 4
                }
                0x0092 -> {
                    val n = u16(b, d)
                    for (i in 0 until n) if (d + 2 + i * 4 + 3 <= wb.size) {
                        palette[8 + i] = "%02X%02X%02X".format(wb[d + 2 + i * 4].toInt() and 255, wb[d + 3 + i * 4].toInt() and 255, wb[d + 4 + i * 4].toInt() and 255)
                    }
                }
                0x007D -> cur?.let { s ->
                    val w = Math.round(u16(b, d + 4) / 256.0 * 7).toInt()
                    for (c in u16(b, d)..minOf(u16(b, d + 2), XlsxConverter.MAX_COLS - 1)) {
                        s.colW[c] = w
                        if (u16(b, d + 8) and 1 != 0) s.colHidden.add(c)
                    }
                }
                0x0208 -> cur?.let { s ->
                    val r = u16(b, d); val h = u16(b, d + 6)
                    if (h and 0x8000 == 0 && h > 0) s.rowH[r] = h / 20.0
                    if (u16(b, d + 12) and 0x20 != 0) s.rowHidden.add(r)
                }
                0x00E5 -> cur?.let { s ->
                    for (i in 0 until u16(b, d)) {
                        val o = d + 2 + i * 8
                        if (o + 8 <= wb.size && s.merges.size < 5000) s.merges.add(intArrayOf(u16(b, o), u16(b, o + 2), u16(b, o + 4), u16(b, o + 6)))
                    }
                }
                0x0201 -> put(u16(b, d), u16(b, d + 2), "", false, u16(b, d + 4))
                0x00BE -> {
                    val r = u16(b, d); val first = u16(b, d + 2); val count = (len - 6) / 2
                    for (i in 0 until count) put(r, first + i, "", false, u16(b, d + 4 + i * 2))
                }
                0x00FD -> {
                    val idx = b.getInt(d + 6)
                    put(u16(b, d), u16(b, d + 2), sst.getOrNull(idx).orEmpty(), false, u16(b, d + 4))
                }
                0x0203 -> number(u16(b, d), u16(b, d + 2), u16(b, d + 4), b.getDouble(d + 6))
                0x027E -> number(u16(b, d), u16(b, d + 2), u16(b, d + 4), rk(b.getInt(d + 6)))
                0x00BD -> {
                    val r = u16(b, d)
                    val first = u16(b, d + 2)
                    val count = (len - 6) / 6
                    for (i in 0 until count) number(r, first + i, u16(b, d + 4 + i * 6), rk(b.getInt(d + 6 + i * 6)))
                }
                0x0204 -> {
                    val n = u16(b, d + 6)
                    put(u16(b, d), u16(b, d + 2), readUnicode(wb, d + 8, n, 0), false, u16(b, d + 4))
                }
                0x0205 -> {
                    val v = wb[d + 6].toInt()
                    val text = if (wb[d + 7].toInt() == 0) (if (v != 0) "TRUE" else "FALSE") else "#ERR"
                    put(u16(b, d), u16(b, d + 2), text, false, u16(b, d + 4))
                }
                0x0006 -> {
                    val r = u16(b, d); val c = u16(b, d + 2); val xf = u16(b, d + 4)
                    if (u16(b, d + 12) == 0xFFFF && (wb[d + 6].toInt() and 0xFF) == 0) pendingFormula = Triple(r, c, xf)
                    else number(r, c, xf, b.getDouble(d + 6))
                }
                0x0207 -> pendingFormula?.let { (r, c, _) ->
                    put(r, c, readUnicode(wb, d + 2, u16(b, d), 0), false, pendingFormula?.third ?: 0)
                    pendingFormula = null
                }
            }
            pos = d + len
        }

        val indexed = XStyles(null, ColorScheme.DEFAULT).INDEXED
        fun color(i: Int): String? = when {
            i == 64 || i == 0x7FFF -> null
            palette[i] != null -> "#" + palette[i]
            else -> indexed.getOrNull(i)?.let { "#$it" }
        }
        val css = StringBuilder()
        val bs = listOf("", "1px solid", "2px solid", "1px dashed", "1px dotted", "3px solid", "3px double", "1px solid", "1px dashed", "1px dashed", "1px dashed", "2px dashed", "1px dashed", "2px dashed")
        for ((i, x) in xfs.withIndex()) {
            val sb = StringBuilder()
            fonts.getOrNull(x.font)?.let { f ->
                sb.append("font-size:${f.size}pt;")
                if (f.name.isNotEmpty()) sb.append("font-family:'${f.name.replace("'", "")}',sans-serif;")
                if (f.bold) sb.append("font-weight:bold;")
                if (f.italic) sb.append("font-style:italic;")
                if (f.underline || f.strike) sb.append("text-decoration:${listOfNotNull(if (f.underline) "underline" else null, if (f.strike) "line-through" else null).joinToString(" ")};")
                if (f.color != 32767 && f.color != 64) color(f.color)?.let { sb.append("color:$it;") }
            }
            if (x.fill >= 0) color(x.fill)?.let { sb.append("background:$it;") }
            when (x.h) { 1 -> sb.append("text-align:left;"); 2, 6 -> sb.append("text-align:center;"); 3 -> sb.append("text-align:right;") }
            when (x.v) { 0 -> sb.append("vertical-align:top;"); 1 -> sb.append("vertical-align:middle;") }
            if (x.wrap) sb.append("white-space:pre-wrap;word-wrap:break-word;")
            if (x.indent > 0) sb.append("padding-left:${x.indent * 9}px;")
            for ((k, side) in listOf("left", "right", "top", "bottom").withIndex()) {
                val st = x.styles[k]
                if (st in 1 until bs.size) sb.append("border-$side:${bs[st]} ${color(x.colors[k]) ?: "#000"};")
            }
            if (sb.isNotEmpty()) css.append(".x$i{$sb}\n")
        }

        val tabs = StringBuilder()
        val body = StringBuilder()
        sheets.forEachIndexed { i, s ->
            tabs.append("<a href=\"#s$i\">${esc(s.name)}</a>")
            body.append("<h2 id=\"s$i\">${esc(s.name)}</h2>")
            if (s.cells.isEmpty()) {
                body.append("<p><i>Empty sheet.</i></p>")
            } else renderSheet(s, xfFmt.size > 0, { xfs.getOrNull(it)?.wrap == true }, body)
        }
        if (sheets.isEmpty()) body.append("<p>No worksheets found.</p>")
        return XlsxConverter.page(title, tabs, body, css.toString())
    }

    private fun renderSheet(s: Sheet, styled: Boolean, wraps: (Int) -> Boolean, out: StringBuilder) {
        var maxCol = s.cells.values.maxOf { it.keys.max() }
        var maxRow = s.cells.keys.max()
        val anchor = HashMap<Long, Pair<Int, Int>>(); val covered = HashSet<Long>()
        fun k(r: Int, c: Int) = r.toLong() * 1000 + c
        for (m in s.merges) {
            if (m[0] >= XlsxConverter.MAX_ROWS || m[2] >= XlsxConverter.MAX_COLS) continue
            val r2 = minOf(m[1], XlsxConverter.MAX_ROWS - 1); val c2 = minOf(m[3], XlsxConverter.MAX_COLS - 1)
            anchor[k(m[0], m[2])] = (r2 - m[0] + 1) to (c2 - m[2] + 1)
            for (r in m[0]..r2) for (c in m[2]..c2) if (r != m[0] || c != m[2]) covered.add(k(r, c))
            maxRow = maxOf(maxRow, r2); maxCol = maxOf(maxCol, c2)
        }
        fun px(c: Int) = s.colW[c] ?: 64
        var total = 40
        for (c in 0..maxCol) if (c !in s.colHidden) total += px(c)
        out.append("<div class=\"wrap\"><table class=\"sh grid\" style=\"width:${total}px\"><colgroup><col style=\"width:40px\">")
        for (c in 0..maxCol) if (c !in s.colHidden) out.append("<col style=\"width:${px(c)}px\">")
        out.append("</colgroup><tr class=\"hd\"><th></th>")
        for (c in 0..maxCol) if (c !in s.colHidden) out.append("<th>${XlsxConverter.colName(c)}</th>")
        out.append("</tr>")
        for (r in 0..maxRow) {
            if (r in s.rowHidden) continue
            out.append("<tr style=\"height:${s.rowH[r] ?: 15.0}pt\"><th>${r + 1}</th>")
            val row = s.cells[r]
            var c = -1
            while (++c <= maxCol) {
                if (c in s.colHidden || k(r, c) in covered) continue
                val cell = row?.get(c)
                val m = anchor[k(r, c)]
                var spill = 1
                if (cell != null && m == null && !cell.numeric && cell.text.isNotEmpty() && !wraps(cell.xf)) {
                    val need = cell.text.length * 7.0
                    var have = px(c).toDouble(); var n = c + 1
                    while (have < need && n <= maxCol && k(r, n) !in covered && n !in s.colHidden) {
                        val nb = row.get(n)
                        if (nb != null && (nb.text.isNotEmpty() || nb.xf != 0)) break
                        have += px(n); spill++; n++
                    }
                }
                out.append("<td")
                val cls = (if (cell?.numeric == true) "n " else "") + (if (cell != null && styled) "x${cell.xf}" else "")
                if (cls.isNotBlank()) out.append(" class=\"${cls.trim()}\"")
                if (m != null && m.second > 1) out.append(" colspan=\"${m.second}\"") else if (spill > 1) { out.append(" colspan=\"$spill\""); c += spill - 1 }
                if (m != null && m.first > 1) out.append(" rowspan=\"${m.first}\"")
                out.append(">").append(esc(cell?.text.orEmpty())).append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table></div>")
    }

    private fun u16(b: ByteBuffer, o: Int) = b.getShort(o).toInt() and 0xFFFF

    private fun rk(v: Int): Double {
        val n = if (v and 2 != 0) (v shr 2).toDouble()
        else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (v and 1 != 0) n / 100.0 else n
    }

    /** Short string: cch u8, flags u8, chars (BOUNDSHEET name). */
    private fun readShortString(w: ByteArray, o: Int): String {
        val cch = w[o].toInt() and 0xFF
        val wide = w[o + 1].toInt() and 1 != 0
        return decode(w, o + 2, cch, wide)
    }

    /** String whose flags byte sits at [o + lenBytes]; cch already known. lenBytes is bytes of length field before flags. */
    private fun readUnicode(w: ByteArray, o: Int, cch: Int, lenBytes: Int): String {
        // For LABEL/STRING/FORMAT the cch field precedes the flags byte; callers pass o at the flags byte minus lenBytes.
        val flagsAt = if (lenBytes == 0) o else o + lenBytes
        if (flagsAt >= w.size) return ""
        val flags = w[flagsAt].toInt()
        return decode(w, flagsAt + 1, cch, flags and 1 != 0)
    }

    private fun decode(w: ByteArray, o: Int, cch: Int, wide: Boolean): String {
        val bytes = if (wide) cch * 2 else cch
        if (o < 0 || o + bytes > w.size) return ""
        return if (wide) String(w, o, bytes, Charsets.UTF_16LE) else String(w, o, bytes, charset("windows-1252"))
    }

    private fun readSst(w: ByteArray, chunks: List<IntRange>, out: MutableList<String>) {
        var ci = 0
        var p = chunks[0].first + 8
        fun end() = chunks[ci].last + 1
        fun ensure(): Boolean {
            while (p >= end()) {
                if (ci + 1 >= chunks.size) return false
                ci++; p = chunks[ci].first
            }
            return true
        }
        fun u8(): Int { ensure(); return w[p++].toInt() and 0xFF }
        fun skip(n: Int) { var left = n; while (left > 0 && ensure()) { val t = minOf(left, end() - p); p += t; left -= t } }
        try {
            while (ensure()) {
                val cch = u8() or (u8() shl 8)
                val flags = u8()
                var runs = 0
                var ext = 0
                if (flags and 8 != 0) runs = u8() or (u8() shl 8)
                if (flags and 4 != 0) ext = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
                var wide = flags and 1 != 0
                val sb = StringBuilder()
                var left = cch
                while (left > 0) {
                    if (p >= end()) {
                        if (ci + 1 >= chunks.size) return
                        ci++; p = chunks[ci].first
                        wide = w[p++].toInt() and 1 != 0
                    }
                    val unit = if (wide) 2 else 1
                    val take = minOf(left, (end() - p) / unit)
                    if (take == 0) { p = end(); continue }
                    sb.append(decode(w, p, take, wide))
                    p += take * unit
                    left -= take
                }
                skip(runs * 4 + ext)
                out.add(sb.toString())
            }
        } catch (_: IndexOutOfBoundsException) {
        }
    }
}
