package app.koreshok.core.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fb2DocumentParserTest {

    private val fb2 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE FictionBook>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
          <description><title-info><book-title>Сказка</book-title></title-info></description>
          <body>
            <title><p>Сказка</p></title>
            <epigraph><p>Жили-были</p><text-author>Народ</text-author></epigraph>
            <section id="part1">
              <title><p>Часть первая</p></title>
              <section>
                <title><p>Глава 1</p></title>
                <p>Начало <emphasis>курсивом</emphasis> и <strong>жирно</strong>.<a l:href="#n1" type="note">[1]</a></p>
                <empty-line/>
                <image l:href="#pic.png"/>
                <poem><stanza><v>Строка один</v><v>Строка два</v></stanza></poem>
              </section>
              <section>
                <title><p>Глава 2</p></title>
                <p>Продолжение</p>
              </section>
            </section>
          </body>
          <body name="notes">
            <section id="n1"><title><p>1</p></title><p>Текст сноски.</p></section>
            <section id="n2"><title><p>2</p></title><p>Другая сноска.</p></section>
          </body>
          <binary id="pic.png" content-type="image/png">AQI=</binary>
        </FictionBook>
    """.trimIndent().toByteArray()

    private val doc = Fb2DocumentParser.parse(fb2, "file")

    @Test
    fun splitsTitledSectionsIntoChapters() {
        assertEquals("Сказка", doc.title)
        assertEquals(
            listOf(null, "Часть первая", "Глава 1", "Глава 2", "Примечания"),
            doc.chapters.map { it.title },
        )
        assertEquals(
            listOf("Часть первая" to 1, "Глава 1" to 2, "Глава 2" to 2, "Примечания" to 1),
            doc.toc.map { it.title to it.level },
        )
    }

    @Test
    fun keepsInlineStylesAndBlockKinds() {
        val chapter = doc.chapters[2]
        val paragraph = chapter.blocks[1] as TextBlock
        assertEquals("Начало курсивом и жирно.[1]", paragraph.text)
        assertEquals("курсивом", paragraph.text.substring(paragraph.spans.first { it.style == SpanStyle.Italic }.let { it.start until it.end }))
        val note = paragraph.spans.first { it.style is SpanStyle.Link }.style as SpanStyle.Link
        assertEquals(SpanStyle.Link("n1", isNote = true), note)
        assertTrue(chapter.blocks[2] is ImageBlock)
        assertEquals(listOf(BlockKind.POEM, BlockKind.POEM), chapter.blocks.drop(3).map { (it as TextBlock).kind })
        assertEquals(BlockKind.EPIGRAPH, (doc.chapters[0].blocks[1] as TextBlock).kind)
        assertEquals(setOf("pic.png"), doc.images.keys)
    }

    @Test
    fun footnotePopupShowsOnlyThatNote() {
        assertEquals(listOf("Текст сноски."), doc.noteBlocks("n1").map { it.text })
        assertEquals(Position(1, 0), doc.anchors["part1"])
    }

    @Test
    fun progressAndSliderPositionsRoundTrip() {
        assertEquals(0f, doc.progressOf(Position.START))
        val middle = doc.positionAt(0.5f)
        assertTrue(doc.progressOf(middle) in 0.3f..0.7f)
        assertEquals(Position(2, 1, 5), Position.parse(Position(2, 1, 5).serialize()))
    }
}
