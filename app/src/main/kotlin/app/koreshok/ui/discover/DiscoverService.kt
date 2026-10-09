package app.koreshok.ui.discover

import app.koreshok.core.discover.Discover
import app.koreshok.core.discover.TasteGenre
import app.koreshok.core.opds.OpdsEntry
import app.koreshok.data.BookEntity
import app.koreshok.data.FavoriteAuthor
import app.koreshok.data.LibraryRepository
import app.koreshok.data.TastePrefs
import app.koreshok.data.TasteSettings
import app.koreshok.ui.catalog.CatalogService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Calendar

sealed interface RowState {
    data object Loading : RowState
    data class Loaded(val books: List<OpdsEntry>) : RowState
    data class Failed(val message: String) : RowState
}

/** One shelf of suggestions on the Обзор tab. */
data class DiscoverRow(val key: String, val title: String, val subtitle: String, val state: RowState)

/** A recent book by an author the reader follows. */
data class FreshBook(val entry: OpdsEntry, val author: String, val isNew: Boolean)

sealed interface FreshState {
    data object Off : FreshState
    data object Loading : FreshState
    data class Loaded(val books: List<FreshBook>, val missing: List<String>) : FreshState
}

/**
 * Suggestions from the catalogs: popular books in the genres the reader likes, the week's new
 * books in them, and new books by favourite authors. Kept for the whole app session, so the
 * Обзор tab opens instantly after the first load.
 */
