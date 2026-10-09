package app.koreshok.ui.catalog

import app.koreshok.core.opds.OpdsDownload
import app.koreshok.core.opds.OpdsEntry
import app.koreshok.core.opds.OpdsFeed
import app.koreshok.core.opds.OpdsParser
import app.koreshok.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

sealed interface DownloadState {
    data class Running(val progress: Float) : DownloadState
    data class Done(val bookUri: String?) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** What a search in one catalog found: books, and folders such as matching authors or series. */
data class CatalogResults(
    val books: List<OpdsEntry>,
    val folders: List<OpdsEntry>,
    val moreUrl: String?,
    /** The feed holding these results, to open in the catalog browser for the full list. */
    val feedUrl: String,
)

/**
 * Catalog access shared by the catalog browser and the global search: feeds, search templates
 * (looked up once per catalog) and downloads, which keep going when the user leaves the screen.
 */
class CatalogService(private val library: LibraryRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val templates = ConcurrentHashMap<String, String>()

    /** Keyed by download URL. */
    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    suspend fun fetchFeed(url: String): OpdsFeed {
        val (bytes, finalUrl) = OpdsClient.fetch(url)
        val feed = OpdsParser.parse(bytes, finalUrl)
        if (feed.searchTemplate != null) return feed
        val openSearch = feed.openSearchUrl ?: return feed
        val template = runCatching {
            val (description, descriptionUrl) = OpdsClient.fetch(openSearch)
            OpdsParser.searchTemplate(description, descriptionUrl)
        }.getOrNull()
        return feed.copy(searchTemplate = template)
    }

    /** The search template of a catalog, from its root feed; null when it has no search. */
    suspend fun searchTemplate(rootUrl: String): String? {
        templates[rootUrl]?.let { return it }
        val template = fetchFeed(rootUrl).searchTemplate ?: return null
        templates[rootUrl] = template
        return template
    }

    suspend fun search(rootUrl: String, query: String): CatalogResults {
        val template = searchTemplate(rootUrl) ?: throw UnsupportedOperationException("В этом каталоге нет поиска")
        var feedUrl = OpdsParser.searchUrl(template, query)
        var feed = fetchFeed(feedUrl)
        // Some catalogs (Флибуста among them) first ask whether to look for books or authors.
        if (feed.entries.none { it.isBook } && feed.entries.size in 1..3) {
            val books = feed.entries.firstOrNull { it.title.contains("книг", ignoreCase = true) || it.title.contains("book", ignoreCase = true) }
            val folders = feed.entries.filter { it !== books }
            books?.navigationUrl?.let { url ->
                feedUrl = url
                val found = fetchFeed(url)
                // Author folders from the second branch are useful too, but cost another request; keep them as links.
                feed = found.copy(entries = found.entries + folders.filter { it.navigationUrl != null })
            }
        }
        return CatalogResults(
            books = feed.entries.filter { it.isBook },
            folders = feed.entries.filter { !it.isBook && it.navigationUrl != null },
            moreUrl = feed.nextUrl,
            feedUrl = feedUrl,
        )
    }

    fun download(entry: OpdsEntry, file: OpdsDownload) {
        val key = file.url
        if (_downloads.value[key] is DownloadState.Running) return
        set(key, DownloadState.Running(0f))
        scope.launch {
            try {
                val author = entry.authors.firstOrNull()?.let { "$it - " }.orEmpty()
                val saved = OpdsClient.download(file.url, library.downloadsDir, "$author${entry.title}.${file.extension}") {
                    set(key, DownloadState.Running(it))
                }
                val book = library.importDownloaded(saved)
                set(key, DownloadState.Done(book?.uri))
            } catch (e: Exception) {
                set(key, DownloadState.Failed(e.message ?: "ошибка"))
            }
        }
    }

    private fun set(key: String, state: DownloadState) {
        _downloads.update { it + (key to state) }
    }
}
