package eu.elraro.openreader

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Renders a DrawingML chart part (c:chartSpace) as an inline SVG, using the cached values stored in the file. */
object Charts {
    private class Series(
        val name: String, val cats: List<String>, val vals: List<Double?>, val xs: List<Double?>,
        val color: String, val spPr: Node?, val pointColors: Map<Int, String>, val fmt: String,
    )

    private val FONT = "Carlito,Calibri,Liberation Sans,sans-serif"

    /** Looks up a sheet reference such as Sheet1!$A$1:$A$5 when the chart carries no cached values. */
    private var resolver: ((String) -> List<Any?>?)? = null

    fun svg(chartSpace: Node, scheme: ColorScheme, w: Double, h: Double, resolve: ((String) -> List<Any?>?)? = null): String {
        resolver = resolve
        if (chartSpace.child("chartData") != null) return ChartEx.svg(chartSpace, scheme, w, h)
        val chart = chartSpace.child("chart") ?: return ""
        val plot = chart.child("plotArea") ?: return ""
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${n(w)}\" height=\"${n(h)}\" viewBox=\"0 0 ${n(w)} ${n(h)}\" font-family=\"$FONT\" font-size=\"${n(max(9.0, min(13.0, w / 45)))}\">")
        chartSpace.child("spPr")?.child("solidFill")?.children?.firstOrNull()?.let { scheme.resolve(it) }
            ?.let { sb.append("<rect width=\"${n(w)}\" height=\"${n(h)}\" fill=\"$it\"/>") }
        val fs = max(9.0, min(13.0, w / 45))

        // title
        var top = 8.0
        val title = chart.child("title")?.let { textOf(it) }.orEmpty()
        if (title.isNotEmpty()) {
            sb.append("<text x=\"${n(w / 2)}\" y=\"${n(top + fs * 1.3)}\" text-anchor=\"middle\" font-size=\"${n(fs * 1.4)}\" fill=\"#333\">${esc(title)}</text>")
            top += fs * 2.4
        }

        val types = plot.children.filter { it.name.endsWith("Chart") }
        val all = ArrayList<Pair<Node, List<Series>>>()
        var idx = 0
        for (t in types) {
            val list = t.all("ser").map { readSeries(it, idx++, scheme, t.name) }
            all.add(t to list)
        }
        val flat = all.flatMap { it.second }
        val pie = types.any { it.name == "pieChart" || it.name == "doughnutChart" || it.name == "ofPieChart" || it.name == "pie3DChart" }

        // legend
        val legend = chart.child("legend")
        val legendPos = legend?.child("legendPos")?.attr("val") ?: "r"
        var left = 8.0; var right = w - 8; var bottom = h - 8
        if (legend != null && legend.child("delete")?.attr("val") != "1") {
            val entries: List<Pair<String, String>> = if (pie && flat.isNotEmpty()) {
                val s = flat[0]; s.cats.mapIndexed { i, c -> c to (s.pointColors[i] ?: palette(scheme, i)) }
            } else flat.map { it.name to it.color }
            if (legendPos == "r" || legendPos == "l") {
                val lw = (entries.maxOfOrNull { it.first.length } ?: 0) * fs * 0.55 + fs * 2.2
                val x0 = if (legendPos == "r") right - lw else left
                entries.forEachIndexed { i, (nm, col) ->
                    val y = top + fs * 1.6 * (i + 1)
                    sb.append("<rect x=\"${n(x0)}\" y=\"${n(y - fs * 0.8)}\" width=\"${n(fs * 0.9)}\" height=\"${n(fs * 0.9)}\" fill=\"$col\"/>")
                    sb.append("<text x=\"${n(x0 + fs * 1.3)}\" y=\"${n(y)}\" fill=\"#444\">${esc(nm)}</text>")
                }
                if (legendPos == "r") right -= lw + 8 else left += lw + 8
            } else {
                val itemW = entries.map { it.first.length * fs * 0.55 + fs * 2.4 }
                var x = max(left, (w - itemW.sum()) / 2)
                val y = if (legendPos == "t") top + fs else bottom - fs * 0.2
                entries.forEachIndexed { i, (nm, col) ->
                    sb.append("<rect x=\"${n(x)}\" y=\"${n(y - fs * 0.8)}\" width=\"${n(fs * 0.9)}\" height=\"${n(fs * 0.9)}\" fill=\"$col\"/>")
                    sb.append("<text x=\"${n(x + fs * 1.3)}\" y=\"${n(y)}\" fill=\"#444\">${esc(nm)}</text>")
                    x += itemW[i]
                }
                if (legendPos == "t") top += fs * 2 else bottom -= fs * 2
            }
        }

