package app.koreshok.core.document

import java.text.BreakIterator
import java.util.Locale

/** A piece of the book read aloud in one go: a sentence, with where it sits for highlighting. */
data class Utterance(
    val chapter: Int,
    val block: Int,
    val start: Int,
    val end: Int,
    /** What the voice says: the sentence without footnote marks. */
    val spoken: String,
) {
    val position: Position get() = Position(chapter, block, start)
}

object Speech {
    /** Text-to-speech engines accept about 4000 characters at once; stay well below. */
    private const val MAX_LENGTH = 1000

    /** The book sentence by sentence, starting with the sentence that holds [from]. */
    fun utterances(document: Document, from: Position, locale: Locale = Locale.getDefault()): Sequence<Utterance> = sequence {
        val iterator = BreakIterator.getSentenceInstance(locale)
        for (c in from.chapter.coerceAtLeast(0) until document.chapters.size) {
            val blocks = document.chapters[c].blocks
            val firstBlock = if (c == from.chapter) from.block.coerceAtLeast(0) else 0
            for (b in firstBlock until blocks.size) {
                val block = blocks[b] as? TextBlock ?: continue
                val skipBefore = if (c == from.chapter && b == from.block) from.offset else 0
                val notes = block.spans.filter { (it.style as? SpanStyle.Link)?.isNote == true }
                iterator.setText(block.text)
                var start = iterator.first()
                var end = iterator.next()
                while (end != BreakIterator.DONE) {
                    if (end > skipBefore) {
                        for ((s, e) in pieces(block.text, start, end)) {
                            val spoken = spokenText(block.text, s, e, notes)
                            if (spoken.any { it.isLetterOrDigit() }) yield(Utterance(c, b, s, e, spoken))
                        }
                    }
                    start = end
                    end = iterator.next()
                }
            }
        }
    }

    /** A very long sentence is cut at spaces so no piece is too long for the engine. */
    private fun pieces(text: String, start: Int, end: Int): List<Pair<Int, Int>> {
        if (end - start <= MAX_LENGTH) return listOf(start to end)
        val result = mutableListOf<Pair<Int, Int>>()
        var s = start
        while (end - s > MAX_LENGTH) {
            val cut = text.lastIndexOf(' ', s + MAX_LENGTH).takeIf { it > s } ?: (s + MAX_LENGTH)
            result += s to cut
            s = cut
        }
        result += s to end
        return result
    }

    private fun spokenText(text: String, start: Int, end: Int, notes: List<Span>): String {
        if (notes.none { it.start < end && it.end > start }) return text.substring(start, end).trim()
        val out = StringBuilder()
        for (i in start until end) if (notes.none { i >= it.start && i < it.end }) out.append(text[i])
        return out.toString().trim()
    }
}
