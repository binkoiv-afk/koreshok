package app.koreshok.core.format

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal fun parseXml(input: InputStream): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Books are untrusted input: DOCTYPEs are tolerated, but nothing external is loaded
        // and secure processing caps entity expansion.
        trySetFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
        trySetFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        trySetFeature("http://xml.org/sax/features/external-general-entities", false)
        trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isExpandEntityReferences = false
    }
    return factory.newDocumentBuilder().parse(input)
}

private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
    try {
        setFeature(name, value)
    } catch (_: Exception) {
        // Android's parser does not know every feature; it never loads external entities anyway.
    }
}

internal val Node.localNameOrName: String
    get() = localName ?: nodeName.substringAfter(':')

internal fun Element.children(): List<Element> {
    val result = mutableListOf<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node is Element) result += node
    }
    return result
}

internal fun Element.children(localName: String): List<Element> =
    children().filter { it.localNameOrName == localName }

internal fun Element.child(localName: String): Element? =
    children().firstOrNull { it.localNameOrName == localName }

/** Depth-first search for the first descendant with this local name. */
internal fun Element.descendant(localName: String): Element? {
    for (child in children()) {
        if (child.localNameOrName == localName) return child
        child.descendant(localName)?.let { return it }
    }
    return null
}

internal fun Element.descendants(localName: String): List<Element> {
    val result = mutableListOf<Element>()
    fun walk(element: Element) {
        for (child in element.children()) {
            if (child.localNameOrName == localName) result += child
            walk(child)
        }
    }
    walk(this)
    return result
}

/** Attribute by local name, regardless of namespace prefix (opf:role, l:href, xlink:href...). */
internal fun Element.attr(localName: String): String? {
    val attributes = attributes
    for (i in 0 until attributes.length) {
        val node = attributes.item(i)
        if (node.localNameOrName == localName) return node.nodeValue
    }
    return null
}

internal val Element.text: String
    get() = textContent.orEmpty().trim().replace(Regex("\\s+"), " ")

/** Reads one entry of an in-memory ZIP; null when it is absent or bigger than [maxBytes]. */
internal fun readZipEntry(zip: ByteArray, path: String, maxBytes: Int = 32 * 1024 * 1024): ByteArray? {
    ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
        while (true) {
            val entry = stream.nextEntry ?: return null
            if (!entry.isDirectory && entry.name.equals(path, ignoreCase = true)) {
                return stream.readAtMost(maxBytes)
            }
        }
    }
}

/** Reads the whole stream, or returns null once it exceeds [maxBytes]. Avoids readNBytes, which needs API 33. */
fun java.io.InputStream.readAtMost(maxBytes: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        out.write(buffer, 0, read)
        if (out.size() > maxBytes) return null
    }
}

internal fun zipEntryNames(zip: ByteArray): List<String> {
    val names = mutableListOf<String>()
    ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
        while (true) {
            val entry = stream.nextEntry ?: break
            if (!entry.isDirectory) names += entry.name
        }
    }
    return names
}

/** Resolves "../Images/cover.jpg" against the folder of "OEBPS/Text/ch1.xhtml". */
internal fun resolveZipPath(base: String, href: String): String {
    val decoded = java.net.URLDecoder.decode(href.substringBefore('#'), "UTF-8")
    val parts = base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
    for (segment in decoded.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += segment
        }
    }
    return parts.joinToString("/")
}

internal val YEAR = Regex("(\\d{4})")
