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
import app.koreshok.data.AnnotationEntity
import app.koreshok.data.AnnotationKind
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
    data class Ready(val document: Document, val language: String?, val authors: String) : ReaderState
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

    val annotations: StateFlow<List<AnnotationEntity>> =
        library.annotations(uri).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
                _state.value = ReaderState.Ready(document, book.language, book.authors)
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

    /** Adds a bookmark at the page start, or removes the ones already on this page. */
    fun toggleBookmark(pageStart: Position, nextPageStart: Position?) {
        val doc = document ?: return
        val onPage = annotations.value.filter { it.kind == AnnotationKind.BOOKMARK && it.isOnPage(pageStart, nextPageStart) }
        viewModelScope.launch {
            if (onPage.isNotEmpty()) {
                onPage.forEach { library.deleteAnnotation(it.id) }
            } else {
                library.saveAnnotation(
                    AnnotationEntity(
                        bookUri = uri,
                        kind = AnnotationKind.BOOKMARK,
                        chapter = pageStart.chapter,
                        block = pageStart.block,
                        start = pageStart.offset,
                        end = pageStart.offset,
                        text = snippetAt(doc, pageStart),
                        chapterTitle = doc.chapters[pageStart.chapter].title,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    fun saveHighlight(draft: HighlightDraft) {
        val doc = document ?: return
        viewModelScope.launch {
            library.saveAnnotation(
                AnnotationEntity(
                    id = draft.id,
                    bookUri = uri,
                    kind = AnnotationKind.HIGHLIGHT,
                    chapter = draft.chapter,
                    block = draft.block,
                    start = draft.start,
                    end = draft.end,
                    color = draft.color,
                    note = draft.note.takeIf { it.isNotBlank() },
                    text = draft.text,
                    chapterTitle = doc.chapters[draft.chapter].title,
                    createdAt = annotations.value.firstOrNull { it.id == draft.id }?.createdAt ?: System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteAnnotation(id: Long) {
        viewModelScope.launch { library.deleteAnnotation(id) }
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

fun AnnotationEntity.position() = Position(chapter, block, start)

fun AnnotationEntity.isOnPage(pageStart: Position, nextPageStart: Position?): Boolean {
    val at = position()
    return at >= pageStart && (nextPageStart == null || at < nextPageStart) && at.chapter == pageStart.chapter
}
