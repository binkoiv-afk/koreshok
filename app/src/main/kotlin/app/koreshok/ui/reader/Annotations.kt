package app.koreshok.ui.reader

import androidx.compose.ui.graphics.Color
import app.koreshok.core.document.Document
import app.koreshok.core.document.Position
import app.koreshok.core.document.TextBlock
import app.koreshok.data.AnnotationEntity
import app.koreshok.data.AnnotationKind
import java.text.BreakIterator
import java.util.Locale

/** Highlight colors, drawn translucent so they work on every reader theme. */
val HighlightColors = listOf(
    Color(0xFFF2C94C),
    Color(0xFF6FCF97),
    Color(0xFF56CCF2),
    Color(0xFFEB7A8C),
)

fun highlightColor(index: Int): Color = HighlightColors[index.coerceIn(0, HighlightColors.lastIndex)].copy(alpha = 0.38f)

/** A highlight being created or edited in the sheet. */
data class HighlightDraft(
    val id: Long = 0,
    val chapter: Int,
    val block: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val color: Int = 0,
    val note: String = "",
)

fun AnnotationEntity.toDraft() = HighlightDraft(id, chapter, block, start, end, text, color, note.orEmpty())

/** The sentence around [offset]: a long press marks a sentence, the sheet can widen it to the paragraph. */
fun sentenceAt(block: TextBlock, offset: Int, language: String?): IntRange {
    val text = block.text
    if (text.isEmpty()) return IntRange.EMPTY
    val iterator = BreakIterator.getSentenceInstance(language?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault())
    iterator.setText(text)
    val at = offset.coerceIn(0, text.length - 1)
    val end = iterator.following(at).let { if (it == BreakIterator.DONE) text.length else it }
    val start = iterator.previous().let { if (it == BreakIterator.DONE) 0 else it }
    var trimmedEnd = end
    while (trimmedEnd > start && text[trimmedEnd - 1].isWhitespace()) trimmedEnd--
    return start until trimmedEnd
}

/** Markdown for notes apps: chapter headings, quotes, notes under them. */
fun exportMarkdown(title: String, author: String?, annotations: List<AnnotationEntity>): String = buildString {
    append("# ").append(title).append('\n')
    if (!author.isNullOrBlank()) append('\n').append(author).append('\n')
    var chapter: String? = null
    for (item in annotations.sortedWith(compareBy({ it.chapter }, { it.block }, { it.start }))) {
        val itemChapter = item.chapterTitle ?: "Глава ${item.chapter + 1}"
        if (itemChapter != chapter) {
            append("\n## ").append(itemChapter).append('\n')
            chapter = itemChapter
        }
        append('\n')
        when (item.kind) {
            AnnotationKind.HIGHLIGHT -> item.text.lines().forEach { append("> ").append(it).append('\n') }
            AnnotationKind.BOOKMARK -> append("🔖 ").append(item.text).append("…\n")
        }
        if (!item.note.isNullOrBlank()) append('\n').append(item.note).append('\n')
    }
}

/** First words at [position], used to label a bookmark. */
fun snippetAt(document: Document, position: Position, maxChars: Int = 120): String {
    val blocks = document.chapters.getOrNull(position.chapter)?.blocks ?: return ""
    val result = StringBuilder()
    var index = position.block
    var offset = position.offset
    while (index < blocks.size && result.length < maxChars) {
        val block = blocks[index]
        if (block is TextBlock) {
            if (result.isNotEmpty()) result.append(' ')
            result.append(block.text.drop(offset).replace('\n', ' '))
        }
        offset = 0
        index++
    }
    return result.take(maxChars).trim().toString()
}
