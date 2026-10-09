package app.koreshok.core.document

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubDocumentParserTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val epub = zip(
        "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""",
        "OEBPS/book.opf" to """
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test Book</dc:title></metadata>
              <manifest>
                <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="img" href="Images/a.png" media-type="image/png"/>
              </manifest>
              <spine toc="ncx"><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>
        """.trimIndent(),
        "OEBPS/toc.ncx" to """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
              <navPoint><navLabel><text>One</text></navLabel><content src="Text/ch1.xhtml"/>
                <navPoint><navLabel><text>Notes part</text></navLabel><content src="Text/ch2.xhtml#notes"/></navPoint>
              </navPoint>
            </navMap></ncx>
        """.trimIndent(),
        "OEBPS/Text/ch1.xhtml" to """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title><style>p{}</style></head><body>
              <h1>Chapter&nbsp;One</h1>
              <p>Hello <i>world</i>!<sup><a href="ch2.xhtml#fn1">1</a></sup></p>
              <div><img src="../Images/a.png" alt="pic"/></div>
              <ol><li><p>First</p></li><li>Second</li></ol>
            </body></html>
        """.trimIndent(),
        "OEBPS/Text/ch2.xhtml" to """
            <html><body>
              <h2 id="notes">Notes</h2>
              <p id="fn1">The footnote.</p>
              <p id="fn2">Another.</p>
            </body></html>
        """.trimIndent(),
        "OEBPS/Images/a.png" to "PNG",
    )

    private val doc = EpubDocumentParser.parse(epub, "file")

    @Test
    fun readsSpineIntoChapters() {
        assertEquals("Test Book", doc.title)
        assertEquals(listOf("Chapter One", "Notes"), doc.chapters.map { it.title })
        val texts = doc.chapters[0].blocks.map { (it as? TextBlock)?.text ?: it::class.simpleName }
        assertEquals(listOf("Chapter One", "Hello world!1", "ImageBlock", "1. First", "2. Second"), texts)
        assertEquals(setOf("OEBPS/Images/a.png"), doc.images.keys)
    }

    @Test
    fun resolvesTocAndFootnotes() {
        assertEquals(
            listOf(TocEntry("One", 1, Position(0, 0)), TocEntry("Notes part", 2, Position(1, 0))),
            doc.toc,
        )
        val paragraph = doc.chapters[0].blocks[1] as TextBlock
        val link = paragraph.spans.map { it.style }.filterIsInstance<SpanStyle.Link>().single()
        assertEquals(SpanStyle.Link("OEBPS/Text/ch2.xhtml#fn1", isNote = true), link)
        assertEquals(listOf("The footnote."), doc.noteBlocks(link.target).map { it.text })
    }
}
