package app.koreshok.core.format

import app.koreshok.core.model.Author
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubMetadataParserTest {

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val container = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent().toByteArray()

    @Test
    fun readsEpub2CalibreMetadataAndCover() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                <dc:title>Пикник на обочине</dc:title>
                <dc:creator opf:role="aut" opf:file-as="Стругацкий, Аркадий Натанович">Аркадий Стругацкий</dc:creator>
                <dc:creator opf:role="aut">Борис Стругацкий</dc:creator>
                <dc:creator opf:role="ill">Художник Иванов</dc:creator>
                <dc:language>ru</dc:language>
                <dc:date>1972-01-01T00:00:00+00:00</dc:date>
                <dc:subject>sf_social</dc:subject>
                <meta name="calibre:series" content="Мир Полудня"/>
                <meta name="calibre:series_index" content="3.0"/>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                <item id="cover-img" href="../Images/cover%201.jpg" media-type="image/jpeg"/>
              </manifest>
            </package>
        """.trimIndent().toByteArray()
        val cover = byteArrayOf(1, 2, 3)

        val meta = EpubMetadataParser.parse(
            zip("META-INF/container.xml" to container, "OEBPS/content.opf" to opf, "Images/cover 1.jpg" to cover),
            fallbackTitle = "file",
        )

        assertEquals("Пикник на обочине", meta.title)
        assertEquals(
            listOf(
                Author(firstName = "Аркадий", middleName = "Натанович", lastName = "Стругацкий"),
                Author(firstName = "Борис", lastName = "Стругацкий"),
            ),
            meta.authors,
        )
        assertEquals("Мир Полудня", meta.series)
        assertEquals(3f, meta.seriesIndex)
        assertEquals(listOf("sf_social"), meta.genres)
        assertEquals("ru", meta.language)
        assertEquals(1972, meta.year)
        assertArrayEquals(cover, meta.cover)
    }

    @Test
    fun readsEpub3CollectionAndCoverImageProperty() {
        val opf = """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Dune Messiah</dc:title>
                <dc:creator id="c1">Frank Herbert</dc:creator>
                <meta refines="#c1" property="role">aut</meta>
                <meta property="belongs-to-collection" id="col">Dune</meta>
                <meta refines="#col" property="group-position">2</meta>
              </metadata>
              <manifest><item id="x" href="img/c.png" properties="cover-image" media-type="image/png"/></manifest>
            </package>
        """.trimIndent().toByteArray()

        val meta = EpubMetadataParser.parse(
            zip("META-INF/container.xml" to container, "OEBPS/content.opf" to opf, "OEBPS/img/c.png" to byteArrayOf(9)),
            fallbackTitle = "file",
        )

        assertEquals("Dune Messiah", meta.title)
        assertEquals("Herbert Frank", meta.authors.single().sortName)
        assertEquals("Dune", meta.series)
        assertEquals(2f, meta.seriesIndex)
        assertArrayEquals(byteArrayOf(9), meta.cover)
    }

    @Test
    fun brokenArchiveFallsBackToFileName() {
        val meta = EpubMetadataParser.parse(zip("readme.txt" to byteArrayOf()), fallbackTitle = "Моя книга")
        assertEquals("Моя книга", meta.title)
        assertNull(meta.cover)
    }
}
