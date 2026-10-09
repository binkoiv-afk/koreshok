package app.bookreader.core.model

enum class BookFormat(val extensions: List<String>) {
    EPUB(listOf("epub")),
    FB2(listOf("fb2")),
    FB2_ZIP(listOf("fb2.zip", "fbz")),
    PDF(listOf("pdf")),
    DJVU(listOf("djvu", "djv")),
    MOBI(listOf("mobi", "azw", "azw3", "prc")),
    TXT(listOf("txt")),
    RTF(listOf("rtf")),
    DOCX(listOf("docx")),
    CBZ(listOf("cbz")),
    CBR(listOf("cbr"));

    companion object {
        /** Longest extension first, so "book.fb2.zip" is FB2_ZIP rather than an unknown ".zip". */
        private val byExtension = entries
            .flatMap { format -> format.extensions.map { it to format } }
            .sortedByDescending { it.first.length }

        fun fromFileName(name: String): BookFormat? {
            val lower = name.lowercase()
            return byExtension.firstOrNull { lower.endsWith("." + it.first) }?.second
        }
    }
}

data class Author(
    val firstName: String = "",
    val middleName: String = "",
    val lastName: String = "",
) {
    /** "Толстой Лев Николаевич": the form used for sorting and grouping. */
    val sortName: String
        get() = listOf(lastName, firstName, middleName).filter { it.isNotBlank() }.joinToString(" ")

    /** "Лев Толстой": the form shown under a cover. */
    val displayName: String
        get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { middleName }

    companion object {
        /** Splits a free-form "Лев Николаевич Толстой" or "Толстой, Лев" into parts. */
        fun parse(raw: String): Author {
            val text = raw.trim().replace(Regex("\\s+"), " ")
            if (text.isEmpty()) return Author()
            if (',' in text) {
                val (last, rest) = text.split(',', limit = 2).map { it.trim() }
                val parts = rest.split(' ').filter { it.isNotEmpty() }
                return Author(
                    firstName = parts.firstOrNull().orEmpty(),
                    middleName = parts.drop(1).joinToString(" "),
                    lastName = last,
                )
            }
            val parts = text.split(' ')
            return when (parts.size) {
                1 -> Author(lastName = parts[0])
                2 -> Author(firstName = parts[0], lastName = parts[1])
                else -> Author(
                    firstName = parts.first(),
                    middleName = parts.subList(1, parts.size - 1).joinToString(" "),
                    lastName = parts.last(),
                )
            }
        }
    }
}

data class BookMetadata(
    val title: String,
    val authors: List<Author> = emptyList(),
    val series: String? = null,
    val seriesIndex: Float? = null,
    val genres: List<String> = emptyList(),
    val language: String? = null,
    val year: Int? = null,
    val publisher: String? = null,
    val description: String? = null,
    val cover: ByteArray? = null,
) {
    // Cover bytes are excluded so equality stays about the descriptive fields.
    override fun equals(other: Any?): Boolean =
        other is BookMetadata && copy(cover = null).toString() == other.copy(cover = null).toString()

    override fun hashCode(): Int = copy(cover = null).toString().hashCode()
}
