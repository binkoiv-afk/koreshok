package app.koreshok.core.document

/** One place in the book where the searched text occurs. */
data class SearchHit(
    val position: Position,
    val chapterTitle: String?,
    /** A line of context around the match. */
    val snippet: String,
    val matchStart: Int,
    val matchLength: Int,
)

/** Full-text search in an open book; case and ё/е do not matter. */
object BookSearch {

    fun search(document: Document, query: String, limit: Int = 500): List<SearchHit> {
        val needle = normalize(query.trim())
        if (needle.length < 2) return emptyList()
        val hits = mutableListOf<SearchHit>()
        document.chapters.forEachIndexed { c, chapter ->
            chapter.blocks.forEachIndexed { b, block ->
                if (block !is TextBlock) return@forEachIndexed
                val haystack = normalize(block.text)
                var from = 0
                while (true) {
                    val at = haystack.indexOf(needle, from)
                    if (at < 0) break
                    hits += hit(document, c, b, block.text, at, needle.length)
                    if (hits.size >= limit) return hits
                    from = at + needle.length
                }
            }
        }
        return hits
    }

    private fun hit(document: Document, chapter: Int, block: Int, text: String, at: Int, length: Int): SearchHit {
        var start = (at - BEFORE).coerceAtLeast(0)
        var end = (at + length + AFTER).coerceAtMost(text.length)
        // Cut at word boundaries so the snippet does not start mid-word.
        if (start > 0) text.indexOf(' ', start).takeIf { it in start until at }?.let { start = it + 1 }
        if (end < text.length) text.lastIndexOf(' ', end).takeIf { it > at + length }?.let { end = it }
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        val snippet = prefix + text.substring(start, end).replace('\n', ' ') + suffix
        return SearchHit(
            position = Position(chapter, block, at),
            chapterTitle = document.chapters[chapter].title,
            snippet = snippet,
            matchStart = prefix.length + at - start,
            matchLength = length,
        )
    }

    /** Same length as the input, so offsets found in it are offsets in the original. */
    private fun normalize(text: String): String = buildString(text.length) {
        for (ch in text) append(if (ch == 'ё' || ch == 'Ё') 'е' else ch.lowercaseChar())
    }

    private const val BEFORE = 40
    private const val AFTER = 70
}
