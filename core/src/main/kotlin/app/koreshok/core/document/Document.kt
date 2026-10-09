package app.koreshok.core.document

/**
 * Format-independent book content. EPUB and FB2 both parse into this, and the reader
 * lays it out with its own typography, so every reflowable format looks the same.
 */
data class Document(
    val title: String,
    val chapters: List<Chapter>,
    val toc: List<TocEntry>,
    /** Anchor id → where it points. Ids are "file#id" for EPUB and plain ids for FB2. */
    val anchors: Map<String, Position>,
    val images: Map<String, ByteArray>,
) {
    /** Character counts per chapter, used to turn a position into overall progress. */
    val chapterLengths: List<Int> by lazy { chapters.map { chapter -> chapter.blocks.sumOf { it.length } } }
    val totalLength: Int by lazy { chapterLengths.sum() }

    fun progressOf(position: Position): Float {
        if (totalLength == 0) return 0f
        val chapter = position.chapter.coerceIn(0, chapters.lastIndex)
        val before = chapterLengths.take(chapter).sum()
        val inChapter = chapters[chapter].blocks.take(position.block).sumOf { it.length } + position.offset
        return ((before + inChapter).toFloat() / totalLength).coerceIn(0f, 1f)
    }

    /** The position at a fraction of the book, for the progress slider. */
    fun positionAt(fraction: Float): Position {
        var remaining = (fraction.coerceIn(0f, 1f) * totalLength).toInt()
        chapters.forEachIndexed { c, chapter ->
            if (remaining < chapterLengths[c] || c == chapters.lastIndex) {
                chapter.blocks.forEachIndexed { b, block ->
                    if (remaining < block.length) return Position(c, b, 0)
                    remaining -= block.length
                }
                return Position(c, 0, 0)
            }
            remaining -= chapterLengths[c]
        }
        return Position(0, 0, 0)
    }
}

data class Chapter(
    val title: String?,
    val blocks: List<Block>,
)

data class TocEntry(
    val title: String,
    val level: Int,
    val position: Position,
)

/** A place in the book: chapter, block in it, character offset in that block. */
data class Position(val chapter: Int, val block: Int, val offset: Int = 0) : Comparable<Position> {
    override fun compareTo(other: Position): Int =
        compareValuesBy(this, other, Position::chapter, Position::block, Position::offset)

    fun serialize(): String = "$chapter:$block:$offset"

    companion object {
        val START = Position(0, 0, 0)

        fun parse(value: String?): Position? {
            val parts = value?.split(':')?.mapNotNull { it.toIntOrNull() } ?: return null
            return if (parts.size == 3) Position(parts[0], parts[1], parts[2]) else null
        }
    }
}

enum class BlockKind {
    PARAGRAPH,
    HEADING,
    SUBTITLE,
    QUOTE,
    POEM,
    EPIGRAPH,
    TEXT_AUTHOR,
    CODE,
    LIST_ITEM,
}

sealed interface Block {
    /** Ids that point at this block, so links and footnotes can find it. */
    val anchors: List<String>
    val length: Int
}

data class TextBlock(
    val kind: BlockKind,
    val text: String,
    val spans: List<Span> = emptyList(),
    /** Heading level 1..6, or nesting depth for quotes and lists. */
    val level: Int = 0,
    override val anchors: List<String> = emptyList(),
) : Block {
    override val length: Int get() = text.length
}

data class ImageBlock(
    val imageId: String,
    val alt: String? = null,
    override val anchors: List<String> = emptyList(),
) : Block {
    override val length: Int get() = 1
}

data class SeparatorBlock(override val anchors: List<String> = emptyList()) : Block {
    override val length: Int get() = 1
}

data class Span(val start: Int, val end: Int, val style: SpanStyle)

sealed interface SpanStyle {
    data object Bold : SpanStyle
    data object Italic : SpanStyle
    data object Strike : SpanStyle
    data object Superscript : SpanStyle
    data object Subscript : SpanStyle
    data object Code : SpanStyle

    /** [target] is an anchor key from [Document.anchors], or an external URL. */
    data class Link(val target: String, val isNote: Boolean) : SpanStyle
}
