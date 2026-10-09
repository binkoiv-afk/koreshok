package app.koreshok.core.document

/**
 * Collects inline text with styles while walking markup, and normalizes whitespace the way
 * a browser would: runs of spaces collapse, leading and trailing spaces are dropped.
 */
internal class TextBuilder {
    private val text = StringBuilder()
    private val spans = mutableListOf<Span>()
    private val open = ArrayDeque<Pair<Int, SpanStyle>>()
    private var preformatted = 0

    val isBlank: Boolean get() = text.isBlank()

    fun append(raw: String) {
        if (preformatted > 0) {
            text.append(raw)
            return
        }
        for (ch in raw) {
            val c = if (ch == ' ') ch else if (ch.isWhitespace()) ' ' else ch
            if (c == ' ' && (text.isEmpty() || text.last() == ' ' || text.last() == '\n')) continue
            text.append(c)
        }
    }

    fun lineBreak() {
        trimTrailingSpace()
        if (text.isNotEmpty()) text.append('\n')
    }

    fun push(style: SpanStyle) {
        if (style == SpanStyle.Code) preformatted++
        open.addLast(text.length to style)
    }

    fun pop() {
        val (start, style) = open.removeLastOrNull() ?: return
        if (style == SpanStyle.Code) preformatted--
        if (text.length > start) spans += Span(start, text.length, style)
    }

    private fun trimTrailingSpace() {
        while (text.isNotEmpty() && text.last() == ' ') text.setLength(text.length - 1)
    }

    fun build(kind: BlockKind, level: Int = 0, anchors: List<String> = emptyList()): TextBlock? {
        trimTrailingSpace()
        while (text.isNotEmpty() && text.last() == '\n') text.setLength(text.length - 1)
        if (text.isBlank()) return null
        val length = text.length
        return TextBlock(
            kind = kind,
            text = text.toString(),
            spans = spans.map { Span(it.start.coerceAtMost(length), it.end.coerceAtMost(length), it.style) }
                .filter { it.end > it.start },
            level = level,
            anchors = anchors,
        )
    }
}
