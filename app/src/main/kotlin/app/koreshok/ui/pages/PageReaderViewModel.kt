package app.koreshok.ui.pages

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.koreshok.KoreshokApp
import app.koreshok.core.model.BookFormat
import app.koreshok.data.AnnotationEntity
import app.koreshok.data.AnnotationKind
import app.koreshok.ui.reader.ReaderPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PageBookState {
    data object Loading : PageBookState
    data class Failed(val message: String) : PageBookState
    data class Ready(val source: PageSource, val title: String, val startPage: Int) : PageBookState
}

class PageReaderViewModel(private val uri: String, private val app: KoreshokApp) : AndroidViewModel(app) {
    private val library = app.library

    private val _state = MutableStateFlow<PageBookState>(PageBookState.Loading)
    val state: StateFlow<PageBookState> = _state.asStateFlow()

    val prefs: StateFlow<ReaderPrefs?> = app.readerSettings.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val bookmarks: StateFlow<List<AnnotationEntity>> =
        library.annotations(uri).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            try {
                val book = library.book(uri) ?: error("Книга не найдена в библиотеке")
                val source = PageSource.open(app, uri, BookFormat.valueOf(book.format))
                if (source.pageCount == 0) error("В файле нет страниц")
                val start = parsePage(book.position)?.coerceIn(0, source.pageCount - 1) ?: 0
                _state.value = PageBookState.Ready(source, book.title, start)
                onPageShown(start)
            } catch (e: Exception) {
                _state.value = PageBookState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun onPageShown(page: Int) {
        val source = (state.value as? PageBookState.Ready)?.source ?: return
        val progress = if (source.pageCount <= 1) 1f else page.toFloat() / (source.pageCount - 1)
        viewModelScope.launch { library.saveProgress(uri, "$PREFIX$page", progress) }
    }

    fun toggleBookmark(page: Int) {
        viewModelScope.launch {
            val existing = bookmarks.value.firstOrNull { it.kind == AnnotationKind.BOOKMARK && it.chapter == page }
            if (existing != null) {
                library.deleteAnnotation(existing.id)
            } else {
                library.saveAnnotation(
                    AnnotationEntity(
                        bookUri = uri,
                        kind = AnnotationKind.BOOKMARK,
                        chapter = page,
                        block = 0,
                        start = 0,
                        end = 0,
                        text = "Страница ${page + 1}",
                        chapterTitle = null,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { library.deleteAnnotation(id) }
    }

    override fun onCleared() {
        (state.value as? PageBookState.Ready)?.source?.close()
    }

    companion object {
        /** Saved positions of page books look like "page:12", so text positions never parse them. */
        private const val PREFIX = "page:"

        fun parsePage(position: String?): Int? =
            position?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toIntOrNull()

        fun factory(uri: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KoreshokApp
                PageReaderViewModel(uri, app)
            }
        }
    }
}
