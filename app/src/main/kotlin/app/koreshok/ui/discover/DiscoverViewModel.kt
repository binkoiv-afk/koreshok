package app.koreshok.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.koreshok.KoreshokApp
import app.koreshok.core.discover.Discover
import app.koreshok.core.opds.OpdsDownload
import app.koreshok.core.opds.OpdsEntry
import app.koreshok.data.FavoriteAuthor
import app.koreshok.data.LibraryRepository
import app.koreshok.data.TasteSettings
import app.koreshok.ui.catalog.CatalogService
import app.koreshok.ui.catalog.DownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the questionnaire can offer from the shelf: frequent authors and likely genres. */
data class ShelfTaste(val authors: List<String> = emptyList(), val genres: Set<String> = emptySet())

/** A book pulled at random from the catalogs; null book while it is being picked. */
data class RandomPick(val book: OpdsEntry?, val failed: Boolean = false)

class DiscoverViewModel(
    private val service: DiscoverService,
    private val taste: TasteSettings,
    library: LibraryRepository,
    private val catalogs: CatalogService,
) : ViewModel() {
    val prefs = service.prefs
    val rows = service.rows
    val fresh = service.fresh
    val downloads: StateFlow<Map<String, DownloadState>> = catalogs.downloads

    val shelfTaste: StateFlow<ShelfTaste> = library.books.map { books ->
        val authors = books.flatMap { book -> book.authors.split(", ", ";").map(String::trim).filter(String::isNotEmpty) }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(16)
            .map { it.key }
        ShelfTaste(authors, Discover.guessGenres(books.flatMap { it.genres }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShelfTaste())

    private val _random = MutableStateFlow<RandomPick?>(null)
    val random: StateFlow<RandomPick?> = _random.asStateFlow()

    init {
        service.refresh()
    }

    fun refresh() = service.refresh(force = true)

    fun markFreshSeen() = service.markFreshSeen()

    fun saveTaste(genres: Set<String>, authors: List<FavoriteAuthor>, followNew: Boolean) {
        viewModelScope.launch {
            taste.save(genres, authors, followNew)
            service.refresh()
        }
    }

    fun dismissQuiz() {
        viewModelScope.launch { taste.dismiss() }
    }

    fun pickRandom() {
        _random.value = RandomPick(null)
        viewModelScope.launch {
            val book = service.randomBook()
            // The sheet may have been closed meanwhile.
            if (_random.value != null) _random.value = RandomPick(book, failed = book == null)
        }
    }

    fun closeRandom() {
        _random.value = null
    }

    fun download(entry: OpdsEntry, file: OpdsDownload) = catalogs.download(entry, file)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KoreshokApp
                DiscoverViewModel(app.discover, app.taste, app.library, app.catalogs)
            }
        }
    }
}
