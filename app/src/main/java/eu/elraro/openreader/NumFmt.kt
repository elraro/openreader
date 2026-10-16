package eu.elraro.openreader

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Excel number format codes (subset: numbers, percent, thousands, currency, scientific, dates/times). */
object NumFmt {
    private val BUILTIN = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%", 11 to "0.00E+00",
        14 to "yyyy-mm-dd", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM",
        20 to "h:mm", 21 to "h:mm:ss", 22 to "yyyy-mm-dd h:mm", 37 to "#,##0;-#,##0", 38 to "#,##0;[Red]-#,##0",
        39 to "#,##0.00;-#,##0.00", 40 to "#,##0.00;[Red]-#,##0.00", 41 to "#,##0", 42 to "#,##0", 43 to "#,##0.00", 44 to "#,##0.00",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 49 to "@",
    )

    fun code(id: Int, custom: Map<Int, String>): String = custom[id] ?: BUILTIN[id] ?: "General"

    fun isDate(code: String): Boolean {
        val s = strip(code).lowercase()
        if (s == "general") return false
        return s.any { it in "ymdhs" } && !s.contains("e+") && !s.contains("e-")
    }

    private fun strip(code: String): String =
        code.substringBefore(';').replace(Regex("\"[^\"]*\"|\\[[^]]*]|\\\\.|_.|\\*."), "")

    fun format(value: Double, code: String): String {
        if (code == "General" || code.isEmpty() || code == "@") return general(value)
        val sections = splitSections(code)
        var sec = sections[0]
        var v = value
        if (sections.size >= 2 && value < 0) { sec = sections[1]; v = -value }
        else if (sections.size >= 3 && value == 0.0) sec = sections[2]
        else if (sections.size == 1 && isDate(sec)) { /* no sign handling */ }
        return if (isDate(sec)) date(value, sec) else number(v, sec, negativeNoSection = sections.size == 1 && value < 0)
    }

    fun general(d: Double): String =
        if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString()
        else java.math.BigDecimal(d).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        for (c in code) {
            if (c == '"') q = !q
            if (c == ';' && !q) { out.add(sb.toString()); sb.setLength(0) } else sb.append(c)
        }
        out.add(sb.toString())
        return out
    }

    private fun number(v: Double, sec: String, negativeNoSection: Boolean): String {
        // tokenise into literal prefix, numeric pattern, literal suffix
        val prefix = StringBuilder(); val pat = StringBuilder(); val suffix = StringBuilder()
        var i = 0
        var state = 0 // 0 prefix, 1 pattern, 2 suffix
        var percent = false
        var sci = false
        while (i < sec.length) {
            val c = sec[i]
            fun lit(s: String) { if (state == 1) state = 2; (if (state == 0) prefix else suffix).append(s) }
            when {
                c == '"' -> { val e = sec.indexOf('"', i + 1).let { if (it < 0) sec.length else it }; lit(sec.substring(i + 1, e)); i = e }
                c == '\\' && i + 1 < sec.length -> { lit(sec[i + 1].toString()); i++ }
                c == '_' -> { lit(" "); i++ }
                c == '*' -> i++
                c == '[' -> {
                    val e = sec.indexOf(']', i).let { if (it < 0) sec.length - 1 else it }
                    val inner = sec.substring(i + 1, e)
                    if (inner.startsWith("$")) lit(inner.substring(1).substringBefore('-'))
                    i = e
                }
                c == '%' -> { percent = true; lit("%") }
                (c == 'E' || c == 'e') && i + 1 < sec.length && (sec[i + 1] == '+' || sec[i + 1] == '-') && state == 1 -> {
                    sci = true; pat.append("E"); i++
                    while (i + 1 < sec.length && sec[i + 1] == '0') { pat.append('0'); i++ }
                }
                c in "0#?.," -> { if (state == 0) state = 1; if (state == 1) pat.append(c) else suffix.append(c) }
                else -> lit(c.toString())
            }
            i++
        }
        val p = pat.toString()
        if (p.isEmpty()) return prefix.toString() + suffix
        var x = if (percent) v * 100 else v
        val body: String
        if (sci) {
            val mant = p.substringBefore('E')
            val dec = mant.substringAfter('.', "").count { it in "0#?" }
            val s = String.format(Locale.US, "%.${dec}E", x)
            body = s
        } else {
            val intPart = p.substringBefore('.')
            val decPart = p.substringAfter('.', "")
            val dec = decPart.count { it in "0#?" }
            val minDec = decPart.count { it == '0' }
            val grouping = intPart.contains(',')
            // trailing commas scale by 1000
            var scale = 0
            var t = p.length
            while (t > 0 && p[t - 1] == ',') { scale++; t-- }
            if (scale > 0) x /= Math.pow(1000.0, scale.toDouble())
            val minInt = intPart.count { it == '0' }
            var s = java.math.BigDecimal(x).setScale(dec, java.math.RoundingMode.HALF_UP).abs().toPlainString()
            var ip = s.substringBefore('.')
            var fp = s.substringAfter('.', "")
            while (fp.length > minDec && fp.endsWith("0")) fp = fp.dropLast(1)
            if (ip == "0" && minInt == 0 && intPart.isNotEmpty() && !intPart.contains('0')) ip = if (fp.isEmpty()) "0" else ""
            while (ip.length < minInt) ip = "0$ip"
            if (grouping) ip = ip.reversed().chunked(3).joinToString(",").reversed()
            body = if (fp.isEmpty()) ip else "$ip.$fp"
        }
        val neg = (negativeNoSection || false) && x != 0.0
        return (if (neg) "-" else "") + prefix + body + suffix
    }

    private fun date(serial: Double, sec: String): String {
        val ms = Math.round((serial - 25569.0) * 86400000.0)
        val hasAmPm = Regex("am/pm|a/p", RegexOption.IGNORE_CASE).containsMatchIn(sec)
        val sb = StringBuilder()
        var i = 0
        var lastWasHour = false
        val tokens = ArrayList<String>()
        // collect tokens first to look ahead for minutes-vs-month
        while (i < sec.length) {
            val c = sec[i]
            when {
                c == '"' -> { val e = sec.indexOf('"', i + 1).let { if (it < 0) sec.length else it }; tokens.add("'" + sec.substring(i + 1, e)); i = e + 1 }
                c == '\\' && i + 1 < sec.length -> { tokens.add("'" + sec[i + 1]); i += 2 }
                c == '_' || c == '*' -> i += 2
                c == '[' -> {
                    val e = sec.indexOf(']', i).let { if (it < 0) sec.length - 1 else it }
                    val inner = sec.substring(i + 1, e).lowercase()
                    if (inner.all { it in "hms" }) tokens.add(inner.take(1).repeat(inner.length)) // [h] elapsed: approximated
                    i = e + 1
                }
                c.lowercaseChar() in "ymdhs" -> { var j = i; while (j < sec.length && sec[j].lowercaseChar() == c.lowercaseChar()) j++; tokens.add(sec.substring(i, j).lowercase()); i = j }
                sec.regionMatches(i, "AM/PM", 0, 5, true) -> { tokens.add("ampm"); i += 5 }
                sec.regionMatches(i, "A/P", 0, 3, true) -> { tokens.add("ampm"); i += 3 }
                else -> { tokens.add("'$c"); i++ }
            }
        }
        for ((k, t) in tokens.withIndex()) {
            when {
                t.startsWith("'") -> sb.append("'").append(t.substring(1).replace("'", "''")).append("'")
                t[0] == 'y' -> sb.append(if (t.length <= 2) "yy" else "yyyy")
                t[0] == 'd' -> sb.append(when (t.length) { 1 -> "d"; 2 -> "dd"; 3 -> "EEE"; else -> "EEEE" })
                t[0] == 'h' -> { sb.append(if (hasAmPm) (if (t.length == 1) "h" else "hh") else (if (t.length == 1) "H" else "HH")); lastWasHour = true; continue }
                t[0] == 's' -> sb.append(if (t.length == 1) "s" else "ss")
                t == "ampm" -> sb.append("a")
                t[0] == 'm' -> {
                    // minutes when preceded by hours or followed by seconds
                    val prev = tokens.subList(0, k).lastOrNull { !it.startsWith("'") }
                    val next = tokens.subList(k + 1, tokens.size).firstOrNull { !it.startsWith("'") }
                    val minutes = prev?.get(0) == 'h' || next?.get(0) == 's'
                    sb.append(if (minutes) (if (t.length == 1) "m" else "mm") else when (t.length) { 1 -> "M"; 2 -> "MM"; 3 -> "MMM"; else -> "MMMM" })
                }
            }
            lastWasHour = false
        }
        val f = SimpleDateFormat(sb.toString(), Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }
}
