package app.koreshok.core.discover

import app.koreshok.core.opds.OpdsDownload
import app.koreshok.core.opds.OpdsEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoverTest {
    private fun folder(title: String, books: String, url: String) = OpdsEntry(url, title, emptyList(), books, null, url, emptyList())
    private fun book(title: String, year: String?, vararg authors: String) =
        OpdsEntry(title, title, authors.toList(), null, null, null, listOf(OpdsDownload("http://x/$title", "application/epub+zip")), issued = year)

    private val pelevins = listOf(
        folder("Пелевин Александр Сергеевич", "17 книг", "/opds/author/179656"),
        folder("Пелевин Виктор Олегович", "145 книг", "/opds/author/9450"),
        folder("Пелевина Катерина", "26 книг", "/opds/author/1"),
    )

    @Test
    fun findsAuthorWrittenEitherWay() {
        assertEquals("/opds/author/9450", Discover.bestAuthor("Виктор Пелевин", pelevins)?.navigationUrl)
        assertEquals("/opds/author/9450", Discover.bestAuthor("Пелевин Виктор", pelevins)?.navigationUrl)
        // Surname alone: the one with most books.
        assertEquals("/opds/author/9450", Discover.bestAuthor("пелевин", pelevins)?.navigationUrl)
        assertEquals("/opds/author/179656", Discover.bestAuthor("Александр Пелевин", pelevins)?.navigationUrl)
        assertNull(Discover.bestAuthor("Сорокин", pelevins))
    }

    @Test
    fun searchesBySurnameFromEitherEnd() {
        assertEquals(listOf("пелевин", "виктор"), Discover.authorSearchTerms("Виктор Пелевин"))
        assertEquals(listOf("пелевин"), Discover.authorSearchTerms("Пелевин"))
    }

    @Test
    fun freshBooksSkipOldAnthologiesAndShelf() {
        val feed = listOf(
            book("Малый жанр в новейшей русской прозе", "2026", "Иванов", "Пелевин Виктор", "Сорокин", "Толстая"),
            book("Возвращение Синей Бороды [litres]", "2026", "Пелевин Виктор Олегович"),
            book("Возвращение Синей Бороды", "2026", "Пелевин Виктор Олегович"),
            book("Круть", "2025", "Пелевин Виктор Олегович"),
            book("Омон Ра", "2003", "Пелевин Виктор Олегович"),
        )
        val fresh = Discover.freshBooks("Виктор Пелевин", feed, 2026) { it.title == "Круть" }
        assertEquals(listOf("Возвращение Синей Бороды [litres]"), fresh.map { it.title })
        assertEquals("Возвращение Синей Бороды", Discover.cleanTitle(fresh[0].title))
    }

    @Test
    fun showsAuthorsNameFirst() {
        assertEquals("Виктор Пелевин", Discover.displayAuthor("Пелевин Виктор Олегович"))
        assertEquals("Пелевин Виктор", Discover.displayAuthor("Пелевин Виктор"))
    }

    @Test
    fun guessesGenresFromShelf() {
        val guessed = Discover.guessGenres(listOf("Фэнтези", "Городское фэнтези", "Классический детектив", "Поэзия"))
        assertEquals(setOf("fantasy", "detective", "poetry"), guessed)
    }
}
