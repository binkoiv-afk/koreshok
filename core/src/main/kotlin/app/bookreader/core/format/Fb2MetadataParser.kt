package app.bookreader.core.format

import app.bookreader.core.model.Author
import app.bookreader.core.model.BookMetadata
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

object Fb2MetadataParser {

    /** Parses a plain .fb2 file. The XML prolog decides the encoding (UTF-8, windows-1251...). */
    fun parse(fb2: ByteArray, fallbackTitle: String): BookMetadata {
        val root = parseXml(ByteArrayInputStream(fb2)).documentElement
        val description = root.child("description") ?: return BookMetadata(title = fallbackTitle)
        val titleInfo = description.child("title-info") ?: return BookMetadata(title = fallbackTitle)
        val publishInfo = description.child("publish-info")
        val sequence = titleInfo.child("sequence")

        return BookMetadata(
            title = titleInfo.child("book-title")?.text?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            authors = titleInfo.children("author").map(::author).filter { it.sortName.isNotBlank() },
            series = sequence?.attr("name")?.trim()?.takeIf { it.isNotEmpty() },
            seriesIndex = sequence?.attr("number")?.toFloatOrNull(),
            genres = titleInfo.children("genre").map { it.text }.filter { it.isNotBlank() }.distinct(),
            language = titleInfo.child("lang")?.text?.takeIf { it.isNotBlank() },
            year = (publishInfo?.child("year")?.text ?: titleInfo.child("date")?.let { it.attr("value") ?: it.text })
                ?.let { YEAR.find(it)?.value?.toIntOrNull() },
            publisher = publishInfo?.child("publisher")?.text?.takeIf { it.isNotBlank() },
            description = titleInfo.child("annotation")?.text?.takeIf { it.isNotBlank() },
            cover = cover(root, titleInfo),
        )
    }

    /** Parses .fb2.zip / .fbz: the first .fb2 entry of the archive. */
    fun parseZipped(zip: ByteArray, fallbackTitle: String): BookMetadata {
        ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".fb2", ignoreCase = true)) {
                    val bytes = stream.readAtMost(64 * 1024 * 1024) ?: break
                    return parse(bytes, fallbackTitle)
                }
            }
        }
        return BookMetadata(title = fallbackTitle)
    }

    private fun author(element: org.w3c.dom.Element): Author {
        val author = Author(
            firstName = element.child("first-name")?.text.orEmpty(),
            middleName = element.child("middle-name")?.text.orEmpty(),
            lastName = element.child("last-name")?.text.orEmpty(),
        )
        if (author.sortName.isNotBlank()) return author
        return Author(lastName = element.child("nickname")?.text.orEmpty())
    }

    private fun cover(root: org.w3c.dom.Element, titleInfo: org.w3c.dom.Element): ByteArray? {
        val href = titleInfo.child("coverpage")?.child("image")?.attr("href") ?: return null
        val id = href.removePrefix("#")
        val binary = root.children("binary").firstOrNull { it.attr("id") == id } ?: return null
        return try {
            Base64.getMimeDecoder().decode(binary.textContent.orEmpty())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
