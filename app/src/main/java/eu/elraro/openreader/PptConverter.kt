package eu.elraro.openreader

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PowerPoint 97-2003 (.ppt): slides are resolved through the persist directory of the newest edit (so earlier
 * versions of a slide are not shown), then drawn with their shape positions, fills, lines, pictures and formatted text,
 * inheriting placeholder positions and text styles from the slide master.
 */
object PptConverter {
    private const val DOCUMENT = 1000; private const val DOC_ATOM = 1001; private const val MAIN_MASTER = 1016
    private const val SLIDE = 1006; private const val SLIDE_ATOM = 1007; private const val SLIDE_LIST = 4080
    private const val SLIDE_PERSIST = 1011; private const val PPDRAWING = 1036; private const val PPDRAWING_GROUP = 1035
    private const val ENVIRONMENT = 1010; private const val FONT_COLLECTION = 2005; private const val FONT_ENTITY = 4023
    private const val TEXT_HEADER = 3999; private const val TEXT_CHARS = 4000; private const val TEXT_BYTES = 4008
    private const val STYLE_TEXT_PROP = 4001; private const val TEXT_MASTER_STYLE = 4003; private const val COLOR_SCHEME = 2032
    private const val OE_PLACEHOLDER = 3011; private const val OUTLINE_TEXT_REF = 3998
    private const val NOTES = 1008; private const val USER_EDIT = 4085; private const val PERSIST_DIR = 6002

    private class Rec(val inst: Int, val ver: Int, val type: Int, val len: Int, val body: Int) { val end get() = body + len }

    private class PP {
        var bulletFlags: Int? = null; var bulletChar: Int? = null; var bulletFont: Int? = null; var align: Int? = null; var line: Int? = null
        var before: Int? = null; var after: Int? = null; var left: Int? = null; var indent: Int? = null
        fun copy() = PP().also { it.bulletFlags = bulletFlags; it.bulletChar = bulletChar; it.bulletFont = bulletFont; it.align = align; it.line = line; it.before = before; it.after = after; it.left = left; it.indent = indent }
        fun merge(o: PP) { o.bulletFlags?.let { bulletFlags = it }; o.bulletChar?.let { bulletChar = it }; o.bulletFont?.let { bulletFont = it }; o.align?.let { align = it }; o.line?.let { line = it }
            o.before?.let { before = it }; o.after?.let { after = it }; o.left?.let { left = it }; o.indent?.let { indent = it } }
    }
    private class CF {
        var flags: Int? = null; var font: Int? = null; var size: Int? = null; var color: Int? = null; var pos: Int? = null
        fun copy() = CF().also { it.flags = flags; it.font = font; it.size = size; it.color = color; it.pos = pos }
        fun merge(o: CF) { o.flags?.let { flags = it }; o.font?.let { font = it }; o.size?.let { size = it }; o.color?.let { color = it }; o.pos?.let { pos = it } }
    }
    private class Level(val pf: PP, val cf: CF)
    private class TextBlock(val type: Int, val text: String, val style: ByteArray?)

    private class Shape(
        val kind: Int, val spid: Int, var x: Double, var y: Double, var w: Double, var h: Double, val opt: Map<Int, Int>,
        val blocks: List<TextBlock>, val placeholder: Int, val outlineRef: Int, val flipH: Boolean, val flipV: Boolean,
        val hasAnchor: Boolean,
    ) { var extraRot = 0.0 }

    private class Page(val shapes: List<Shape>, val scheme: IntArray?, val background: Int?, val levels: Map<Int, List<Level>>, val bgOpt: Map<Int, Int>?)

    private class Ctx(
        val s: ByteArray, val b: ByteBuffer, val pictures: ByteArray?, val fonts: List<String>, val bse: List<Rec>, val bseBuf: ByteArray,
        val blipCache: HashMap<Int, String?> = HashMap(),
    )

    private fun u16(b: ByteBuffer, o: Int) = b.getShort(o).toInt() and 0xFFFF

    private fun children(b: ByteBuffer, start: Int, end: Int): List<Rec> {
        val out = ArrayList<Rec>()
        var p = start
        while (p + 8 <= end) {
            val vi = u16(b, p); val type = u16(b, p + 2); val len = b.getInt(p + 4)
            if (len < 0 || p + 8 + len.toLong() > end) break
            out.add(Rec(vi shr 4, vi and 0xF, type, len, p + 8))
            p += 8 + len
        }
        return out
    }

    fun toHtml(cfb: Cfb, title: String): String {
        val s = cfb.stream("PowerPoint Document") ?: throw IllegalArgumentException("Missing PowerPoint Document stream")
        return try {
            render(cfb, s, title)
        } catch (e: RuntimeException) {
            PptTextOnly.toHtml(cfb, title)
        }
    }

    private fun render(cfb: Cfb, s: ByteArray, title: String): String {
        val b = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN)
        val cu = cfb.stream("Current User") ?: throw IllegalStateException("no current user")
        val cub = ByteBuffer.wrap(cu).order(ByteOrder.LITTLE_ENDIAN)
        var edit = cub.getInt(16)

