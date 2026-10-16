package eu.elraro.openreader

/** DrawingML colour scheme (theme1.xml) with lumMod/lumOff/tint/shade support. */
class ColorScheme(private val colors: Map<String, String>) {
    fun get(name: String): String? = colors[when (name) {
        "tx1" -> "dk1"; "bg1" -> "lt1"; "tx2" -> "dk2"; "bg2" -> "lt2"; else -> name
    }]

    /** Resolves a colour element (srgbClr/schemeClr/sysClr/prstClr) with its transforms to #rrggbb. */
    fun resolve(n: Node?): String? {
        n ?: return null
        val base = when (n.name) {
            "srgbClr" -> n.attr("val")
            "schemeClr" -> n.attr("val")?.let { get(it) }
            "sysClr" -> n.attr("lastClr") ?: if (n.attr("val") == "window") "FFFFFF" else "000000"
            "prstClr" -> if (n.attr("val") == "white") "FFFFFF" else "000000"
            else -> null
        } ?: return null
        if (base.length != 6) return null
        var rgb = intArrayOf(base.substring(0, 2).toInt(16), base.substring(2, 4).toInt(16), base.substring(4, 6).toInt(16))
        for (t in n.children) {
            val v = t.attr("val")?.toDoubleOrNull()?.div(100000) ?: continue
            when (t.name) {
                "lumMod" -> rgb = lum(rgb) { it * v }
                "lumOff" -> rgb = lum(rgb) { it + v }
                "tint" -> rgb = IntArray(3) { (255 - (255 - rgb[it]) * v).toInt().coerceIn(0, 255) }
                "shade" -> rgb = IntArray(3) { (rgb[it] * v).toInt().coerceIn(0, 255) }
            }
        }
        return "#%02x%02x%02x".format(rgb[0], rgb[1], rgb[2])
    }

    private inline fun lum(rgb: IntArray, f: (Double) -> Double): IntArray {
        val hsl = rgbToHsl(rgb)
        hsl[2] = f(hsl[2]).coerceIn(0.0, 1.0)
        return hslToRgb(hsl)
    }

    companion object {
        val DEFAULT = ColorScheme(mapOf(
            "dk1" to "000000", "lt1" to "FFFFFF", "dk2" to "44546A", "lt2" to "E7E6E6", "accent1" to "4472C4", "accent2" to "ED7D31",
            "accent3" to "A5A5A5", "accent4" to "FFC000", "accent5" to "5B9BD5", "accent6" to "70AD47", "hlink" to "0563C1", "folHlink" to "954F72",
        ))

        fun parse(theme: Node?): ColorScheme {
            val cs = theme?.find("clrScheme") ?: return DEFAULT
            val m = HashMap(DEFAULT.colors)
            for (c in cs.children) {
                val e = c.children.firstOrNull() ?: continue
                val v = if (e.name == "sysClr") e.attr("lastClr") else e.attr("val")
                if (v != null && v.length == 6) m[c.name] = v
            }
            return ColorScheme(m)
        }

        /** Excel tint: positive lightens, negative darkens (via luminance). */
        fun tint(hex: String, tint: Double): String {
            val rgb = intArrayOf(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
            val hsl = rgbToHsl(rgb)
            hsl[2] = if (tint < 0) hsl[2] * (1 + tint) else hsl[2] * (1 - tint) + tint
            val o = hslToRgb(hsl)
            return "%02x%02x%02x".format(o[0], o[1], o[2])
        }

        fun rgbToHsl(c: IntArray): DoubleArray {
            val r = c[0] / 255.0; val g = c[1] / 255.0; val b = c[2] / 255.0
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val l = (mx + mn) / 2
            if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
            val d = mx - mn
            val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
            val h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } / 6
            return doubleArrayOf(h, s, l)
        }

        fun hslToRgb(h: DoubleArray): IntArray {
            val (hh, s, l) = Triple(h[0], h[1], h[2])
            if (s == 0.0) { val v = (l * 255).toInt().coerceIn(0, 255); return intArrayOf(v, v, v) }
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            fun f(t0: Double): Int {
                var t = t0
                if (t < 0) t += 1; if (t > 1) t -= 1
                val v = when {
                    t < 1.0 / 6 -> p + (q - p) * 6 * t
                    t < 0.5 -> q
                    t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                    else -> p
                }
                return Math.round(v * 255).toInt().coerceIn(0, 255)
            }
            return intArrayOf(f(hh + 1.0 / 3), f(hh), f(hh - 1.0 / 3))
        }
    }
}
