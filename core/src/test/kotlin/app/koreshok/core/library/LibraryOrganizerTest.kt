package app.koreshok.core.library

import app.koreshok.core.model.Author
import app.koreshok.core.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrganizerTest {

    private data class Item(
        override val title: String,
        override val authorSortName: String = "",
        override val series: String? = null,
        override val seriesIndex: Float? = null,
        override val genres: List<String> = emptyList(),
        override val language: String? = null,
        override val year: Int? = null,
        override val format: String = "EPUB",
        override val addedAt: Long = 0,
        override val lastOpenedAt: Long? = null,
        override val progress: Float = 0f,
    ) : ShelfItem

    @Test
    fun sortsByAuthorThenSeriesNumber() {
        val books = listOf(
            Item("Дюна 3", "Херберт Фрэнк", "Дюна", 3f),
            Item("Ёлка", "Абрамов Фёдор"),
            Item("Дюна 1", "Херберт Фрэнк", "Дюна", 1f),
            Item("Дети Дюны", "Херберт Фрэнк", "Дюна", 2f),
        )
        assertEquals(
            listOf("Ёлка", "Дюна 1", "Дети Дюны", "Дюна 3"),
            LibraryOrganizer.sort(books, SortOrder.AUTHOR).map { it.title },
        )
    }

    @Test
    fun yoSortsAsYe() {
        val books = listOf(Item("Жук"), Item("Ёж"), Item("Еда"))
        assertEquals(listOf("Еда", "Ёж", "Жук"), LibraryOrganizer.sort(books, SortOrder.TITLE).map { it.title })
    }

    @Test
    fun groupsByGenreWithBookUnderEachGenreAndUnknownLast() {
        val books = listOf(
            Item("A", genres = listOf("sf_fantasy", "adventure")),
            Item("B"),
            Item("C", genres = listOf("adventure")),
        )
        val names = mapOf("sf_fantasy" to "Фэнтези", "adventure" to "Приключения")
        val groups = LibraryOrganizer.group(books, GroupBy.GENRE) { names[it] ?: it }
        assertEquals(listOf("Приключения", "Фэнтези", LibraryOrganizer.NO_VALUE), groups.map { it.title })
        assertEquals(listOf("A", "C"), groups[0].items.map { it.title })
    }

    @Test
    fun groupsByStatusInReadingOrder() {
        val books = listOf(Item("done", progress = 1f), Item("new"), Item("half", progress = 0.5f))
        assertEquals(
            listOf("Не начатые", "Читаю", "Прочитанные"),
            LibraryOrganizer.group(books, GroupBy.STATUS).map { it.title },
        )
    }

    @Test
    fun searchIgnoresCaseAndYo() {
        val books = listOf(Item("Мёртвые души", "Гоголь Николай"), Item("Война и мир", "Толстой Лев"))
        assertEquals(listOf("Мёртвые души"), LibraryOrganizer.filter(books, "МЕРТВЫЕ").map { it.title })
        assertEquals(listOf("Война и мир"), LibraryOrganizer.filter(books, "толст").map { it.title })
    }

    @Test
    fun detectsFormatsAndParsesAuthors() {
        assertEquals(BookFormat.FB2_ZIP, BookFormat.fromFileName("Книга.FB2.zip"))
        assertEquals(BookFormat.MOBI, BookFormat.fromFileName("x.azw3"))
        assertEquals(null, BookFormat.fromFileName("photo.jpg"))
        assertEquals("Толстой Лев Николаевич", Author.parse("Лев Николаевич Толстой").sortName)
        assertEquals("Толстой Лев", Author.parse("Толстой, Лев").sortName)
    }
}
