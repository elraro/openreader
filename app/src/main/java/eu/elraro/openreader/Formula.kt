package eu.elraro.openreader

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** Spreadsheet error value, e.g. #DIV/0!. */
class XlError(val code: String) { override fun toString() = code }

/**
 * Small formula evaluator for cells that have a formula but no cached result.
 * Values are Double, String, Boolean, XlError or null (blank).
 */
class Workbook {
    class Cell(var value: Any?, val formula: String?, val dr: Int = 0, val dc: Int = 0) { var state = 0 }

    val sheets = LinkedHashMap<String, HashMap<Long, Cell>>()

    fun put(sheet: String, row: Int, col: Int, cell: Cell) {
        sheets.getOrPut(sheet) { HashMap() }[key(row, col)] = cell
    }

    /** Values of a reference like 'My Sheet'!$B$2:$B$6, row by row; null if unparseable. */
    fun rangeValues(ref: String): List<Any?>? {
        val bang = ref.lastIndexOf('!')
        val sheet = (if (bang >= 0) ref.substring(0, bang) else sheets.keys.firstOrNull() ?: return null).trim('\'').replace("''", "'")
        val m = Regex("\\$?([A-Z]{1,3})\\$?([0-9]+)(?::\\$?([A-Z]{1,3})\\$?([0-9]+))?").matchEntire(ref.substring(bang + 1)) ?: return null
        fun col(s: String) = s.fold(0) { a, c -> a * 26 + (c - 'A' + 1) } - 1
        val c1 = col(m.groupValues[1]); val r1 = m.groupValues[2].toInt() - 1
        val c2 = if (m.groupValues[3].isEmpty()) c1 else col(m.groupValues[3]); val r2 = if (m.groupValues[4].isEmpty()) r1 else m.groupValues[4].toInt() - 1
        if ((r2 - r1 + 1).toLong() * (c2 - c1 + 1) > 20000) return null
        val out = ArrayList<Any?>()
        for (r in r1..r2) for (c in c1..c2) out.add(value(sheet, r, c))
        return out
    }

    fun eval(sheet: String, row: Int, col: Int): Any? = value(sheet, row, col)

    internal fun value(sheet: String, row: Int, col: Int): Any? {
        val cell = sheets[sheet]?.get(key(row, col)) ?: return null
        if (cell.formula == null || cell.state == 2) return cell.value
        if (cell.state == 1) return XlError("#REF!") // circular
        cell.state = 1
        cell.value = try {
            Parser(this, sheet, cell.formula, cell.dr, cell.dc).evaluate()
        } catch (e: StackOverflowError) { XlError("#VALUE!") } catch (e: Exception) { XlError("#VALUE!") }
        cell.state = 2
        return cell.value
    }

    private class Range(val rows: Int, val cols: Int, val cells: List<Any?>) {
        fun at(r: Int, c: Int) = cells[r * cols + c]
    }

    private class Parser(val wb: Workbook, val sheet: String, val src: String, val dr: Int, val dc: Int) {
        var p = 0

        fun evaluate(): Any? {
            val v = expr(0)
            skip()
            return scalar(v)
        }

        fun skip() { while (p < src.length && src[p] == ' ') p++ }
        fun peek(): Char { skip(); return if (p < src.length) src[p] else '\u0000' }

        // precedence: comparison 1, & 2, +- 3, */ 4, ^ 5
        private fun opAt(): Pair<String, Int>? {
            skip()
            if (p >= src.length) return null
            val two = if (p + 1 < src.length) src.substring(p, p + 2) else ""
            if (two == "<>" || two == "<=" || two == ">=") return two to 1
            return when (src[p]) {
                '=', '<', '>' -> src[p].toString() to 1
                '&' -> "&" to 2
                '+', '-' -> src[p].toString() to 3
                '*', '/' -> src[p].toString() to 4
                '^' -> "^" to 5
                else -> null
            }
        }

        fun expr(minPrec: Int): Any? {
            var left = unary()
            while (true) {
                val (op, prec) = opAt() ?: break
                if (prec < minPrec) break
                p += op.length
                val right = expr(prec + 1)
                left = binary(op, left, right)
            }
            return left
        }

