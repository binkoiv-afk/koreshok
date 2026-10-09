package app.koreshok.core.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsParserTest {

    private val feed = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
          <title>Книги по жанру</title>
          <link href="/opds/search?searchTerms={searchTerms}" rel="search" type="application/atom+xml"/>
          <link href="/opds/genre/12/1" rel="next" type="application/atom+xml;profile=opds-catalog"/>
          <entry>
            <id>tag:root:authors</id>
            <title>По авторам</title>
            <link href="/opds/authorsindex" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
            <content type="text">Авторы по алфавиту</content>
          </entry>
          <entry>
            <id>tag:book:42</id>
            <title>Пикник на обочине</title>
            <author><name>Стругацкий Аркадий</name></author>
            <author><name>Стругацкий Борис</name></author>
            <content type="text/html">Повесть &lt;br/&gt;о Зоне</content>
            <link href="/b/42/fb2" rel="http://opds-spec.org/acquisition/open-access" type="application/fb2+zip"/>
            <link href="/b/42/epub" rel="http://opds-spec.org/acquisition/open-access" type="application/epub+zip"/>
            <link href="/b/42/mobi" rel="http://opds-spec.org/acquisition/open-access" type="application/x-mobipocket-ebook"/>
            <link href="/i/42/cover.jpg" rel="http://opds-spec.org/image" type="image/jpeg"/>
            <link href="/a/7" rel="related" type="application/atom+xml" title="Все книги автора"/>
          </entry>
        </feed>
    """.trimIndent().toByteArray()

    private val parsed = OpdsParser.parse(feed, "http://flibusta.is/opds/genre/12")

    @Test
    fun readsNavigationAndBooks() {
        assertEquals("Книги по жанру", parsed.title)
        val folder = parsed.entries[0]
        assertEquals("http://flibusta.is/opds/authorsindex", folder.navigationUrl)
        assertTrue(!folder.isBook)

        val book = parsed.entries[1]
        assertTrue(book.isBook)
        assertNull(book.navigationUrl)
        assertEquals(listOf("Стругацкий Аркадий", "Стругацкий Борис"), book.authors)
        assertEquals(listOf("EPUB", "FB2", "MOBI"), book.downloads.map { it.label })
        assertEquals("http://flibusta.is/b/42/fb2", book.downloads[1].url)
        assertEquals("fb2.zip", book.downloads[1].extension)
        assertEquals("http://flibusta.is/i/42/cover.jpg", book.coverUrl)
        assertEquals("Повесть\nо Зоне", book.summary)
    }

    @Test
    fun readsPagingAndSearch() {
        assertEquals("http://flibusta.is/opds/genre/12/1", parsed.nextUrl)
        val template = parsed.searchTemplate!!
        assertEquals("http://flibusta.is/opds/search?searchTerms=%D0%94%D1%8E%D0%BD%D0%B0", OpdsParser.searchUrl(template, "Дюна"))
    }

    @Test
    fun readsOpenSearchDescription() {
        val description = """
            <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
              <Url type="text/html" template="https://example.org/search?q={searchTerms}"/>
              <Url type="application/atom+xml" template="/opds/search/{searchTerms}/{startPage?}"/>
            </OpenSearchDescription>
        """.trimIndent().toByteArray()
        val template = OpdsParser.searchTemplate(description, "https://example.org/opds/")!!
        assertEquals("https://example.org/opds/search/war+and+peace/", OpdsParser.searchUrl(template, "war and peace"))
    }
}
