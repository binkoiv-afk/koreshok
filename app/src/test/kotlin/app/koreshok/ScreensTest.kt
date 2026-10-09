package app.koreshok

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.koreshok.core.library.GroupBy
import app.koreshok.core.library.ShelfGroup
import app.koreshok.core.opds.OpdsDownload
import app.koreshok.core.opds.OpdsEntry
import app.koreshok.data.BookEntity
import app.koreshok.data.FolderEntity
import app.koreshok.data.ShelfPrefs
import app.koreshok.ui.catalog.CatalogLink
import app.koreshok.ui.catalog.CatalogList
import app.koreshok.ui.catalog.CatalogResults
import app.koreshok.ui.home.MoreContent
import app.koreshok.ui.library.BookDetails
import app.koreshok.ui.library.LibraryState
import app.koreshok.ui.library.ShelfActions
import app.koreshok.ui.library.ShelfContent
import app.koreshok.ui.library.UpdateState
import app.koreshok.ui.search.CatalogSearch
import app.koreshok.ui.search.SearchContent
import app.koreshok.ui.search.SearchSection
import app.koreshok.ui.theme.KoreshokTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import app.koreshok.sync.SyncAccount
import app.koreshok.sync.SyncCard
import app.koreshok.sync.SyncPrefs
import app.koreshok.sync.SyncSetup
import app.koreshok.sync.SyncStatus
import app.koreshok.core.document.BlockKind
import app.koreshok.core.document.Chapter
import app.koreshok.core.document.Document
import app.koreshok.core.document.Position
import app.koreshok.core.document.TextBlock
import app.koreshok.data.FavoriteAuthor
import app.koreshok.data.TastePrefs
import app.koreshok.ui.discover.DiscoverActions
import app.koreshok.ui.discover.DiscoverRow
import app.koreshok.ui.discover.DiscoverUi
import app.koreshok.ui.discover.FreshBook
import app.koreshok.ui.discover.FreshState
import app.koreshok.ui.discover.RowState
import app.koreshok.ui.discover.ShelfRandom
import app.koreshok.ui.discover.ShelfTaste
import app.koreshok.ui.discover.TasteQuiz
import app.koreshok.ui.discover.discoverItems
import app.koreshok.ui.reader.LayoutSpec
import app.koreshok.ui.reader.Location
import app.koreshok.ui.reader.ReaderPrefs
import app.koreshok.ui.reader.ReaderTheme
import app.koreshok.ui.reader.ScrollReader
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

/**
 * Renders the main screens with sample books. CI publishes the pictures, which is how the
 * design gets reviewed without a phone at hand.
 */
class ScreensTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 100.0)

    private fun snap(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        if (dark) paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(nightMode = NightMode.NIGHT))
        paparazzi.snapshot(name) {
            KoreshokTheme(dark = dark) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    @Test fun shelf() = snap("shelf") { ShelfContent(shelfState(), UpdateState.None, ShelfActions()) }

    @Test fun shelfDark() = snap("shelf_dark", dark = true) { ShelfContent(shelfState(), UpdateState.None, ShelfActions()) }

    @Test fun shelfList() = snap("shelf_list") {
        ShelfContent(shelfState().copy(prefs = ShelfPrefs(list = true, groupBy = GroupBy.AUTHOR)), UpdateState.None, ShelfActions())
    }

    @Test fun shelfEmpty() = snap("shelf_empty") { ShelfContent(LibraryState(loaded = true), UpdateState.None, ShelfActions()) }

    @Test fun details() = snap("details") { BookDetails(books[0]) {} }

    @Test fun search() = snap("search") {
        val found = CatalogResults(
            books = listOf(
                opds("Мастер и Маргарита", "Михаил Булгаков", "Роман о дьяволе, посетившем Москву 1930-х, и о любви мастера."),
                opds("Собачье сердце", "Михаил Булгаков", "Повесть о профессоре Преображенском и его опыте."),
            ),
            folders = listOf(OpdsEntry("a", "Булгаков Михаил Афанасьевич", emptyList(), null, null, "x", emptyList())),
            moreUrl = "x",
            feedUrl = "x",
        )
        SearchContent(
            query = "булгаков",
            submitted = "булгаков",
            catalogs = catalogs,
            disabled = setOf(catalogs[4].url),
            sections = listOf(
                SearchSection(catalogs[0], CatalogSearch.Found(found)),
                SearchSection(catalogs[1], CatalogSearch.Loading),
                SearchSection(catalogs[2], CatalogSearch.Failed("Сервер ответил 503")),
            ),
            local = books.take(2),
            downloads = emptyMap(),
            onQuery = {}, onSubmit = {}, onToggleCatalog = {}, onOpenLocal = {}, onDownload = { _, _ -> }, onRead = {}, onOpenFeed = { _, _ -> },
        )
    }

    @Test fun searchEmpty() = snap("search_empty") {
        SearchContent("", "", catalogs, emptySet(), emptyList(), emptyList(), emptyMap(), {}, {}, {}, {}, { _, _ -> }, {}, { _, _ -> })
    }

    @Test fun readerSettings() = snap("reader_settings") {
        app.koreshok.ui.reader.ReaderSettingsPanel(app.koreshok.ui.reader.ReaderPrefs()) {}
    }

    @Test fun discover() = snap("discover") { discoverPage() }

    @Test fun discoverDark() = snap("discover_dark", dark = true) { discoverPage() }

    @Test fun quizGenres() = snap("quiz_genres") {
        TasteQuiz(TastePrefs(), ShelfTaste(listOf("Виктор Пелевин"), setOf("fantasy", "modern", "sf")), { _, _, _ -> })
    }

    @Test fun quizAuthors() = snap("quiz_authors") {
        TasteQuiz(
            TastePrefs(authors = listOf(FavoriteAuthor("Виктор Пелевин"), FavoriteAuthor("Людмила Улицкая"))),
            ShelfTaste(listOf("Михаил Булгаков", "Станислав Лем", "Фрэнк Герберт", "Лев Толстой"), emptySet()),
            { _, _, _ -> },
            startStep = 1,
        )
    }

    @Test fun shelfRandom() = snap("shelf_random") { ShelfRandom(books[0], {}, {}) }

    @Test fun scrollReader() = snap("reader_scroll") { scrollPage(ReaderPrefs(scroll = true)) }

    @Test fun scrollReaderSepia() = snap("reader_scroll_sepia") { scrollPage(ReaderPrefs(scroll = true, theme = ReaderTheme.SEPIA)) }

    @Composable
    private fun scrollPage(prefs: ReaderPrefs) {
        val density = 2.625f
        val margin = (prefs.margin * density).toInt()
        val paragraphs = listOf(
            "В час жаркого весеннего заката на Патриарших прудах появилось двое граждан. Первый из них, одетый в летнюю серенькую пару, был маленького роста, упитан, лыс, свою приличную шляпу пирожком нес в руке, а на хорошо выбритом лице его помещались сверхъестественных размеров очки в черной роговой оправе.",
            "Второй — плечистый, рыжеватый, вихрастый молодой человек в заломленной на затылок клетчатой кепке — был в ковбойке, жеваных белых брюках и в черных тапочках.",
            "Первый был не кто иной, как Михаил Александрович Берлиоз, председатель правления одной из крупнейших московских литературных ассоциаций, сокращенно именуемой МАССОЛИТ, и редактор толстого художественного журнала, а молодой спутник его — поэт Иван Николаевич Понырев, пишущий под псевдонимом Бездомный.",
            "Попав в тень чуть зеленеющих лип, писатели первым долгом бросились к пестро раскрашенной будочке с надписью «Пиво и воды».",
        )
        val document = Document(
            title = "Мастер и Маргарита",
            chapters = listOf(
                Chapter("Глава 1. Никогда не разговаривайте с неизвестными", listOf(TextBlock(BlockKind.HEADING, "Глава 1. Никогда не разговаривайте с неизвестными", level = 1)) + (paragraphs + paragraphs).map { TextBlock(BlockKind.PARAGRAPH, it) }),
            ),
            toc = emptyList(),
            anchors = emptyMap(),
            images = emptyMap(),
        )
        val layout = LayoutSpec(widthPx = 1080 - margin * 2, heightPx = 2200, density = density, prefs = prefs, language = "ru")
        Box(Modifier.fillMaxSize().background(prefs.theme.background)) {
            ScrollReader(
                document = document,
                location = Location(Position(0, 0)),
                layout = layout,
                marginPx = margin,
                annotations = emptyList(),
                onPositionShown = {},
                onToggleBookmark = { _, _ -> },
                onLink = { _, _ -> },
                onCenterTap = {},
                onHighlight = {},
            )
        }
    }

    @Composable
    private fun discoverPage() {
        val popular = listOf(
            opds("Шантарам", "Робертс Грегори Дэвид", "Роман о беглеце в Бомбее."),
            opds("Алхимик", "Коэльо Пауло", "Притча о пастухе и его мечте."),
            opds("Маленькая жизнь", "Янагихара Ханья", "О дружбе длиной в жизнь."),
            opds("Щегол", "Тартт Донна", "Мальчик, картина и потеря."),
        )
        val fantasy = listOf(
            opds("Имя ветра", "Ротфусс Патрик", ""),
            opds("Ведьмак. Последнее желание", "Сапковский Анджей", ""),
            opds("Американские боги", "Гейман Нил", ""),
            opds("Цвет волшебства", "Пратчетт Терри", ""),
        )
        SearchContent(
            "", "", catalogs, emptySet(), emptyList(), emptyList(), emptyMap(), {}, {}, {}, {}, { _, _ -> }, {}, { _, _ -> },
            discover = {
                discoverItems(
                    DiscoverUi(
                        prefs = TastePrefs(asked = false),
                        rows = listOf(
                            DiscoverRow("new", "Новинки недели", "современная проза, фэнтези", RowState.Loading),
                            DiscoverRow("modern", "Современная проза", "Популярное во Флибусте", RowState.Loaded(popular)),
                            DiscoverRow("fantasy", "Фэнтези", "Популярное во Флибусте", RowState.Loaded(fantasy)),
                        ),
                        fresh = FreshState.Loaded(
                            listOf(
                                FreshBook(opds("Возвращение Синей Бороды", "Пелевин Виктор Олегович", "").copy(issued = "2026"), "Виктор Пелевин", isNew = true),
                                FreshBook(opds("Круть", "Пелевин Виктор Олегович", "").copy(issued = "2025"), "Виктор Пелевин", isNew = false),
                                FreshBook(opds("Сад", "Степнова Марина Львовна", "").copy(issued = "2025"), "Марина Степнова", isNew = false),
                            ),
                            missing = emptyList(),
                        ),
                    ),
                    DiscoverActions(),
                )
            },
        )
    }

    @Test fun sync() = snap("sync") {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(20.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            SyncCard(SyncPrefs(null, null), SyncStatus.Idle, {}, {}, {})
            SyncCard(SyncPrefs(SyncAccount(SyncAccount.YANDEX, "igor", "x"), 0), SyncStatus.Done(0, 3), {}, {}, {})
            SyncSetup({ null }, {})
        }
    }

    @Test fun libraries() = snap("libraries") { CatalogList(catalogs, {}, {}, {}) }

    @Test fun more() = snap("more") {
        MoreContent(
            folders = listOf(FolderEntity("f", "Книги", 0), FolderEntity("g", "Download", 0)),
            totalBooks = 128,
            scan = null,
            build = 12,
            update = UpdateState.None,
            checkMessage = null,
            onAddFolder = {}, onRemoveFolder = {}, onRescan = {}, onCheckUpdate = {}, onUpdate = {},
        )
    }

    private val catalogs = app.koreshok.core.opds.OpdsPresets.all.map { CatalogLink(null, it.title, it.url) }

    private fun opds(title: String, author: String, summary: String) = OpdsEntry(
        id = title,
        title = title,
        authors = listOf(author),
        summary = summary,
        coverUrl = null,
        navigationUrl = null,
        downloads = listOf(
            OpdsDownload("https://x/$title/fb2", "application/fb2+zip"),
            OpdsDownload("https://x/$title/epub", "application/epub+zip"),
            OpdsDownload("https://x/$title/mobi", "application/x-mobipocket-ebook"),
        ),
    )

    private fun shelfState(): LibraryState {
        val reading = books.filter { it.progress > 0f && it.progress < 0.99f }.sortedByDescending { it.lastOpenedAt }
        return LibraryState(
            groups = listOf(ShelfGroup("", books)),
            totalBooks = books.size,
            reading = reading,
            statusCounts = mapOf(
                app.koreshok.core.library.ReadingStatus.READING to reading.size,
                app.koreshok.core.library.ReadingStatus.NEW to 5,
                app.koreshok.core.library.ReadingStatus.FINISHED to 1,
            ),
            loaded = true,
        )
    }

    private val books = listOf(
        book("Мастер и Маргарита", "Михаил Булгаков", 0.34f, 10, series = null, description = "Роман о дьяволе, посетившем Москву 1930-х годов, о Мастере и его возлюбленной."),
        book("Дюна", "Фрэнк Герберт", 0.71f, 9, series = "Хроники Дюны", index = 1f),
        book("Пикник на обочине", "Аркадий и Борис Стругацкие", 0.12f, 8),
        book("Война и мир. Том первый", "Лев Толстой", 0f, null),
        book("Преступление и наказание", "Фёдор Достоевский", 1f, 2),
        book("Солярис", "Станислав Лем", 0f, null),
        book("Сто лет одиночества", "Габриэль Гарсиа Маркес", 0f, null),
        book("Шантарам", "Грегори Дэвид Робертс", 0f, null),
        book("Атлант расправил плечи", "Айн Рэнд", 0f, null),
    )

    private fun book(
        title: String,
        author: String,
        progress: Float,
        opened: Long?,
        series: String? = null,
        index: Float? = null,
        description: String? = null,
    ) = BookEntity(
        uri = title,
        folderUri = "f",
        fileName = "$title.fb2",
        sizeBytes = 1,
        modifiedAt = 0,
        format = "FB2",
        title = title,
        authors = author,
        authorSortName = author.substringAfterLast(' '),
        series = series,
        seriesIndex = index,
        genres = listOf("sf", "prose_classic"),
        language = "ru",
        year = 1967,
        publisher = null,
        description = description,
        coverPath = null,
        addedAt = 0,
        lastOpenedAt = opened,
        progress = progress,
    )
}
