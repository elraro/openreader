package eu.elraro.openreader

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Minimal Mac PICT (version 2) reader: the bitmap opcodes PackBitsRect and DirectBitsRect, converted to PNG. */
object Pict {
    fun toPngDataUri(d: ByteArray): String? = try { decode(d)?.let { "data:image/png;base64," + Base64.encodeToString(it, Base64.NO_WRAP) } } catch (e: RuntimeException) { null }

    private fun u16(d: ByteArray, o: Int) = ((d[o].toInt() and 255) shl 8) or (d[o + 1].toInt() and 255)
    private fun i16(d: ByteArray, o: Int) = u16(d, o).toShort().toInt()

    private fun decode(d: ByteArray): ByteArray? {
        var p = 10
        if (d.size < 40) return null
        // version 2 header: 0x0011 0x02FF 0x0C00 + 24 bytes
        if (u16(d, p) != 0x0011 || u16(d, p + 2) != 0x02FF) return null
        p += 4
        while (p + 2 <= d.size) {
            val op = u16(d, p); p += 2
            when (op) {
                0x0000, 0x001E -> {}
                0x0C00 -> p += 24
                0x0001 -> p += u16(d, p)
                0x0003, 0x0005, 0x0008, 0x000D, 0x0004 -> p += 2
                0x0006, 0x0007, 0x000B, 0x000C, 0x000E, 0x000F, 0x0021 -> p += 4
                0x0002, 0x0009, 0x000A, 0x0010, 0x0020 -> p += 8
                0x001A, 0x001B, 0x001F, 0x0022 -> p += 6
                0x0023 -> p += 2
                0x00A0 -> p += 2
                0x00A1 -> p += 4 + u16(d, p + 2)
                0x0098, 0x009A, 0x0099, 0x009B -> return bitmap(d, p, op)
                else -> return null
            }
        }
        return null
    }

    private fun unpack(d: ByteArray, start: Int, rowBytes: Int, bytesPerRow: Int, out: ByteArray, outOff: Int): Int {
        var p = start
        if (rowBytes < 8) { System.arraycopy(d, p, out, outOff, rowBytes); return p + rowBytes }
        val len = if (rowBytes > 250) u16(d, p).also { p += 2 } else (d[p++].toInt() and 255)
        val end = p + len
        var o = outOff
        val limit = outOff + bytesPerRow
        while (p < end && o < limit) {
            val n = d[p++].toInt()
            if (n >= 0) { val c = n + 1; for (k in 0 until c) { if (o < limit) out[o++] = d[p]; p++ } }
            else { val c = 1 - n; val v = d[p++]; for (k in 0 until c) if (o < limit) out[o++] = v }
        }
        return end
    }

    private fun bitmap(d: ByteArray, start: Int, op: Int): ByteArray? {
        var p = start
        val direct = op == 0x009A || op == 0x009B
        if (direct) p += 4 // baseAddr
        val rowBytes = u16(d, p) and 0x3FFF; val isPix = u16(d, p) and 0x8000 != 0; p += 2
        val top = i16(d, p); val left = i16(d, p + 2); val bottom = i16(d, p + 4); val right = i16(d, p + 6); p += 8
        val w = right - left; val h = bottom - top
        if (w <= 0 || h <= 0 || w > 8000 || h > 8000) return null
        var pixelSize = 1; var cmpCount = 1; var packType = 0
        if (isPix) {
            packType = u16(d, p + 2); pixelSize = u16(d, p + 20); cmpCount = u16(d, p + 22); p += 36
        }
        val palette = ArrayList<Int>()
        if (!direct) {
            if (isPix) {
                val n = u16(d, p + 6) + 1; p += 8
                for (i in 0 until n) { val idx = u16(d, p); palette.add(0); val c = (d[p + 2].toInt() and 255 shl 16) or (d[p + 4].toInt() and 255 shl 8) or (d[p + 6].toInt() and 255); if (idx < 4096) { while (palette.size <= idx) palette.add(0); palette[idx] = c }; p += 8 }
            } else { palette.add(0xFFFFFF); palette.add(0) }
        }
        p += 16 + 2 // srcRect, dstRect, mode
        if (op == 0x0099 || op == 0x009B) p += u16(d, p)
        val rgba = ByteArray(w * h * 4)
        val line = ByteArray(maxOf(rowBytes, w * 4 + 8))
        for (y in 0 until h) {
            java.util.Arrays.fill(line, 0)
            val raw = if (direct && pixelSize == 16) rowBytes else rowBytes
            // packType 4 (32-bit) stores component planes; pack type 3 (16-bit) packs 16-bit words
            p = if (direct && pixelSize == 32 && packType == 4) unpack(d, p, rowBytes, w * cmpCount, line, 0) else unpack(d, p, rowBytes, raw, line, 0)
            for (x in 0 until w) {
                val o = (y * w + x) * 4
                var r = 0; var g = 0; var b = 0
                when {
                    direct && pixelSize == 32 -> { // planes R,G,B (xRGB would add an unused plane first)
                        val planes = if (cmpCount == 3) 3 else 3
                        val base = if (cmpCount == 4) w else 0
                        r = line[base + x].toInt() and 255; g = line[base + w + x].toInt() and 255; b = line[base + 2 * w + x].toInt() and 255
                    }
                    direct && pixelSize == 16 -> { val v = ((line[x * 2].toInt() and 255) shl 8) or (line[x * 2 + 1].toInt() and 255); r = ((v shr 10) and 31) * 255 / 31; g = ((v shr 5) and 31) * 255 / 31; b = (v and 31) * 255 / 31 }
                    else -> {
                        val bits = if (pixelSize <= 0) 1 else pixelSize
                        val idx = when (bits) {
                            8 -> line[x].toInt() and 255
                            4 -> (line[x / 2].toInt() shr (if (x % 2 == 0) 4 else 0)) and 15
                            2 -> (line[x / 4].toInt() shr (6 - 2 * (x % 4))) and 3
                            else -> (line[x / 8].toInt() shr (7 - x % 8)) and 1
                        }
                        val c = palette.getOrElse(idx) { 0 }; r = (c shr 16) and 255; g = (c shr 8) and 255; b = c and 255
                    }
                }
                rgba[o] = r.toByte(); rgba[o + 1] = g.toByte(); rgba[o + 2] = b.toByte(); rgba[o + 3] = 255.toByte()
            }
        }
        return png(rgba, w, h)
    }

    private fun png(rgba: ByteArray, w: Int, h: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) { raw.write(0); raw.write(rgba, y * w * 4, w * 4) }
        val def = Deflater(); def.setInput(raw.toByteArray()); def.finish()
        val z = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!def.finished()) { val n = def.deflate(buf); z.write(buf, 0, n) }
        def.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            val td = type.toByteArray() + data
            out.write(td)
            out.write(ByteBuffer.allocate(4).putInt(CRC32().also { it.update(td) }.value.toInt()).array())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(6).put(0).put(0).put(0).array())
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
