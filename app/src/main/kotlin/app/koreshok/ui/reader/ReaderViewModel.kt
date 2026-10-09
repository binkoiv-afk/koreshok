package app.koreshok.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.koreshok.KoreshokApp
import app.koreshok.core.document.Document
import app.koreshok.core.document.Documents
import app.koreshok.core.document.Position
import app.koreshok.core.model.BookFormat
import app.koreshok.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReaderState {
    data object Loading : ReaderState
    data class Failed(val message: String) : ReaderState
    data class Ready(val document: Document, val language: String?) : ReaderState
}

/**
 * Where the reader should be. [jump] changes whenever the page must be moved from outside
 * the pager (open, TOC, link, slider, crossing a chapter); page swipes only update [position].
 */
data class Location(val position: Position, val toChapterEnd: Boolean = false, val jump: Int = 0)

class ReaderViewModel(
    private val uri: String,
    private val library: LibraryRepository,
    private val settings: ReaderSettings,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val _location = MutableStateFlow(Location(Position.START))
    val location: StateFlow<Location> = _location.asStateFlow()

    /** Positions to return to after following links, newest last. */
    private val _history = MutableStateFlow<List<Position>>(emptyList())
    val history: StateFlow<List<Position>> = _history.asStateFlow()

    /** Null until the saved settings are read, so the first layout already uses them. */
    val prefs: StateFlow<ReaderPrefs?> = settings.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            try {
                val book = library.book(uri) ?: error("Книга не найдена в библиотеке")
                val format = BookFormat.valueOf(book.format)
                val bytes = library.readBytes(uri)
                val document = withContext(Dispatchers.Default) { Documents.parse(bytes, format, book.title) }
                val saved = Position.parse(book.position)
                    ?.takeIf { it.chapter in document.chapters.indices }
                    ?: Position.START
                _location.value = Location(saved, jump = 1)
                _state.value = ReaderState.Ready(document, book.language)
                library.saveProgress(uri, saved.serialize(), document.progressOf(saved))
            } catch (e: Exception) {
                _state.value = ReaderState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private val document: Document? get() = (state.value as? ReaderState.Ready)?.document

    /** The pager settled on a page that starts at [position]. */
    fun onPageShown(position: Position) {
        if (position == _location.value.position) return
        _location.value = _location.value.copy(position = position, toChapterEnd = false)
        val doc = document ?: return
        viewModelScope.launch { library.saveProgress(uri, position.serialize(), doc.progressOf(position)) }
    }

    fun goTo(position: Position, toChapterEnd: Boolean = false) {
        _location.value = Location(position, toChapterEnd, _location.value.jump + 1)
    }

    fun nextChapter() {
        val doc = document ?: return
        val chapter = _location.value.position.chapter
        if (chapter < doc.chapters.lastIndex) goTo(Position(chapter + 1, 0))
    }

    fun previousChapter(toEnd: Boolean) {
        val chapter = _location.value.position.chapter
        if (chapter > 0) goTo(Position(chapter - 1, 0), toChapterEnd = toEnd)
    }

    fun seek(fraction: Float) {
        val doc = document ?: return
        goTo(doc.positionAt(fraction))
    }

    /** Follows an internal link, remembering where we came from. */
    fun followLink(target: String): Boolean {
        val position = document?.anchors?.get(target) ?: return false
        _history.value = _history.value + _location.value.position
        goTo(position)
        return true
    }

    fun goBack() {
        val last = _history.value.lastOrNull() ?: return
        _history.value = _history.value.dropLast(1)
        goTo(last)
    }

    fun updatePrefs(transform: (ReaderPrefs) -> ReaderPrefs) {
        val current = prefs.value ?: return
        viewModelScope.launch { settings.save(transform(current)) }
    }

    companion object {
        fun factory(uri: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KoreshokApp
                ReaderViewModel(uri, app.library, app.readerSettings)
            }
        }
    }
}
