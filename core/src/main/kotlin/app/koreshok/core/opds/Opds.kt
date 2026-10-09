package app.koreshok.core.opds

import app.koreshok.core.format.attr
import app.koreshok.core.format.child
import app.koreshok.core.format.children
import app.koreshok.core.format.descendants
import app.koreshok.core.format.parseXml
import app.koreshok.core.format.text
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder

data class OpdsFeed(
    val title: String,
    val entries: List<OpdsEntry>,
    val nextUrl: String? = null,
    /** Either a ready template with {searchTerms}, or an OpenSearch description to fetch first. */
    val searchTemplate: String? = null,
    val openSearchUrl: String? = null,
)

data class OpdsEntry(
    val id: String,
    val title: String,
    val authors: List<String>,
    val summary: String?,
    val coverUrl: String?,
    /** Where tapping the entry leads when it is a folder of the catalog. */
    val navigationUrl: String?,
    val downloads: List<OpdsDownload>,
) {
    val isBook: Boolean get() = downloads.isNotEmpty()
}

data class OpdsDownload(val url: String, val mimeType: String) {
    /** Short label for a button: "FB2", "EPUB", "PDF"… */
    val label: String get() = OpdsFormats.label(mimeType)
    val extension: String get() = OpdsFormats.extension(mimeType)
}

object OpdsFormats {
    private val known = listOf(
        Triple("application/epub+zip", "EPUB", "epub"),
        Triple("application/fb2+zip", "FB2", "fb2.zip"),
        Triple("application/x-zip-compressed-fb2", "FB2", "fb2.zip"),
        Triple("application/fb2", "FB2", "fb2"),
        Triple("application/x-fictionbook+xml", "FB2", "fb2"),
        Triple("text/fb2+xml", "FB2", "fb2"),
        Triple("application/fb2+xml", "FB2", "fb2"),
        Triple("application/pdf", "PDF", "pdf"),
        Triple("application/x-mobipocket-ebook", "MOBI", "mobi"),
        Triple("application/x-mobi8-ebook", "AZW3", "azw3"),
        Triple("image/vnd.djvu", "DJVU", "djvu"),
        Triple("image/x-djvu", "DJVU", "djvu"),
        Triple("application/rtf", "RTF", "rtf"),
        Triple("text/rtf", "RTF", "rtf"),
        Triple("text/plain", "TXT", "txt"),
        Triple("application/txt+zip", "TXT", "txt.zip"),
        Triple("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "DOCX", "docx"),
        Triple("application/x-cbz", "CBZ", "cbz"),
        Triple("application/vnd.comicbook+zip", "CBZ", "cbz"),
    )

    fun label(mime: String): String = known.firstOrNull { it.first == base(mime) }?.second
        ?: base(mime).substringAfterLast('/').substringAfterLast('-').uppercase()

    fun extension(mime: String): String = known.firstOrNull { it.first == base(mime) }?.third ?: "bin"

    /** Formats Koreshok shows first, best reading experience first. */
    val preference = listOf("EPUB", "FB2", "AZW3", "MOBI", "PDF", "DJVU", "DOCX", "RTF", "TXT", "CBZ")

    private fun base(mime: String) = mime.substringBefore(';').trim().lowercase()
}

object OpdsParser {

    fun parse(bytes: ByteArray, baseUrl: String): OpdsFeed {
        val feed = parseXml(ByteArrayInputStream(bytes)).documentElement
        val links = feed.children("link")
        return OpdsFeed(
            title = feed.child("title")?.text.orEmpty(),
            entries = feed.children("entry").map { entry(it, baseUrl) },
            nextUrl = links.firstOrNull { it.attr("rel") == "next" }?.attr("href")?.let { resolve(baseUrl, it) },
            searchTemplate = searchLinks(links, baseUrl).firstOrNull { it.contains("{searchTerms}") },
            openSearchUrl = links.firstOrNull {
                it.attr("rel") == "search" && it.attr("type").orEmpty().contains("opensearchdescription")
            }?.attr("href")?.let { resolve(baseUrl, it) },
        )
    }

    /**
     * Search links that are ready templates. Atom ones come first; Либрусек labels its template
     * as an OpenSearch description, so any search link with {searchTerms} counts.
     */
    private fun searchLinks(links: List<Element>, baseUrl: String): List<String> = links
        .filter { it.attr("rel") == "search" }
        .sortedBy { if (it.attr("type").orEmpty().contains("atom")) 0 else 1 }
        .mapNotNull { it.attr("href") }
        .map { resolve(baseUrl, it) }

