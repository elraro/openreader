package eu.elraro.openreader

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Dev helper: converts every file in $OR_DOCS to HTML in $OR_OUT. Does nothing when unset. */
@RunWith(RobolectricTestRunner::class)
class RenderDump {
    @Test
    fun dump() {
        val inDir = System.getenv("OR_DOCS") ?: return
        val outDir = File(System.getenv("OR_OUT") ?: return)
        for (f in File(inDir).listFiles().orEmpty()) {
            val html = runCatching {
                if (Cfb.isCfb(f.readBytes())) {
                    val c = Cfb.parse(f.readBytes())
                    when {
                        c.hasStream("WordDocument") -> DocConverter.toHtml(c, f.name)
                        c.hasStream("PowerPoint Document") -> PptConverter.toHtml(c, f.name)
                        else -> XlsConverter.toHtml(c, f.name)
                    }
                } else OoxmlPackage(f).use {
                    when {
                        it.has("word/document.xml") -> DocxConverter.toHtml(it, f.name)
                        it.has("xl/workbook.xml") -> XlsxConverter.toHtml(it, f.name)
                        else -> PptxConverter.toHtml(it, f.name)
                    }
                }
            }.getOrElse { "<pre>ERROR ${it.stackTraceToString()}</pre>" }
            File(outDir, f.name + ".html").writeText(html.replace(Fonts.BASE_URL, "file:///home/elraro/openreader/app/src/main/assets/"))
        }
    }
}
