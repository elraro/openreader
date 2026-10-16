package eu.elraro.openreader

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Text and tables from Word 97-2003 (.doc). Formatting and images are not recovered. */
object DocConverter {
    fun toHtml(cfb: Cfb, title: String): String {
        val wd = cfb.stream("WordDocument") ?: throw IllegalArgumentException("Missing WordDocument stream")
        val b = ByteBuffer.wrap(wd).order(ByteOrder.LITTLE_ENDIAN)
        if (wd.size < 0x1AA || (b.getShort(0).toInt() and 0xFFFF) != 0xA5EC) throw IllegalArgumentException("Not a Word 97+ document")
        val flags = b.getShort(0x0A).toInt()
        if (flags and 0x100 != 0) throw IllegalArgumentException("Encrypted documents are not supported")
        val table = cfb.stream(if (flags and 0x200 != 0) "1Table" else "0Table")
            ?: throw IllegalArgumentException("Missing table stream")
        val ccpText = b.getInt(0x4C)
        val fcClx = b.getInt(0x1A2)
        val lcbClx = b.getInt(0x1A6)
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > table.size) throw IllegalArgumentException("Bad piece table")

        val text = readText(wd, table, fcClx, lcbClx, ccpText)
        return htmlPage(title, "p{margin:0 0 .6em}.page{padding:16px;max-width:900px;margin:0 auto;line-height:1.45}table{border-collapse:collapse;margin:.6em 0}td{border:1px solid #999;padding:4px 6px;vertical-align:top}", "<div class=\"page\">${render(text)}</div>")
    }

    private fun readText(wd: ByteArray, tbl: ByteArray, fcClx: Int, lcbClx: Int, ccpText: Int): String {
        val t = ByteBuffer.wrap(tbl).order(ByteOrder.LITTLE_ENDIAN)
        var p = fcClx
        val end = fcClx + lcbClx
        while (p < end && (tbl[p].toInt() and 0xFF) == 1) p += 3 + (t.getShort(p + 1).toInt() and 0xFFFF)
        if (p >= end || (tbl[p].toInt() and 0xFF) != 2) throw IllegalArgumentException("Bad piece table")
        val lcb = t.getInt(p + 1)
        val plc = p + 5
        val n = (lcb - 4) / 12
        val sb = StringBuilder()
        for (i in 0 until n) {
            val cpStart = t.getInt(plc + i * 4)
            val cpEnd = minOf(t.getInt(plc + (i + 1) * 4), ccpText)
            if (cpEnd <= cpStart) continue
            val fcRaw = t.getInt(plc + (n + 1) * 4 + i * 8 + 2)
            val count = cpEnd - cpStart
            if (fcRaw and 0x40000000 != 0) {
                val off = (fcRaw and 0x3FFFFFFF) / 2
                if (off + count <= wd.size) sb.append(String(wd, off, count, charset("windows-1252")))
            } else if (fcRaw >= 0 && fcRaw + count * 2 <= wd.size) {
                sb.append(String(wd, fcRaw, count * 2, Charsets.UTF_16LE))
            }
        }
        return sb.toString()
    }

    private fun render(raw: String): String {
        // Drop field instructions (0x13 .. 0x14), keep their results; drop picture markers.
        val text = StringBuilder()
        var depth = 0
        val inInstr = BooleanArray(16)
        for (ch in raw) {
            when (ch) {
                '\u0013' -> { if (depth < 15) depth++; inInstr[depth] = true }
                '\u0014' -> if (depth > 0) inInstr[depth] = false
                '\u0015' -> if (depth > 0) depth--
                else -> if (depth == 0 || !inInstr[depth]) if (ch != '\u0001' && ch != '\u0008') text.append(ch)
            }
        }
        val out = StringBuilder()
        val cur = StringBuilder()
        // Table rows are collected cell by cell; consecutive rows each become a table row.
        val rows = ArrayList<ArrayList<String>>()
        fun flushTable() {
            if (rows.isEmpty()) return
            out.append("<table>")
            for (r in rows) {
                out.append("<tr>")
                r.forEach { out.append("<td>").append(it).append("</td>") }
                out.append("</tr>")
            }
            out.append("</table>")
            rows.clear()
        }
        var cells = ArrayList<String>()
        for (ch in text) {
            when (ch) {
                '\u0007' -> {
                    if (cur.isEmpty() && cells.isNotEmpty()) { rows.add(cells); cells = ArrayList() }
                    else { cells.add(cur.toString().trim()); cur.setLength(0) }
                }
                '\r' -> {
                    if (cells.isEmpty()) {
                        flushTable()
                        out.append("<p>").append(if (cur.isEmpty()) "&nbsp;" else cur).append("</p>")
                        cur.setLength(0)
                    } else cur.append("<br>")
                }
                '\u000b' -> cur.append("<br>")
                '\u000c' -> cur.append("<hr>")
                '\t' -> cur.append("&emsp;")
                '&' -> cur.append("&amp;")
                '<' -> cur.append("&lt;")
                '>' -> cur.append("&gt;")
                else -> if (ch >= ' ') cur.append(ch)
            }
        }
        flushTable()
        if (cur.isNotEmpty()) out.append("<p>").append(cur).append("</p>")
        return out.toString()
    }
}
