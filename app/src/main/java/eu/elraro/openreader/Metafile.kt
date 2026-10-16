package eu.elraro.openreader

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts the common parts of EMF and WMF vector images to SVG (lines, shapes, paths, text and embedded bitmaps). */
object Metafile {
    fun toSvgDataUri(data: ByteArray): String? {
        val svg = try {
            when {
                data.size > 44 && le(data, 0) == 1 && le(data, 40) == 0x464D4520 -> Emf(data).svg()
                data.size > 22 && le(data, 0) == 0x9AC6CDD7.toInt() -> Wmf(data).svg()
                else -> null
            }
        } catch (e: Exception) { null } ?: return null
        return "data:image/svg+xml;base64," + Base64.encodeToString(svg.toByteArray(), Base64.NO_WRAP)
    }

    private fun le(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

    private fun rgb(c: Int) = "rgb(${c and 255},${(c shr 8) and 255},${(c shr 16) and 255})"
    private fun n6(v: Double) = String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    private fun n(v: Number) = String.format(java.util.Locale.US, "%.2f", v.toDouble()).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    /** Wraps a DIB (BITMAPINFO + bits) into a BMP data URI. */
    fun dibUri(dib: ByteArray): String? {
        if (dib.size < 40) return null
        val hdr = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN)
        val hsize = hdr.getInt(0)
        val bpp = hdr.getShort(14).toInt()
        val compr = hdr.getInt(16)
        var clr = hdr.getInt(32)
        if (clr == 0 && bpp <= 8) clr = 1 shl bpp
        val masks = if (compr == 3 && hsize == 40) 12 else 0
        val offBits = 14 + hsize + masks + clr * 4
        val bmp = ByteBuffer.allocate(14 + dib.size).order(ByteOrder.LITTLE_ENDIAN)
        bmp.put('B'.code.toByte()).put('M'.code.toByte()).putInt(14 + dib.size).putInt(0).putInt(offBits).put(dib)
        return "data:image/bmp;base64," + Base64.encodeToString(bmp.array(), Base64.NO_WRAP)
    }

    private class Obj(var kind: Char, var color: Int = 0, var width: Double = 1.0, var none: Boolean = false, var style: Int = 0,
                      var size: Double = 12.0, var bold: Boolean = false, var italic: Boolean = false, var face: String = "")

    private abstract class Canvas {
        val body = StringBuilder()
        var pen = Obj('p', 0, 1.0)
        var brush = Obj('b', 0xFFFFFF, 0.0)
        var font = Obj('f')
        var textColor = 0
        var curX = 0.0; var curY = 0.0
        val path = StringBuilder()
        var inPath = false

        fun style(fill: Boolean = true, stroke: Boolean = true): String {
            val f = if (fill && !brush.none) rgb(brush.color) else "none"
            val s = if (stroke && !pen.none) "stroke=\"${rgb(pen.color)}\" stroke-width=\"${n(maxOf(pen.width, 1.0))}\"" else "stroke=\"none\""
            return "fill=\"$f\" $s"
        }
        fun poly(pts: List<Pair<Double, Double>>, close: Boolean) {
            if (pts.isEmpty()) return
            val d = "M" + pts.joinToString(" L") { "${n(it.first)},${n(it.second)}" } + if (close) " Z" else ""
            if (inPath) path.append(d) else body.append("<path d=\"$d\" ${style(fill = close)}/>")
        }
        fun text(x: Double, y: Double, s: String) {
            if (s.isBlank()) return
            val sz = maxOf(font.size, 1.0)
            body.append("<text x=\"${n(x)}\" y=\"${n(y)}\" font-size=\"${n(sz)}\" fill=\"${rgb(textColor)}\" font-family=\"${esc(font.face.ifEmpty { "sans-serif" })},sans-serif\"" +
                (if (font.bold) " font-weight=\"bold\"" else "") + (if (font.italic) " font-style=\"italic\"" else "") +
                " xml:space=\"preserve\">${esc(s)}</text>")
        }
    }

