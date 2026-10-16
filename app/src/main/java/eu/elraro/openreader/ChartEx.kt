package eu.elraro.openreader

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The "extended" chart types (chartex): waterfall, funnel, treemap, sunburst, box & whisker, histogram, region map. */
object ChartEx {
    private class Data(val levels: List<List<String>>, val values: List<Double?>)
    private val FONT = "Carlito,Calibri,Liberation Sans,sans-serif"

    private fun n(d: Double): String = String.format(java.util.Locale.US, "%.2f", d).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    private fun color(s: ColorScheme, i: Int): String {
        val base = s.get("accent${i % 6 + 1}") ?: "4472C4"
        return "#" + if (i < 6) base else ColorScheme.tint(base, if ((i / 6) % 2 == 1) -0.25 else 0.25)
    }

    private fun readData(chartData: Node): Map<String, Data> {
        val out = HashMap<String, Data>()
        for (d in chartData.all("data")) {
            val id = d.attr("id") ?: continue
            val levels = ArrayList<List<String>>()
            for (sd in d.all("strDim")) for (lvl in sd.all("lvl")) {
                val cnt = lvl.attr("ptCount")?.toIntOrNull() ?: 0
                val arr = MutableList(cnt) { "" }
                for (p in lvl.all("pt")) { val i = p.attr("idx")?.toIntOrNull() ?: continue; if (i in 0 until cnt) arr[i] = p.text.toString() }
                levels.add(arr)
            }
            val vals = ArrayList<Double?>()
            (d.all("numDim").firstOrNull { it.attr("type") == "val" } ?: d.all("numDim").firstOrNull { it.attr("type") == "size" } ?: d.all("numDim").firstOrNull())?.child("lvl")?.let { lvl ->
                val cnt = lvl.attr("ptCount")?.toIntOrNull() ?: 0
                val arr = MutableList<Double?>(cnt) { null }
                for (p in lvl.all("pt")) { val i = p.attr("idx")?.toIntOrNull() ?: continue; if (i in 0 until cnt) arr[i] = p.text.toString().toDoubleOrNull() }
                vals.addAll(arr)
            }
            out[id] = Data(levels, vals)
        }
        return out
    }

