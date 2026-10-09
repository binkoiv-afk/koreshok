package app.koreshok.core.document

import app.koreshok.core.format.EpubMetadataParser
import app.koreshok.core.format.attr
import app.koreshok.core.format.child
import app.koreshok.core.format.children
import app.koreshok.core.format.parseXml
import app.koreshok.core.format.readZipEntry
import app.koreshok.core.format.resolveZipPath
import app.koreshok.core.format.text
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Turns an EPUB 2/3 into a [Document]: one chapter per spine file, TOC from nav.xhtml or toc.ncx. */
object EpubDocumentParser {

    fun parse(epub: ByteArray, fallbackTitle: String): Document {
        val entries = readAllEntries(epub)
        val opfPath = EpubMetadataParser.findOpfPath(epub) ?: throw IllegalArgumentException("Not an EPUB")
        val opf = parseXml(ByteArrayInputStream(entries[opfPath] ?: error("Missing $opfPath"))).documentElement
        val title = opf.child("metadata")?.child("title")?.text?.takeIf { it.isNotBlank() } ?: fallbackTitle

        val manifest = opf.child("manifest")?.children("item").orEmpty().associate { item ->
            item.attr("id").orEmpty() to ManifestItem(
                path = resolveZipPath(opfPath, item.attr("href").orEmpty()),
                mediaType = item.attr("media-type").orEmpty(),
                properties = item.attr("properties").orEmpty().split(' '),
            )
        }
        val spine = opf.child("spine")
        val spineItems = spine?.children("itemref").orEmpty()
            .filter { it.attr("linear") != "no" }
            .mapNotNull { manifest[it.attr("idref")] }
            .filter { it.mediaType.contains("html") || it.path.endsWith("html", ignoreCase = true) }

        val anchors = mutableMapOf<String, Position>()
        val chapters = mutableListOf<Chapter>()
        val images = mutableMapOf<String, ByteArray>()
        for (item in spineItems) {
            val bytes = entries[item.path] ?: continue
            val html = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
            val walker = HtmlWalker(item.path, chapters.size, anchors)
            walker.walk(html.body())
            val blocks = walker.finish()
            if (blocks.isEmpty()) {
                // Still let links to an empty file land somewhere sensible.
                anchors[item.path] = Position(chapters.size, 0)
                continue
            }
            anchors[item.path] = Position(chapters.size, 0)
            for (block in blocks) {
                if (block is ImageBlock) entries[block.imageId]?.let { images[block.imageId] = it }
            }
            val heading = blocks.firstOrNull { it is TextBlock && it.kind == BlockKind.HEADING } as TextBlock?
            chapters += Chapter(heading?.text?.replace('\n', ' '), blocks)
        }
        if (chapters.isEmpty()) chapters += Chapter(title, emptyList())

        val navItem = manifest.values.firstOrNull { "nav" in it.properties }
        val ncxItem = spine?.attr("toc")?.let { manifest[it] }
            ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        val rawToc = navItem?.let { nav -> entries[nav.path]?.let { navToc(it, nav.path) } }?.takeIf { it.isNotEmpty() }
            ?: ncxItem?.let { ncx -> entries[ncx.path]?.let { ncxToc(it, ncx.path) } }.orEmpty()
        val toc = rawToc.mapNotNull { (label, level, target) ->
            val position = anchors[target] ?: anchors[target.substringBefore('#')] ?: return@mapNotNull null
            TocEntry(label, level, position)
        }.ifEmpty {
            chapters.mapIndexedNotNull { i, chapter -> chapter.title?.let { TocEntry(it, 1, Position(i, 0)) } }
        }

        return Document(title, chapters, toc, anchors, images)
    }

    private data class ManifestItem(val path: String, val mediaType: String, val properties: List<String>)

    private data class RawTocEntry(val label: String, val level: Int, val target: String)