        fun unary(): Any? {
            val c = peek()
            if (c == '-') { p++; val v = unary(); return err(v) ?: -num(v) }
            if (c == '+') { p++; return unary() }
            var v = primary()
            while (peek() == '%') { p++; v = err(v) ?: (num(v) / 100) }
            return v
        }

        fun primary(): Any? {
            val c = peek()
            when {
                c == '(' -> { p++; val v = expr(0); if (peek() == ')') p++; return v }
                c == '"' -> {
                    p++; val sb = StringBuilder()
                    while (p < src.length) {
                        if (src[p] == '"') { if (p + 1 < src.length && src[p + 1] == '"') { sb.append('"'); p += 2; continue }; p++; break }
                        sb.append(src[p++])
                    }
                    return sb.toString()
                }
                c == '#' -> { val s = p; while (p < src.length && (src[p].isLetterOrDigit() || src[p] in "#/!?")) p++; return XlError(src.substring(s, p)) }
                c.isDigit() || c == '.' -> {
                    val s = p
                    while (p < src.length && (src[p].isDigit() || src[p] == '.')) p++
                    if (p < src.length && (src[p] == 'E' || src[p] == 'e') && p + 1 < src.length && (src[p + 1].isDigit() || src[p + 1] in "+-")) {
                        p += 2; while (p < src.length && src[p].isDigit()) p++
                    }
                    return src.substring(s, p).toDoubleOrNull() ?: XlError("#VALUE!")
                }
                c == '\'' || c.isLetter() || c == '_' || c == '$' -> return nameOrRef()
            }
            p = src.length
            return XlError("#VALUE!")
        }

        private fun nameOrRef(): Any? {
            var sh = sheet
            val start = p
            var sheetPrefix = false
            if (src[p] == '\'') {
                val sb = StringBuilder(); p++
                while (p < src.length) {
                    if (src[p] == '\'') { if (p + 1 < src.length && src[p + 1] == '\'') { sb.append('\''); p += 2; continue }; p++; break }
                    sb.append(src[p++])
                }
                if (p < src.length && src[p] == '!') { p++; sh = sb.toString(); sheetPrefix = true } else { p = start; p = src.length; return XlError("#NAME?") }
            } else {
                var q = p
                while (q < src.length && (src[q].isLetterOrDigit() || src[q] in "_.$")) q++
                if (q < src.length && src[q] == '!') { sh = src.substring(p, q); p = q + 1; sheetPrefix = true }
            }
            val s = p
            while (p < src.length && (src[p].isLetterOrDigit() || src[p] in "_.$")) p++
            val word = src.substring(s, p)
            if (!sheetPrefix && p < src.length && src[p] == '(') {
                p++
                val args = ArrayList<Any?>()
                if (peek() == ')') p++ else {
                    while (true) {
                        args.add(if (peek() == ',' || peek() == ')') null else expr(0))
                        val d = peek()
                        if (d == ',') { p++; continue }
                        if (d == ')') p++
                        break
                    }
                }
                return call(word.uppercase(), args)
            }
            if (!sheetPrefix) {
                when (word.uppercase()) { "TRUE" -> return true; "FALSE" -> return false }
            }
            val a = parseRef(word) ?: return XlError("#NAME?")
            if (peek() == ':') {
                val save = p
                p++
                val s2 = p
                while (p < src.length && (src[p].isLetterOrDigit() || src[p] == '$')) p++
                val b = parseRef(src.substring(s2, p))
                if (b == null) { p = save; return cellVal(sh, a.first, a.second) }
                return range(sh, a, b)
            }
            return cellVal(sh, a.first, a.second)
        }

        /** "$B$2" -> (row, col), with the shared-formula offset applied to relative parts. */
        private fun parseRef(w: String): Pair<Int, Int>? {
            val m = REF.matchEntire(w) ?: return null
            val colAbs = m.groupValues[1].isNotEmpty(); val rowAbs = m.groupValues[3].isNotEmpty()
            var col = 0
            for (ch in m.groupValues[2].uppercase()) col = col * 26 + (ch - 'A' + 1)
            val row = m.groupValues[4].toInt()
            if (col > 16384 || row > 1048576) return null
            return (row - 1 + if (rowAbs) 0 else dr) to (col - 1 + if (colAbs) 0 else dc)
        }

