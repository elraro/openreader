package eu.elraro.openreader

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Slide text from PowerPoint 97-2003 (.ppt). Layout, images and formatting are not recovered. */
/** Fallback for files whose edit history can't be resolved: slide text only. */
object PptTextOnly {
    private const val SLIDE = 1006
    private const val SLIDE_LIST = 4080
    private const val SLIDE_PERSIST = 1011
    private const val TEXT_HEADER = 3999
    private const val TEXT_CHARS = 4000
    private const val TEXT_BYTES = 4008

    private class TextBlock(val kind: Int, val text: String)

    fun toHtml(cfb: Cfb, title: String): String {
        val s = cfb.stream("PowerPoint Document") ?: throw IllegalArgumentException("Missing PowerPoint Document stream")
        val b = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN)
        val listSlides = ArrayList<MutableList<TextBlock>>() // from SlideListWithText (placeholders)
        val containerSlides = ArrayList<MutableList<TextBlock>>() // from Slide containers (text boxes)
        walk(s, b, 0, s.size, null, listSlides, containerSlides, false)

        val count = maxOf(listSlides.size, containerSlides.size)
        val body = StringBuilder()
        for (i in 0 until count) {
            body.append("<div class=\"slide\"><div class=\"num\">Slide ${i + 1}</div>")
            val blocks = listSlides.getOrNull(i).orEmpty() + containerSlides.getOrNull(i).orEmpty()
            if (blocks.isEmpty()) body.append("<p><i>(no text)</i></p>")
            for (blk in blocks) {
                val paras = blk.text.split('\r', '\n', '\u000b').filter { it.isNotBlank() }
                val isTitle = blk.kind == 0 || blk.kind == 6
                for (p in paras) {
                    body.append(if (isTitle) "<h2>" else "<p>").append(esc(p)).append(if (isTitle) "</h2>" else "</p>")
                }
            }
            body.append("</div>")
        }
        if (count == 0) body.append("<p>No slides found.</p>")
        val css = ".slide{margin:12px;padding:12px;border:1px solid #bbb;border-radius:4px;background:#fff}.num{font-size:12px;color:#666}h2{font-size:1.3em;margin:.3em 0}p{margin:0 0 .4em}"
        return htmlPage(title, css, body.toString())
    }

    private fun walk(
        s: ByteArray, b: ByteBuffer, start: Int, end: Int,
        current: MutableList<TextBlock>?,
        listSlides: MutableList<MutableList<TextBlock>>,
        containerSlides: MutableList<MutableList<TextBlock>>,
        inSlideList: Boolean,
    ) {
        var p = start
        var cur = current
        var pendingKind = 1
        while (p + 8 <= end) {
            val verInst = b.getShort(p).toInt() and 0xFFFF
            val type = b.getShort(p + 2).toInt() and 0xFFFF
            val len = b.getInt(p + 4)
            val body = p + 8
            if (len < 0 || body + len > end) return
            val container = verInst and 0xF == 0xF
            when {
                container && type == SLIDE -> {
                    val list = ArrayList<TextBlock>().also { containerSlides.add(it) }
                    walk(s, b, body, body + len, list, listSlides, containerSlides, false)
                }
                container && type == SLIDE_LIST -> {
                    // instance 0 = slides, 1 = masters, 2 = notes
                    val instance = verInst shr 4
                    walk(s, b, body, body + len, null, listSlides, containerSlides, instance == 0)
                }
                container -> walk(s, b, body, body + len, cur, listSlides, containerSlides, inSlideList)
                type == SLIDE_PERSIST && inSlideList -> cur = ArrayList<TextBlock>().also { listSlides.add(it) }
                type == TEXT_HEADER && len >= 4 -> pendingKind = b.getInt(body)
                type == TEXT_CHARS && cur != null -> {
                    cur.add(TextBlock(pendingKind, String(s, body, len / 2 * 2, Charsets.UTF_16LE)))
                    pendingKind = 1
                }
                type == TEXT_BYTES && cur != null -> {
                    cur.add(TextBlock(pendingKind, String(s, body, len, charset("windows-1252"))))
                    pendingKind = 1
                }
            }
            p = body + len
        }
    }
}
