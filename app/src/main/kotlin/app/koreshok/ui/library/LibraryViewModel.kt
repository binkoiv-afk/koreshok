package app.koreshok.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.koreshok.KoreshokApp
import app.koreshok.core.format.Fb2Genres
import app.koreshok.core.library.GroupBy
import app.koreshok.core.library.LibraryOrganizer
import app.koreshok.core.library.ShelfGroup
import app.koreshok.core.library.SortOrder
import app.koreshok.data.BookEntity
import app.koreshok.data.FolderEntity
import app.koreshok.data.LibraryRepository
import app.koreshok.data.ScanProgress
import app.koreshok.data.ShelfPrefs
import app.koreshok.data.ShelfSettings
import kotlinx.coroutines.flow.MutableStateFlow
import app.koreshok.update.AppUpdate
import app.koreshok.update.Updates
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
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

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateState
    data class Ready(val update: AppUpdate, val apk: File) : UpdateState
    data class Failed(val update: AppUpdate, val message: String) : UpdateState
}

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

    private val _update = MutableStateFlow<UpdateState>(UpdateState.None)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /** Null while idle, otherwise the result of the last manual check, for the folders sheet. */
    private val _checkMessage = MutableStateFlow<String?>(null)
    val checkMessage: StateFlow<String?> = _checkMessage.asStateFlow()

    init {
        viewModelScope.launch { library.rescanAll() }
        viewModelScope.launch { checkForUpdate(manual = false) }
    }

    fun checkForUpdate(manual: Boolean = true) = viewModelScope.launch {
        if (manual) _checkMessage.value = "Проверяю…"
        val found = runCatching { Updates.check() }
        found.getOrNull()?.let { _update.value = UpdateState.Available(it) }
        if (manual) {
            _checkMessage.value = when {
                found.isFailure -> "Не удалось проверить: нет связи с GitHub"
                found.getOrNull() == null -> "У вас последняя версия"
                else -> "Есть новая версия"
            }
        }
    }

    fun downloadUpdate(activityContext: Context) {
        // The download outlives the screen, so it must not hold on to the activity.
        val context = activityContext.applicationContext
        val update = when (val state = _update.value) {
            is UpdateState.Available -> state.update
            is UpdateState.Failed -> state.update
            is UpdateState.Ready -> {
                Updates.install(context, state.apk)
                return
            }
            else -> return
        }
        viewModelScope.launch {
            _update.value = UpdateState.Downloading(update, 0f)
            try {
                val apk = Updates.download(context, update) { progress ->
                    _update.value = UpdateState.Downloading(update, progress)
                }
                _update.value = UpdateState.Ready(update, apk)
                Updates.install(context, apk)
            } catch (e: Exception) {
                _update.value = UpdateState.Failed(update, e.message ?: "ошибка загрузки")
            }
        }
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
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KoreshokApp
                LibraryViewModel(app.library, app.shelfSettings)
            }
        }
    }
}
