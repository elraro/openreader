package eu.elraro.openreader

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/** Reads pictures (BLIP records) from OfficeArt data as used by .doc and .ppt files. */
object OfficeArt {
    class Blip(val dataUri: String)

    private fun u16(b: ByteBuffer, o: Int) = b.getShort(o).toInt() and 0xFFFF

    /** Finds the first picture in records [start, end) of [data], descending into containers and BSE records. */
    fun firstBlip(data: ByteArray, start: Int, end: Int): Blip? {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return scan(data, b, start, minOf(end, data.size), 0)
    }

    private fun scan(d: ByteArray, b: ByteBuffer, start: Int, end: Int, depth: Int): Blip? {
        if (depth > 8) return null
        var p = start
        while (p + 8 <= end) {
            val verInst = u16(b, p); val type = u16(b, p + 2); val len = b.getInt(p + 4)
            val body = p + 8
            if (len < 0 || body.toLong() + len > end) return null
            when {
                type in 0xF018..0xF117 -> blip(d, b, p, body, len)?.let { return it }
                type == 0xF007 -> scan(d, b, body + 36, body + len, depth + 1)?.let { return it }
                verInst and 0xF == 0xF -> scan(d, b, body, body + len, depth + 1)?.let { return it }
            }
            p = body + len
        }
        return null
    }

    /** Decodes the BLIP record whose header starts at [p]. */
    fun blip(d: ByteArray, b: ByteBuffer, p: Int, body: Int, len: Int): Blip? {
        val inst = u16(b, p) shr 4
        val type = u16(b, p + 2)
        val uids = if (inst and 1 == 1 || (type == 0xF01D && inst == 0x46B) ) 32 else 16
        val end = body + len
        return try {
            when (type) {
                0xF01A, 0xF01B -> { // EMF, WMF: 34-byte header, usually deflated
                    val h = body + uids
                    val cbSave = b.getInt(h + 28); val compression = d[h + 32].toInt() and 0xFF
                    val dataStart = h + 34
                    if (cbSave <= 0 || dataStart + cbSave > end) return null
                    val raw = if (compression == 0) inflate(d, dataStart, cbSave) else d.copyOfRange(dataStart, dataStart + cbSave)
                    val raw2 = if (type == 0xF01B && raw.size > 4 && le(raw, 0) != 0x9AC6CDD7.toInt()) wmfWithoutHeader(raw, b, h) else raw
                    Metafile.toSvgDataUri(raw2)?.let { Blip(it) }
                }
                0xF01C -> { // PICT: take the JPEG/PNG that QuickTime-style PICTs embed
                    val h = body + uids
                    val cbSave = b.getInt(h + 28); val compression = d[h + 32].toInt() and 0xFF
                    val dataStart = h + 34
                    if (cbSave <= 0 || dataStart + cbSave > end) return null
                    val raw = if (compression == 0) inflate(d, dataStart, cbSave) else d.copyOfRange(dataStart, dataStart + cbSave)
                    embeddedImage(raw) ?: Pict.toPngDataUri(raw)?.let { Blip(it) }
                }
                0xF01D, 0xF02A -> Blip("data:image/jpeg;base64," + Base64.encodeToString(d, body + uids + 1, end - body - uids - 1, Base64.NO_WRAP))
                0xF01E -> Blip("data:image/png;base64," + Base64.encodeToString(d, body + uids + 1, end - body - uids - 1, Base64.NO_WRAP))
                0xF01F -> Metafile.dibUri(d.copyOfRange(body + uids + 1, end))?.let { Blip(it) }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun inflate(d: ByteArray, start: Int, len: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(d, start, len)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inf.finished() && out.size() < 16_000_000) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    /** First JPEG or PNG inside raw picture data (PICT files wrap them in QuickTime opcodes). */
    private fun embeddedImage(raw: ByteArray): Blip? {
        for (i in 0 until raw.size - 4) {
            if (raw[i] == 0xFF.toByte() && raw[i + 1] == 0xD8.toByte() && raw[i + 2] == 0xFF.toByte()) {
                var e = raw.size - 2
                while (e > i && !(raw[e] == 0xFF.toByte() && raw[e + 1] == 0xD9.toByte())) e--
                if (e > i) return Blip("data:image/jpeg;base64," + Base64.encodeToString(raw, i, e + 2 - i, Base64.NO_WRAP))
            }
            if (raw[i] == 0x89.toByte() && raw[i + 1] == 'P'.code.toByte() && raw[i + 2] == 'N'.code.toByte() && raw[i + 3] == 'G'.code.toByte())
                return Blip("data:image/png;base64," + Base64.encodeToString(raw, i, raw.size - i, Base64.NO_WRAP))
        }
        return null
    }

    private fun le(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

    /** OfficeArt stores WMF without the placeable header; rebuild it from the stored bounds. */
    private fun wmfWithoutHeader(raw: ByteArray, b: ByteBuffer, h: Int): ByteArray {
        val l = b.getInt(h + 4); val t = b.getInt(h + 8); val r = b.getInt(h + 12); val bt = b.getInt(h + 16)
        val out = ByteBuffer.allocate(22 + raw.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x9AC6CDD7.toInt()).putShort(0).putShort(l.toShort()).putShort(t.toShort()).putShort(r.toShort()).putShort(bt.toShort())
            .putShort(1440).putInt(0).putShort(0).put(raw)
        return out.array()
    }
}
