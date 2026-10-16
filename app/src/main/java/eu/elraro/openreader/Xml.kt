package eu.elraro.openreader

import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Minimal namespace-agnostic DOM: names and attribute keys are local names. */
class Node(val name: String, val attrs: Map<String, String>) {
    val children = ArrayList<Node>()
    val text = StringBuilder()

    fun attr(key: String): String? = attrs[key]
    fun child(n: String): Node? = children.firstOrNull { it.name == n }
    fun all(n: String): List<Node> = children.filter { it.name == n }
    fun find(n: String): Node? {
        for (c in children) {
            if (c.name == n) return c
            c.find(n)?.let { return it }
        }
        return null
    }
    fun findAll(n: String, out: MutableList<Node> = ArrayList()): List<Node> {
        for (c in children) {
            if (c.name == n) out.add(c)
            c.findAll(n, out)
        }
        return out
    }
}

fun parseXml(input: InputStream): Node {
    val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    parser.setInput(input, null)
    val stack = ArrayList<Node>()
    var root: Node? = null
    var ev = parser.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> {
                val attrs = HashMap<String, String>()
                for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                val n = Node(parser.name, attrs)
                stack.lastOrNull()?.children?.add(n)
                if (root == null) root = n
                stack.add(n)
            }
            XmlPullParser.TEXT -> stack.lastOrNull()?.text?.append(parser.text)
            XmlPullParser.END_TAG -> stack.removeAt(stack.size - 1)
        }
        ev = parser.next()
    }
    return root ?: throw IllegalArgumentException("Empty XML")
}

/** An OOXML package (zip) with relationship resolution. */
class OoxmlPackage(file: File) : AutoCloseable {
    private val zip = ZipFile(file)
    private val names = zip.entries().asSequence().map { it.name }.toSet()

    fun has(name: String) = name in names

    fun bytes(name: String): ByteArray? =
        zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

    fun xml(name: String): Node? =
        zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { parseXml(it) } }

    /** Relationship id -> full part path, for the given part. */
    fun rels(part: String): Map<String, String> {
        val dir = part.substringBeforeLast('/', "")
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
        val root = xml(relsPath) ?: return emptyMap()
        val out = HashMap<String, String>()
        for (r in root.all("Relationship")) {
            if (r.attr("TargetMode") == "External") continue
            val id = r.attr("Id") ?: continue
            out[id] = resolve(dir, r.attr("Target") ?: continue)
        }
        return out
    }

    /** Relationship id -> target for external links (hyperlinks). */
    fun externalRels(part: String): Map<String, String> {
        val dir = part.substringBeforeLast('/', "")
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
        val root = xml(relsPath) ?: return emptyMap()
        val out = HashMap<String, String>()
        for (r in root.all("Relationship")) {
            if (r.attr("TargetMode") != "External") continue
            out[r.attr("Id") ?: continue] = r.attr("Target") ?: continue
        }
        return out
    }

    /** Returns a data: URI for an image part, or null if unsupported/too large. */
    fun imageDataUri(part: String): String? {
        val ext = part.substringAfterLast('.').lowercase()
        val mime = when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> return null
        }
        val b = bytes(part) ?: return null
        if (b.size > 4_000_000) return null
        return "data:$mime;base64," + Base64.encodeToString(b, Base64.NO_WRAP)
    }

    override fun close() = zip.close()

    companion object {
        fun resolve(baseDir: String, target: String): String {
            if (target.startsWith("/")) return target.substring(1)
            val parts = ArrayList<String>()
            if (baseDir.isNotEmpty()) parts.addAll(baseDir.split('/'))
            for (seg in target.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(seg)
                }
            }
            return parts.joinToString("/")
        }
    }
}

fun esc(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (c in s) when (c) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        '"' -> sb.append("&quot;")
        else -> sb.append(c)
    }
    return sb.toString()
}

private val HEX = Regex("^[0-9A-Fa-f]{6}$")
fun safeColor(v: String?): String? = v?.takeIf { HEX.matches(it) }?.let { "#$it" }

fun htmlPage(title: String, css: String, body: String, viewport: String = "width=device-width, initial-scale=1") = """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="$viewport">
<title>${esc(title)}</title>
<style>
${Fonts.fontFaceCss}
body{font-family:sans-serif;margin:0;color:#222;background:#fff;word-wrap:break-word}
img{max-width:100%;height:auto}
$css
</style></head><body>$body</body></html>"""
