package app.koreshok.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.koreshok.KoreshokApp
import app.koreshok.core.opds.OpdsDownload
import app.koreshok.core.opds.OpdsEntry
import app.koreshok.core.opds.OpdsFeed
import app.koreshok.core.opds.OpdsParser
import app.koreshok.core.opds.OpdsPresets
import app.koreshok.data.AppDatabase
import app.koreshok.data.CatalogEntity
import app.koreshok.data.LibraryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CatalogLink(val id: Long?, val title: String, val url: String)

/** One opened page of a catalog; pages stack up as the user goes deeper. */
data class FeedPage(
    val url: String,
    val title: String,
    val entries: List<OpdsEntry> = emptyList(),
    val nextUrl: String? = null,
    val searchTemplate: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

sealed interface DownloadState {
    data class Running(val progress: Float) : DownloadState
    data class Done(val bookUri: String?) : DownloadState
    data class Failed(val message: String) : DownloadState
}

class CatalogViewModel(
    private val db: AppDatabase,
    private val library: LibraryRepository,
) : ViewModel() {

    val catalogs: StateFlow<List<CatalogLink>> = db.catalogs().observeAll()
        .map { saved ->
            OpdsPresets.all.map { CatalogLink(null, it.title, it.url) } +
                saved.map { CatalogLink(it.id, it.title, it.url) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, OpdsPresets.all.map { CatalogLink(null, it.title, it.url) })

    private val _stack = MutableStateFlow<List<FeedPage>>(emptyList())
    val stack: StateFlow<List<FeedPage>> = _stack.asStateFlow()

    /** Keyed by download URL. */
    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    private var loadJob: Job? = null

    fun open(url: String, title: String) {
        _stack.value = _stack.value + FeedPage(url, title)
        load(url)
    }

    fun back(): Boolean {
        if (_stack.value.isEmpty()) return false
        loadJob?.cancel()
        _stack.value = _stack.value.dropLast(1)
        return true
    }

    fun closeAll() {
        loadJob?.cancel()
        _stack.value = emptyList()
    }

    fun retry() {
        _stack.value.lastOrNull()?.let { load(it.url) }
    }

    fun search(query: String) {
        val template = _stack.value.lastOrNull()?.searchTemplate ?: return
        if (query.isBlank()) return
        open(OpdsParser.searchUrl(template, query), "Поиск: $query")
    }

    private fun updateTop(transform: (FeedPage) -> FeedPage) {
        val stack = _stack.value
        if (stack.isEmpty()) return
        _stack.value = stack.dropLast(1) + transform(stack.last())
    }

    private fun load(url: String) {
        loadJob?.cancel()
        updateTop { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val feed = fetchFeed(url)
                // Search stays available deeper in the catalog even when sub-feeds do not repeat it.
                val inherited = _stack.value.dropLast(1).lastOrNull()?.searchTemplate
                updateTop {
                    it.copy(
                        title = feed.title.ifBlank { it.title },
                        entries = feed.entries,
                        nextUrl = feed.nextUrl,
                        searchTemplate = feed.searchTemplate ?: inherited,
                        loading = false,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                updateTop { it.copy(loading = false, error = e.message ?: "Не удалось загрузить каталог") }
            }
        }
    }

    fun loadMore() {
        val top = _stack.value.lastOrNull() ?: return
        val next = top.nextUrl ?: return
        if (top.loadingMore) return
        updateTop { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val feed = fetchFeed(next)
                updateTop { it.copy(entries = it.entries + feed.entries, nextUrl = feed.nextUrl, loadingMore = false) }
            } catch (e: Exception) {
                updateTop { it.copy(loadingMore = false, error = e.message) }
            }
        }
    }

    private suspend fun fetchFeed(url: String): OpdsFeed {
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

    fun download(entry: OpdsEntry, file: OpdsDownload) {
        val key = file.url
        if (_downloads.value[key] is DownloadState.Running) return
        setDownload(key, DownloadState.Running(0f))
        viewModelScope.launch {
            try {
                val author = entry.authors.firstOrNull()?.let { "$it - " }.orEmpty()
                val saved = OpdsClient.download(file.url, library.downloadsDir, "$author${entry.title}.${file.extension}") {
                    setDownload(key, DownloadState.Running(it))
                }
                val book = library.importDownloaded(saved)
                setDownload(key, DownloadState.Done(book?.uri))
            } catch (e: Exception) {
                setDownload(key, DownloadState.Failed(e.message ?: "ошибка"))
            }
        }
    }

    private fun setDownload(key: String, state: DownloadState) {
        _downloads.value = _downloads.value + (key to state)
    }

    fun addCatalog(title: String, url: String) = viewModelScope.launch {
        val clean = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        db.catalogs().insert(CatalogEntity(title = title.trim().ifBlank { clean }, url = clean, addedAt = System.currentTimeMillis()))
    }

    fun removeCatalog(id: Long) = viewModelScope.launch { db.catalogs().delete(id) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KoreshokApp
                CatalogViewModel(app.database, app.library)
            }
        }
    }
}