    private fun readAllEntries(epub: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(epub)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (entry.isDirectory) continue
                result[entry.name] = stream.readBytes()
            }
        }
        return result
    }

    private fun navToc(bytes: ByteArray, navPath: String): List<RawTocEntry> {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
        val nav = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.selectFirst("nav") ?: return emptyList()
        val result = mutableListOf<RawTocEntry>()
        fun walkList(list: Element, level: Int) {
            for (li in list.children().filter { it.tagName() == "li" }) {
                val link = li.children().firstOrNull { it.tagName() == "a" || it.tagName() == "span" }
                val href = link?.attr("href").orEmpty()
                val label = link?.text()?.trim().orEmpty()
                if (label.isNotEmpty() && href.isNotEmpty()) {
                    result += RawTocEntry(label, level, resolveHref(navPath, href))
                }
                li.children().filter { it.tagName() == "ol" || it.tagName() == "ul" }.forEach { walkList(it, level + 1) }
            }
        }
        nav.children().filter { it.tagName() == "ol" || it.tagName() == "ul" }.forEach { walkList(it, 1) }
        return result
    }

    private fun ncxToc(bytes: ByteArray, ncxPath: String): List<RawTocEntry> {
        val root = parseXml(ByteArrayInputStream(bytes)).documentElement
        val result = mutableListOf<RawTocEntry>()
        fun walk(parent: org.w3c.dom.Element, level: Int) {
            for (point in parent.children("navPoint")) {
                val label = point.child("navLabel")?.child("text")?.text.orEmpty()
                val src = point.child("content")?.attr("src").orEmpty()
                if (label.isNotEmpty() && src.isNotEmpty()) result += RawTocEntry(label, level, resolveHref(ncxPath, src))
                walk(point, level + 1)
            }
        }
        root.child("navMap")?.let { walk(it, 1) }
        return result
    }

    /** "../Text/ch1.xhtml#n5" seen from [base] → "OEBPS/Text/ch1.xhtml#n5". */
    internal fun resolveHref(base: String, href: String): String {
        val fragment = href.substringAfter('#', "")
        val path = href.substringBefore('#')
        val file = if (path.isEmpty()) base else resolveZipPath(base, path)
        return if (fragment.isEmpty()) file else "$file#$fragment"
    }

    private class HtmlWalker(
        private val path: String,
        private val chapterIndex: Int,
        private val anchors: MutableMap<String, Position>,
    ) {
        private val blocks = mutableListOf<Block>()
        private var builder = TextBuilder()
        private val pendingAnchors = mutableListOf<String>()
        private var kind = BlockKind.PARAGRAPH
        private var level = 0
        private var listDepth = 0
        private val listCounters = ArrayDeque<Int?>()

        /** List marker waiting for the item's first text, which may sit inside a nested <p>. */
        private var pendingMarker: String? = null

        fun finish(): List<Block> {
            flush()
            return blocks
        }

        private fun add(block: Block) {
            val withAnchors = if (pendingAnchors.isEmpty()) block else when (block) {
                is TextBlock -> block.copy(anchors = block.anchors + pendingAnchors)
                is ImageBlock -> block.copy(anchors = block.anchors + pendingAnchors)
                is SeparatorBlock -> block.copy(anchors = block.anchors + pendingAnchors)
            }
            pendingAnchors.clear()
            for (id in withAnchors.anchors) anchors["$path#$id"] = Position(chapterIndex, blocks.size)
            blocks += withAnchors
        }

        private fun flush() {
            builder.build(kind, level)?.let(::add)
            builder = TextBuilder()
        }

        fun walk(element: Element) {
            for (node in element.childNodes()) walkNode(node)
        }

        private fun walkNode(node: Node) {
            when (node) {
                is TextNode -> {
                    val text = node.wholeText
                    if (text.isNotBlank()) pendingMarker?.let { builder.append(it); pendingMarker = null }
                    builder.append(text)
                }
                is Element -> element(node)
            }
        }

        private fun element(el: Element) {
            val tag = el.normalName()
            if (tag in SKIPPED) return
            val id = el.id().takeIf { it.isNotBlank() }
            when {
                tag == "br" -> builder.lineBreak()
                tag == "hr" -> {
                    flush()
                    id?.let { pendingAnchors += it }
                    add(SeparatorBlock())
                }
                tag == "img" || tag == "image" -> {
                    val src = el.attr("src").ifEmpty { el.attr("xlink:href") }.ifEmpty { el.attr("href") }
                    if (src.isNotEmpty() && !src.startsWith("data:")) {
                        flush()
                        id?.let { pendingAnchors += it }
                        add(ImageBlock(resolveHref(path, src).substringBefore('#'), alt = el.attr("alt").ifBlank { null }))
                    }
                }
                tag in BLOCK_TAGS -> block(el, tag, id)
                else -> inline(el, tag, id)
            }
        }

        private fun block(el: Element, tag: String, id: String?) {
            flush()
            id?.let { pendingAnchors += it }
            val savedKind = kind
            val savedLevel = level
            val classes = el.classNames().map { it.lowercase() }
            when {
                tag.length == 2 && tag[0] == 'h' && tag[1].isDigit() -> {
                    kind = BlockKind.HEADING
                    level = tag[1].digitToInt()
                }
                tag == "blockquote" -> kind = BlockKind.QUOTE
                tag == "pre" -> kind = BlockKind.CODE
                tag == "li" -> kind = BlockKind.LIST_ITEM
                classes.any { it == "poem" || it == "stanza" || it == "v" || it == "verse" } -> kind = BlockKind.POEM
                classes.any { it.startsWith("epigraph") } -> kind = BlockKind.EPIGRAPH
                classes.any { it == "text-author" || it == "author" } -> kind = BlockKind.TEXT_AUTHOR
                classes.any { it == "subtitle" } -> kind = BlockKind.SUBTITLE
                classes.any { it == "cite" || it == "citation" } -> kind = BlockKind.QUOTE
            }
            when (tag) {
                "ul" -> listCounters.addLast(null).also { listDepth++ }
                "ol" -> listCounters.addLast(el.attr("start").toIntOrNull() ?: 1).also { listDepth++ }
                "li" -> {
                    level = listDepth
                    val counter = listCounters.lastOrNull()
                    pendingMarker = if (counter == null) "• " else "$counter. "
                    if (counter != null) listCounters[listCounters.lastIndex] = counter + 1
                }
            }
            if (tag == "pre") builder.push(SpanStyle.Code)
            walk(el)
            if (tag == "pre") builder.pop()
            flush()
            if (tag == "li") pendingMarker = null
            if (tag == "ul" || tag == "ol") {
                listCounters.removeLastOrNull()
                listDepth--
            }
            kind = savedKind
            level = savedLevel
        }

        private fun inline(el: Element, tag: String, id: String?) {
            // Inline ids belong to the paragraph being built.
            id?.let { pendingAnchors += it }
            val style: SpanStyle? = when (tag) {
                "b", "strong" -> SpanStyle.Bold
                "i", "em", "cite", "dfn" -> SpanStyle.Italic
                "s", "strike", "del" -> SpanStyle.Strike
                "sup" -> SpanStyle.Superscript
                "sub" -> SpanStyle.Subscript
                "code", "tt", "kbd", "samp" -> SpanStyle.Code
                "a" -> el.attr("href").takeIf { it.isNotEmpty() }?.let { href ->
                    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(href)) {
                        SpanStyle.Link(href, isNote = false)
                    } else {
                        SpanStyle.Link(resolveHref(path, href), isNote = isNoteRef(el))
                    }
                }
                else -> null
            }
            if (style != null) builder.push(style)
            walk(el)
            if (style != null) builder.pop()
        }

        /** EPUB 3 marks note references; older books just put a short number in a link, often in <sup>. */
        private fun isNoteRef(a: Element): Boolean {
            if (a.attr("epub:type").contains("noteref")) return true
            val text = a.text().trim()
            val short = text.length <= 4 && text.isNotEmpty() && text.all { it.isDigit() || it in "*[]()†‡" }
            return short || (a.parent()?.normalName() == "sup" && text.length <= 6)
        }

        companion object {
            private val SKIPPED = setOf("head", "script", "style", "title", "nav")
            private val BLOCK_TAGS = setOf(
                "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "li", "ul", "ol",
                "section", "article", "aside", "header", "footer", "figure", "figcaption", "dl", "dt", "dd",
                "table", "tr", "caption", "main", "center", "address",
            )
        }
    }
}
