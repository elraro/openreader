package eu.elraro.openreader

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reader for OLE2 / Compound File Binary containers (legacy .doc/.xls/.ppt). */
class Cfb private constructor(private val data: ByteBuffer) {
    private class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Int
    private val fat: IntArray
    private var miniFat = IntArray(0)
    private val entries = ArrayList<Entry>()
    private var miniStream = ByteArray(0)

    init {
        data.order(ByteOrder.LITTLE_ENDIAN)
        sectorSize = 1 shl data.getShort(30).toInt()
        miniSectorSize = 1 shl data.getShort(32).toInt()
        miniCutoff = data.getInt(56)
        val numFat = data.getInt(44)
        val firstDir = data.getInt(48)
        val firstMiniFat = data.getInt(60)
        val numMiniFat = data.getInt(64)
        var difat = data.getInt(68)
        val numDifat = data.getInt(72)

        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) {
            val s = data.getInt(76 + i * 4)
            if (s >= 0 && fatSectors.size < numFat) fatSectors.add(s)
        }
        var guard = 0
        while (difat >= 0 && guard++ < numDifat + 1 && fatSectors.size < numFat) {
            val off = sectorOffset(difat)
            for (i in 0 until sectorSize / 4 - 1) {
                val s = data.getInt(off + i * 4)
                if (s >= 0 && fatSectors.size < numFat) fatSectors.add(s)
            }
            difat = data.getInt(off + sectorSize - 4)
        }
        val per = sectorSize / 4
        fat = IntArray(fatSectors.size * per)
        fatSectors.forEachIndexed { i, s ->
            val off = sectorOffset(s)
            for (j in 0 until per) fat[i * per + j] = data.getInt(off + j * 4)
        }

        val dir = readChain(firstDir, Long.MAX_VALUE)
        val db = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        for (p in 0 until dir.size / 128) {
            val b = p * 128
            val type = dir[b + 66].toInt()
            if (type == 0) continue
            val len = (db.getShort(b + 64).toInt() and 0xFFFF).coerceIn(2, 64)
            val name = String(dir, b, len - 2, Charsets.UTF_16LE)
            entries.add(Entry(name, type, db.getInt(b + 116), db.getInt(b + 120).toLong() and 0xFFFFFFFFL))
        }
        entries.firstOrNull { it.type == 5 }?.let { miniStream = readChain(it.start, it.size) }
        if (numMiniFat > 0) {
            val mf = readChain(firstMiniFat, Long.MAX_VALUE)
            val mb = ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN)
            miniFat = IntArray(mf.size / 4) { mb.getInt(it * 4) }
        }
    }

    private fun sectorOffset(s: Int): Int = (s + 1) * sectorSize

    private fun readChain(start: Int, size: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var s = start
        var n = 0
        while (s >= 0 && s < fat.size + 1 && out.size() < size && n++ <= fat.size) {
            val off = sectorOffset(s)
            if (off + sectorSize > data.limit()) break
            val buf = ByteArray(sectorSize)
            data.position(off)
            data.get(buf)
            out.write(buf)
            s = if (s < fat.size) fat[s] else -1
        }
        val all = out.toByteArray()
        return if (size < all.size) all.copyOf(size.toInt()) else all
    }

    fun hasStream(name: String) = entries.any { it.type == 2 && it.name == name }

    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
        if (e.size >= miniCutoff) return readChain(e.start, e.size)
        val out = ByteArrayOutputStream()
        var s = e.start
        var n = 0
        while (s >= 0 && s < miniFat.size && out.size() < e.size && n++ <= miniFat.size) {
            val off = s * miniSectorSize
            if (off + miniSectorSize > miniStream.size) break
            out.write(miniStream, off, miniSectorSize)
            s = miniFat[s]
        }
        val all = out.toByteArray()
        return if (e.size < all.size) all.copyOf(e.size.toInt()) else all
    }

    companion object {
        private val MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

        fun hasMagic(bytes: ByteArray) = bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

        fun isCfb(bytes: ByteArray) = bytes.size >= 512 && hasMagic(bytes)

        fun parse(bytes: ByteArray): Cfb = Cfb(ByteBuffer.wrap(bytes))
    }
}