    fun svg(root: Node, scheme: ColorScheme, w: Double, h: Double): String {
        val chart = root.child("chart") ?: return ""
        val data = readData(root.child("chartData") ?: return "")
        val series = chart.child("plotArea")?.child("plotAreaRegion")?.all("series").orEmpty()
        if (series.isEmpty()) return ""
        val fs = max(9.0, min(13.0, w / 45))
        val sb = StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${n(w)}\" height=\"${n(h)}\" viewBox=\"0 0 ${n(w)} ${n(h)}\" font-family=\"$FONT\" font-size=\"${n(fs)}\">")
        var top = 8.0
        val title = chart.child("title")?.findAll("t")?.joinToString("") { it.text.toString() }.orEmpty()
        if (title.isNotEmpty()) {
            sb.append("<text x=\"${n(w / 2)}\" y=\"${n(top + fs * 1.3)}\" text-anchor=\"middle\" font-size=\"${n(fs * 1.4)}\" fill=\"#333\">${esc(title)}</text>")
            top += fs * 2.4
        }
        val l = 10.0; val r = w - 10; val b = h - 10
        val layout = series.first().attr("layoutId") ?: ""
        val first = data[series.first().child("dataId")?.attr("val")] ?: data.values.first()
        try {
            when (layout) {
                "boxWhisker" -> boxWhisker(sb, series, data, scheme, l, top, r, b, fs)
                "waterfall" -> waterfall(sb, series.first(), first, scheme, l, top, r, b, fs)
                "funnel" -> funnel(sb, first, scheme, l, top, r, b, fs)
                "treemap" -> treemap(sb, first, scheme, l, top, r, b, fs)
                "sunburst" -> sunburst(sb, first, scheme, l, top, r, b, fs)
                "clusteredColumn", "paretoLine" -> histogram(sb, first, scheme, l, top, r, b, fs)
                else -> bars(sb, first, scheme, l, top, r, b, fs) // region maps and unknown kinds: values per category
            }
        } catch (e: RuntimeException) { if (System.getenv("OR_DEBUG") != null) e.printStackTrace() }
        sb.append("</svg>")
        return sb.toString()
    }

    // ------------------------------------------------------------------ helpers

    private fun niceStep(range: Double): Double {
        if (range <= 0) return 1.0
        val raw = range / 5
        val mag = 10.0.pow(floor(ln(raw) / ln(10.0)))
        val f = raw / mag
        return mag * when { f <= 1.0 -> 1.0; f <= 2.0 -> 2.0; f <= 5.0 -> 5.0; else -> 10.0 }
    }

    private class Axis(val lo: Double, val hi: Double, val step: Double)
    private fun axis(min0: Double, max0: Double): Axis {
        var lo = min(0.0, min0); var hi = max(0.0, max0)
        if (hi == lo) hi = lo + 1
        val step = niceStep(hi - lo)
        return Axis(floor(lo / step) * step, ceil(hi / step) * step, step)
    }

    private fun drawValueAxis(sb: StringBuilder, ax: Axis, x0: Double, y0: Double, x1: Double, y1: Double, fs: Double) {
        var t = ax.lo; var g = 0
        while (t <= ax.hi + ax.step * 1e-6 && g++ < 40) {
            val y = y1 - (t - ax.lo) / (ax.hi - ax.lo) * (y1 - y0)
            sb.append("<line x1=\"${n(x0)}\" y1=\"${n(y)}\" x2=\"${n(x1)}\" y2=\"${n(y)}\" stroke=\"#d9d9d9\"/>")
            sb.append("<text x=\"${n(x0 - 5)}\" y=\"${n(y + fs * 0.35)}\" text-anchor=\"end\" fill=\"#595959\">${esc(NumFmt.general(t))}</text>")
            t += ax.step
        }
    }

    private fun labelWidth(ax: Axis, fs: Double) = max(fs * 2.5, listOf(ax.lo, ax.hi).maxOf { NumFmt.general(it).length } * fs * 0.6) + 8

    // ------------------------------------------------------------------ box & whisker

    private fun quantile(sorted: List<Double>, p: Double, exclusive: Boolean): Double {
        val nn = sorted.size
        val pos = if (exclusive) p * (nn + 1) - 1 else p * (nn - 1)
        val lo = floor(pos).toInt().coerceIn(0, nn - 1); val hi = ceil(pos).toInt().coerceIn(0, nn - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - floor(pos)).coerceIn(0.0, 1.0)
    }

    private fun boxWhisker(sb: StringBuilder, series: List<Node>, data: Map<String, Data>, scheme: ColorScheme, l: Double, t: Double, r: Double, b: Double, fs: Double) {
        class Box(val q1: Double, val med: Double, val q3: Double, val lo: Double, val hi: Double, val mean: Double, val out: List<Double>)
        val cats = LinkedHashMap<String, Int>()
        val groups = ArrayList<Pair<String, List<Pair<String, Box>>>>() // series name -> boxes per category
        var all = ArrayList<Double>()
        for (s in series) {
            val d = data[s.child("dataId")?.attr("val")] ?: continue
            val exclusive = s.find("statistics")?.attr("quartileMethod") != "inclusive"
            val catLvl = d.levels.firstOrNull().orEmpty()
            val byCat = LinkedHashMap<String, MutableList<Double>>()
            for ((i, v) in d.values.withIndex()) if (v != null) byCat.getOrPut(catLvl.getOrNull(i)?.ifEmpty { "" } ?: "") { ArrayList() }.add(v)
            val boxes = byCat.map { (c, vals) ->
                cats.putIfAbsent(c, cats.size)
                val sorted = vals.sorted(); all.addAll(sorted)
                val q1 = quantile(sorted, 0.25, exclusive); val q3 = quantile(sorted, 0.75, exclusive)
                val iqr = q3 - q1
                val inRange = sorted.filter { it >= q1 - 1.5 * iqr && it <= q3 + 1.5 * iqr }
                c to Box(q1, quantile(sorted, 0.5, exclusive), q3, inRange.minOrNull() ?: sorted.first(), inRange.maxOrNull() ?: sorted.last(), sorted.average(), sorted.filter { it < q1 - 1.5 * iqr || it > q3 + 1.5 * iqr })
            }
            groups.add((s.child("tx")?.find("v")?.text?.toString() ?: "") to boxes)
        }
        if (all.isEmpty()) return
        val ax = axis(all.min(), all.max())
        val x0 = l + labelWidth(ax, fs); val x1 = r; val y0 = t + 6; val y1 = b - fs * 2.4 - (if (groups.size > 1) fs * 1.6 else 0.0)
        drawValueAxis(sb, ax, x0, y0, x1, y1, fs)
        fun vy(v: Double) = y1 - (v - ax.lo) / (ax.hi - ax.lo) * (y1 - y0)
        val band = (x1 - x0) / max(1, cats.size)
        val ns = groups.size
        val bw = band * 0.7 / ns
        for ((c, ci) in cats) sb.append("<text x=\"${n(x0 + band * (ci + 0.5))}\" y=\"${n(y1 + fs * 1.3)}\" text-anchor=\"middle\" fill=\"#595959\">${esc(c)}</text>")
        groups.forEachIndexed { si, (name, boxes) ->
            val col = color(scheme, si)
            for ((c, bx) in boxes) {
                val cx = x0 + band * (cats.getValue(c) + 0.5) + (si - (ns - 1) / 2.0) * bw
                sb.append("<line x1=\"${n(cx)}\" y1=\"${n(vy(bx.hi))}\" x2=\"${n(cx)}\" y2=\"${n(vy(bx.lo))}\" stroke=\"#595959\"/>")
                sb.append("<line x1=\"${n(cx - bw / 4)}\" y1=\"${n(vy(bx.hi))}\" x2=\"${n(cx + bw / 4)}\" y2=\"${n(vy(bx.hi))}\" stroke=\"#595959\"/>")
                sb.append("<line x1=\"${n(cx - bw / 4)}\" y1=\"${n(vy(bx.lo))}\" x2=\"${n(cx + bw / 4)}\" y2=\"${n(vy(bx.lo))}\" stroke=\"#595959\"/>")
                sb.append("<rect x=\"${n(cx - bw / 2 + 1)}\" y=\"${n(vy(bx.q3))}\" width=\"${n(bw - 2)}\" height=\"${n(max(1.0, vy(bx.q1) - vy(bx.q3)))}\" fill=\"$col\" fill-opacity=\"0.85\" stroke=\"#595959\"/>")
                sb.append("<line x1=\"${n(cx - bw / 2 + 1)}\" y1=\"${n(vy(bx.med))}\" x2=\"${n(cx + bw / 2 - 1)}\" y2=\"${n(vy(bx.med))}\" stroke=\"#fff\" stroke-width=\"2\"/>")
                val my = vy(bx.mean)
                sb.append("<path d=\"M${n(cx - 4)},${n(my - 4)} L${n(cx + 4)},${n(my + 4)} M${n(cx + 4)},${n(my - 4)} L${n(cx - 4)},${n(my + 4)}\" stroke=\"#333\" fill=\"none\"/>")
                for (o in bx.out) sb.append("<circle cx=\"${n(cx)}\" cy=\"${n(vy(o))}\" r=\"2.5\" fill=\"none\" stroke=\"$col\"/>")
            }
            if (ns > 1) {
                val lx = x0 + si * (fs * 8)
                sb.append("<rect x=\"${n(lx)}\" y=\"${n(b - fs)}\" width=\"${n(fs * 0.9)}\" height=\"${n(fs * 0.9)}\" fill=\"$col\"/><text x=\"${n(lx + fs * 1.3)}\" y=\"${n(b - fs * 0.2)}\" fill=\"#444\">${esc(name)}</text>")
            }
        }
    }

    // ------------------------------------------------------------------ waterfall

    private fun waterfall(sb: StringBuilder, s: Node, d: Data, scheme: ColorScheme, l: Double, t: Double, r: Double, b: Double, fs: Double) {
        val cats = d.levels.firstOrNull().orEmpty()
        val subtotals = s.find("subtotals")?.all("idx")?.mapNotNull { it.attr("val")?.toIntOrNull() }?.toSet().orEmpty()
        class Bar(val from: Double, val to: Double, val kind: Int)
        val bars = ArrayList<Bar>(); var run = 0.0
        for ((i, v) in d.values.withIndex()) {
            val value = v ?: 0.0
            if (i in subtotals) bars.add(Bar(0.0, run, 2)) else { bars.add(Bar(run, run + value, if (value >= 0) 0 else 1)); run += value }
        }
        val ax = axis(bars.minOf { min(it.from, it.to) }, bars.maxOf { max(it.from, it.to) })
        val x0 = l + labelWidth(ax, fs); val y0 = t + 6; val y1 = b - fs * 2.2
        drawValueAxis(sb, ax, x0, y0, r, y1, fs)
        fun vy(v: Double) = y1 - (v - ax.lo) / (ax.hi - ax.lo) * (y1 - y0)
        val band = (r - x0) / max(1, bars.size)
        bars.forEachIndexed { i, bar ->
            val x = x0 + band * i + band * 0.15
            val col = color(scheme, bar.kind)
            sb.append("<rect x=\"${n(x)}\" y=\"${n(min(vy(bar.from), vy(bar.to)))}\" width=\"${n(band * 0.7)}\" height=\"${n(max(1.0, abs(vy(bar.from) - vy(bar.to))))}\" fill=\"$col\"/>")
            if (i + 1 < bars.size) sb.append("<line x1=\"${n(x + band * 0.7)}\" y1=\"${n(vy(bar.to))}\" x2=\"${n(x + band * 1.3)}\" y2=\"${n(vy(bar.to))}\" stroke=\"#8c8c8c\" stroke-dasharray=\"2 2\"/>")
            val label = cats.getOrNull(i).orEmpty()
            sb.append("<text x=\"${n(x + band * 0.35)}\" y=\"${n(y1 + fs * 1.3)}\" text-anchor=\"middle\" fill=\"#595959\">${esc(if (label.length > (band / (fs * 0.55)).toInt().coerceAtLeast(3)) label.take((band / (fs * 0.55)).toInt().coerceAtLeast(3) - 1) + "…" else label)}</text>")
        }
    }

    // ------------------------------------------------------------------ funnel

    private fun funnel(sb: StringBuilder, d: Data, scheme: ColorScheme, l: Double, t: Double, r: Double, b: Double, fs: Double) {
        val cats = d.levels.firstOrNull().orEmpty()
        val vals = d.values.map { max(0.0, it ?: 0.0) }
        val mx = vals.maxOrNull()?.takeIf { it > 0 } ?: return
        val labelW = (cats.maxOfOrNull { it.length } ?: 0) * fs * 0.55 + 10
        val x0 = l + labelW; val cx = (x0 + r) / 2; val full = r - x0
        val band = (b - t) / vals.size
        vals.forEachIndexed { i, v ->
            val wv = full * v / mx
            sb.append("<rect x=\"${n(cx - wv / 2)}\" y=\"${n(t + band * i + band * 0.08)}\" width=\"${n(wv)}\" height=\"${n(band * 0.84)}\" fill=\"${color(scheme, 0)}\"/>")
            sb.append("<text x=\"${n(x0 - 6)}\" y=\"${n(t + band * (i + 0.5) + fs * 0.35)}\" text-anchor=\"end\" fill=\"#595959\">${esc(cats.getOrNull(i).orEmpty())}</text>")
            sb.append("<text x=\"${n(cx)}\" y=\"${n(t + band * (i + 0.5) + fs * 0.35)}\" text-anchor=\"middle\" fill=\"#fff\">${esc(NumFmt.general(v))}</text>")
        }
    }

    // ------------------------------------------------------------------ treemap and sunburst

    private class TNode(val name: String, var value: Double = 0.0, val children: LinkedHashMap<String, TNode> = LinkedHashMap())

    /** Hierarchy from the category levels (the last level is the top, the first the leaf). */
    private fun tree(d: Data): TNode {
        val root = TNode("")
        val levels = d.levels.reversed()
        for (i in d.values.indices) {
            val v = d.values[i] ?: continue
            if (v <= 0) continue
            var cur = root
            val path = levels.map { it.getOrNull(i).orEmpty() }.filter { it.isNotEmpty() }
            for ((k, name) in path.withIndex()) {
                val child = cur.children.getOrPut(name) { TNode(name) }
                if (k == path.size - 1) child.value += v
                cur = child
            }
            if (path.isEmpty()) root.children.getOrPut("(blank)") { TNode("(blank)") }.value += v
        }
        fun total(n: TNode): Double { if (n.children.isNotEmpty()) n.value = n.children.values.sumOf { total(it) }; return n.value }
        total(root)
        return root
    }

    private fun treemap(sb: StringBuilder, d: Data, scheme: ColorScheme, l: Double, t: Double, r: Double, b: Double, fs: Double) {
        val root = tree(d)
        var ci = 0
        fun layout(node: TNode, x: Double, y: Double, w: Double, h: Double, depth: Int, col: Int) {
            val kids = node.children.values.sortedByDescending { it.value }
            val total = kids.sumOf { it.value }
            if (kids.isEmpty() || total <= 0) return
            var rx = x; var ry = y; var rw = w; var rh = h
            var rest = kids
            // slice-and-dice with alternating strips, good enough for readable proportions
            var remaining = total
            while (rest.isNotEmpty()) {
                val horizontal = rw >= rh
                val side = if (horizontal) rh else rw
                // take items into the strip while their aspect ratio improves
                var take = 1; var best = Double.MAX_VALUE
                for (k in 1..rest.size) {
                    val s = rest.take(k).sumOf { it.value }
                    val strip = s / remaining * (if (horizontal) rw else rh)
                    val worst = rest.take(k).maxOf { val len = it.value / s * side; max(strip / len, len / strip) }
                    if (worst <= best) { best = worst; take = k } else break
                }
                val group = rest.take(take); val gs = group.sumOf { it.value }
                val strip = gs / remaining * (if (horizontal) rw else rh)
                var off = 0.0
                for (c in group) {
                    val len = c.value / gs * side
                    val cx = if (horizontal) rx else rx + off; val cy = if (horizontal) ry + off else ry
                    val cw = if (horizontal) strip else len; val chh = if (horizontal) len else strip
                    val colour = if (depth == 0) color(scheme, ci++) else color(scheme, col)
                    val colIdx = if (depth == 0) ci - 1 else col
                    if (c.children.isEmpty()) {
                        sb.append("<rect x=\"${n(cx)}\" y=\"${n(cy)}\" width=\"${n(max(0.0, cw - 1))}\" height=\"${n(max(0.0, chh - 1))}\" fill=\"$colour\" stroke=\"#fff\"/>")
                        if (cw > fs * 3 && chh > fs * 1.5) sb.append("<text x=\"${n(cx + 4)}\" y=\"${n(cy + fs + 2)}\" fill=\"#fff\">${esc(c.name.take(max(2, (cw / (fs * 0.55)).toInt())))}</text>")
                    } else {
                        sb.append("<rect x=\"${n(cx)}\" y=\"${n(cy)}\" width=\"${n(max(0.0, cw - 1))}\" height=\"${n(max(0.0, chh - 1))}\" fill=\"$colour\" fill-opacity=\"0.35\" stroke=\"#fff\"/>")
                        sb.append("<text x=\"${n(cx + 4)}\" y=\"${n(cy + fs + 2)}\" fill=\"#333\" font-weight=\"bold\">${esc(c.name.take(max(2, (cw / (fs * 0.6)).toInt())))}</text>")
                        layout(c, cx + 2, cy + fs * 1.6, cw - 5, chh - fs * 1.6 - 3, depth + 1, colIdx)
                    }
                    off += len
                }
                if (horizontal) { rx += strip; rw -= strip } else { ry += strip; rh -= strip }
                remaining -= gs; rest = rest.drop(take)
            }
        }
        layout(root, l, t, r - l, b - t, 0, 0)
    }

    private fun sunburst(sb: StringBuilder, d: Data, scheme: ColorScheme, l: Double, t: Double, r: Double, b: Double, fs: Double) {
        val root = tree(d)
        if (root.value <= 0) return
        fun depthOf(n: TNode): Int = 1 + (n.children.values.maxOfOrNull { depthOf(it) } ?: 0)
        val rings = depthOf(root) - 1
        val cx = (l + r) / 2; val cy = (t + b) / 2
        val rad = min(r - l, b - t) / 2 - 4
        val ringW = rad / (rings + 0.5)
        var ci = 0
        fun arc(a0: Double, a1: Double, r0: Double, r1: Double, fill: String) {
            val large = if (a1 - a0 > PI) 1 else 0
            val full = a1 - a0 >= 2 * PI - 1e-6
            val e = if (full) a0 + 2 * PI - 1e-4 else a1
            val p = "M${n(cx + r1 * cos(a0))},${n(cy + r1 * sin(a0))} A${n(r1)},${n(r1)} 0 $large 1 ${n(cx + r1 * cos(e))},${n(cy + r1 * sin(e))} L${n(cx + r0 * cos(e))},${n(cy + r0 * sin(e))} A${n(r0)},${n(r0)} 0 $large 0 ${n(cx + r0 * cos(a0))},${n(cy + r0 * sin(a0))} Z"
            sb.append("<path d=\"$p\" fill=\"$fill\" stroke=\"#fff\"/>")
        }
        fun draw(node: TNode, a0: Double, a1: Double, ring: Int, col: Int) {
            var a = a0
            for (c in node.children.values) {
                val span = (a1 - a0) * c.value / node.value
                val colIdx = if (ring == 0) ci++ else col
                val fill = color(scheme, colIdx)
                val r0 = ringW * (ring + 0.5) ; val r1 = r0 + ringW
                arc(a, a + span, if (ring == 0) 0.0 else r0, r1.takeIf { true } ?: r1, fill)
                if (span * (r0 + r1) / 2 > fs * 2.5) {
                    val m = a + span / 2; val rr = (if (ring == 0) r1 / 2 else (r0 + r1) / 2)
                    sb.append("<text x=\"${n(cx + rr * cos(m))}\" y=\"${n(cy + rr * sin(m) + fs * 0.35)}\" text-anchor=\"middle\" fill=\"#fff\">${esc(c.name.take(10))}</text>")
                }
                if (c.children.isNotEmpty()) draw(c, a, a + span, ring + 1, colIdx)
                a += span
            }
        }
        draw(root, -PI / 2, 3 * PI / 2, 0, 0)
    }

    // ------------------------------------------------------------------ histogram and plain bars

    private fun histogram(sb: StringBuilder, d: Data, scheme: ColorScheme, l: Double, t: Double, r: Double, b: Double, fs: Double) {
        val v = d.values.filterNotNull().sorted()
        if (v.isEmpty()) return
        val bins = max(1, ceil(ln(v.size.toDouble()) / ln(2.0) + 1).toInt())
        val lo = v.first(); val hi = v.last()
        val width = if (hi > lo) niceStep((hi - lo) * 5 / bins).let { s -> max(s / 1.0, (hi - lo) / bins) } else 1.0
        val start = floor(lo / width) * width
        val nb = max(1, ceil((hi - start) / width + 1e-9).toInt())
        val counts = IntArray(nb)
        for (x in v) counts[min(nb - 1, ((x - start) / width).toInt())]++
        val ax = axis(0.0, counts.max().toDouble())
        val x0 = l + labelWidth(ax, fs); val y0 = t + 6; val y1 = b - fs * 2.2
        drawValueAxis(sb, ax, x0, y0, r, y1, fs)
        val band = (r - x0) / nb
        for (i in 0 until nb) {
            val hgt = counts[i] / (ax.hi - ax.lo) * (y1 - y0)
            sb.append("<rect x=\"${n(x0 + band * i + 1)}\" y=\"${n(y1 - hgt)}\" width=\"${n(band - 2)}\" height=\"${n(hgt)}\" fill=\"${color(scheme, 0)}\"/>")
            sb.append("<text x=\"${n(x0 + band * (i + 0.5))}\" y=\"${n(y1 + fs * 1.3)}\" text-anchor=\"middle\" fill=\"#595959\" font-size=\"${n(fs * 0.85)}\">${esc("[" + NumFmt.general(start + width * i) + ")")}</text>")
        }
    }

    private fun bars(sb: StringBuilder, d: Data, scheme: ColorScheme, l: Double, t: Double, r: Double, b: Double, fs: Double) {
        val cats = d.levels.firstOrNull().orEmpty()
        val vals = d.values.map { it ?: 0.0 }
        if (vals.isEmpty()) return
        val ax = axis(vals.min(), vals.max())
        val x0 = l + labelWidth(ax, fs); val y0 = t + 6; val y1 = b - fs * 2.2
        drawValueAxis(sb, ax, x0, y0, r, y1, fs)
        fun vy(v: Double) = y1 - (v - ax.lo) / (ax.hi - ax.lo) * (y1 - y0)
        val band = (r - x0) / vals.size
        vals.forEachIndexed { i, v ->
            sb.append("<rect x=\"${n(x0 + band * i + band * 0.15)}\" y=\"${n(min(vy(v), vy(0.0)))}\" width=\"${n(band * 0.7)}\" height=\"${n(max(1.0, abs(vy(v) - vy(0.0))))}\" fill=\"${color(scheme, 0)}\"/>")
            sb.append("<text x=\"${n(x0 + band * (i + 0.5))}\" y=\"${n(y1 + fs * 1.3)}\" text-anchor=\"middle\" fill=\"#595959\">${esc(cats.getOrNull(i).orEmpty().take(10))}</text>")
        }
    }
}
