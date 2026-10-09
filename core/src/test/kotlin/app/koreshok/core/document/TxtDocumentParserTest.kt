package app.koreshok.core.document

import org.junit.Test
import org.junit.Assert.assertEquals

class TxtDocumentParserTest {
    @Test
    fun `wrapped paragraphs and chapter headings`() {
        val text = "Глава 1\n\nПервая строка\nпродолжение.\n\nВторой абзац.\n\nГлава 2\n\nТретий."
        val doc = TxtDocumentParser.parse(text.toByteArray(), "Книга")
        assertEquals(2, doc.chapters.size)
        assertEquals(listOf("Глава 1", "Глава 2"), doc.toc.map { it.title })
        val first = doc.chapters[0].blocks.map { (it as TextBlock).text }
        assertEquals(listOf("Глава 1", "Первая строка продолжение.", "Второй абзац."), first)
    }

    @Test
    fun `windows-1251 is detected`() {
        val bytes = "Привет, мир".toByteArray(charset("windows-1251"))
        assertEquals("Привет, мир", TxtDocumentParser.decode(bytes))
    }

    @Test
    fun `line per paragraph without blank lines`() {
        val doc = TxtDocumentParser.parse("один\nдва\nтри".toByteArray(), "Книга")
        assertEquals(3, doc.chapters.single().blocks.size)
        assertEquals(emptyList<TocEntry>(), doc.toc)
    }
}
