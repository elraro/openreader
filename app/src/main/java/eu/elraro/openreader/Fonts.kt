package eu.elraro.openreader

/** Font substitution: Office font names are aliased to bundled metric-compatible fonts (served from assets). */
object Fonts {
    const val BASE_URL = "https://appassets.local/"

    private class Family(val file: String, val aliases: List<String>, val lineFactor: Double)

    private val families = listOf(
        Family("Carlito", listOf("Calibri", "Calibri Light", "Segoe UI", "Candara", "Corbel"), 1.22),
        Family("Caladea", listOf("Cambria", "Cambria Math"), 1.17),
        Family("LiberationSans", listOf("Arial", "Helvetica", "Arial Narrow", "Tahoma", "Verdana", "Trebuchet MS", "Aptos", "Segoe", "Century Gothic"), 1.15),
        Family("LiberationSerif", listOf("Times New Roman", "Times", "Georgia", "Garamond", "Book Antiqua", "Palatino Linotype", "Century Schoolbook", "Bookman Old Style"), 1.15),
        Family("LiberationMono", listOf("Courier New", "Courier", "Consolas", "Lucida Console"), 1.13),
    )

    private val styles = listOf("Regular" to ("normal" to "normal"), "Bold" to ("bold" to "normal"), "Italic" to ("normal" to "italic"), "BoldItalic" to ("bold" to "italic"))

    val fontFaceCss: String by lazy {
        val sb = StringBuilder()
        for (f in families) for (alias in f.aliases) for ((suffix, ws) in styles) {
            sb.append("@font-face{font-family:'$alias';font-weight:${ws.first};font-style:${ws.second};src:url('${BASE_URL}fonts/${f.file}-$suffix.ttf')}\n")
        }
        sb.toString()
    }

    private val serifHints = listOf("times", "georgia", "garamond", "palatino", "book", "century", "roman", "mincho", "song", "cambria", "serif")
    private val monoHints = listOf("mono", "courier", "consolas", "console", "code")

    fun css(name: String): String {
        val n = name.replace("'", "").replace("\"", "")
        val l = n.lowercase()
        val generic = when {
            monoHints.any { it in l } -> "monospace"
            serifHints.any { it in l } && "sans" !in l -> "serif"
            else -> "sans-serif"
        }
        return "'$n',$generic"
    }

    /** Ratio between Word's single line height and the font size. */
    fun lineFactor(name: String?): Double {
        if (name == null) return 1.15
        val l = name.lowercase()
        return families.firstOrNull { f -> f.aliases.any { it.lowercase() == l } }?.lineFactor ?: 1.15
    }
}
