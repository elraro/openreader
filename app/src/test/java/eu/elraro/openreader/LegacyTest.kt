package eu.elraro.openreader

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Fixtures come from Apache POI's test-data (Apache License 2.0). */
@RunWith(RobolectricTestRunner::class)
class LegacyTest {
    private fun load(name: String) = Cfb.parse(javaClass.getResourceAsStream("/legacy/$name")!!.readBytes())

    @Test
    fun doc() {
        val html = DocConverter.toHtml(load("SampleDoc.doc"), "t")
        assertTrue(html, html.contains("I am a test document"))
        assertTrue(html.contains("It’s Arial Black in 16 point"))
    }

    @Test
    fun xls() {
        val html = XlsConverter.toHtml(load("SampleSS.xls"), "t")
        assertTrue(html, html.contains("Test spreadsheet"))
        assertTrue(html.contains("Sheet Number 2"))
        assertTrue(html.contains("<td class=\"n\">13</td>"))
    }

    @Test
    fun ppt() {
        val html = PptConverter.toHtml(load("basic_test_ppt_file.ppt"), "t")
        assertTrue(html, html.contains("This is a test title"))
        assertTrue(html.contains("This is page two"))
    }
}
