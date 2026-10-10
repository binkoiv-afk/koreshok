package app.koreshok.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.koreshok.KoreshokApp
import app.koreshok.core.library.LibraryOrganizer
import app.koreshok.core.library.SortOrder
import app.koreshok.core.opds.OpdsPresets
import app.koreshok.data.AppDatabase
import app.koreshok.data.BookEntity
import app.koreshok.data.LibraryRepository
import app.koreshok.ui.catalog.CatalogLink
import app.koreshok.ui.catalog.CatalogResults
import app.koreshok.ui.catalog.CatalogService
import app.koreshok.ui.catalog.DownloadState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.koreshok.core.opds.OpdsDownload
import app.koreshok.core.opds.OpdsEntry

sealed interface CatalogSearch {
    data object Loading : CatalogSearch
    data class Found(val results: CatalogResults) : CatalogSearch
    data class Failed(val message: String) : CatalogSearch
}

data class SearchSection(val catalog: CatalogLink, val state: CatalogSearch)

class SearchViewModel(
    db: AppDatabase,
    library: LibraryRepository,
    private val service: CatalogService,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The query the catalogs were last asked about; local results follow every keystroke. */
    private val _submitted = MutableStateFlow("")
    val submitted: StateFlow<String> = _submitted.asStateFlow()

    val catalogs: StateFlow<List<CatalogLink>> = db.catalogs().observeAll()
        .map { saved -> OpdsPresets.all.filter { it.searchable }.map { CatalogLink(null, it.title, it.url) } + saved.map { CatalogLink(it.id, it.title, it.url) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, OpdsPresets.all.filter { it.searchable }.map { CatalogLink(null, it.title, it.url) })

    /** Catalog URLs switched off for search by the user. */
    private val _disabled = MutableStateFlow<Set<String>>(emptySet())
    val disabled: StateFlow<Set<String>> = _disabled.asStateFlow()

    private val _sections = MutableStateFlow<List<SearchSection>>(emptyList())
    val sections: StateFlow<List<SearchSection>> = _sections.asStateFlow()

    val local: StateFlow<List<BookEntity>> = combine(library.books, _query) { books, query ->
        if (query.isBlank()) emptyList() else LibraryOrganizer.sort(LibraryOrganizer.filter(books, query), SortOrder.TITLE)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloads: StateFlow<Map<String, DownloadState>> = service.downloads

    private var searchJob: Job? = null

    fun setQuery(value: String) {
        _query.value = value
        if (value.isBlank()) {
            searchJob?.cancel()
            _submitted.value = ""
            _sections.value = emptyList()
        }
    }

    fun toggleCatalog(url: String) {
        _disabled.update { if (url in it) it - url else it + url }
    }

    fun submit() {
        val text = _query.value.trim()
        if (text.isEmpty()) return
        _submitted.value = text
        val targets = catalogs.value.filter { it.url !in _disabled.value }
        _sections.value = targets.map { SearchSection(it, CatalogSearch.Loading) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Every catalog is asked at once; each section fills in as its answer arrives.
            targets.forEach { catalog ->
                launch {
                    val result = try {
                        CatalogSearch.Found(service.search(catalog.url, text))
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        CatalogSearch.Failed(e.message ?: "нет ответа")
                    }
                    _sections.update { list -> list.map { if (it.catalog.url == catalog.url) it.copy(state = result) else it } }
                }
            }
        }
    }

    fun download(entry: OpdsEntry, file: OpdsDownload) = service.download(entry, file)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KoreshokApp
                SearchViewModel(app.database, app.library, app.catalogs)
            }
        }
    }
}
