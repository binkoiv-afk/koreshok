package app.koreshok.core.format

import app.koreshok.core.model.Author
import app.koreshok.core.model.BookMetadata
import java.io.ByteArrayInputStream

object EpubMetadataParser {

    fun parse(epub: ByteArray, fallbackTitle: String): BookMetadata {
        val opfPath = findOpfPath(epub) ?: return BookMetadata(title = fallbackTitle)
        val opfBytes = readZipEntry(epub, opfPath) ?: return BookMetadata(title = fallbackTitle)
        val opf = parseXml(ByteArrayInputStream(opfBytes)).documentElement
        val metadata = opf.child("metadata") ?: return BookMetadata(title = fallbackTitle)
        val metas = metadata.children("meta")

        val authors = metadata.children("creator")
            .filter { (it.attr("role") ?: creatorRoleFromRefines(metas, it) ?: "aut") == "aut" }
            .map { creator -> creator.attr("file-as")?.takeIf { ',' in it }?.let(Author::parse) ?: Author.parse(creator.text) }
            .filter { it.sortName.isNotBlank() }

        // Calibre writes <meta name="calibre:series">; EPUB 3 uses belongs-to-collection.
        val calibreSeries = metas.firstOrNull { it.attr("name") == "calibre:series" }?.attr("content")
        val calibreIndex = metas.firstOrNull { it.attr("name") == "calibre:series_index" }?.attr("content")
        val collection = metas.firstOrNull { it.attr("property") == "belongs-to-collection" }
        val collectionIndex = collection?.attr("id")?.let { id ->
            metas.firstOrNull { it.attr("refines") == "#$id" && it.attr("property") == "group-position" }?.text
        }

        return BookMetadata(
            title = metadata.child("title")?.text?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            authors = authors,
            series = calibreSeries?.takeIf { it.isNotBlank() } ?: collection?.text?.takeIf { it.isNotBlank() },
            seriesIndex = (calibreIndex ?: collectionIndex)?.toFloatOrNull(),
            genres = metadata.children("subject").map { it.text }.filter { it.isNotBlank() }.distinct(),
            language = metadata.child("language")?.text?.takeIf { it.isNotBlank() },
            year = metadata.child("date")?.text?.let { YEAR.find(it)?.value?.toIntOrNull() },
            publisher = metadata.child("publisher")?.text?.takeIf { it.isNotBlank() },
            description = metadata.child("description")?.text?.takeIf { it.isNotBlank() },
            cover = findCover(epub, opfPath, opf, metas),
        )
    }

    internal fun findOpfPath(epub: ByteArray): String? {
        val container = readZipEntry(epub, "META-INF/container.xml")
        if (container != null) {
            val root = parseXml(ByteArrayInputStream(container)).documentElement
            root.descendant("rootfile")?.attr("full-path")?.let { return it }
        }
        return zipEntryNames(epub).firstOrNull { it.endsWith(".opf", ignoreCase = true) }
    }

    private fun creatorRoleFromRefines(metas: List<org.w3c.dom.Element>, creator: org.w3c.dom.Element): String? {
        val id = creator.attr("id") ?: return null
        return metas.firstOrNull { it.attr("refines") == "#$id" && it.attr("property") == "role" }?.text
    }

    private fun findCover(
        epub: ByteArray,
        opfPath: String,
        opf: org.w3c.dom.Element,
        metas: List<org.w3c.dom.Element>,
    ): ByteArray? {
        val items = opf.child("manifest")?.children("item").orEmpty()
        val coverId = metas.firstOrNull { it.attr("name") == "cover" }?.attr("content")
        val item = items.firstOrNull { it.attr("properties")?.split(' ')?.contains("cover-image") == true }
            ?: items.firstOrNull { it.attr("id") == coverId }
            ?: items.firstOrNull {
                it.attr("media-type")?.startsWith("image/") == true &&
                    (it.attr("id")?.contains("cover", ignoreCase = true) == true ||
                        it.attr("href")?.contains("cover", ignoreCase = true) == true)
            }
            ?: return null
        val href = item.attr("href") ?: return null
        return readZipEntry(epub, resolveZipPath(opfPath, href), maxBytes = 8 * 1024 * 1024)
    }
}