    /** Reads the Atom search template out of an OpenSearch description document. */
    fun searchTemplate(openSearch: ByteArray, baseUrl: String): String? {
        val root = parseXml(ByteArrayInputStream(openSearch)).documentElement
        val urls = root.descendants("Url")
        val url = urls.firstOrNull { it.attr("type").orEmpty().contains("atom") } ?: urls.firstOrNull()
        return url?.attr("template")?.let { resolve(baseUrl, it) }
    }

    fun searchUrl(template: String, query: String): String =
        template.replace("{searchTerms}", URLEncoder.encode(query.trim(), "UTF-8"))
            .replace(Regex("\\{[^}]*\\?\\}"), "")

    private fun entry(entry: Element, baseUrl: String): OpdsEntry {
        val links = entry.children("link")
        val downloads = links
            .filter { it.attr("rel").orEmpty().startsWith("http://opds-spec.org/acquisition") }
            .mapNotNull { link ->
                val href = link.attr("href") ?: return@mapNotNull null
                OpdsDownload(resolve(baseUrl, href), link.attr("type").orEmpty())
            }
            .distinctBy { it.label }
            .sortedBy { OpdsFormats.preference.indexOf(it.label).let { i -> if (i < 0) 99 else i } }
        val navigation = links.firstOrNull { link ->
            val type = link.attr("type").orEmpty()
            val rel = link.attr("rel").orEmpty()
            type.contains("atom+xml") && !rel.contains("acquisition") && rel != "alternate" ||
                type.contains("kind=navigation") || rel == "subsection"
        } ?: links.firstOrNull { it.attr("type").orEmpty().contains("atom+xml") && it.attr("rel") != "alternate" }
        val cover = links.firstOrNull { it.attr("rel").orEmpty().contains("thumbnail") }
            ?: links.firstOrNull { it.attr("rel").orEmpty().let { rel -> rel.contains("opds-spec.org/image") || rel == "x-stanza-cover-image" } }
        val authors = entry.children("author").mapNotNull { it.child("name")?.text?.takeIf { name -> name.isNotBlank() } }
        val summary = (entry.child("summary") ?: entry.child("content"))?.text?.takeIf { it.isNotBlank() }
        return OpdsEntry(
            id = entry.child("id")?.text.orEmpty().ifBlank { entry.child("title")?.text.orEmpty() },
            title = entry.child("title")?.text.orEmpty(),
            authors = authors,
            summary = summary?.let(::stripTags),
            coverUrl = cover?.attr("href")?.let { resolve(baseUrl, it) },
            navigationUrl = if (downloads.isEmpty()) navigation?.attr("href")?.let { resolve(baseUrl, it) } else null,
            downloads = downloads,
        )
    }

    /** Some catalogs put escaped HTML into summaries. */
    private fun stripTags(text: String): String =
        text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" ?\n ?"), "\n")
            .trim()

    /** Resolves a relative href; search templates keep their {placeholders}, which URI itself rejects. */
    fun resolve(base: String, href: String): String = try {
        val safe = href.replace(" ", "%20").replace("{", "%7B").replace("}", "%7D")
        URI(base).resolve(safe).toString().replace("%7B", "{").replace("%7D", "}")
    } catch (_: Exception) {
        href
    }
}

/** Catalogs offered out of the box. */
object OpdsPresets {
    data class Preset(val title: String, val url: String, val description: String)

    val all = listOf(
        Preset("Флибуста", "http://flibusta.is/opds", "Самая большая русская библиотека: художественная и не только"),
        Preset("Либрусек", "http://lib.rus.ec/opds", "Старейшая русская библиотека, много редкого"),
        Preset("Coollib", "https://coollib.net/opds", "Русские книги, удобные подборки по сериям"),
        Preset("Мир фантастики", "https://www.fantasy-worlds.org/opds/", "Фантастика и фэнтези, авторы и циклы"),
        Preset("Project Gutenberg", "https://m.gutenberg.org/ebooks.opds/", "70 000 классических книг на английском и других языках"),
    )

    fun description(url: String): String? = all.firstOrNull { it.url == url }?.description
}