        // persist directory: newest edit wins
        val persist = HashMap<Int, Int>()
        var docPersist = -1
        var guard = 0
        var first = true
        while (edit in 0 until s.size - 8 && guard++ < 1000) {
            if (u16(b, edit + 2) != USER_EDIT) throw IllegalStateException("bad edit")
            val body = edit + 8
            val offLast = b.getInt(body + 8); val offDir = b.getInt(body + 12)
            if (first) { docPersist = b.getInt(body + 16); first = false }
            if (offDir in 0 until s.size - 8 && u16(b, offDir + 2) == PERSIST_DIR) {
                val len = b.getInt(offDir + 4)
                var p = offDir + 8
                val end = p + len
                while (p + 4 <= end) {
                    val v = b.getInt(p); p += 4
                    val id = v and 0xFFFFF; val cnt = v ushr 20
                    for (k in 0 until cnt) { if (p + 4 > end) break; persist.putIfAbsent(id + k, b.getInt(p)); p += 4 }
                }
            }
            if (offLast <= 0 || offLast == edit) break
            edit = offLast
        }
        val docOff = persist[docPersist] ?: throw IllegalStateException("no document")
        val doc = Rec(0, 0xF, DOCUMENT, b.getInt(docOff + 4), docOff + 8)

        var sw = 5760; var sh = 4320
        val fonts = ArrayList<String>()
        val slides = ArrayList<Pair<Int, Int>>() // persistId, slideId
        val masters = HashMap<Int, Int>()        // slideId -> persistId
        var bseBuf = s; val bse = ArrayList<Rec>()
        val envLevels = HashMap<Int, List<Level>>()
        for (r in children(b, doc.body, doc.end)) when {
            r.type == DOC_ATOM && r.len >= 8 -> { sw = b.getInt(r.body); sh = b.getInt(r.body + 4) }
            r.type == ENVIRONMENT -> for (e in children(b, r.body, r.end)) {
                if (e.type == FONT_COLLECTION) for (f in children(b, e.body, e.end)) if (f.type == FONT_ENTITY)
                    fonts.add(String(s, f.body, minOf(64, f.len), Charsets.UTF_16LE).substringBefore('\u0000'))
                if (e.type == TEXT_MASTER_STYLE) envLevels[e.inst] = masterStyle(b, e)
            }
            r.type == SLIDE_LIST -> {
                val list = children(b, r.body, r.end)
                for (p in list) if (p.type == SLIDE_PERSIST && p.len >= 16) {
                    val pid = b.getInt(p.body); val sid = b.getInt(p.body + 12)
                    if (r.inst == 0) slides.add(pid to sid) else if (r.inst == 1) masters[sid] = pid
                }
            }
            r.type == PPDRAWING_GROUP -> collectBse(b, r, bse)
        }
        val ctx = Ctx(s, b, cfb.stream("Pictures"), fonts, bse, s)
        if (slides.isEmpty()) throw IllegalStateException("no slides")

        // text of placeholder shapes lives in the slide list, in slide order; the notes list is keyed by notes slide id
        val slideTexts = ArrayList<List<TextBlock>>()
        val notesBySid = HashMap<Int, List<TextBlock>>()
        val notesPersist = HashMap<Int, Int>()
        for (r in children(b, doc.body, doc.end)) if (r.type == SLIDE_LIST && (r.inst == 0 || r.inst == 2)) {
            var type = 1
            var cur: ArrayList<TextBlock>? = null
            var lastIdx = -1
            for (c in children(b, r.body, r.end)) when (c.type) {
                SLIDE_PERSIST -> {
                    cur = ArrayList()
                    if (r.inst == 0) slideTexts.add(cur) else if (c.len >= 16) { notesBySid[b.getInt(c.body + 12)] = cur; notesPersist[b.getInt(c.body + 12)] = b.getInt(c.body) }
                    lastIdx = -1
                }
                TEXT_HEADER -> type = b.getInt(c.body)
                TEXT_CHARS -> { cur?.add(TextBlock(type, String(s, c.body, c.len / 2 * 2, Charsets.UTF_16LE), null)); lastIdx = (cur?.size ?: 0) - 1 }
                TEXT_BYTES -> { cur?.add(TextBlock(type, String(s, c.body, c.len, charset("windows-1252")), null)); lastIdx = (cur?.size ?: 0) - 1 }
                STYLE_TEXT_PROP -> if (lastIdx >= 0) cur?.let { it[lastIdx] = TextBlock(it[lastIdx].type, it[lastIdx].text, s.copyOfRange(c.body, c.end)) }
            }
        }

        val pageCache = HashMap<Int, Page?>()
        fun page(persistId: Int?): Page? = persistId?.let { id -> pageCache.getOrPut(id) {
            val off = persist[id] ?: return@getOrPut null
            val rec = Rec(0, 0xF, 0, b.getInt(off + 4), off + 8)
            readPage(ctx, rec, if (u16(b, off + 2) == SLIDE) slideTexts.getOrNull(slides.indexOfFirst { it.first == id }).orEmpty() else emptyList())
        } }

