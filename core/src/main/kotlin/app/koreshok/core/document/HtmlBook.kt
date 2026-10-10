package app.koreshok.core.document

/**
 * One long HTML text split into chapters at its headings. MOBI, DOCX and RTF all arrive as a
 * single flow like this, unlike EPUB, which already comes in chapter files.
 */
internal object HtmlBook {

    /**
     * [images] holds the bytes for the `img/…` sources the caller wrote into [html].
     * [guessHeadings] turns paragraphs like "Глава 3" into headings, for formats without styles.
     */
    fun parse(html: org.jsoup.nodes.Document, title: String, images: Map<String, ByteArray>, guessHeadings: Boolean = false): Document {
        val flatAnchors = mutableMapOf<String, Position>()
        val walker = EpubDocumentParser.HtmlWalker("", 0, flatAnchors)
        walker.walk(html.body())
        var blocks = walker.finish()
        if (guessHeadings) {
            blocks = blocks.map { block ->
                if (block is TextBlock && block.kind == BlockKind.PARAGRAPH && TxtDocumentParser.chapterHeading.matches(block.text)) {
                    block.copy(kind = BlockKind.HEADING, level = 2)
                } else {
                    block
                }
            }
        }

        val splitLevel = splitLevel(blocks)
        val starts = mutableListOf(0)
        if (splitLevel != null) {
            blocks.forEachIndexed { index, block ->
                if (index > 0 && block is TextBlock && block.kind == BlockKind.HEADING && block.level <= splitLevel) starts += index
            }
        }
        // Long stretches without headings are cut too: the reader lays out a chapter at a time.
        val cut = mutableListOf<Int>()
        for ((i, start) in starts.withIndex()) {
            val end = starts.getOrElse(i + 1) { blocks.size }
            cut += start
            var length = 0
            for (index in start until end) {
                length += blocks[index].length
                if (length >= PART_LENGTH && index + 1 < end) {
                    cut += index + 1
                    length = 0
                }
            }
        }

        val chapters = cut.mapIndexed { i, start ->
            val part = blocks.subList(start, cut.getOrElse(i + 1) { blocks.size })
            val heading = part.firstOrNull() as? TextBlock
            Chapter(heading?.takeIf { it.kind == BlockKind.HEADING }?.text?.replace('\n', ' '), part.toList())
        }.ifEmpty { listOf(Chapter(title, emptyList())) }

        fun positionOf(flatIndex: Int): Position {
            val chapter = cut.indexOfLast { it <= flatIndex }.coerceAtLeast(0)
            return Position(chapter, flatIndex - cut.getOrElse(chapter) { 0 })
        }

        val anchors = flatAnchors.mapValues { (_, position) -> positionOf(position.block) }
        val tocLevel = (splitLevel ?: 0) + 1
        val toc = blocks.withIndex()
            .filter { (_, block) -> block is TextBlock && block.kind == BlockKind.HEADING && block.level in (splitLevel ?: 1)..tocLevel }
            .map { (index, block) ->
                val heading = block as TextBlock
                TocEntry(heading.text.replace('\n', ' '), (heading.level - (splitLevel ?: heading.level) + 1).coerceAtLeast(1), positionOf(index))
            }
            .ifEmpty {
                if (chapters.size == 1) emptyList() else chapters.indices.map { TocEntry("Часть ${it + 1}", 1, Position(it, 0)) }
            }

        val used = blocks.filterIsInstance<ImageBlock>().map { it.imageId }.toSet()
        return Document(title, chapters, toc, anchors, images.filterKeys { it in used })
    }

    /**
     * The heading level that starts chapters: the top one, unless it is used just once (a book
     * title above the chapters), then the next one down.
     */
    private fun splitLevel(blocks: List<Block>): Int? {
        val levels = blocks.filterIsInstance<TextBlock>().filter { it.kind == BlockKind.HEADING }.map { it.level }
        if (levels.isEmpty()) return null
        val distinct = levels.distinct().sorted()
        val top = distinct.first()
        return if (levels.count { it == top } <= 1 && distinct.size > 1) distinct[1] else top
    }

    private const val PART_LENGTH = 40_000
}
