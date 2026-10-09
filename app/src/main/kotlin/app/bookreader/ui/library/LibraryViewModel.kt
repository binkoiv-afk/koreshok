package app.bookreader.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.bookreader.BookReaderApp
import app.bookreader.core.format.Fb2Genres
import app.bookreader.core.library.GroupBy
import app.bookreader.core.library.LibraryOrganizer
import app.bookreader.core.library.ShelfGroup
import app.bookreader.core.library.SortOrder
import app.bookreader.data.BookEntity
import app.bookreader.data.FolderEntity
import app.bookreader.data.LibraryRepository
import app.bookreader.data.ScanProgress
import app.bookreader.data.ShelfPrefs
import app.bookreader.data.ShelfSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryState(
    val groups: List<ShelfGroup<BookEntity>> = emptyList(),
    val totalBooks: Int = 0,
    val folders: List<FolderEntity> = emptyList(),
    val prefs: ShelfPrefs = ShelfPrefs(),
    val query: String = "",
    val scan: ScanProgress? = null,
    val loaded: Boolean = false,
)

class LibraryViewModel(
    private val library: LibraryRepository,
    private val settings: ShelfSettings,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<LibraryState> = combine(
        library.books,
        library.folders,
        settings.prefs,
        query,
        library.scan,
    ) { books, folders, prefs, query, scan ->
        val visible = LibraryOrganizer.filter(books, query)
        val sorted = LibraryOrganizer.sort(visible, prefs.sort, prefs.descending)
        LibraryState(
            groups = LibraryOrganizer.group(sorted, prefs.groupBy, Fb2Genres::displayName),
            totalBooks = books.size,
            folders = folders,
            prefs = prefs,
            query = query,
            scan = scan,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    init {
        viewModelScope.launch { library.rescanAll() }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSort(sort: SortOrder) = viewModelScope.launch {
        // Picking the active order again flips its direction.
        val current = state.value.prefs
        if (current.sort == sort) settings.setDescending(!current.descending) else {
            settings.setSort(sort)
            settings.setDescending(false)
        }
    }

    fun setGroupBy(groupBy: GroupBy) = viewModelScope.launch { settings.setGroupBy(groupBy) }

    fun addFolder(uri: Uri) = viewModelScope.launch { library.addFolder(uri) }

    fun removeFolder(uri: String) = viewModelScope.launch { library.removeFolder(uri) }

    fun rescan() = viewModelScope.launch { library.rescanAll() }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BookReaderApp
                LibraryViewModel(app.library, app.shelfSettings)
            }
        }
    }
}