        val wPx = sw / 6.0; val hPx = sh / 6.0
        val body = StringBuilder()
        slides.forEachIndexed { i, (pid, _) ->
            val off = persist[pid] ?: return@forEachIndexed
            if (u16(b, off + 2) != SLIDE) return@forEachIndexed
            val slide = page(pid) ?: return@forEachIndexed
            val slideRec = Rec(0, 0xF, SLIDE, b.getInt(off + 4), off + 8)
            val slideAtom = children(b, slideRec.body, slideRec.end).firstOrNull { it.type == SLIDE_ATOM }
            val masterSid = slideAtom?.let { if (it.len >= 16) b.getInt(it.body + 12) else null }
            val notesSid = slideAtom?.let { if (it.len >= 20) b.getInt(it.body + 16) else null }
            val master = page(masters[masterSid])
            val scheme = slide.scheme ?: master?.scheme
            body.append("<div class=\"num\">Slide ${i + 1}</div>")
            val bg = slide.background?.let { color(it, scheme) } ?: master?.background?.let { color(it, scheme) } ?: "#ffffff"
            body.append("<div class=\"slide\" style=\"width:${f(wPx)}px;height:${f(hPx)}px;background:$bg\">")
            val levels = HashMap<Int, List<Level>>(envLevels); master?.let { levels.putAll(it.levels) }
            master?.shapes?.filter { it.placeholder == 0 && it.hasAnchor }?.forEach { shape(it, null, ctx, scheme, levels, body) }
            for (sh0 in slide.shapes) {
                val m = if (sh0.placeholder != 0 && !sh0.hasAnchor) masterFor(master, sh0.placeholder) else null
                shape(sh0, m, ctx, scheme, levels, body)
            }
            body.append("</div>")
            var notes = notesSid?.let { notesBySid[it] }?.filter { it.type == 2 }?.joinToString(" ") { it.text.replace('\r', ' ').replace('\u000b', ' ') }?.trim()
            if (notes.isNullOrEmpty()) {
                // notes written as text boxes of the notes page itself
                val noff = notesSid?.let { notesPersist[it] }?.let { persist[it] }
                if (noff != null && u16(b, noff + 2) == NOTES) {
                    val nrec = Rec(0, 0xF, NOTES, b.getInt(noff + 4), noff + 8)
                    notes = readPage(ctx, nrec, emptyList()).shapes.filter { true }
                        .flatMap { it.blocks }.filter { it.type == 2 && it.text.any { c -> c.isLetterOrDigit() } }.joinToString(" ") { it.text.replace('\r', ' ').replace('\u000b', ' ') }.trim()
                }
            }
            if (!notes.isNullOrEmpty()) body.append("<div class=\"notes\"><b>Notes:</b> ${esc(notes)}</div>")
        }
        val zoom = f(minOf(1.0, 780.0 / wPx))
        val css = """
body{background:#d9d9d9;padding:8px 0}
.num{font-size:12px;color:#555;margin:6px auto 2px;width:${f(wPx)}px}
.slide{position:relative;margin:0 auto 6px;overflow:hidden;box-shadow:0 1px 4px rgba(0,0,0,.4);page-break-inside:avoid;white-space:normal;font-family:'Arial',sans-serif}
.sh{position:absolute;box-sizing:border-box;display:flex;flex-direction:column}
.sh div.pp{white-space:pre-wrap;overflow-wrap:break-word}
.notes{font-size:12px;color:#444;margin:0 auto 10px;width:${f(wPx)}px;background:#fffbe6;padding:4px;box-sizing:border-box}
@media print{body{background:#fff;padding:0}.notes{zoom:$zoom}.slide{box-shadow:none;margin:0 0 8px 0;zoom:$zoom}.num{page-break-before:always;zoom:$zoom}}
"""
        return htmlPage(title, css, body.toString(), "width=${Math.round(wPx) + 16}")
    }

    /** Outlines (percent coordinates) of common auto shapes, keyed by MSOSPT shape type. */
    private val POLYGONS = mapOf(
        4 to "50% 0,100% 50%,50% 100%,0 50%", 5 to "50% 0,100% 100%,0 100%", 6 to "0 0,100% 100%,0 100%",
        7 to "25% 0,100% 0,75% 100%,0 100%", 8 to "20% 0,80% 0,100% 100%,0 100%",
        9 to "25% 0,75% 0,100% 50%,75% 100%,25% 100%,0 50%",
        10 to "29% 0,71% 0,100% 29%,100% 71%,71% 100%,29% 100%,0 71%,0 29%",
        11 to "33% 0,67% 0,67% 33%,100% 33%,100% 67%,67% 67%,67% 100%,33% 100%,33% 67%,0 67%,0 33%,33% 33%",
        12 to "50% 0,61% 35%,98% 35%,68% 57%,79% 91%,50% 70%,21% 91%,32% 57%,2% 35%,39% 35%",
        13 to "0 25%,60% 25%,60% 0,100% 50%,60% 100%,60% 75%,0 75%", 15 to "0 0,75% 0,100% 50%,75% 100%,0 100%",
        56 to "50% 0,100% 38%,81% 100%,19% 100%,0 38%", 66 to "100% 25%,40% 25%,40% 0,0 50%,40% 100%,40% 75%,100% 75%",
        67 to "25% 0,75% 0,75% 60%,100% 60%,50% 100%,0 60%,25% 60%", 68 to "50% 0,100% 40%,75% 40%,75% 100%,25% 100%,25% 40%,0 40%",
        69 to "0 50%,25% 0,25% 30%,75% 30%,75% 0,100% 50%,75% 100%,75% 70%,25% 70%,25% 100%",
        55 to "0 0,75% 0,100% 50%,75% 100%,0 100%,25% 50%", 110 to "50% 0,100% 50%,50% 100%,0 50%", 127 to "50% 0,100% 50%,50% 100%,0 50%",
        111 to "20% 0,100% 0,80% 100%,0 100%", 117 to "25% 0,75% 0,100% 50%,75% 100%,25% 100%,0 50%",
        118 to "0 20%,100% 0,100% 100%,0 100%", 119 to "0 0,100% 0,80% 100%,20% 100%", 126 to "0 0,100% 0,0 100%,100% 100%",
        128 to "50% 0,100% 100%,0 100%", 129 to "0 0,100% 0,50% 100%", 65 to "0 0,100% 0,100% 80%,80% 100%,0 100%",
        73 to "40% 0,10% 55%,45% 55%,30% 100%,90% 40%,55% 40%,75% 0",
    )

    private fun star(n: Int, inner: Double): String = (0 until n * 2).joinToString(",") { i ->
        val a = -Math.PI / 2 + Math.PI * i / n
        val r = if (i % 2 == 0) 50.0 else 50.0 * inner
        "${f(50 + r * Math.cos(a))}% ${f(50 + r * Math.sin(a))}%"
    }

    /** Polygons computed from a formula: stars and the heart. */
    private fun generated(kind: Int): String? = when (kind) {
        187 -> star(4, 0.38); 58 -> star(8, 0.72); 59 -> star(16, 0.85); 92 -> star(24, 0.88); 60 -> star(32, 0.9); 18 -> star(10, 0.8)
        74 -> (0 until 40).joinToString(",") { i ->
            val t = 2 * Math.PI * i / 40
            val x = 16 * Math.pow(Math.sin(t), 3.0); val y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t)
            "${f(50 + x * 3.0)}% ${f(45 - y * 2.8)}%"
        }
        else -> null
    }

    private fun masterFor(master: Page?, ph: Int): Shape? {
        master ?: return null
        val want = when (ph) {
            0x0D, 0x10, 0x12 -> listOf(1, 3)       // title, centered title, vertical title
            0x0E, 0x11, 0x0F, 0x13, 0x14 -> listOf(2, 4) // body, subtitle, centered body, vertical body, object
            0x07 -> listOf(7); 0x08 -> listOf(8); 0x09 -> listOf(9); 0x0A -> listOf(10)
            else -> listOf(2)
        }
        for (w in want) master.shapes.firstOrNull { it.placeholder == w }?.let { return it }
        return null
    }

    // ------------------------------------------------------------------ containers

    private fun collectBse(b: ByteBuffer, r: Rec, out: MutableList<Rec>) {
        for (c in children(b, r.body, r.end)) {
            if (c.ver == 0xF) collectBse(b, c, out)
            else if (c.type == 0xF007) out.add(c)
        }
    }

    private fun readPage(ctx: Ctx, container: Rec, listTexts: List<TextBlock>): Page {
        val b = ctx.b
        var scheme: IntArray? = null
        val levels = HashMap<Int, List<Level>>()
        var shapes: List<Shape> = emptyList()
        var background: Int? = null; var bgOpt: Map<Int, Int>? = null
        for (c in children(b, container.body, container.end)) when (c.type) {
            COLOR_SCHEME -> if (c.len >= 32) scheme = IntArray(8) { ctx.b.getInt(c.body + it * 4) and 0xFFFFFF }
            TEXT_MASTER_STYLE -> levels[c.inst] = masterStyle(b, c)
            PPDRAWING -> {
                for (dg in children(b, c.body, c.end)) if (dg.type == 0xF002) for (e in children(b, dg.body, dg.end)) {
                    if (e.type == 0xF003) shapes = readGroup(ctx, e, listTexts, null)
                    else if (e.type == 0xF004) { // background shape
                        for (p in children(b, e.body, e.end)) if (p.type == 0xF00B) {
                            val o = readOpt(b, p)
                            bgOpt = o; o[0x0181]?.let { background = it }
                        }
                    }
                }
            }
        }
        return Page(shapes, scheme, background, levels, bgOpt)
    }

    /** group mapping: child coordinate space (cl, ct, cw, ch) -> placed frame (x, y, w, h) in master units, with rotation in degrees */
    private class Map2(val cl: Double, val ct: Double, val cw: Double, val ch: Double, val x: Double, val y: Double, val w: Double, val h: Double, val rot: Double = 0.0) {
        fun mx(v: Double) = if (cw == 0.0) v else x + (v - cl) * w / cw
        fun my(v: Double) = if (ch == 0.0) v else y + (v - ct) * h / ch
        fun sw(v: Double) = if (cw == 0.0) v else v * w / cw
        fun sh(v: Double) = if (ch == 0.0) v else v * h / ch
    }

    /** Places a frame given in a group's child space on the slide: [x, y, w, h, rotation added by enclosing groups]. */
    private fun place(map: Map2?, x: Double, y: Double, w: Double, h: Double): DoubleArray {
        if (map == null) return doubleArrayOf(x, y, w, h, 0.0)
        var nx = map.mx(x); var ny = map.my(y); val nw = map.sw(w); val nh = map.sh(h)
        if (map.rot != 0.0) {
            val gx = map.x + map.w / 2; val gy = map.y + map.h / 2
            val cx = nx + nw / 2 - gx; val cy = ny + nh / 2 - gy
            val rad = Math.toRadians(map.rot)
            val rx = cx * Math.cos(rad) - cy * Math.sin(rad); val ry = cx * Math.sin(rad) + cy * Math.cos(rad)
            nx = gx + rx - nw / 2; ny = gy + ry - nh / 2
        }
        return doubleArrayOf(nx, ny, nw, nh, map.rot)
    }

    private fun anchorOf(ctx: Ctx, parts: List<Rec>): DoubleArray? { // x, y, w, h, isChildAnchor(1/0)
        val b = ctx.b
        parts.firstOrNull { it.type == 0xF010 }?.let { a ->
            if (a.len == 8) { val y = b.getShort(a.body).toDouble(); val x = b.getShort(a.body + 2).toDouble(); return doubleArrayOf(x, y, b.getShort(a.body + 4) - x, b.getShort(a.body + 6) - y, 0.0) }
            if (a.len >= 16) { val y = b.getInt(a.body).toDouble(); val x = b.getInt(a.body + 4).toDouble(); return doubleArrayOf(x, y, b.getInt(a.body + 8) - x, b.getInt(a.body + 12) - y, 0.0) }
        }
        parts.firstOrNull { it.type == 0xF00F }?.let { a ->
            if (a.len >= 16) { val x = b.getInt(a.body).toDouble(); val y = b.getInt(a.body + 4).toDouble(); return doubleArrayOf(x, y, b.getInt(a.body + 8) - x, b.getInt(a.body + 12) - y, 1.0) }
        }
        return null
    }

    private fun readGroup(ctx: Ctx, spgr: Rec, listTexts: List<TextBlock>, map: Map2?, used: MutableSet<Int> = HashSet()): List<Shape> {
        val b = ctx.b
        val out = ArrayList<Shape>()
        for (sp in children(b, spgr.body, spgr.end)) {
            if (sp.type == 0xF003) {
                // nested group: its first shape describes the frame and the child coordinate space
                val desc = children(b, sp.body, sp.end).firstOrNull { it.type == 0xF004 }?.let { children(b, it.body, it.end) }.orEmpty()
                val coord = desc.firstOrNull { it.type == 0xF009 }
                val fr = anchorOf(ctx, desc)
                if (coord != null && fr != null) {
                    val pl = place(map, fr[0], fr[1], fr[2], fr[3])
                    val ownRot = desc.firstOrNull { it.type == 0xF00B }?.let { readOpt(b, it)[0x0004] }?.let { it / 65536.0 } ?: 0.0
                    val nm = Map2(b.getInt(coord.body).toDouble(), b.getInt(coord.body + 4).toDouble(),
                        (b.getInt(coord.body + 8) - b.getInt(coord.body)).toDouble(), (b.getInt(coord.body + 12) - b.getInt(coord.body + 4)).toDouble(),
                        pl[0], pl[1], pl[2], pl[3], pl[4] + ownRot)
                    out.addAll(readGroup(ctx, sp, listTexts, nm, used))
                } else out.addAll(readGroup(ctx, sp, listTexts, map, used))
                continue
            }
            if (sp.type != 0xF004) continue
            val parts = children(b, sp.body, sp.end)
            if (parts.any { it.type == 0xF009 }) continue // group descriptor, not a shape
            val sprec = parts.firstOrNull { it.type == 0xF00A }
            val spid = sprec?.let { b.getInt(it.body) } ?: 0
            val flags = sprec?.let { b.getInt(it.body + 4) } ?: 0
            val kind = sprec?.inst ?: 0
            val opt = parts.firstOrNull { it.type == 0xF00B }?.let { readOpt(b, it) } ?: emptyMap()
            val fr = anchorOf(ctx, parts)
            var x = 0.0; var y = 0.0; var w = 0.0; var h = 0.0; val anchored = fr != null; var extraRot = 0.0
            if (fr != null) {
                if (map != null) { val pl = place(map, fr[0], fr[1], fr[2], fr[3]); x = pl[0]; y = pl[1]; w = pl[2]; h = pl[3]; extraRot = pl[4] }
                else { x = fr[0]; y = fr[1]; w = fr[2]; h = fr[3] }
            }
            // text: placeholders point into the slide list, text boxes carry their own
            var placeholder = 0; var outline = -1
            parts.firstOrNull { it.type == 0xF011 }?.let { cd ->
                for (r in children(b, cd.body, cd.end)) {
                    if (r.type == OE_PLACEHOLDER && r.len >= 6) placeholder = b.get(r.body + 4).toInt() and 0xFF
                    if (r.type == OUTLINE_TEXT_REF && r.len >= 4) outline = b.getInt(r.body)
                }
            }
            val blocks = ArrayList<TextBlock>()
            if (outline >= 0 && outline < listTexts.size) { blocks.add(listTexts[outline]); used.add(outline) }
            else if (placeholder != 0) {
                // no explicit link: take the next unused slide-list text of the matching kind
                val types = when (placeholder) { 0x0D, 0x10, 0x12 -> listOf(0, 6); 0x0E, 0x11, 0x0F, 0x13, 0x14 -> listOf(1, 5, 7, 8); else -> emptyList() }
                val k = listTexts.indices.firstOrNull { it !in used && listTexts[it].type in types }
                if (k != null) { used.add(k); blocks.add(listTexts[k]) }
            }
            parts.firstOrNull { it.type == 0xF00D }?.let { tb ->
                var type = 4; var cur: TextBlock? = null
                for (r in children(b, tb.body, tb.end)) when (r.type) {
                    TEXT_HEADER -> type = b.getInt(r.body)
                    TEXT_CHARS -> { cur = TextBlock(type, String(ctx.s, r.body, r.len / 2 * 2, Charsets.UTF_16LE), null); blocks.add(cur) }
                    TEXT_BYTES -> { cur = TextBlock(type, String(ctx.s, r.body, r.len, charset("windows-1252")), null); blocks.add(cur) }
                    STYLE_TEXT_PROP -> if (cur != null) { blocks[blocks.size - 1] = TextBlock(cur.type, cur.text, ctx.s.copyOfRange(r.body, r.end)) }
                }
            }
            out.add(Shape(kind, spid, x, y, w, h, opt, blocks, placeholder, outline, flags and 0x40 != 0, flags and 0x80 != 0, anchored).also { it.extraRot = extraRot })
        }
        return out
    }

    private fun readOpt(b: ByteBuffer, r: Rec): Map<Int, Int> {
        val m = HashMap<Int, Int>()
        for (i in 0 until r.inst) {
            val o = r.body + i * 6
            if (o + 6 > r.end) break
            val id = u16(b, o) and 0x3FFF
            m[id] = b.getInt(o + 2)
        }
        return m
    }

    private fun masterStyle(b: ByteBuffer, r: Rec): List<Level> {
        val out = ArrayList<Level>()
        try {
            var p = r.body
            val n = u16(b, p); p += 2
            for (i in 0 until minOf(n, 5)) {
                if (r.inst >= 5) p += 2
                val pmask = b.getInt(p); p += 4
                val pf = PP(); p = readPara(b, p, pmask, pf)
                val cmask = b.getInt(p); p += 4
                val cf = CF(); p = readChar(b, p, cmask, cf)
                out.add(Level(pf, cf))
            }
        } catch (e: IndexOutOfBoundsException) {}
        return out
    }

    private fun readPara(b: ByteBuffer, start: Int, mask: Int, pf: PP): Int {
        var p = start
        if (mask and 0xF != 0) { pf.bulletFlags = u16(b, p); p += 2 }
        if (mask and 0x80 != 0) { pf.bulletChar = u16(b, p); p += 2 }
        if (mask and 0x10 != 0) { pf.bulletFont = u16(b, p); p += 2 }
        if (mask and 0x40 != 0) p += 2
        if (mask and 0x20 != 0) p += 4
        if (mask and 0x800 != 0) { pf.align = u16(b, p); p += 2 }
        if (mask and 0x1000 != 0) { pf.line = b.getShort(p).toInt(); p += 2 }
        if (mask and 0x2000 != 0) { pf.before = b.getShort(p).toInt(); p += 2 }
        if (mask and 0x4000 != 0) { pf.after = b.getShort(p).toInt(); p += 2 }
        if (mask and 0x100 != 0) { pf.left = u16(b, p); p += 2 }
        if (mask and 0x400 != 0) { pf.indent = u16(b, p); p += 2 }
        if (mask and 0x8000 != 0) p += 2
        if (mask and 0x100000 != 0) { val n = u16(b, p); p += 2 + n * 4 }
        if (mask and 0x10000 != 0) p += 2
        if (mask and 0xE0000 != 0) p += 2
        if (mask and 0x200000 != 0) p += 2
        return p
    }

    private fun readChar(b: ByteBuffer, start: Int, mask: Int, cf: CF): Int {
        var p = start
        if (mask and 0xFFFF != 0) { cf.flags = u16(b, p); p += 2 }
        if (mask and 0x10000 != 0) { cf.font = u16(b, p); p += 2 }
        if (mask and 0x200000 != 0) p += 2
        if (mask and 0x400000 != 0) p += 2
        if (mask and 0x800000 != 0) p += 2
        if (mask and 0x20000 != 0) { cf.size = u16(b, p); p += 2 }
        if (mask and 0x40000 != 0) { cf.color = b.getInt(p); p += 4 }
        if (mask and 0x80000 != 0) { cf.pos = b.getShort(p).toInt(); p += 2 }
        return p
    }

    // ------------------------------------------------------------------ drawing

    private fun f(d: Double) = String.format(java.util.Locale.US, "%.2f", d).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    private fun u(units: Double) = units / 6.0 // master units (1/576 inch) -> CSS px

    /** MSOCLR / text colour value to CSS; scheme-indexed values use the page's colour scheme. */
    private fun color(v: Int, scheme: IntArray?): String? {
        val flags = v ushr 24
        if (flags and 0x08 != 0 || (flags != 0 && flags != 0xFE && flags and 0x1F == 0x08)) {
            val c = scheme?.getOrNull(v and 0xFF) ?: return null
            return "#%02x%02x%02x".format(c and 255, (c shr 8) and 255, (c shr 16) and 255)
        }
        if (flags == 0xFE || flags == 0 || flags and 0x01 == 0) return "#%02x%02x%02x".format(v and 255, (v shr 8) and 255, (v shr 16) and 255)
        return null
    }

    /** Colour of a text run: the top byte is a colour-scheme index, or 0xFE when the RGB in the low bytes is meant. */
    private fun textColor(v: Int, scheme: IntArray?): String? {
        val top = v ushr 24
        val c = when {
            top == 0xFE -> v and 0xFFFFFF
            top in 0..7 -> scheme?.getOrNull(top) ?: return null
            else -> return null
        }
        return "#%02x%02x%02x".format(c and 255, (c shr 8) and 255, (c shr 16) and 255)
    }

    private fun blipUri(ctx: Ctx, pib: Int): String? = ctx.blipCache.getOrPut(pib) {
        val bse = ctx.bse.getOrNull(pib - 1) ?: return@getOrPut null
        val b = ctx.b
        if (bse.len > 36 + 8) OfficeArt.firstBlip(ctx.s, bse.body + 36, bse.end)?.dataUri?.let { return@getOrPut it }
        val fo = b.getInt(bse.body + 28)
        val pics = ctx.pictures ?: return@getOrPut null
        if (fo < 0 || fo >= pics.size) return@getOrPut null
        OfficeArt.firstBlip(pics, fo, pics.size)?.dataUri
    }

    private fun shape(sh: Shape, inherit: Shape?, ctx: Ctx, scheme: IntArray?, levels: Map<Int, List<Level>>, out: StringBuilder) {
        val a = if (sh.hasAnchor) sh else inherit ?: return
        if (a.w <= 0 && a.h <= 0) return
        val opt = HashMap<Int, Int>(); inherit?.let { opt.putAll(it.opt) }; opt.putAll(sh.opt)
        val x = u(a.x); val y = u(a.y); val w = u(a.w); val h = u(a.h)
        val css = StringBuilder("left:${f(x)}px;top:${f(y)}px;width:${f(w)}px;height:${f(h)}px;")
        val tr = ArrayList<String>()
        val rot = (opt[0x0004] ?: 0) / 65536.0 + sh.extraRot
        if (rot != 0.0) tr.add("rotate(${f(rot)}deg)")
        if (sh.flipH) tr.add("scaleX(-1)"); if (sh.flipV) tr.add("scaleY(-1)")
        if (tr.isNotEmpty()) css.append("transform:${tr.joinToString(" ")};")
        val fillFlags = opt[0x01BF]; val lineFlags = opt[0x01FF]
        val filled = if (fillFlags != null && fillFlags and 0x100000 != 0) fillFlags and 0x10 != 0 else opt.containsKey(0x0181)
        val lined = if (lineFlags != null && lineFlags and 0x80000 != 0) lineFlags and 0x8 != 0 else opt.containsKey(0x01C0)
        val pib = opt[0x0104]
        if (sh.kind == 20 || (sh.kind == 0 && sh.blocks.isEmpty() && pib == null && lined && !filled && (a.w == 0.0 || a.h == 0.0))) {
            val lc = opt[0x01C0]?.let { color(it, scheme) } ?: "#000000"
            val lw = maxOf(1.0, (opt[0x01CB] ?: 9525) / 9525.0)
            val x1 = if (sh.flipH) w else 0.0; val x2 = if (sh.flipH) 0.0 else w
            val y1 = if (sh.flipV) h else 0.0; val y2 = if (sh.flipV) 0.0 else h
            out.append("<svg style=\"position:absolute;left:${f(x - lw)}px;top:${f(y - lw)}px;overflow:visible\" width=\"${f(w + 2 * lw)}\" height=\"${f(h + 2 * lw)}\"><line x1=\"${f(x1 + lw)}\" y1=\"${f(y1 + lw)}\" x2=\"${f(x2 + lw)}\" y2=\"${f(y2 + lw)}\" stroke=\"$lc\" stroke-width=\"${f(lw)}\"/></svg>")
            return
        }
        if (filled) css.append("background:${opt[0x0181]?.let { color(it, scheme) } ?: "#ffffff"};")
        if (lined) {
            val lc = opt[0x01C0]?.let { color(it, scheme) } ?: "#000000"
            css.append("border:${f(maxOf(1.0, (opt[0x01CB] ?: 9525) / 9525.0))}px solid $lc;")
        }
        (POLYGONS[sh.kind] ?: generated(sh.kind))?.let { css.append("clip-path:polygon($it);") }
        if (sh.kind == 23) css.append("-webkit-mask-image:radial-gradient(closest-side,transparent 52%,#000 53%);")
        if (sh.kind == 22) css.append("border-radius:50% / 12%;")
        if (sh.kind == 116 || sh.kind == 176 || sh.kind == 62) css.append("border-radius:${f(minOf(w, h) * (if (sh.kind == 116) 0.5 else 0.17))}px;")
        if (sh.kind == 63 || sh.kind == 120 || sh.kind == 124 || sh.kind == 125) css.append("border-radius:50%;")
        when (sh.kind) { 3 -> css.append("border-radius:50%;"); 2 -> css.append("border-radius:${f(minOf(w, h) * 0.17)}px;") }
        val ml = (opt[0x0081] ?: 91440) / 9525.0; val mt = (opt[0x0082] ?: 45720) / 9525.0
        val mr = (opt[0x0083] ?: 91440) / 9525.0; val mb = (opt[0x0084] ?: 45720) / 9525.0
        css.append("padding:${f(mt)}px ${f(mr)}px ${f(mb)}px ${f(ml)}px;")
        when (opt[0x0087] ?: 0) { 1, 4 -> css.append("justify-content:center;"); 2, 5 -> css.append("justify-content:flex-end;") }
        val inner = StringBuilder()
        if (pib != null) {
            val uri = blipUri(ctx, pib)
            if (uri != null) inner.append("<img src=\"$uri\" style=\"position:absolute;left:0;top:0;width:100%;height:100%\">")
        }
        for (blk in sh.blocks) text(blk, ctx, scheme, levels, inner)
        out.append("<div class=\"sh\" style=\"$css\">$inner</div>")
    }

    private fun text(blk: TextBlock, ctx: Ctx, scheme: IntArray?, levels: Map<Int, List<Level>>, out: StringBuilder) {
        val text = blk.text
        val master = when (blk.type) { 0, 6 -> 0; 1, 5, 7, 8 -> 1; 2 -> 2; else -> 4 }
        val lv = levels[master].orEmpty()
        // style runs
        class PRun(val start: Int, val end: Int, val level: Int, val pf: PP)
        class CRun(val start: Int, val end: Int, val cf: CF)
        val pruns = ArrayList<PRun>(); val cruns = ArrayList<CRun>()
        blk.style?.let { st ->
            val b = ByteBuffer.wrap(st).order(ByteOrder.LITTLE_ENDIAN)
            try {
                var p = 0; var pos = 0
                while (pos < text.length + 1 && p + 10 <= st.size) {
                    val cnt = b.getInt(p); val ind = u16(b, p + 4); val mask = b.getInt(p + 6)
                    val pf = PP(); p = readPara(b, p + 10, mask, pf)
                    pruns.add(PRun(pos, pos + cnt, ind, pf)); pos += cnt
                }
                pos = 0
                while (pos < text.length + 1 && p + 8 <= st.size) {
                    val cnt = b.getInt(p); val mask = b.getInt(p + 4)
                    val cf = CF(); p = readChar(b, p + 8, mask, cf)
                    cruns.add(CRun(pos, pos + cnt, cf)); pos += cnt
                }
            } catch (e: IndexOutOfBoundsException) {}
        }
        var ps = 0
        while (ps <= text.length) {
            var pe = text.indexOf('\r', ps); if (pe < 0) pe = text.length
            if (ps == text.length && ps > 0) break
            val prun = pruns.firstOrNull { ps >= it.start && ps < it.end }
            val level = prun?.level ?: 0
            val pf = lv.getOrNull(level)?.pf?.copy() ?: PP()
            prun?.let { pf.merge(it.pf) }
            val base = lv.getOrNull(level)?.cf?.copy() ?: CF()
            val sizePx = ((base.size ?: 18) * 96 / 72.0)
            val pcss = StringBuilder()
            when (pf.align) { 1 -> pcss.append("text-align:center;"); 2 -> pcss.append("text-align:right;"); 3 -> pcss.append("text-align:justify;") }
            val left = u((pf.left ?: 0).toDouble()); val ind = u((pf.indent ?: 0).toDouble())
            if (left > 0) pcss.append("margin-left:${f(left)}px;")
            if (pf.line != null && pf.line != 0) pcss.append(if (pf.line!! > 0) "line-height:${f(pf.line!! / 100.0)};" else "line-height:${f(u(-pf.line!!.toDouble()))}px;")
            pf.before?.let { if (it > 0) pcss.append("margin-top:${f(sizePx * it / 100.0)}px;") else if (it < 0) pcss.append("margin-top:${f(u(-it.toDouble()))}px;") }
            pf.after?.let { if (it > 0) pcss.append("margin-bottom:${f(sizePx * it / 100.0)}px;") else if (it < 0) pcss.append("margin-bottom:${f(u(-it.toDouble()))}px;") }
            val bullet = (pf.bulletFlags ?: 0) and 1 != 0
            val sb = StringBuilder()
            if (bullet) {
                val symbolic = pf.bulletFont?.let { ctx.fonts.getOrNull(it) }?.let { Regex("wingdings|symbol|webdings", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true
                val code = (pf.bulletChar ?: 0x2022)
                val ch = if (symbolic) when (code and 0xFF) { 0xD8 -> '➢'; 0xA7, 0x6E, 0x71 -> '▪'; 0x76 -> '❖'; 0xFC -> '✓'; 0x77 -> '◆'; 0xB7 -> '•'; else -> '•' }
                         else if (code in 0x20..0xFFFF) code.toChar() else '•'
                pcss.append("padding-left:${f(maxOf(ind, 0.0))}px;text-indent:${f(-maxOf(ind, 0.0))}px;")
                sb.append("<span style=\"display:inline-block;min-width:${f(maxOf(ind, 0.0))}px;text-indent:0\">${esc(ch.toString())}</span>")
            } else if (ind != 0.0) pcss.append("text-indent:${f(ind - left)}px;")
            // character runs inside the paragraph
            var cs = ps
            var any = false
            while (cs < pe) {
                val crun = cruns.firstOrNull { cs >= it.start && cs < it.end }
                var ce = minOf(pe, crun?.end ?: pe)
                if (ce <= cs) ce = pe
                val cf = base.copy(); crun?.let { cf.merge(it.cf) }
                val seg = text.substring(cs, ce)
                val st = StringBuilder()
                cf.size?.let { st.append("font-size:${f(it * 96 / 72.0)}px;") }
                cf.font?.let { fi -> ctx.fonts.getOrNull(fi)?.takeIf { it.isNotEmpty() }?.let { st.append("font-family:'${it.replace("'", "")}',sans-serif;") } }
                cf.flags?.let { fl ->
                    if (fl and 1 != 0) st.append("font-weight:bold;")
                    if (fl and 2 != 0) st.append("font-style:italic;")
                    if (fl and 4 != 0) st.append("text-decoration:underline;")
                }
                cf.color?.let { c -> textColor(c, scheme)?.let { st.append("color:$it;") } }
                cf.pos?.let { if (it > 0) st.append("vertical-align:super;font-size:smaller;") else if (it < 0) st.append("vertical-align:sub;font-size:smaller;") }
                sb.append("<span style=\"$st\">").append(esc(seg).replace("\u000b", "<br>")).append("</span>")
                any = true
                cs = ce
            }
            if (!any && !bullet) sb.append("<span style=\"font-size:${f(sizePx)}px\">&nbsp;</span>")
            out.append("<div class=\"pp\" style=\"font-size:${f(sizePx)}px;${pcss}\">$sb</div>")
            if (pe >= text.length) break
            ps = pe + 1
        }
    }
}