class DiscoverService(
    private val catalogs: CatalogService,
    private val taste: TasteSettings,
    private val library: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val requests = Semaphore(4)

    private val _rows = MutableStateFlow<List<DiscoverRow>>(emptyList())
    val rows: StateFlow<List<DiscoverRow>> = _rows.asStateFlow()

    private val _fresh = MutableStateFlow<FreshState>(FreshState.Off)
    val fresh: StateFlow<FreshState> = _fresh.asStateFlow()

    val prefs: StateFlow<TastePrefs?> = taste.prefs.stateIn(scope, SharingStarted.Eagerly, null)

    /** New books by favourite authors the reader has not seen yet: the badge on the tab. */
    val news: StateFlow<Int> = combine(_fresh, taste.prefs) { fresh, prefs ->
        (fresh as? FreshState.Loaded)?.books?.count { it.entry.id !in prefs.seen } ?: 0
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    private var loadedFor: Triple<Set<String>, List<String>, Boolean>? = null
    private var job: Job? = null

    /** Loads suggestions unless they are already there for the same tastes. */
    fun refresh(force: Boolean = false) {
        scope.launch {
            val prefs = taste.prefs.first()
            val key = Triple(prefs.genres, prefs.authors.map { it.name }, prefs.followNew)
            val failed = _rows.value.any { it.state is RowState.Failed }
            if (!force && key == loadedFor && !failed) return@launch
            loadedFor = key
            job?.cancel()
            job = scope.launch { load(prefs) }
        }
    }

    private suspend fun load(prefs: TastePrefs) = coroutineScope {
        val shelf = shelfTitles(library.books.first())
        val genres = prefs.genres.mapNotNull(Discover::genre).ifEmpty { DEFAULT_GENRES.mapNotNull(Discover::genre) }

        val rows = buildList {
            add(DiscoverRow("new", "Новинки недели", newSubtitle(prefs, genres), RowState.Loading))
            genres.forEach { add(DiscoverRow(it.key, it.label, "Популярное во Флибусте", RowState.Loading)) }
        }
        _rows.value = rows

        if (prefs.followNew && prefs.authors.isNotEmpty()) {
            _fresh.value = FreshState.Loading
            launch { _fresh.value = loadFresh(prefs.authors, shelf) }
        } else {
            _fresh.value = FreshState.Off
        }

        launch {
            setRow("new") {
                val lists = genres.take(4).map { genre -> async { fetch(Discover.newUrl(genre)) } }.awaitAll()
                interleave(lists).filterNot { onShelf(it, shelf) }.distinctBy { Discover.normalizeTitle(it.title) }.take(18)
            }
        }
        genres.forEach { genre ->
            launch { setRow(genre.key) { popular(genre).filterNot { onShelf(it, shelf) }.take(18) } }
        }
    }

    private fun newSubtitle(prefs: TastePrefs, genres: List<TasteGenre>) =
        if (prefs.genres.isEmpty()) "Только что во Флибусте" else genres.take(4).joinToString(", ") { it.label.lowercase() }

    /** The popular list of a big genre can take Флибуста a while; the week's new books are a fallback. */
    private suspend fun popular(genre: TasteGenre): List<OpdsEntry> =
        runCatching { fetch(Discover.popularUrl(genre)) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: fetch(Discover.newUrl(genre))

    private suspend fun fetch(url: String): List<OpdsEntry> =
        requests.withPermit { catalogs.fetchFeed(url).entries.filter { it.isBook } }

    private suspend fun setRow(key: String, load: suspend () -> List<OpdsEntry>) {
        val state = try {
            RowState.Loaded(load())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            RowState.Failed(e.message ?: "нет связи")
        }
        _rows.update { rows -> rows.map { if (it.key == key) it.copy(state = state) else it } }
    }

    private suspend fun loadFresh(authors: List<FavoriteAuthor>, shelf: Set<String>): FreshState = coroutineScope {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val results = authors.map { author ->
            async {
                runCatching {
                    val url = author.url ?: findAuthor(author.name)?.also { taste.resolve(author, it) } ?: return@runCatching null
                    Discover.freshBooks(author.name, fetch(Discover.authorLatestUrl(url)), year) { onShelf(it, shelf) }
                        .take(4)
                        .map { FreshBook(it, author.name, isNew = true) }
                }.getOrNull().let { author to it }
            }
        }.awaitAll()
        val seen = taste.prefs.first().seen
        FreshState.Loaded(
            books = results.flatMap { it.second.orEmpty() }
                .map { it.copy(isNew = it.entry.id !in seen) }
                .sortedWith(compareByDescending<FreshBook> { it.isNew }.thenByDescending { it.entry.year ?: 0 }),
            missing = results.filter { it.second == null }.map { it.first.name },
        )
    }

    /** Finds an author's page in Флибуста; null when the name matches no one. */
    suspend fun findAuthor(name: String): String? {
        for (term in Discover.authorSearchTerms(name)) {
            val feed = requests.withPermit { catalogs.fetchFeed(Discover.authorSearchUrl(term)) }
            Discover.bestAuthor(name, feed.entries)?.navigationUrl?.let { return it }
        }
        return null
    }

    fun markFreshSeen() {
        val books = (_fresh.value as? FreshState.Loaded)?.books ?: return
        if (books.isEmpty()) return
        scope.launch { taste.markSeen(books.map { it.entry.id }) }
    }

    /** A random book from the suggestions, or from a random genre when nothing is loaded yet. */
    suspend fun randomBook(): OpdsEntry? {
        val shelf = shelfTitles(library.books.first())
        val loaded = _rows.value.flatMap { (it.state as? RowState.Loaded)?.books.orEmpty() }.filterNot { onShelf(it, shelf) }
        if (loaded.isNotEmpty()) return loaded.random()
        val genres = prefs.value?.genres?.mapNotNull(Discover::genre)?.ifEmpty { null } ?: Discover.genres
        return runCatching { popular(genres.random()).filterNot { onShelf(it, shelf) }.randomOrNull() }.getOrNull()
    }

    private fun shelfTitles(books: List<BookEntity>) = books.map { Discover.normalizeTitle(it.title) }.toSet()

    private fun onShelf(entry: OpdsEntry, shelf: Set<String>) = Discover.normalizeTitle(entry.title) in shelf

    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val result = mutableListOf<T>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) lists.forEach { list -> list.getOrNull(i)?.let(result::add) }
        return result
    }

    private companion object {
        /** Before the questionnaire, something most people like. */
        val DEFAULT_GENRES = listOf("modern", "detective", "fantasy", "sf")
    }
}
