package eu.elraro.openreader

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Cell values from Excel 97-2003 (.xls, BIFF8). Formatting is not recovered. */
object XlsConverter {
    private class Sheet(val name: String) {
        val cells = HashMap<Int, HashMap<Int, Pair<String, Boolean>>>()
    }

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

        fun put(r: Int, c: Int, text: String, numeric: Boolean) {
            val s = cur ?: return
            if (text.isEmpty() || r >= XlsxConverter.MAX_ROWS || c >= XlsxConverter.MAX_COLS) return
            s.cells.getOrPut(r) { HashMap() }[c] = text to numeric
        }
        fun number(r: Int, c: Int, xf: Int, d: Double) {
            val code = NumFmt.code(xfFmt.getOrNull(xf) ?: 0, customFmt)
            put(r, c, NumFmt.format(d, code), !NumFmt.isDate(code))
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
                0x00E0 -> if (len >= 4) xfFmt.add(b.getShort(d + 2).toInt() and 0xFFFF)
                0x00FD -> {
                    val idx = b.getInt(d + 6)
                    put(u16(b, d), u16(b, d + 2), sst.getOrNull(idx).orEmpty(), false)
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
                    put(u16(b, d), u16(b, d + 2), readUnicode(wb, d + 8, n, 0), false)
                }
                0x0205 -> {
                    val v = wb[d + 6].toInt()
                    val text = if (wb[d + 7].toInt() == 0) (if (v != 0) "TRUE" else "FALSE") else "#ERR"
                    put(u16(b, d), u16(b, d + 2), text, false)
                }
                0x0006 -> {
                    val r = u16(b, d); val c = u16(b, d + 2); val xf = u16(b, d + 4)
                    if (u16(b, d + 12) == 0xFFFF && (wb[d + 6].toInt() and 0xFF) == 0) pendingFormula = Triple(r, c, xf)
                    else number(r, c, xf, b.getDouble(d + 6))
                }
                0x0207 -> pendingFormula?.let { (r, c, _) ->
                    put(r, c, readUnicode(wb, d + 2, u16(b, d), 0), false)
                    pendingFormula = null
                }
            }
            pos = d + len
        }

        val tabs = StringBuilder()
        val body = StringBuilder()
        sheets.forEachIndexed { i, s ->
            tabs.append("<a href=\"#s$i\">${esc(s.name)}</a>")
            body.append("<h2 id=\"s$i\">${esc(s.name)}</h2>")
            if (s.cells.isEmpty()) {
                body.append("<p><i>Empty sheet.</i></p>")
            } else {
                val maxCol = s.cells.values.maxOf { it.keys.max() }
                val grid = s.cells.keys.sorted().map { (it + 1) to s.cells.getValue(it) }
                XlsxConverter.renderGrid(grid, maxCol, body)
            }
        }
        if (sheets.isEmpty()) body.append("<p>No worksheets found.</p>")
        return XlsxConverter.page(title, tabs, body)
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