        private fun cellVal(sh: String, r: Int, c: Int): Any? = if (r < 0 || c < 0) XlError("#REF!") else wb.value(sh, r, c)

        private fun range(sh: String, a: Pair<Int, Int>, b: Pair<Int, Int>): Any? {
            val r1 = minOf(a.first, b.first); val r2 = maxOf(a.first, b.first)
            val c1 = minOf(a.second, b.second); val c2 = maxOf(a.second, b.second)
            if (r1 < 0 || c1 < 0) return XlError("#REF!")
            if ((r2 - r1 + 1).toLong() * (c2 - c1 + 1) > 200_000) return XlError("#VALUE!")
            val sheetCells = wb.sheets[sh]
            val out = ArrayList<Any?>()
            for (r in r1..r2) for (c in c1..c2) out.add(if (sheetCells == null || !sheetCells.containsKey(key(r, c))) null else wb.value(sh, r, c))
            return Range(r2 - r1 + 1, c2 - c1 + 1, out)
        }

        // ------------------------------------------------------------ conversions

        private fun scalar(v: Any?): Any? = if (v is Range) v.cells.firstOrNull() else v
        private fun err(v: Any?): XlError? = (scalar(v) as? XlError)
        private fun num(v: Any?): Double = when (val s = scalar(v)) {
            null -> 0.0
            is Double -> s
            is Boolean -> if (s) 1.0 else 0.0
            is String -> s.trim().toDoubleOrNull() ?: throw IllegalArgumentException("value")
            else -> 0.0
        }
        private fun str(v: Any?): String = when (val s = scalar(v)) {
            null -> ""
            is Double -> NumFmt.general(s)
            is Boolean -> if (s) "TRUE" else "FALSE"
            else -> s.toString()
        }
        private fun bool(v: Any?): Boolean = when (val s = scalar(v)) {
            is Boolean -> s
            is Double -> s != 0.0
            is String -> s.equals("TRUE", true)
            else -> false
        }

        private fun binary(op: String, a: Any?, b: Any?): Any? {
            err(a)?.let { return it }; err(b)?.let { return it }
            return try {
                when (op) {
                    "+" -> num(a) + num(b)
                    "-" -> num(a) - num(b)
                    "*" -> num(a) * num(b)
                    "/" -> { val d = num(b); if (d == 0.0) XlError("#DIV/0!") else num(a) / d }
                    "^" -> num(a).pow(num(b))
                    "&" -> str(a) + str(b)
                    else -> compare(op, scalar(a), scalar(b))
                }
            } catch (e: IllegalArgumentException) { XlError("#VALUE!") }
        }

        private fun compare(op: String, a: Any?, b: Any?): Boolean {
            val c = when {
                a is String && b is String -> a.compareTo(b, true)
                a is String && b !is String -> 1
                a !is String && b is String -> -1
                else -> num(a ?: 0.0).compareTo(num(b ?: 0.0))
            }
            return when (op) { "=" -> c == 0; "<>" -> c != 0; "<" -> c < 0; ">" -> c > 0; "<=" -> c <= 0; else -> c >= 0 }
        }

        /** Numbers found in the arguments (ranges skip text and blanks, scalars are coerced). */
        private fun numbers(args: List<Any?>): List<Double> {
            val out = ArrayList<Double>()
            for (a in args) {
                if (a is Range) for (x in a.cells) { if (x is Double) out.add(x) else if (x is XlError) throw IllegalStateException(x.code) }
                else if (a is XlError) throw IllegalStateException(a.code)
                else if (a != null) out.add(num(a))
            }
            return out
        }

        private fun flat(args: List<Any?>): List<Any?> = args.flatMap { if (it is Range) it.cells else listOf(it) }

