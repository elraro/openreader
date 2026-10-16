package eu.elraro.openreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewFeaturesTest {
    private fun book(): Workbook {
        val b = Workbook()
        for (r in 0..4) b.put("S", r, 0, Workbook.Cell((r + 1).toDouble(), null))
        b.put("S", 0, 1, Workbook.Cell(null, "SUM(A1:A5)"))
        b.put("S", 1, 1, Workbook.Cell(null, "B1*2+1"))
        b.put("S", 2, 1, Workbook.Cell(null, "IF(B1>10,\"big\",\"small\")&\"!\""))
        b.put("S", 3, 1, Workbook.Cell(null, "1/0"))
        b.put("S", 4, 1, Workbook.Cell(null, "A1+A1", 1, 0)) // shared formula shifted one row: A2+A2
        b.put("S", 5, 1, Workbook.Cell(null, "B6"))          // circular
        return b
    }

    @Test fun formulas() {
        val b = book()
        assertEquals(15.0, b.eval("S", 0, 1))
        assertEquals(31.0, b.eval("S", 1, 1))
        assertEquals("big!", b.eval("S", 2, 1))
        assertEquals("#DIV/0!", b.eval("S", 3, 1).toString())
        assertEquals(4.0, b.eval("S", 4, 1))
        assertTrue(b.eval("S", 5, 1) is XlError)
    }

    @Test fun moreFunctions() {
        val b = book()
        for (r in 0..4) b.put("S", r, 2, Workbook.Cell(if (r % 2 == 0) "x" else "y", null))
        b.put("S", 10, 0, Workbook.Cell(null, "MEDIAN(A1:A5)"))
        b.put("S", 11, 0, Workbook.Cell(null, "INDEX(A1:A5,MATCH(4,A1:A5,0))"))
        b.put("S", 12, 0, Workbook.Cell(null, "SUMIFS(A1:A5,C1:C5,\"x\")"))
        b.put("S", 13, 0, Workbook.Cell(null, "SUMPRODUCT(A1:A5,A1:A5)"))
        b.put("S", 14, 0, Workbook.Cell(null, "TEXTJOIN(\"-\",TRUE,C1:C3)"))
        b.put("S", 15, 0, Workbook.Cell(null, "FOO(1)"))
        assertEquals(3.0, b.eval("S", 10, 0))
        assertEquals(4.0, b.eval("S", 11, 0))
        assertEquals(9.0, b.eval("S", 12, 0)) // rows 1,3,5 -> 1+3+5
        assertEquals(55.0, b.eval("S", 13, 0))
        assertEquals("x-y-x", b.eval("S", 14, 0))
        assertEquals("#NAME?", b.eval("S", 15, 0).toString())
    }

    @Test fun chartSvg() {
        val xml = """<c:chartSpace xmlns:c="c" xmlns:a="a"><c:chart><c:plotArea><c:barChart><c:barDir val="col"/><c:grouping val="clustered"/>
            <c:ser><c:tx><c:strRef><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>Sales</c:v></c:pt></c:strCache></c:strRef></c:tx>
            <c:cat><c:strRef><c:strCache><c:ptCount val="2"/><c:pt idx="0"><c:v>Q1</c:v></c:pt><c:pt idx="1"><c:v>Q2</c:v></c:pt></c:strCache></c:strRef></c:cat>
            <c:val><c:numRef><c:numCache><c:ptCount val="2"/><c:pt idx="0"><c:v>3</c:v></c:pt><c:pt idx="1"><c:v>5</c:v></c:pt></c:numCache></c:numRef></c:val></c:ser>
            </c:barChart></c:plotArea><c:legend><c:legendPos val="b"/></c:legend></c:chart></c:chartSpace>"""
        val svg = Charts.svg(parseXml(xml.byteInputStream()), ColorScheme.DEFAULT, 400.0, 250.0)
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.contains(">Q1<") && svg.contains(">Sales<"))
        assertEquals(2, Regex("<rect [^>]*fill=\"#4472c4\"|<rect [^>]*fill=\"#4472C4\"", RegexOption.IGNORE_CASE).findAll(svg).count { true } - 1)
    }

    @Test fun metafileRejectsGarbage() {
        assertNull(Metafile.toSvgDataUri(ByteArray(100)))
    }
}
