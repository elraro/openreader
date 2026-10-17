package eu.elraro.openreader

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class TocTest {
    @Test
    fun tableOfContentsKeepsTitles() {
        val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        val f = File.createTempFile("toc", ".zip")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write("""<w:document $w><w:body>
              <w:sdt><w:sdtPr><w:docPartObj><w:docPartGallery w:val="Table of Contents"/></w:docPartObj></w:sdtPr><w:sdtContent>
              <w:p><w:r><w:t>Contents</w:t></w:r></w:p>
              <w:p><w:pPr><w:pStyle w:val="TOC1"/><w:tabs><w:tab w:val="right" w:leader="dot" w:pos="9016"/></w:tabs></w:pPr>
                <w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> TOC \o "1-3" \h \z \u </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>
                <w:hyperlink w:anchor="_Toc1" w:history="1"><w:r><w:rPr><w:rStyle w:val="Hyperlink"/><w:noProof/></w:rPr><w:t>Introduction</w:t></w:r>
                <w:r><w:rPr><w:noProof/><w:webHidden/></w:rPr><w:tab/></w:r>
                <w:r><w:rPr><w:noProof/><w:webHidden/></w:rPr><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:rPr><w:noProof/><w:webHidden/></w:rPr><w:instrText xml:space="preserve"> PAGEREF _Toc1 \h </w:instrText></w:r><w:r><w:rPr><w:noProof/><w:webHidden/></w:rPr><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:rPr><w:noProof/><w:webHidden/></w:rPr><w:t>1</w:t></w:r><w:r><w:rPr><w:noProof/><w:webHidden/></w:rPr><w:fldChar w:fldCharType="end"/></w:r></w:hyperlink></w:p>
              <w:p><w:pPr><w:pStyle w:val="TOC2"/></w:pPr>
                <w:hyperlink w:anchor="_Toc2"><w:r><w:t>Background</w:t></w:r><w:r><w:tab/></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> PAGEREF _Toc2 \h </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>2</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:hyperlink></w:p>
              <w:p><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>
              </w:sdtContent></w:sdt>
            </w:body></w:document>""".toByteArray())
            z.closeEntry()
        }
        val html = DocxConverter.toHtml(OoxmlPackage(f), "t")
        assertTrue(html, html.contains("Introduction") && html.contains("Background"))
        // TOC page numbers are cached PAGEREF results, not the running page number
        assertTrue(html, !html.contains("class=\"fpg\""))
        assertTrue(html, html.contains(">1</span>") && html.contains("2"))
    }
}
