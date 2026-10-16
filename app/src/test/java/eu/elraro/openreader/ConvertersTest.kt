package eu.elraro.openreader

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ConvertersTest {
    private fun zip(vararg parts: Pair<String, String>): OoxmlPackage {
        val f = File.createTempFile("test", ".zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, c) in parts) {
                z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry()
            }
        }
        return OoxmlPackage(f)
    }

    @Test
    fun docx() {
        val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        val pkg = zip(
            "word/document.xml" to """<w:document $w><w:body>
              <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Hi &lt;there&gt;</w:t></w:r></w:p>
              <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r></w:p>
              <w:tbl><w:tr><w:tc><w:p><w:r><w:t>cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>""",
        )
        val html = DocxConverter.toHtml(pkg, "t")
        assertTrue(html, html.contains(">Hi &lt;there&gt;</div>"))
        assertTrue(html.contains("font-weight:bold;\">bold</span>"))
        assertTrue(html.contains(">cell</div></td>"))
    }

    @Test
    fun xlsx() {
        val ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        val pkg = zip(
            "xl/workbook.xml" to """<workbook $ns><sheets><sheet name="Data" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst $ns><si><t>Name</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet $ns><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1"><v>3.5</v></c></row></sheetData></worksheet>""",
        )
        val html = XlsxConverter.toHtml(pkg, "t")
        assertTrue(html, html.contains(">Name</td>"))
        assertTrue(html.contains("class=\"n x0\">3.5</td>"))
        assertTrue(html.contains("Data"))
    }

    @Test
    fun pptx() {
        val ns = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        val pkg = zip(
            "ppt/presentation.xml" to """<p:presentation $ns><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="9144000" cy="5143500"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId2" Target="slides/slide1.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to """<p:sld $ns><p:cSld><p:spTree>
              <p:sp><p:spPr><a:xfrm><a:off x="914400" y="914400"/><a:ext cx="4572000" cy="1000000"/></a:xfrm></p:spPr>
              <p:txBody><a:p><a:r><a:rPr sz="2400"/><a:t>Hello &amp; bye</a:t></a:r></a:p></p:txBody></p:sp>
            </p:spTree></p:cSld></p:sld>""",
        )
        val html = PptxConverter.toHtml(pkg, "t")
        assertTrue(html, html.contains("Hello &amp; bye"))
        assertTrue(html.contains("left:96px"))
    }
}
