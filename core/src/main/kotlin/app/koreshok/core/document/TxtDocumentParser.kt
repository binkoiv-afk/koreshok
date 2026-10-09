package app.koreshok.core.document

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Plain text books. The encoding is guessed (BOM, then strict UTF-8, then Windows-1251,
 * which is what most old Russian .txt files use). Paragraphs are split on blank lines when
 * the file has them, otherwise on every line; lines like "Глава 3" start new chapters.
 */
object TxtDocumentParser {
    private val chapterHeading = Regex(
        """^\s*(глава|часть|книга|пролог|эпилог|chapter|part|book|prologue|epilogue)(?![\p{L}]).{0,60}$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(bytes: ByteArray, fallbackTitle: String): Document {
        val text = decode(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.lines()
        val blankLines = lines.count { it.isBlank() }
        // A file with blank lines between paragraphs wraps them across several lines.
        val wrapped = blankLines > lines.size / 10
        val paragraphs = mutableListOf<String>()
        if (wrapped) {
            val current = StringBuilder()
            for (line in lines) {
                if (line.isBlank()) {
                    if (current.isNotBlank()) paragraphs += current.toString()
                    current.setLength(0)
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(line.trim())
                }
            }
            if (current.isNotBlank()) paragraphs += current.toString()
        } else {
            lines.filter { it.isNotBlank() }.mapTo(paragraphs) { it.trim() }
        }

        val chapters = mutableListOf<Chapter>()
        val toc = mutableListOf<TocEntry>()
        var title: String? = null
        var blocks = mutableListOf<Block>()
        fun flush() {
            if (blocks.isNotEmpty() || title != null) chapters += Chapter(title, blocks)
            blocks = mutableListOf()
        }
        for (paragraph in paragraphs) {
            if (chapterHeading.matches(paragraph)) {
                flush()
                title = paragraph
                toc += TocEntry(paragraph, 0, Position(chapters.size, 0))
                blocks += TextBlock(BlockKind.HEADING, paragraph, level = 2)
            } else {
                blocks += TextBlock(BlockKind.PARAGRAPH, paragraph.replace(Regex("\\s+"), " "))
            }
        }
        flush()
        if (chapters.isEmpty()) chapters += Chapter(null, emptyList())
        val parts = splitLong(chapters)
        val contents = toc.ifEmpty {
            if (parts.size == 1) emptyList() else parts.indices.map { TocEntry("Часть ${it + 1}", 0, Position(it, 0)) }
        }
        return Document(fallbackTitle, parts, contents, emptyMap(), emptyMap())
    }

    /**
     * The reader lays out a chapter at a time, so a heading-less text of several megabytes
     * is cut into parts. Only chapters without headings are split, keeping the TOC valid.
     */
    private fun splitLong(chapters: List<Chapter>): List<Chapter> {
        if (chapters.size != 1 || chapters[0].title != null) return chapters
        val blocks = chapters[0].blocks
        val parts = mutableListOf<Chapter>()
        var current = mutableListOf<Block>()
        var length = 0
        for (block in blocks) {
            current += block
            length += block.length
            if (length >= PART_LENGTH) {
                parts += Chapter(null, current)
                current = mutableListOf()
                length = 0
            }
        }
        if (current.isNotEmpty() || parts.isEmpty()) parts += Chapter(null, current)
        return parts
    }

    internal fun decode(bytes: ByteArray): String {
        when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.startsWith(0xFF, 0xFE) -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.startsWith(0xFE, 0xFF) -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1251"))
        }
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

    private const val PART_LENGTH = 40_000
}