    // ---------------------------------------------------------------- EMF

    private class Emf(val d: ByteArray) : Canvas() {
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val objs = HashMap<Int, Obj>()
        var winX = 0.0; var winY = 0.0; var winW = 0.0; var winH = 0.0
        var textAlign = 0
        var count = 0
        var plusImage: String? = null
        var vpX = 0.0; var vpY = 0.0; var vpW = 0.0; var vpH = 0.0
        var world = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        /** a∘b: apply b first, then a (SVG matrix order [a b c d e f]). */
        fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[0] * b[0] + a[2] * b[1], a[1] * b[0] + a[3] * b[1], a[0] * b[2] + a[2] * b[3], a[1] * b[2] + a[3] * b[3],
            a[0] * b[4] + a[2] * b[5] + a[4], a[1] * b[4] + a[3] * b[5] + a[5])

        fun mapping(): DoubleArray {
            val sx = if (winW != 0.0 && vpW != 0.0) vpW / winW else 1.0; val sy = if (winH != 0.0 && vpH != 0.0) vpH / winH else 1.0
            return doubleArrayOf(sx, 0.0, 0.0, sy, vpX - winX * sx, vpY - winY * sy)
        }

        fun svg(): String? {
            var bx = bb.getInt(8).toDouble(); var by = bb.getInt(12).toDouble()
            var bw = bb.getInt(16) - bx + 1; var bh = bb.getInt(20) - by + 1
            var pos = 0
            while (pos + 8 <= d.size) {
                val type = bb.getInt(pos); val size = bb.getInt(pos + 4)
                if (size < 8 || pos + size > d.size) break
                val before = body.length
                record(type, pos, size)
                if (body.length > before) {
                    val m = mul(mapping(), world)
                    if (m[0] != 1.0 || m[1] != 0.0 || m[2] != 0.0 || m[3] != 1.0 || m[4] != 0.0 || m[5] != 0.0) {
                        body.insert(before, "<g transform=\"matrix(${m.joinToString(" ") { n6(it) }})\">"); body.append("</g>")
                    }
                }
                if (type == 14) break
                pos += size
                if (++count > 200_000) break
            }
            if (bw <= 0 || bh <= 0) return null
            if (body.isEmpty() && plusImage != null) body.append("<image href=\"$plusImage\" x=\"${n(bx)}\" y=\"${n(by)}\" width=\"${n(bw)}\" height=\"${n(bh)}\" preserveAspectRatio=\"none\"/>")
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"${n(bx)} ${n(by)} ${n(bw)} ${n(bh)}\" width=\"${n(bw)}\" height=\"${n(bh)}\">$body</svg>"
        }

        fun i(p: Int) = bb.getInt(p)
        fun s16(p: Int) = bb.getShort(p).toInt()

        fun stock(id: Int) {
            when (id and 0x7FFFFFFF) {
                0 -> brush = Obj('b', 0xFFFFFF, 0.0); 1 -> brush = Obj('b', 0xC0C0C0, 0.0); 2 -> brush = Obj('b', 0x808080, 0.0)
                3 -> brush = Obj('b', 0x404040, 0.0); 4 -> brush = Obj('b', 0, 0.0); 5 -> brush = Obj('b', none = true)
                6 -> pen = Obj('p', 0xFFFFFF, 1.0); 7 -> pen = Obj('p', 0, 1.0); 8 -> pen = Obj('p', none = true)
            }
        }

