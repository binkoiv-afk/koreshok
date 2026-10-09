package app.koreshok.core.format

import app.koreshok.core.model.Author
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Fb2MetadataParserTest {

    private fun fb2(encoding: String) = """
        <?xml version="1.0" encoding="$encoding"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
          <description>
            <title-info>
              <genre>sf_fantasy</genre>
              <genre>adventure</genre>
              <author><first-name>Ник</first-name><last-name>Перумов</last-name></author>
              <book-title>Кольцо Тьмы</book-title>
              <annotation><p>Первая   книга.</p></annotation>
              <date value="1993-01-01">1993</date>
              <coverpage><image l:href="#cover.jpg"/></coverpage>
              <lang>ru</lang>
              <sequence name="Кольцо Тьмы" number="1"/>
            </title-info>
            <publish-info><publisher>Северо-Запад</publisher><year>1995</year></publish-info>
          </description>
          <body><section><p>Текст</p></section></body>
          <binary id="cover.jpg" content-type="image/jpeg">AQID</binary>
        </FictionBook>
    """.trimIndent()

    @Test
    fun readsUtf8Fb2() {
        val meta = Fb2MetadataParser.parse(fb2("UTF-8").toByteArray(), "file")

        assertEquals("Кольцо Тьмы", meta.title)
        assertEquals(listOf(Author(firstName = "Ник", lastName = "Перумов")), meta.authors)
        assertEquals("Кольцо Тьмы", meta.series)
        assertEquals(1f, meta.seriesIndex)
        assertEquals(listOf("sf_fantasy", "adventure"), meta.genres)
        assertEquals("ru", meta.language)
        assertEquals(1995, meta.year)
        assertEquals("Северо-Запад", meta.publisher)
        assertEquals("Первая книга.", meta.description)
        assertArrayEquals(byteArrayOf(1, 2, 3), meta.cover)
    }

    @Test
    fun readsWindows1251Fb2() {
        val bytes = fb2("windows-1251").toByteArray(Charset.forName("windows-1251"))
        assertEquals("Кольцо Тьмы", Fb2MetadataParser.parse(bytes, "file").title)
    }

    @Test
    fun readsZippedFb2() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("book.fb2"))
            zip.write(fb2("UTF-8").toByteArray())
            zip.closeEntry()
        }
        assertEquals("Перумов Ник", Fb2MetadataParser.parseZipped(out.toByteArray(), "file").authors.single().sortName)
    }

    @Test
    fun genreCodesHaveRussianNames() {
        assertEquals("Фэнтези", Fb2Genres.displayName("sf_fantasy"))
        assertEquals("Science Fiction", Fb2Genres.displayName("Science Fiction"))
    }
}