        private fun criteria(crit: Any?): (Any?) -> Boolean {
            val s = str(crit)
            val m = Regex("^(<=|>=|<>|<|>|=)?(.*)$").matchEntire(s)!!
            val op = m.groupValues[1].ifEmpty { "=" }
            val rhs = m.groupValues[2]
            val n = rhs.toDoubleOrNull()
            return { v ->
                if (n != null && v is Double) compare(op, v, n)
                else if (n != null && op in listOf("<", ">", "<=", ">=")) false
                else if (rhs.contains('*') || rhs.contains('?')) {
                    val re = Regex("^" + rhs.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) } }.joinToString("") + "$", RegexOption.IGNORE_CASE)
                    (op == "=") == re.matches(str(v))
                } else compare(op, if (v is Double) v else str(v), if (n != null) n else rhs)
            }
        }

        private fun call(name: String, a: List<Any?>): Any? {
            try {
                return when (name) {
                    "SUM" -> numbers(a).sum()
                    "PRODUCT" -> numbers(a).fold(1.0) { x, y -> x * y }
                    "AVERAGE" -> numbers(a).let { if (it.isEmpty()) XlError("#DIV/0!") else it.sum() / it.size }
                    "MIN" -> numbers(a).minOrNull() ?: 0.0
                    "MAX" -> numbers(a).maxOrNull() ?: 0.0
                    "COUNT" -> flat(a).count { it is Double }.toDouble()
                    "COUNTA" -> flat(a).count { it != null && it != "" }.toDouble()
                    "COUNTBLANK" -> flat(a).count { it == null || it == "" }.toDouble()
                    "ABS" -> abs(num(a[0]))
                    "SQRT" -> num(a[0]).let { if (it < 0) XlError("#NUM!") else sqrt(it) }
                    "POWER" -> num(a[0]).pow(num(a[1]))
                    "MOD" -> { val d = num(a[1]); if (d == 0.0) XlError("#DIV/0!") else num(a[0]) - d * floor(num(a[0]) / d) }
                    "INT" -> floor(num(a[0]))
                    "ROUND" -> round(num(a[0]), num(a[1]).toInt(), 0)
                    "ROUNDUP" -> round(num(a[0]), num(a[1]).toInt(), 1)
                    "ROUNDDOWN" -> round(num(a[0]), num(a[1]).toInt(), 2)
                    "IF" -> if (err(a[0]) != null) err(a[0]) else if (bool(a[0])) (a.getOrNull(1) ?: 0.0) else if (a.size > 2) (a[2] ?: 0.0) else false
                    "IFERROR" -> if (scalar(a[0]) is XlError) a.getOrNull(1) else a[0]
                    "AND" -> flat(a).filter { it != null }.all { bool(it) }
                    "OR" -> flat(a).filter { it != null }.any { bool(it) }
                    "NOT" -> !bool(a[0])
                    "ISBLANK" -> scalar(a[0]) == null
                    "ISNUMBER" -> scalar(a[0]) is Double
                    "ISTEXT" -> scalar(a[0]) is String
                    "ISERROR" -> scalar(a[0]) is XlError
                    "CONCATENATE", "CONCAT" -> flat(a).joinToString("") { str(it) }
                    "LEN" -> str(a[0]).length.toDouble()
                    "UPPER" -> str(a[0]).uppercase()
                    "LOWER" -> str(a[0]).lowercase()
                    "TRIM" -> str(a[0]).trim().replace(Regex(" +"), " ")
                    "LEFT" -> str(a[0]).take(if (a.size > 1) num(a[1]).toInt() else 1)
                    "RIGHT" -> str(a[0]).takeLast(if (a.size > 1) num(a[1]).toInt() else 1)
                    "MID" -> str(a[0]).drop(num(a[1]).toInt() - 1).take(num(a[2]).toInt())
                    "TEXT" -> NumFmt.format(num(a[0]), str(a[1]))
                    "VALUE" -> str(a[0]).trim().toDoubleOrNull() ?: XlError("#VALUE!")
                    "SUMIF", "AVERAGEIF" -> {
                        val rng = a[0] as? Range ?: return XlError("#VALUE!")
                        val test = criteria(a[1])
                        val sumR = (a.getOrNull(2) as? Range) ?: rng
                        var s = 0.0; var n = 0
                        for (i in rng.cells.indices) if (test(rng.cells[i])) { (sumR.cells.getOrNull(i) as? Double)?.let { s += it; n++ } }
                        if (name == "SUMIF") s else if (n == 0) XlError("#DIV/0!") else s / n
                    }
                    "COUNTIF" -> {
                        val test = criteria(a[1])
                        ((a[0] as? Range)?.cells ?: listOf(a[0])).count { test(it) }.toDouble()
                    }
                    "VLOOKUP" -> vlookup(a)
                    "DATE" -> {
                        val y = num(a[0]).toInt(); val m = num(a[1]).toInt(); val d = num(a[2]).toInt()
                        java.time.LocalDate.of(y, 1, 1).plusMonths((m - 1).toLong()).plusDays((d - 1).toLong())
                            .toEpochDay().toDouble() + 25569.0
                    }
                    "YEAR", "MONTH", "DAY" -> {
                        val d = java.time.LocalDate.ofEpochDay((num(a[0]) - 25569.0).toLong())
                        (when (name) { "YEAR" -> d.year; "MONTH" -> d.monthValue; else -> d.dayOfMonth }).toDouble()
                    }

                    "MEDIAN" -> numbers(a).sorted().let { if (it.isEmpty()) XlError("#NUM!") else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
                    "STDEV", "STDEV.S", "VAR", "VAR.S", "STDEVP", "STDEV.P", "VARP", "VAR.P" -> {
                        val v = numbers(a); val pop = name.endsWith("P") || name.endsWith(".P")
                        if (v.size < (if (pop) 1 else 2)) XlError("#DIV/0!") else {
                            val m = v.average(); val ss = v.sumOf { (it - m) * (it - m) } / (if (pop) v.size else v.size - 1)
                            if (name.startsWith("STDEV")) sqrt(ss) else ss
                        }
                    }
                    "LARGE", "SMALL" -> { val v = numbers(listOf(a[0])).sorted(); val k = num(a[1]).toInt(); if (k < 1 || k > v.size) XlError("#NUM!") else if (name == "SMALL") v[k - 1] else v[v.size - k] }
                    "RANK", "RANK.EQ" -> { val x = num(a[0]); val v = numbers(listOf(a[1])); if (v.none { it == x }) XlError("#N/A") else {
                        val desc = a.size < 3 || num(a[2]) == 0.0; (1 + v.count { if (desc) it > x else it < x }).toDouble() } }
                    "SUMPRODUCT" -> { val rs = a.map { it as? Range ?: return XlError("#VALUE!") }; var t = 0.0
                        for (i in rs[0].cells.indices) { var pr = 1.0; for (r in rs) pr *= (r.cells.getOrNull(i) as? Double ?: 0.0); t += pr }; t }
                    "SUMIFS", "COUNTIFS", "AVERAGEIFS" -> {
                        val sumR = if (name == "COUNTIFS") null else a[0] as? Range
                        val pairs = (if (name == "COUNTIFS") 0 else 1).let { st -> (st until a.size step 2).map { (a[it] as? Range ?: return XlError("#VALUE!")) to criteria(a[it + 1]) } }
                        val n0 = pairs[0].first.cells.size; var s = 0.0; var c = 0
                        for (i in 0 until n0) if (pairs.all { (r, t) -> t(r.cells.getOrNull(i)) }) { c++; (sumR?.cells?.getOrNull(i) as? Double)?.let { s += it } }
                        when (name) { "SUMIFS" -> s; "COUNTIFS" -> c.toDouble(); else -> if (c == 0) XlError("#DIV/0!") else s / c }
                    }
                    "INDEX" -> { val r = a[0] as? Range; val row = num(a[1]).toInt(); val col = if (a.size > 2) num(a[2]).toInt() else 1
                        if (r == null) a[0] else if (r.rows == 1 && a.size == 2) r.cells.getOrNull(row - 1) ?: XlError("#REF!")
                        else if (row < 1 || row > r.rows || col < 1 || col > r.cols) XlError("#REF!") else r.at(row - 1, col - 1) }
                    "MATCH" -> { val key = scalar(a[0]); val r = a[1] as? Range ?: return XlError("#N/A"); val mt = if (a.size > 2) num(a[2]).toInt() else 1
                        var best = -1
                        for ((i, v) in r.cells.withIndex()) {
                            if (v == null || key == null) continue
                            if (mt == 0) { if ((v is Double) == (key is Double) && compare("=", v, key)) { best = i; break } }
                            else if ((v is Double) == (key is Double) && (if (mt > 0) compare("<=", v, key) else compare(">=", v, key))) best = i else if (best >= 0) break
                        }
                        if (best < 0) XlError("#N/A") else (best + 1).toDouble() }
                    "HLOOKUP" -> { val key = scalar(a[0]); val t = a[1] as? Range ?: return XlError("#VALUE!"); val row = num(a[2]).toInt() - 1
                        if (row < 0 || row >= t.rows) XlError("#REF!") else { val exact = a.size > 3 && !bool(a[3]); var best = -1
                            for (c in 0 until t.cols) { val v = t.at(0, c); if (v == null || key == null) continue
                                if (exact) { if (compare("=", v, key)) { best = c; break } } else if ((v is Double) == (key is Double) && compare("<=", v, key)) best = c else if (best >= 0) break }
                            if (best < 0) XlError("#N/A") else t.at(row, best) } }
                    "LOOKUP" -> { val key = scalar(a[0]); val r = a[1] as? Range ?: return XlError("#VALUE!"); val res = (a.getOrNull(2) as? Range) ?: r; var best = -1
                        for ((i, v) in r.cells.withIndex()) if (v != null && key != null && (v is Double) == (key is Double) && compare("<=", v, key)) best = i
                        if (best < 0) XlError("#N/A") else res.cells.getOrNull(best) }
                    "CHOOSE" -> { val i = num(a[0]).toInt(); if (i < 1 || i >= a.size) XlError("#VALUE!") else a[i] }
                    "IFNA" -> if ((scalar(a[0]) as? XlError)?.code == "#N/A") a[1] else a[0]
                    "ISNA" -> (scalar(a[0]) as? XlError)?.code == "#N/A"
                    "ISERR" -> (scalar(a[0]) as? XlError)?.let { it.code != "#N/A" } == true
                    "ISLOGICAL" -> scalar(a[0]) is Boolean
                    "ISEVEN" -> num(a[0]).toLong() % 2 == 0L
                    "ISODD" -> num(a[0]).toLong() % 2 != 0L
                    "XOR" -> flat(a).count { bool(it) } % 2 == 1
                    "TRUE" -> true
                    "FALSE" -> false
                    "PI" -> Math.PI
                    "EXP" -> Math.exp(num(a[0]))
                    "LN" -> num(a[0]).let { if (it <= 0) XlError("#NUM!") else Math.log(it) }
                    "LOG10" -> num(a[0]).let { if (it <= 0) XlError("#NUM!") else Math.log10(it) }
                    "LOG" -> num(a[0]).let { x -> if (x <= 0) XlError("#NUM!") else Math.log(x) / Math.log(if (a.size > 1) num(a[1]) else 10.0) }
                    "SIN" -> Math.sin(num(a[0])); "COS" -> Math.cos(num(a[0])); "TAN" -> Math.tan(num(a[0]))
                    "ASIN" -> Math.asin(num(a[0])); "ACOS" -> Math.acos(num(a[0])); "ATAN" -> Math.atan(num(a[0])); "ATAN2" -> Math.atan2(num(a[1]), num(a[0]))
                    "DEGREES" -> Math.toDegrees(num(a[0])); "RADIANS" -> Math.toRadians(num(a[0]))
                    "SIGN" -> Math.signum(num(a[0]))
                    "TRUNC" -> round(num(a[0]), if (a.size > 1) num(a[1]).toInt() else 0, 2)
                    "CEILING" -> { val sg = if (a.size > 1) num(a[1]) else 1.0; if (sg == 0.0) 0.0 else Math.ceil(num(a[0]) / sg) * sg }
                    "FLOOR" -> { val sg = if (a.size > 1) num(a[1]) else 1.0; if (sg == 0.0) XlError("#DIV/0!") else floor(num(a[0]) / sg) * sg }
                    "EVEN" -> num(a[0]).let { v -> val c = Math.ceil(abs(v) / 2) * 2; if (v < 0) -c else c }
                    "ODD" -> num(a[0]).let { v -> var c = Math.ceil(abs(v)); if (c % 2 == 0.0) c += 1; if (v < 0) -c else c }
                    "FACT" -> num(a[0]).toInt().let { n -> if (n < 0 || n > 170) XlError("#NUM!") else (1..n).fold(1.0) { x, y -> x * y } }
                    "GCD" -> numbers(a).map { it.toLong() }.reduce { x, y -> var p = x; var q = y; while (q != 0L) { val t = p % q; p = q; q = t }; p }.toDouble()
                    "RAND", "RANDBETWEEN" -> XlError("#N/A")
                    "FIND", "SEARCH" -> { val hay = str(a[1]); val st = if (a.size > 2) num(a[2]).toInt() - 1 else 0
                        val i = hay.indexOf(str(a[0]), st.coerceAtLeast(0), ignoreCase = name == "SEARCH"); if (i < 0) XlError("#VALUE!") else (i + 1).toDouble() }
                    "SUBSTITUTE" -> str(a[0]).replace(str(a[1]), str(a[2]))
                    "REPLACE" -> { val t0 = str(a[0]); val st = (num(a[1]).toInt() - 1).coerceIn(0, t0.length); t0.substring(0, st) + str(a[3]) + t0.substring((st + num(a[2]).toInt()).coerceIn(st, t0.length)) }
                    "REPT" -> str(a[0]).repeat(num(a[1]).toInt().coerceIn(0, 10000))
                    "PROPER" -> str(a[0]).lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                    "EXACT" -> str(a[0]) == str(a[1])
                    "CHAR" -> num(a[0]).toInt().toChar().toString()
                    "CODE" -> str(a[0]).firstOrNull()?.code?.toDouble() ?: XlError("#VALUE!")
                    "T" -> if (scalar(a[0]) is String) scalar(a[0]) else ""
                    "N" -> (scalar(a[0]) as? Double) ?: 0.0
                    "TEXTJOIN" -> { val sep = str(a[0]); val skip = bool(a[1]); flat(a.drop(2)).filter { !skip || (it != null && it != "") }.joinToString(sep) { str(it) } }
                    "TODAY" -> floor(System.currentTimeMillis() / 86400000.0) + 25569.0
                    "NOW" -> System.currentTimeMillis() / 86400000.0 + 25569.0
                    "HOUR" -> (num(a[0]) % 1 * 24).toInt().toDouble()
                    "MINUTE" -> ((num(a[0]) % 1 * 1440).toInt() % 60).toDouble()
                    "SECOND" -> ((num(a[0]) % 1 * 86400).let { Math.round(it) } % 60).toDouble()
                    "WEEKDAY" -> { val d = java.time.LocalDate.ofEpochDay((num(a[0]) - 25569.0).toLong()).dayOfWeek.value % 7 + 1
                        when (if (a.size > 1) num(a[1]).toInt() else 1) { 2 -> ((d + 5) % 7 + 1).toDouble(); 3 -> ((d + 5) % 7).toDouble(); else -> d.toDouble() } }
                    "EDATE" -> java.time.LocalDate.ofEpochDay((num(a[0]) - 25569.0).toLong()).plusMonths(num(a[1]).toLong()).toEpochDay() + 25569.0
                    "EOMONTH" -> java.time.LocalDate.ofEpochDay((num(a[0]) - 25569.0).toLong()).plusMonths(num(a[1]).toLong()).let { it.withDayOfMonth(it.lengthOfMonth()) }.toEpochDay() + 25569.0
                    "DAYS" -> num(a[0]) - num(a[1])
                    "DATEVALUE" -> try { java.time.LocalDate.parse(str(a[0]).trim()).toEpochDay() + 25569.0 } catch (e: Exception) { XlError("#VALUE!") }
                    "PMT" -> { val r = num(a[0]); val n = num(a[1]); val pv = num(a[2]); val fv = if (a.size > 3) num(a[3]) else 0.0
                        if (r == 0.0) -(pv + fv) / n else -(r * (pv * (1 + r).pow(n) + fv)) / ((1 + r).pow(n) - 1) }
                    "FV" -> { val r = num(a[0]); val n = num(a[1]); val pmt = num(a[2]); val pv = if (a.size > 3) num(a[3]) else 0.0
                        if (r == 0.0) -(pv + pmt * n) else -(pv * (1 + r).pow(n) + pmt * ((1 + r).pow(n) - 1) / r) }
                    "PV" -> { val r = num(a[0]); val n = num(a[1]); val pmt = num(a[2]); val fv = if (a.size > 3) num(a[3]) else 0.0
                        if (r == 0.0) -(fv + pmt * n) else -(fv + pmt * ((1 + r).pow(n) - 1) / r) / (1 + r).pow(n) }
                    "ROWS" -> ((a[0] as? Range)?.rows ?: 1).toDouble()
                    "COLUMNS" -> ((a[0] as? Range)?.cols ?: 1).toDouble()
                    "AREAS" -> 1.0
                    "SUBTOTAL" -> call(when (num(a[0]).toInt() % 100) { 1 -> "AVERAGE"; 2 -> "COUNT"; 3 -> "COUNTA"; 4 -> "MAX"; 5 -> "MIN"; 6 -> "PRODUCT"; 9 -> "SUM"; else -> "SUM" }, a.drop(1))
                    "AGGREGATE" -> call(when (num(a[0]).toInt()) { 1 -> "AVERAGE"; 2 -> "COUNT"; 3 -> "COUNTA"; 4 -> "MAX"; 5 -> "MIN"; 6 -> "PRODUCT"; else -> "SUM" }, a.drop(2))
                    "MAXA" -> flat(a).mapNotNull { it as? Double }.maxOrNull() ?: 0.0
                    "MINA" -> flat(a).mapNotNull { it as? Double }.minOrNull() ?: 0.0
                    "AVERAGEA" -> flat(a).filter { it != null }.map { if (it is Double) it else if (it is Boolean) (if (it) 1.0 else 0.0) else 0.0 }.let { if (it.isEmpty()) XlError("#DIV/0!") else it.average() }
                    else -> XlError("#NAME?")
                }
            } catch (e: IllegalStateException) { return XlError(e.message ?: "#VALUE!") }
            catch (e: IllegalArgumentException) { return XlError("#VALUE!") }
            catch (e: IndexOutOfBoundsException) { return XlError("#VALUE!") }
        }

        private fun round(v: Double, digits: Int, mode: Int): Double {
            val f = 10.0.pow(digits)
            val x = v * f
            val r = when (mode) {
                0 -> if (x >= 0) floor(x + 0.5) else -floor(-x + 0.5)
                1 -> if (x >= 0) Math.ceil(x) else -Math.ceil(-x)
                else -> if (x >= 0) floor(x) else -floor(-x)
            }
            return r / f
        }

        private fun vlookup(a: List<Any?>): Any? {
            val key = scalar(a[0])
            val t = a[1] as? Range ?: return XlError("#VALUE!")
            val col = num(a[2]).toInt() - 1
            if (col < 0 || col >= t.cols) return XlError("#REF!")
            val exact = a.size > 3 && !bool(a[3])
            var best = -1
            for (r in 0 until t.rows) {
                val v = t.at(r, 0)
                if (exact) { if (v != null && key != null && compare("=", v, key)) { best = r; break } }
                else if (v != null && key != null && (v is Double) == (key is Double) && compare("<=", v, key)) best = r else if (!exact && best >= 0) break
            }
            return if (best < 0) XlError("#N/A") else t.at(best, col)
        }
    }

    companion object {
        fun key(row: Int, col: Int) = row.toLong() * 100_000 + col
        private val REF = Regex("(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]{1,7})")

        fun format(v: Any?, numFmt: String): Pair<String, Int>? = when (v) {
            null -> "" to 0
            is Double -> NumFmt.format(v, numFmt) to (if (NumFmt.isDate(numFmt)) 0 else 1)
            is Boolean -> (if (v) "TRUE" else "FALSE") to 2
            is XlError -> v.code to 2
            else -> v.toString() to 0
        }
    }
}