        /** EMF+ object records can carry a PNG/JPEG/GIF; used for files that only hold such an image. */
        fun emfPlus(p: Int, size: Int) {
            if (plusImage != null || size < 20 || i(p + 12) != 0x2B464D45) return
            var q = p + 16; val end = p + size
            while (q + 12 <= end) {
                val t = s16(q) and 0xFFFF; val flags = s16(q + 2) and 0xFFFF; val sz = i(q + 4); val ds = i(q + 8)
                if (sz < 12 || q + sz > end) break
                if (t == 0x4008 && (flags shr 8) and 0x7F == 5 && ds > 28 && q + 12 + ds <= end) {
                    val o = q + 12
                    if (i(o + 4) == 1 && i(o + 24) == 1) { // bitmap holding compressed data
                        val data = d.copyOfRange(o + 28, q + 12 + ds)
                        val mime = when {
                            data.size > 4 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() -> "image/png"
                            data.size > 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "image/jpeg"
                            data.size > 3 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() -> "image/gif"
                            else -> null
                        }
                        if (mime != null) { plusImage = "data:$mime;base64," + Base64.encodeToString(data, Base64.NO_WRAP); return }
                    }
                }
                q += sz
            }
        }

        fun record(type: Int, p: Int, size: Int) {
            when (type) {
                70 -> emfPlus(p, size)
                9 -> { winW = i(p + 8).toDouble(); winH = i(p + 12).toDouble() }
                10 -> { winX = i(p + 8).toDouble(); winY = i(p + 12).toDouble() }
                11 -> { vpW = i(p + 8).toDouble(); vpH = i(p + 12).toDouble() }
                12 -> { vpX = i(p + 8).toDouble(); vpY = i(p + 12).toDouble() }
                35 -> world = doubleArrayOf(bb.getFloat(p + 8).toDouble(), bb.getFloat(p + 12).toDouble(), bb.getFloat(p + 16).toDouble(),
                    bb.getFloat(p + 20).toDouble(), bb.getFloat(p + 24).toDouble(), bb.getFloat(p + 28).toDouble())
                36 -> {
                    val x = doubleArrayOf(bb.getFloat(p + 8).toDouble(), bb.getFloat(p + 12).toDouble(), bb.getFloat(p + 16).toDouble(),
                        bb.getFloat(p + 20).toDouble(), bb.getFloat(p + 24).toDouble(), bb.getFloat(p + 28).toDouble())
                    world = when (i(p + 32)) { 1 -> doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0); 2 -> mul(world, x); 3 -> mul(x, world); else -> x }
                }
                24 -> textColor = i(p + 8)
                22 -> textAlign = i(p + 8)
                27 -> { curX = i(p + 8).toDouble(); curY = i(p + 12).toDouble(); if (inPath) path.append("M${n(curX)},${n(curY)}") }
                54 -> {
                    val x = i(p + 8).toDouble(); val y = i(p + 12).toDouble()
                    if (inPath) path.append("L${n(x)},${n(y)}")
                    else body.append("<line x1=\"${n(curX)}\" y1=\"${n(curY)}\" x2=\"${n(x)}\" y2=\"${n(y)}\" ${style(false)}/>")
                    curX = x; curY = y
                }
                43 -> rect(i(p + 8), i(p + 12), i(p + 16), i(p + 20), 0.0, 0.0)
                44 -> rect(i(p + 8), i(p + 12), i(p + 16), i(p + 20), i(p + 24) / 2.0, i(p + 28) / 2.0)
                42 -> {
                    val l = i(p + 8); val t = i(p + 12); val r = i(p + 16); val b = i(p + 20)
                    val e = "<ellipse cx=\"${n((l + r) / 2.0)}\" cy=\"${n((t + b) / 2.0)}\" rx=\"${n((r - l) / 2.0)}\" ry=\"${n((b - t) / 2.0)}\" ${style()}/>"
                    if (inPath) path.append(arcPath((l + r) / 2.0, (t + b) / 2.0, (r - l) / 2.0, (b - t) / 2.0)) else body.append(e)
                }
                3, 4, 6, 5 -> { // 32-bit polygon, polyline, polylineto, polybezierto
                    val cnt = i(p + 24); val pts = (0 until cnt).map { i(p + 28 + it * 8).toDouble() to i(p + 32 + it * 8).toDouble() }
                    points(type, pts)
                }
                86, 87, 89, 88 -> { // 16-bit variants
                    val cnt = i(p + 24); val pts = (0 until cnt).map { s16(p + 28 + it * 4).toDouble() to s16(p + 30 + it * 4).toDouble() }
                    points(when (type) { 86 -> 3; 87 -> 4; 89 -> 6; else -> 5 }, pts)
                }
                91, 90 -> { // POLYPOLYGON16 / POLYPOLYLINE16
                    val np = i(p + 24); val total = i(p + 28)
                    var off = p + 32 + np * 4
                    for (k in 0 until np) {
                        val c = i(p + 32 + k * 4)
                        val pts = (0 until c).map { s16(off + it * 4).toDouble() to s16(off + 2 + it * 4).toDouble() }
                        off += c * 4
                        poly(pts, type == 91)
                    }
                    if (total < 0) return
                }
                59 -> { inPath = true; path.setLength(0) }
                60 -> inPath = false
                61 -> path.append("Z")
                62, 63, 64 -> if (path.isNotEmpty()) {
                    body.append("<path d=\"$path\" ${style(fill = type != 64, stroke = type != 62)}/>"); path.setLength(0)
                }
                37 -> { val ih = i(p + 8); if (ih < 0) stock(ih) else objs[ih]?.let { when (it.kind) { 'p' -> pen = it; 'b' -> brush = it; 'f' -> font = it } } }
                40 -> objs.remove(i(p + 8))
                38 -> objs[i(p + 8)] = Obj('p', i(p + 24), i(p + 16).toDouble(), none = i(p + 12) == 5, style = i(p + 12))
                39 -> objs[i(p + 8)] = Obj('b', i(p + 16), 0.0, none = i(p + 12) == 1 || i(p + 12) == 5)
                95 -> objs[i(p + 8)] = Obj('p', i(p + 36), i(p + 28).toDouble(), none = (i(p + 24) and 0xF) == 5, style = i(p + 24) and 0xF)
                82 -> {
                    val h = i(p + 12)
                    val face = String(d, p + 40, 64, Charsets.UTF_16LE).substringBefore('\u0000')
                    objs[i(p + 8)] = Obj('f', size = if (h == 0) 12.0 else kotlin.math.abs(h).toDouble(), bold = i(p + 28) >= 600, italic = d[p + 32].toInt() != 0, face = face)
                }
                84 -> {
                    val x = i(p + 36).toDouble(); val y = i(p + 40).toDouble()
                    val nch = i(p + 44); val off = i(p + 48)
                    if (nch in 1..5000 && off > 0 && p + off + nch * 2 <= d.size) {
                        val text = String(d, p + off, nch * 2, Charsets.UTF_16LE)
                        val anchor = when { textAlign and 6 == 6 -> "middle"; textAlign and 2 != 0 -> "end"; else -> "start" }
                        val baseline = if (textAlign and 24 == 24) 0.0 else if (textAlign and 8 != 0) 0.0 else font.size * 0.85
                        val before = body.length
                        text(x, y + baseline, text)
                        if (anchor != "start") body.insert(body.indexOf(" x=", before) , " text-anchor=\"$anchor\"")
                    }
                }
                81 -> { // STRETCHDIBITS
                    val xd = i(p + 24); val yd = i(p + 28)
                    val offBmi = i(p + 48); val cbBmi = i(p + 52); val offBits = i(p + 56); val cbBits = i(p + 60)
                    val cx = i(p + 72); val cy = i(p + 76)
                    if (cbBmi > 0 && cbBits > 0 && p + offBits + cbBits <= d.size && p + offBmi + cbBmi <= d.size) {
                        val dib = ByteArray(cbBmi + cbBits)
                        System.arraycopy(d, p + offBmi, dib, 0, cbBmi); System.arraycopy(d, p + offBits, dib, cbBmi, cbBits)
                        dibUri(dib)?.let { body.append("<image href=\"$it\" x=\"${n(xd)}\" y=\"${n(yd)}\" width=\"${n(cx)}\" height=\"${n(kotlin.math.abs(cy))}\" preserveAspectRatio=\"none\"/>") }
                    }
                }
            }
        }

        fun arcPath(cx: Double, cy: Double, rx: Double, ry: Double) =
            "M${n(cx - rx)},${n(cy)} A${n(rx)},${n(ry)} 0 1 0 ${n(cx + rx)},${n(cy)} A${n(rx)},${n(ry)} 0 1 0 ${n(cx - rx)},${n(cy)} Z"

        fun rect(l: Int, t: Int, r: Int, b: Int, rx: Double, ry: Double) {
            val s = "<rect x=\"${n(minOf(l, r))}\" y=\"${n(minOf(t, b))}\" width=\"${n(kotlin.math.abs(r - l))}\" height=\"${n(kotlin.math.abs(b - t))}\"" +
                (if (rx > 0) " rx=\"${n(rx)}\" ry=\"${n(ry)}\"" else "") + " ${style()}/>"
            if (inPath) path.append("M$l,$t L$r,$t L$r,$b L$l,$b Z") else body.append(s)
        }

        fun points(kind: Int, pts: List<Pair<Double, Double>>) {
            when (kind) {
                3 -> poly(pts, true)
                4 -> poly(pts, false)
                6 -> { if (inPath) path.append(pts.joinToString("") { "L${n(it.first)},${n(it.second)}" }) else poly(listOf(curX to curY) + pts, false) }
                5 -> {
                    val sb = StringBuilder()
                    var k = 0
                    while (k + 2 < pts.size) { sb.append("C${pts.subList(k, k + 3).joinToString(" ") { "${n(it.first)},${n(it.second)}" }}"); k += 3 }
                    if (inPath) path.append(sb) else body.append("<path d=\"M${n(curX)},${n(curY)} $sb\" ${style(false)}/>")
                }
            }
            pts.lastOrNull()?.let { curX = it.first; curY = it.second }
        }
    }

    // ---------------------------------------------------------------- WMF

    private class Wmf(val d: ByteArray) : Canvas() {
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val objs = ArrayList<Obj?>()
        var orgX = 0.0; var orgY = 0.0; var extX = 0.0; var extY = 0.0

        fun add(o: Obj) { val k = objs.indexOfFirst { it == null }; if (k >= 0) objs[k] = o else objs.add(o) }
        fun w(p: Int) = bb.getShort(p).toInt()

        fun svg(): String? {
            val l = w(6).toDouble(); val t = w(8).toDouble(); val r = w(10).toDouble(); val b = w(12).toDouble()
            var pos = 22 + 18
            var guard = 0
            while (pos + 6 <= d.size && guard++ < 200_000) {
                val size = bb.getInt(pos) * 2
                val fn = bb.getShort(pos + 4).toInt() and 0xFFFF
                if (size < 6 || pos + size > d.size) break
                if (fn == 0) break
                record(fn, pos + 6, size - 6)
                pos += size
            }
            val ox = if (extX != 0.0) orgX else l; val oy = if (extY != 0.0) orgY else t
            val ex = if (extX != 0.0) extX else r - l; val ey = if (extY != 0.0) extY else b - t
            val vw = kotlin.math.abs(ex); val vh = kotlin.math.abs(ey)
            if (vw <= 0 || vh <= 0) return null
            val sx = vw / ex; val sy = vh / ey // negative extents flip the axis
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${n(vw)} ${n(vh)}\" width=\"${n(vw)}\" height=\"${n(vh)}\">" +
                "<g transform=\"matrix(${n6(sx)} 0 0 ${n6(sy)} ${n6(-ox * sx)} ${n6(-oy * sy)})\">$body</g></svg>"
        }

        fun record(fn: Int, p: Int, len: Int) {
            when (fn) {
                0x020B -> { orgY = w(p).toDouble(); orgX = w(p + 2).toDouble() }
                0x020C -> { extY = w(p).toDouble(); extX = w(p + 2).toDouble() }
                0x0209 -> textColor = bb.getInt(p)
                0x0214 -> { curY = w(p).toDouble(); curX = w(p + 2).toDouble() }
                0x0213 -> {
                    val y = w(p).toDouble(); val x = w(p + 2).toDouble()
                    body.append("<line x1=\"${n(curX)}\" y1=\"${n(curY)}\" x2=\"${n(x)}\" y2=\"${n(y)}\" ${style(false)}/>"); curX = x; curY = y
                }
                0x02FA -> add(Obj('p', bb.getInt(p + 6), w(p + 2).toDouble(), none = w(p) == 5, style = w(p)))
                0x02FC -> add(Obj('b', bb.getInt(p + 2), 0.0, none = w(p) == 1))
                0x02FB -> {
                    val h = w(p)
                    add(Obj('f', size = if (h == 0) 12.0 else kotlin.math.abs(h).toDouble(), bold = w(p + 10) >= 600, italic = d[p + 12].toInt() != 0,
                        face = String(d, p + 18, minOf(32, len - 18).coerceAtLeast(0), Charsets.ISO_8859_1).substringBefore('\u0000')))
                }
                0x012D -> objs.getOrNull(w(p))?.let { when (it.kind) { 'p' -> pen = it; 'b' -> brush = it; 'f' -> font = it } }
                0x01F0 -> objs.getOrNull(w(p))?.let { objs[w(p)] = null }
                0x041B -> rect(w(p + 6), w(p + 4), w(p + 2), w(p), 0.0)
                0x061C -> rect(w(p + 10), w(p + 8), w(p + 6), w(p + 4), w(p + 2) / 2.0)
                0x0418 -> {
                    val l = w(p + 6); val t = w(p + 4); val r = w(p + 2); val b = w(p)
                    body.append("<ellipse cx=\"${n((l + r) / 2.0)}\" cy=\"${n((t + b) / 2.0)}\" rx=\"${n((r - l) / 2.0)}\" ry=\"${n((b - t) / 2.0)}\" ${style()}/>")
                }
                0x0324, 0x0325 -> {
                    val c = w(p)
                    poly((0 until c).map { w(p + 2 + it * 4).toDouble() to w(p + 4 + it * 4).toDouble() }, fn == 0x0324)
                }
                0x0538 -> {
                    val np = w(p); var off = p + 2 + np * 2
                    for (k in 0 until np) {
                        val c = w(p + 2 + k * 2)
                        poly((0 until c).map { w(off + it * 4).toDouble() to w(off + 2 + it * 4).toDouble() }, true); off += c * 4
                    }
                }
                0x0521 -> {
                    val c = w(p); val padded = (c + 1) and 1.inv()
                    val s = String(d, p + 2, c.coerceAtLeast(0), Charsets.ISO_8859_1)
                    text(w(p + 4 + padded).toDouble(), w(p + 2 + padded).toDouble() + font.size * 0.85, s)
                }
                0x0A32 -> {
                    val y = w(p); val x = w(p + 2); val c = w(p + 4); val opt = w(p + 6)
                    val off = p + 8 + if (opt and 6 != 0) 8 else 0
                    if (c > 0 && off + c <= d.size) text(x.toDouble(), y + font.size * 0.85, String(d, off, c, Charsets.ISO_8859_1))
                }
                0x0F43 -> { // STRETCHDIB: rop(4) usage(2) srcH srcW ySrc xSrc destH destW yDest xDest dib
                    val dh = w(p + 14); val dw = w(p + 16); val yd = w(p + 18); val xd = w(p + 20)
                    val dib = d.copyOfRange(p + 22, p + len)
                    dibUri(dib)?.let { body.append("<image href=\"$it\" x=\"${xd}\" y=\"${yd}\" width=\"${dw}\" height=\"${kotlin.math.abs(dh)}\" preserveAspectRatio=\"none\"/>") }
                }
            }
        }

        fun rect(l: Int, t: Int, r: Int, b: Int, rr: Double) {
            body.append("<rect x=\"${minOf(l, r)}\" y=\"${minOf(t, b)}\" width=\"${kotlin.math.abs(r - l)}\" height=\"${kotlin.math.abs(b - t)}\"" +
                (if (rr > 0) " rx=\"${n(rr)}\"" else "") + " ${style()}/>")
        }
    }
}
