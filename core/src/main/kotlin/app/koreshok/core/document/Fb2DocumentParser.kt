package app.koreshok.core.document

import app.koreshok.core.format.attr
import app.koreshok.core.format.child
import app.koreshok.core.format.children
import app.koreshok.core.format.localNameOrName
import app.koreshok.core.format.parseXml
import app.koreshok.core.format.readAtMost
import app.koreshok.core.format.text
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/** Turns a whole FB2 file into a [Document]. Every titled section starts a new chapter. */
object Fb2DocumentParser {

    fun parse(fb2: ByteArray, fallbackTitle: String): Document {
        val root = parseXml(ByteArrayInputStream(fb2)).documentElement
        return Builder(root, fallbackTitle).build()
    }

    fun parseZipped(zip: ByteArray, fallbackTitle: String): Document {
        ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".fb2", ignoreCase = true)) {
                    val bytes = stream.readAtMost(128 * 1024 * 1024) ?: break
                    return parse(bytes, fallbackTitle)
                }
            }
        }
        throw IllegalArgumentException("No .fb2 file in archive")
    }

    private class Builder(private val root: Element, private val fallbackTitle: String) {
        private val chapters = mutableListOf<Chapter>()
        private val toc = mutableListOf<TocEntry>()
        private val anchors = mutableMapOf<String, Position>()
        private var chapterTitle: String? = null
        private var blocks = mutableListOf<Block>()
        private val pendingAnchors = mutableListOf<String>()

        fun build(): Document {
            val bookTitle = root.child("description")?.child("title-info")?.child("book-title")?.text
                ?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val bodies = root.children("body")
            for (body in bodies) {
                val isNotes = body.attr("name").let { it == "notes" || it == "comments" }
                if (isNotes) {
                    startChapter(body.child("title")?.text?.takeIf { it.isNotBlank() } ?: "Примечания", level = 1)
                } else if (body !== bodies.first()) {
                    startChapter(body.child("title")?.text?.takeIf { it.isNotBlank() }, level = 1)
                }
                container(body, depth = 0)
            }
            flushChapter()
            if (chapters.isEmpty()) chapters += Chapter(bookTitle, emptyList())
            return Document(bookTitle, chapters, toc, anchors, images())
        }

        private fun startChapter(title: String?, level: Int) {
            flushChapter()
            chapterTitle = title
            if (title != null) toc += TocEntry(title, level, Position(chapters.size, 0))
        }

        private fun flushChapter() {
            if (blocks.isNotEmpty()) chapters += Chapter(chapterTitle, blocks)
            blocks = mutableListOf()
            chapterTitle = null
        }

        private fun add(block: Block?) {
            if (block == null) return
            val withAnchors = if (pendingAnchors.isEmpty()) block else when (block) {
                is TextBlock -> block.copy(anchors = block.anchors + pendingAnchors)
                is ImageBlock -> block.copy(anchors = block.anchors + pendingAnchors)
                is SeparatorBlock -> block.copy(anchors = block.anchors + pendingAnchors)
            }
            pendingAnchors.clear()
            for (id in withAnchors.anchors) anchors[id] = Position(chapters.size, blocks.size)
            blocks += withAnchors
        }

        private fun markAnchor(element: Element) {
            element.attr("id")?.takeIf { it.isNotBlank() }?.let { pendingAnchors += it }
        }

        /** body, section, cite, epigraph, annotation, poem, stanza: things that hold blocks. */
        private fun container(element: Element, depth: Int, kind: BlockKind = BlockKind.PARAGRAPH) {
            markAnchor(element)
            for (child in element.children()) {
                when (child.localNameOrName) {
                    "section" -> section(child, depth + 1)
                    "title" -> titleBlocks(child, depth)
                    "p" -> paragraph(child, kind)
                    "subtitle" -> paragraph(child, BlockKind.SUBTITLE)
                    "text-author" -> paragraph(child, BlockKind.TEXT_AUTHOR)
                    "empty-line" -> Unit
                    "image" -> image(child)
                    "epigraph" -> container(child, depth, BlockKind.EPIGRAPH)
                    "cite" -> container(child, depth, BlockKind.QUOTE)
                    "annotation" -> container(child, depth, BlockKind.QUOTE)
                    "poem", "stanza" -> container(child, depth, BlockKind.POEM)
                    "v" -> paragraph(child, BlockKind.POEM)
                    "date" -> paragraph(child, BlockKind.TEXT_AUTHOR)
                    "table" -> table(child)
                    else -> Unit
                }
            }
        }

        private fun section(section: Element, depth: Int) {
            val title = section.child("title")?.let(::plainTitle)?.takeIf { it.isNotBlank() }
            // Main-body sections with a title become chapters; notes stay together in one chapter.
            if (title != null && !isInNotes(section)) startChapter(title, depth)
            container(section, depth)
        }

        private fun isInNotes(element: Element): Boolean {
            var node: Node? = element.parentNode
            while (node is Element) {
                if (node.localNameOrName == "body") return node.attr("name") == "notes" || node.attr("name") == "comments"
                node = node.parentNode
            }
            return false
        }

        private fun plainTitle(title: Element): String =
            title.children("p").joinToString(". ") { it.text }.ifBlank { title.text }

        private fun titleBlocks(title: Element, depth: Int) {
            markAnchor(title)
            val builder = TextBuilder()
            var first = true
            for (p in title.children()) {
                if (p.localNameOrName != "p") continue
                if (!first) builder.lineBreak()
                inline(p, builder)
                first = false
            }
            if (first) inline(title, builder)
            add(builder.build(BlockKind.HEADING, level = depth.coerceIn(1, 6)))
        }

        private fun paragraph(element: Element, kind: BlockKind) {
            markAnchor(element)
            val builder = TextBuilder()
            inline(element, builder)
            add(builder.build(kind))
        }

        private fun table(table: Element) {
            for (row in table.children("tr")) {
                val builder = TextBuilder()
                row.children().forEachIndexed { i, cell ->
                    if (i > 0) builder.append(" | ")
                    inline(cell, builder)
                }
                add(builder.build(BlockKind.PARAGRAPH))
            }
        }

        private fun image(element: Element) {
            markAnchor(element)
            val href = element.attr("href")?.removePrefix("#") ?: return
            add(ImageBlock(href, alt = element.attr("alt")))
        }

        private fun inline(element: Element, builder: TextBuilder) {
            val nodes = element.childNodes
            for (i in 0 until nodes.length) {
                when (val node = nodes.item(i)) {
                    is Element -> {
                        val style = when (node.localNameOrName) {
                            "strong" -> SpanStyle.Bold
                            "emphasis" -> SpanStyle.Italic
                            "strikethrough" -> SpanStyle.Strike
                            "sup" -> SpanStyle.Superscript
                            "sub" -> SpanStyle.Subscript
                            "code" -> SpanStyle.Code
                            "a" -> node.attr("href")?.let { href ->
                                if (href.startsWith("#")) {
                                    SpanStyle.Link(href.removePrefix("#"), isNote = node.attr("type") == "note")
                                } else {
                                    SpanStyle.Link(href, isNote = false)
                                }
                            }
                            else -> null
                        }
                        if (node.localNameOrName == "image") continue
                        node.attr("id")?.takeIf { it.isNotBlank() }?.let { pendingAnchors += it }
                        if (style != null) builder.push(style)
                        inline(node, builder)
                        if (style != null) builder.pop()
                    }
                    else -> if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                        builder.append(node.nodeValue.orEmpty())
                    }
                }
            }
        }

        private fun images(): Map<String, ByteArray> = root.children("binary").mapNotNull { binary ->
            val id = binary.attr("id") ?: return@mapNotNull null
            val bytes = try {
                Base64.getMimeDecoder().decode(binary.textContent.orEmpty())
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            id to bytes
        }.toMap()
    }
}
