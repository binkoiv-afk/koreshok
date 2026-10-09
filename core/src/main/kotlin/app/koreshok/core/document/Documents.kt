package app.koreshok.core.document

import app.koreshok.core.model.BookFormat

object Documents {
    /** Formats the reflowable reader can open today. */
    val supported = setOf(BookFormat.EPUB, BookFormat.FB2, BookFormat.FB2_ZIP, BookFormat.TXT)

    fun parse(bytes: ByteArray, format: BookFormat, fallbackTitle: String): Document = when (format) {
        BookFormat.EPUB -> EpubDocumentParser.parse(bytes, fallbackTitle)
        BookFormat.FB2 -> Fb2DocumentParser.parse(bytes, fallbackTitle)
        BookFormat.FB2_ZIP -> Fb2DocumentParser.parseZipped(bytes, fallbackTitle)
        BookFormat.TXT -> TxtDocumentParser.parse(bytes, fallbackTitle)
        else -> throw UnsupportedOperationException("$format is not supported yet")
    }
}

/**
 * The text of a footnote for a popup: the block the link points at and the ones after it,
 * up to the next anchored block. A bare number heading like "1" is skipped.
 */
fun Document.noteBlocks(target: String, maxBlocks: Int = 6): List<TextBlock> {
    val position = anchors[target] ?: return emptyList()
    val blocks = chapters.getOrNull(position.chapter)?.blocks ?: return emptyList()
    val result = mutableListOf<TextBlock>()
    var index = position.block
    while (index < blocks.size && result.size < maxBlocks) {
        val block = blocks[index]
        if (index > position.block && block.anchors.isNotEmpty()) break
        if (block is TextBlock) {
            val isNumberHeading = block.kind == BlockKind.HEADING && block.text.length <= 6
            if (!isNumberHeading) result += block
        }
        index++
    }
    return result
}