        if (flat.isEmpty()) { sb.append("</svg>"); return sb.toString() }
        if (types.any { it.name == "radarChart" }) {
            radar(sb, flat, left, top, right, bottom, fs, types.first { it.name == "radarChart" }.child("radarStyle")?.attr("val") == "filled")
        } else if (pie) {
            val doughnut = types.any { it.name == "doughnutChart" }
            pieChart(sb, flat[0], scheme, left, top, right, bottom, doughnut, fs)
        } else {
            cartesian(sb, all, types, scheme, left, top, right, bottom, fs, plot)
        }
        sb.append("</svg>")
        return sb.toString()
    }

    // ---------------------------------------------------------------- data

    private fun textOf(n: Node): String = n.findAll("t").joinToString("") { it.text.toString() }.ifEmpty {
        n.find("v")?.text?.toString().orEmpty()
    }

    private fun resolved(ref: Node): List<Any?> =
        ref.find("f")?.text?.toString()?.let { resolver?.invoke(it) } ?: emptyList()

    private fun strings(ref: Node?): List<String> {
        ref ?: return emptyList()
        val cache = ref.find("strCache") ?: ref.find("numCache")
            ?: return resolved(ref).map { v -> when (v) { null -> ""; is Double -> NumFmt.general(v); else -> v.toString() } }
        val count = cache.child("ptCount")?.attr("val")?.toIntOrNull() ?: 0
        val out = MutableList(count) { "" }
        val code = cache.child("formatCode")?.text?.toString()
        for (p in cache.all("pt")) {
            val i = p.attr("idx")?.toIntOrNull() ?: continue
            val v = p.child("v")?.text?.toString().orEmpty()
            val s = if (cache.name == "numCache") v.toDoubleOrNull()?.let { NumFmt.format(it, code ?: "General") } ?: v else v
            while (out.size <= i && i < 10000) out.add("")
            if (i < out.size) out[i] = s
        }
        return out
    }

    private fun numbers(ref: Node?): List<Double?> {
        ref ?: return emptyList()
        val cache = ref.find("numCache") ?: ref.find("strCache") ?: return resolved(ref).map { it as? Double }
        val count = cache.child("ptCount")?.attr("val")?.toIntOrNull() ?: 0
        val out = MutableList<Double?>(count) { null }
        for (p in cache.all("pt")) {
            val i = p.attr("idx")?.toIntOrNull() ?: continue
            while (out.size <= i && i < 10000) out.add(null)
            if (i < out.size) out[i] = p.child("v")?.text?.toString()?.toDoubleOrNull()
        }
        return out
    }

    private fun palette(scheme: ColorScheme, i: Int): String {
        val base = scheme.get("accent${i % 6 + 1}") ?: "4472C4"
        if (i < 6) return "#$base"
        // like Office: later cycles get progressively darker/lighter variants
        val k = i / 6
        return "#" + if (k % 2 == 1) ColorScheme.tint(base, -0.25 * k.coerceAtMost(3)) else ColorScheme.tint(base, 0.25 * k.coerceAtMost(3))
    }

    private fun readSeries(s: Node, i: Int, scheme: ColorScheme, kind: String): Series {
        val name = strings(s.child("tx")).firstOrNull().orEmpty().ifEmpty { "Series ${i + 1}" }
        val spPr = s.child("spPr")
        val fill = spPr?.child("solidFill")?.children?.firstOrNull()?.let { scheme.resolve(it) }
        val line = spPr?.child("ln")?.child("solidFill")?.children?.firstOrNull()?.let { scheme.resolve(it) }
        val color = (if (kind.startsWith("line") || kind.startsWith("scatter") || kind.startsWith("radar")) line ?: fill else fill ?: line) ?: palette(scheme, i)
        val pts = HashMap<Int, String>()
        for (d in s.all("dPt")) {
            val idx = d.child("idx")?.attr("val")?.toIntOrNull() ?: continue
            d.child("spPr")?.child("solidFill")?.children?.firstOrNull()?.let { scheme.resolve(it) }?.let { pts[idx] = it }
        }
        val valNode = s.child("val") ?: s.child("yVal")
        val code = valNode?.find("formatCode")?.text?.toString() ?: "General"
        return Series(name, strings(s.child("cat") ?: s.child("xVal")), numbers(valNode), numbers(s.child("xVal")), color, spPr, pts, code)
    }

    // ---------------------------------------------------------------- pie

    private fun pieChart(sb: StringBuilder, s: Series, scheme: ColorScheme, l: Double, t: Double, r: Double, b: Double, doughnut: Boolean, fs: Double) {
        val vals = s.vals.map { max(0.0, it ?: 0.0) }
        val total = vals.sum()
        if (total <= 0) return
        val cx = (l + r) / 2; val cy = (t + b) / 2
        val rad = min(r - l, b - t) / 2 - fs * 2
        if (rad <= 0) return
        var a = -PI / 2
        vals.forEachIndexed { i, v ->
            val da = v / total * 2 * PI
            val col = s.pointColors[i] ?: palette(scheme, i)
            if (da >= 2 * PI - 1e-6) {
                sb.append("<circle cx=\"${n(cx)}\" cy=\"${n(cy)}\" r=\"${n(rad)}\" fill=\"$col\" stroke=\"#fff\"/>")
            } else if (v > 0) {
                val x1 = cx + rad * cos(a); val y1 = cy + rad * sin(a)
                val x2 = cx + rad * cos(a + da); val y2 = cy + rad * sin(a + da)
                sb.append("<path d=\"M${n(cx)},${n(cy)} L${n(x1)},${n(y1)} A${n(rad)},${n(rad)} 0 ${if (da > PI) 1 else 0} 1 ${n(x2)},${n(y2)} Z\" fill=\"$col\" stroke=\"#fff\" stroke-width=\"1\"/>")
            }
            if (v / total >= 0.04) {
                val m = a + da / 2; val lr = rad * (if (doughnut) 0.78 else 0.65)
                sb.append("<text x=\"${n(cx + lr * cos(m))}\" y=\"${n(cy + lr * sin(m) + fs * 0.35)}\" text-anchor=\"middle\" fill=\"#fff\">${(v / total * 100).let { Math.round(it) }}%</text>")
            }
            a += da
        }
        if (doughnut) sb.append("<circle cx=\"${n(cx)}\" cy=\"${n(cy)}\" r=\"${n(rad * 0.5)}\" fill=\"#fff\"/>")
    }

    private fun radar(sb: StringBuilder, series: List<Series>, l: Double, t: Double, r: Double, b: Double, fs: Double, filled: Boolean) {
        val n = series.maxOf { max(it.cats.size, it.vals.size) }.coerceAtLeast(3)
        val cx = (l + r) / 2; val cy = (t + b) / 2
        val rad = min(r - l, b - t) / 2 - fs * 2
        if (rad <= 10) return
        val hi = series.flatMap { it.vals }.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val step = niceStep(hi, 4); val top = Math.ceil(hi / step) * step
        fun pt(i: Int, v: Double): Pair<Double, Double> { val a = -PI / 2 + 2 * PI * i / n; val rr = rad * v / top; return (cx + rr * cos(a)) to (cy + rr * sin(a)) }
        var ring = step; var g = 0
        while (ring <= top + step * 1e-6 && g++ < 20) {
            sb.append("<polygon fill=\"none\" stroke=\"#d9d9d9\" points=\"${(0 until n).joinToString(" ") { pt(it, ring).let { p -> "${n(p.first)},${n(p.second)}" } }}\"/>")
            sb.append("<text x=\"${n(cx + 3)}\" y=\"${n(cy - rad * ring / top + fs * 0.9)}\" fill=\"#7f7f7f\" font-size=\"${n(fs * 0.85)}\">${esc(NumFmt.general(ring))}</text>")
            ring += step
        }
        val labels = series.maxByOrNull { it.cats.size }?.cats.orEmpty()
        for (i in 0 until n) {
            val e = pt(i, top)
            sb.append("<line x1=\"${n(cx)}\" y1=\"${n(cy)}\" x2=\"${n(e.first)}\" y2=\"${n(e.second)}\" stroke=\"#d9d9d9\"/>")
            val lp = pt(i, top * 1.1)
            val anchor = when { abs(lp.first - cx) < 4 -> "middle"; lp.first > cx -> "start"; else -> "end" }
            sb.append("<text x=\"${n(lp.first)}\" y=\"${n(lp.second + fs * 0.35)}\" text-anchor=\"$anchor\" fill=\"#595959\">${esc(labels.getOrNull(i) ?: "")}</text>")
        }
        for (s in series) {
            val pts = (0 until n).map { pt(it, s.vals.getOrNull(it) ?: 0.0) }
            val p = pts.joinToString(" ") { "${n(it.first)},${n(it.second)}" }
            sb.append("<polygon points=\"$p\" fill=\"${if (filled) s.color else "none"}\" fill-opacity=\"0.5\" stroke=\"${s.color}\" stroke-width=\"2\"/>")
        }
    }

    // ---------------------------------------------------------------- bar / line / area / scatter

    private fun niceStep(range: Double, target: Int): Double {
        if (range <= 0) return 1.0
        val raw = range / target
        val mag = 10.0.pow(floor(ln(raw) / ln(10.0)))
        val f = raw / mag
        return mag * when { f <= 1.0 -> 1.0; f <= 2.0 -> 2.0; f <= 5.0 -> 5.0; else -> 10.0 }
    }

    private fun cartesian(
        sb: StringBuilder, all: List<Pair<Node, List<Series>>>, types: List<Node>, scheme: ColorScheme,
        l: Double, t: Double, r: Double, b: Double, fs: Double, plot: Node,
    ) {
        val horizontal = types.any { it.name.startsWith("bar") && it.child("barDir")?.attr("val") == "bar" }
        val scatter = types.any { it.name == "scatterChart" || it.name == "bubbleChart" }
        val series = all.flatMap { it.second }
        val ncat = series.maxOf { max(it.cats.size, it.vals.size) }.coerceAtLeast(1)

        // value range, taking stacking into account
        var lo = 0.0; var hi = 0.0
        var xlo = 0.0; var xhi = 1.0
        var first = true
        for ((node, list) in all) {
            val grouping = node.child("grouping")?.attr("val") ?: "standard"
            val stacked = grouping == "stacked" || grouping == "percentStacked"
            if (grouping == "percentStacked") { lo = 0.0; hi = max(hi, 1.0); continue }
            if (stacked) {
                for (i in 0 until ncat) {
                    var pos = 0.0; var neg = 0.0
                    for (s in list) { val v = s.vals.getOrNull(i) ?: 0.0; if (v >= 0) pos += v else neg += v }
                    hi = max(hi, pos); lo = min(lo, neg)
                }
            } else for (s in list) for (v in s.vals) if (v != null) { hi = max(hi, v); lo = min(lo, v) }
            if (scatter) for (s in list) for (x in s.xs) if (x != null) {
                if (first) { xlo = x; xhi = x; first = false } else { xlo = min(xlo, x); xhi = max(xhi, x) }
            }
        }
        if (hi == lo) hi = lo + 1
        val step = niceStep(hi - lo, 5)
        val scaling = plot.all("valAx").lastOrNull()?.child("scaling")
        var vmin = scaling?.child("min")?.attr("val")?.toDoubleOrNull() ?: if (lo < 0) floor(lo / step) * step else 0.0
        var vmax = scaling?.child("max")?.attr("val")?.toDoubleOrNull() ?: (Math.ceil(hi / step) * step)
        if (vmax <= vmin) vmax = vmin + step
        val percent = all.any { it.first.child("grouping")?.attr("val") == "percentStacked" }
        val fmt = if (percent) "0%" else series.firstOrNull()?.fmt?.takeIf { it != "General" && !it.contains("@") } ?: "General"

        val labelW = if (horizontal) min((r - l) / 3, max(fs * 3.0, (series.maxByOrNull { it.cats.size }?.cats?.maxOfOrNull { it.take(14).length } ?: 3) * fs * 0.55) + 6)
            else max(fs * 3.0, listOf(vmin, vmax).maxOf { NumFmt.format(it, fmt).length } * fs * 0.6) + 6
        val catLabelH = fs * 1.8
        val px0 = l + labelW + 4; val px1 = r - 6; val py0 = t + 6
        val py1 = b - catLabelH
        if (px1 - px0 < 20 || py1 - py0 < 20) return
        val pw = px1 - px0; val ph = py1 - py0

        fun vy(v: Double) = py1 - (v - vmin) / (vmax - vmin) * ph
        fun vx(v: Double) = px0 + (v - vmin) / (vmax - vmin) * pw

        // gridlines + value axis labels
        var tick = vmin
        var guard = 0
        while (tick <= vmax + step * 1e-6 && guard++ < 50) {
            if (!horizontal) {
                val y = vy(tick)
                sb.append("<line x1=\"${n(px0)}\" y1=\"${n(y)}\" x2=\"${n(px1)}\" y2=\"${n(y)}\" stroke=\"#d9d9d9\"/>")
                sb.append("<text x=\"${n(px0 - 5)}\" y=\"${n(y + fs * 0.35)}\" text-anchor=\"end\" fill=\"#595959\">${esc(NumFmt.format(tick, fmt))}</text>")
            } else {
                val x = vx(tick)
                sb.append("<line x1=\"${n(x)}\" y1=\"${n(py0)}\" x2=\"${n(x)}\" y2=\"${n(py1)}\" stroke=\"#d9d9d9\"/>")
                sb.append("<text x=\"${n(x)}\" y=\"${n(py1 + fs * 1.2)}\" text-anchor=\"middle\" fill=\"#595959\">${esc(NumFmt.format(tick, fmt))}</text>")
            }
            tick += step
        }

        if (scatter) {
            if (xhi == xlo) xhi = xlo + 1
            val xs = niceStep(xhi - xlo, 5)
            val x0 = floor(xlo / xs) * xs; val x1 = Math.ceil(xhi / xs) * xs
            var xt = x0; var g = 0
            while (xt <= x1 + xs * 1e-6 && g++ < 50) {
                val x = px0 + (xt - x0) / (x1 - x0) * pw
                sb.append("<text x=\"${n(x)}\" y=\"${n(py1 + fs * 1.2)}\" text-anchor=\"middle\" fill=\"#595959\">${esc(NumFmt.general(xt))}</text>")
                xt += xs
            }
            for (s in series) {
                val pts = s.vals.indices.mapNotNull { i ->
                    val x = s.xs.getOrNull(i) ?: return@mapNotNull null
                    val y = s.vals[i] ?: return@mapNotNull null
                    (px0 + (x - x0) / (x1 - x0) * pw) to vy(y)
                }
                val hasLine = s.spPr?.child("ln")?.child("noFill") == null
                if (hasLine && pts.size > 1 && all.any { it.first.child("scatterStyle")?.attr("val")?.contains("line") == true }) {
                    sb.append("<polyline fill=\"none\" stroke=\"${s.color}\" stroke-width=\"2\" points=\"${pts.joinToString(" ") { "${n(it.first)},${n(it.second)}" }}\"/>")
                }
                for ((x, y) in pts) sb.append("<circle cx=\"${n(x)}\" cy=\"${n(y)}\" r=\"3.5\" fill=\"${s.color}\"/>")
            }
            axes(sb, px0, py0, px1, py1, vy(0.0).coerceIn(py0, py1), false)
            return
        }

        val band = if (horizontal) ph / ncat else pw / ncat
        val cats = series.maxByOrNull { it.cats.size }?.cats.orEmpty()
        for (i in 0 until ncat) {
            val label = cats.getOrNull(i) ?: (i + 1).toString()
            val maxChars = max(3, (band / (fs * 0.55)).toInt())
            val txt = if (label.length > maxChars) label.take(maxChars - 1) + "…" else label
            if (!horizontal) {
                sb.append("<text x=\"${n(px0 + band * (i + 0.5))}\" y=\"${n(py1 + fs * 1.2)}\" text-anchor=\"middle\" fill=\"#595959\">${esc(txt)}</text>")
            } else {
                sb.append("<text x=\"${n(px0 - 5)}\" y=\"${n(py0 + band * (ncat - 1 - i + 0.5) + fs * 0.35)}\" text-anchor=\"end\" fill=\"#595959\">${esc(label.take(14))}</text>")
            }
        }

        val zero = if (horizontal) vx(0.0.coerceIn(vmin, vmax)) else vy(0.0.coerceIn(vmin, vmax))
        for ((node, list) in all) {
            val kind = node.name
            val grouping = node.child("grouping")?.attr("val") ?: "standard"
            when {
                kind.startsWith("bar") -> {
                    val stacked = grouping == "stacked" || grouping == "percentStacked"
                    val overlap = node.child("overlap")?.attr("val")?.toDoubleOrNull() ?: if (stacked) 100.0 else 0.0
                    val gap = (node.child("gapWidth")?.attr("val")?.toDoubleOrNull() ?: 150.0) / 100
                    val k = if (stacked) 1 else list.size
                    val barW = band / (k - (k - 1) * overlap / 100 + gap)
                    for (i in 0 until ncat) {
                        var pos = 0.0; var neg = 0.0
                        val total = if (grouping == "percentStacked") list.sumOf { abs(it.vals.getOrNull(i) ?: 0.0) }.takeIf { it > 0 } ?: 1.0 else 1.0
                        list.forEachIndexed { si, s ->
                            val raw = s.vals.getOrNull(i) ?: return@forEachIndexed
                            val v = raw / total
                            val col = s.pointColors[i] ?: s.color
                            val start: Double; val end: Double
                            if (stacked) { if (v >= 0) { start = pos; pos += v; end = pos } else { start = neg; neg += v; end = neg } } else { start = 0.0; end = v }
                            val offset = (band - (barW * (k - (k - 1) * overlap / 100))) / 2 +
                                (if (stacked) 0.0 else si * barW * (1 - overlap / 100))
                            if (!horizontal) {
                                val x = px0 + band * i + offset
                                val y1 = vy(end); val y0 = vy(start)
                                sb.append("<rect x=\"${n(x)}\" y=\"${n(min(y0, y1))}\" width=\"${n(barW)}\" height=\"${n(max(0.5, abs(y0 - y1)))}\" fill=\"$col\"/>")
                            } else {
                                val y = py0 + band * (ncat - 1 - i) + offset
                                val x0 = vx(start); val x1 = vx(end)
                                sb.append("<rect x=\"${n(min(x0, x1))}\" y=\"${n(y)}\" width=\"${n(max(0.5, abs(x1 - x0)))}\" height=\"${n(barW)}\" fill=\"$col\"/>")
                            }
                        }
                    }
                }
                kind == "stockChart" -> {
                    val has4 = list.size >= 4
                    val o = if (has4) list[0] else null; val hiS = list[if (has4) 1 else 0]; val loS = list[if (has4) 2 else 1]; val clS = list.getOrNull(if (has4) 3 else 2)
                    for (i in 0 until ncat) {
                        val h = hiS.vals.getOrNull(i) ?: continue; val lo = loS.vals.getOrNull(i) ?: continue
                        val x = px0 + band * (i + 0.5)
                        sb.append("<line x1=\"${n(x)}\" y1=\"${n(vy(h))}\" x2=\"${n(x)}\" y2=\"${n(vy(lo))}\" stroke=\"#404040\" stroke-width=\"2\"/>")
                        o?.vals?.getOrNull(i)?.let { sb.append("<line x1=\"${n(x - 5)}\" y1=\"${n(vy(it))}\" x2=\"${n(x)}\" y2=\"${n(vy(it))}\" stroke=\"#404040\" stroke-width=\"2\"/>") }
                        clS?.vals?.getOrNull(i)?.let { sb.append("<line x1=\"${n(x)}\" y1=\"${n(vy(it))}\" x2=\"${n(x + 5)}\" y2=\"${n(vy(it))}\" stroke=\"#404040\" stroke-width=\"2\"/>") }
                    }
                }
                kind.startsWith("line") || kind.startsWith("area") -> {
                    val isArea = kind.startsWith("area")
                    val stacked = grouping == "stacked" || grouping == "percentStacked"
                    val acc = DoubleArray(ncat)
                    for (s in list) {
                        val pts = ArrayList<Pair<Double, Double>>()
                        val base = ArrayList<Double>()
                        for (i in 0 until ncat) {
                            val v = s.vals.getOrNull(i) ?: continue
                            var y = v
                            if (stacked) { acc[i] += v; y = acc[i] }
                            pts.add((px0 + band * (i + 0.5)) to vy(y))
                            base.add(vy(if (stacked) acc[i] - v else 0.0.coerceIn(vmin, vmax)))
                        }
                        if (pts.isEmpty()) continue
                        if (isArea) {
                            val path = "M" + pts.joinToString(" L") { "${n(it.first)},${n(it.second)}" } +
                                " L" + pts.indices.reversed().joinToString(" L") { "${n(pts[it].first)},${n(base[it])}" } + " Z"
                            sb.append("<path d=\"$path\" fill=\"${s.color}\" fill-opacity=\"0.75\"/>")
                        } else {
                            if (s.spPr?.child("ln")?.child("noFill") == null && pts.size > 1) {
                                val wpx = (s.spPr?.child("ln")?.attr("w")?.toDoubleOrNull() ?: 28575.0) / 12700 * 1.3
                                sb.append("<polyline fill=\"none\" stroke=\"${s.color}\" stroke-width=\"${n(wpx.coerceIn(1.5, 5.0))}\" stroke-linejoin=\"round\" points=\"${pts.joinToString(" ") { "${n(it.first)},${n(it.second)}" }}\"/>")
                            }
                            val marker = node.child("marker")?.attr("val") != "0"
                            if (marker) for ((x, y) in pts) sb.append("<circle cx=\"${n(x)}\" cy=\"${n(y)}\" r=\"3\" fill=\"${s.color}\"/>")
                        }
                    }
                }
            }
        }
        axes(sb, px0, py0, px1, py1, zero, horizontal)
    }

    private fun axes(sb: StringBuilder, x0: Double, y0: Double, x1: Double, y1: Double, zero: Double, horizontal: Boolean) {
        if (horizontal) {
            sb.append("<line x1=\"${n(zero)}\" y1=\"${n(y0)}\" x2=\"${n(zero)}\" y2=\"${n(y1)}\" stroke=\"#8c8c8c\"/>")
        } else {
            sb.append("<line x1=\"${n(x0)}\" y1=\"${n(zero)}\" x2=\"${n(x1)}\" y2=\"${n(zero)}\" stroke=\"#8c8c8c\"/>")
        }
    }

    private fun n(d: Double): String {
        val s = String.format(java.util.Locale.US, "%.2f", d)
        return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}
