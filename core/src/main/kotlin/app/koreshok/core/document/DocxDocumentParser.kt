package app.koreshok.core.document

import app.koreshok.core.model.Author
import app.koreshok.core.model.BookMetadata
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Word documents. The body is rewritten as simple HTML: "Heading N" styles become headings,
 * bold and italic runs keep their style, pictures come from word/media.
 */
object DocxDocumentParser {

    fun parse(bytes: ByteArray, fallbackTitle: String): Document {
        val entries = unzip(bytes)
        val document = xml(entries["word/document.xml"] ?: throw IllegalArgumentException("Not a DOCX"))
        val headingLevels = headingStyles(entries["word/styles.xml"])
        val rels = entries["word/_rels/document.xml.rels"]?.let(::xml)
            ?.getElementsByTag("Relationship")
            ?.associate { it.attr("Id") to it.attr("Target") }
            .orEmpty()

        val images = mutableMapOf<String, ByteArray>()
        val html = StringBuilder("<html><body>")
        val body = document.getElementsByTag("w:body").firstOrNull() ?: throw IllegalArgumentException("Empty DOCX")
        for (child in body.children()) {
            when (child.tagName()) {
                "w:p" -> paragraph(child, headingLevels, rels, entries, images, html)
                "w:tbl" -> for (row in child.getElementsByTag("w:tr")) {
                    html.append("<p>")
                    html.append(row.getElementsByTag("w:tc").joinToString(" | ") { escape(it.text()) })
                    html.append("</p>")
                }
            }
        }
        html.append("</body></html>")
        val meta = metadata(entries)
        return HtmlBook.parse(Jsoup.parse(html.toString()), meta.title.ifBlank { fallbackTitle }, images)
    }

    fun metadata(bytes: ByteArray, fallbackTitle: String): BookMetadata {
        val meta = metadata(unzip(bytes))
        return meta.copy(title = meta.title.ifBlank { fallbackTitle })
    }

    private fun metadata(entries: Map<String, ByteArray>): BookMetadata {
        val core = entries["docProps/core.xml"]?.let(::xml) ?: return BookMetadata("")
        val title = core.getElementsByTag("dc:title").firstOrNull()?.text().orEmpty()
        val creator = core.getElementsByTag("dc:creator").firstOrNull()?.text().orEmpty()
        return BookMetadata(title.trim(), authors = listOfNotNull(creator.trim().takeIf { it.isNotEmpty() }?.let(Author::parse)))
    }

    private fun paragraph(
        p: Element,
        headingLevels: Map<String, Int>,
        rels: Map<String, String>,
        entries: Map<String, ByteArray>,
        images: MutableMap<String, ByteArray>,
        html: StringBuilder,
    ) {
        val props = p.getElementsByTag("w:pPr").firstOrNull()
        val style = props?.getElementsByTag("w:pStyle")?.firstOrNull()?.attr("w:val").orEmpty()
        val outline = props?.getElementsByTag("w:outlineLvl")?.firstOrNull()?.attr("w:val")?.toIntOrNull()
        val level = headingLevels[style] ?: outline?.let { it + 1 }?.takeIf { it in 1..6 }
        val tag = if (level != null) "h$level" else "p"
        html.append('<').append(tag).append('>')
        for (node in p.getAllElements()) {
            when (node.tagName()) {
                "w:r" -> run(node, html)
                "a:blip" -> {
                    val target = rels[node.attr("r:embed")] ?: continue
                    val path = "word/" + target.removePrefix("/word/").removePrefix("./")
                    val bytes = entries[path] ?: continue
                    val id = "img/${images.size}"
                    images[id] = bytes
                    html.append("<img src=\"").append(id).append("\">")
                }
            }
        }
        html.append("</").append(tag).append('>')
    }

    private fun run(r: Element, html: StringBuilder) {
        val props = r.children().firstOrNull { it.tagName() == "w:rPr" }
        fun on(name: String) = props?.children()?.firstOrNull { it.tagName() == name }?.let { it.attr("w:val") !in setOf("0", "false") } == true
        val bold = on("w:b")
        val italic = on("w:i")
        if (bold) html.append("<b>")
        if (italic) html.append("<i>")
        for (part in r.children()) {
            when (part.tagName()) {
                "w:t" -> html.append(escape(part.wholeText()))
                "w:tab" -> html.append(' ')
                "w:br", "w:cr" -> html.append("<br>")
            }
        }
        if (italic) html.append("</i>")
        if (bold) html.append("</b>")
    }

    /** Style id → heading level, from styles named "heading 1".."heading 6" and "Title". */
    private fun headingStyles(bytes: ByteArray?): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val styles = bytes?.let(::xml)?.getElementsByTag("w:style").orEmpty()
        for (style in styles) {
            val id = style.attr("w:styleId")
            val name = style.getElementsByTag("w:name").firstOrNull()?.attr("w:val").orEmpty().lowercase()
            val level = Regex("^(heading|заголовок)\\s*(\\d)$").find(name)?.groupValues?.get(2)?.toIntOrNull()
                ?: if (name == "title") 1 else null
            if (level != null && level in 1..6) result[id] = level
        }
        // Documents written without styles.xml still use the built-in ids.
        for (n in 1..6) result.putIfAbsent("Heading$n", n)
        return result
    }

    private fun xml(bytes: ByteArray) = Jsoup.parse(String(bytes, Charsets.UTF_8), "", Parser.xmlParser())

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) result[entry.name] = zip.readBytes()
            }
        }
        return result
    }
}
