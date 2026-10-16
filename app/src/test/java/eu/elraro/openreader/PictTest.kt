package eu.elraro.openreader

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

@RunWith(RobolectricTestRunner::class)
class PictTest {
    @Test fun decodesPackBitsBitmap() {
        val bytes = javaClass.getResourceAsStream("/legacy/bitmap.pict")!!.readBytes()
        val uri = Pict.toPngDataUri(bytes)
        assertNotNull(uri)
        val png = Base64.decode(uri!!.removePrefix("data:image/png;base64,"), Base64.DEFAULT)
        // IHDR width/height
        fun i32(o: Int) = ((png[o].toInt() and 255) shl 24) or ((png[o + 1].toInt() and 255) shl 16) or ((png[o + 2].toInt() and 255) shl 8) or (png[o + 3].toInt() and 255)
        assertEquals(20, i32(16)); assertEquals(10, i32(20))
        // first IDAT: inflate, first pixel of row 0 is palette entry 0 (red), pixel at x=5 is entry 1 (green)
        val idatLen = i32(33)
        val raw = InflaterInputStream(ByteArrayInputStream(png, 41, idatLen)).readBytes()
        assertEquals(255, raw[1].toInt() and 255); assertEquals(0, raw[2].toInt() and 255)           // x=0 red
        assertEquals(255, raw[1 + 5 * 4 + 1].toInt() and 255)                                      // x=5 green channel
    }
}
