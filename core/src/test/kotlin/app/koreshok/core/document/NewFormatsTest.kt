package app.koreshok.core.document

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NewFormatsTest {

    // ---- MOBI ----

    /** A minimal MOBI: PDB header, record 0 with PalmDOC + MOBI + EXTH headers, text records, one image. */
    private fun mobi(html: String, compress: Boolean, title: String = "Тест", author: String = "Лев Толстой", trailing: Boolean = false): ByteArray {
        val text = html.toByteArray(Charsets.UTF_8)
        val chunks = text.toList().chunked(4096).map { it.toByteArray() }
        val records = chunks.map { chunk ->
            val body = if (compress) palmdocLiteral(chunk) else chunk
            // One trailing entry of size 3 ("multibyte" flag off, bit 1 on): 2 junk bytes + size byte 0x83.
            if (trailing) body + byteArrayOf(9, 9, 0x83.toByte()) else body
        }
        val image = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)

        val exth = ByteArrayOutputStream()
        val exthData = DataOutputStream(exth)
        val items = listOf(100 to author.toByteArray(), 503 to title.toByteArray(), 201 to byteArrayOf(0, 0, 0, 0))
        val exthLength = 12 + items.sumOf { 8 + it.second.size }
        exthData.writeBytes("EXTH"); exthData.writeInt(exthLength); exthData.writeInt(items.size)
        for ((type, value) in items) { exthData.writeInt(type); exthData.writeInt(8 + value.size); exthData.write(value) }

        val headerLength = 0xE8
        val r0 = ByteArray(16 + headerLength)
        fun put16(at: Int, v: Int) { r0[at] = (v shr 8).toByte(); r0[at + 1] = v.toByte() }
        fun put32(at: Int, v: Int) { for (k in 0..3) r0[at + k] = (v shr (24 - 8 * k)).toByte() }
        put16(0, if (compress) 2 else 1)
        put32(4, text.size)
        put16(8, records.size)
        put16(10, 4096)
        "MOBI".toByteArray().copyInto(r0, 16)
        put32(20, headerLength)
        put32(24, 2)
        put32(28, 65001)
        val nameBytes = "Имя из заголовка".toByteArray()
        put32(84, r0.size + exth.size())
        put32(88, nameBytes.size)
        put32(108, records.size + 1)
        put32(128, 0x40)
        put16(0xF2, if (trailing) 2 else 0)
        val record0 = r0 + exth.toByteArray() + nameBytes

        val all = listOf(record0) + records + listOf(image)
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(ByteArray(60))
        data.writeBytes("BOOKMOBI")
        data.write(ByteArray(8))
        data.writeShort(all.size)
        var offset = 78 + all.size * 8 + 2
        for ((i, rec) in all.withIndex()) { data.writeInt(offset); data.writeInt(i); offset += rec.size }
        data.writeShort(0)
        all.forEach(data::write)
        return out.toByteArray()
    }

    /** PalmDOC "compression" using only literal runs, which every decoder must accept. */
    private fun palmdocLiteral(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (chunk in bytes.toList().chunked(8)) {
            out.write(chunk.size)
            chunk.forEach { out.write(it.toInt()) }
        }
        return out.toByteArray()
    }

    private val mobiHtml = """
        <html><body>
        <h1>Война и мир</h1>
        <h2>Глава первая</h2><p>Ну, князь, Генуя и Лукка — поместья фамилии Бонапарте.</p>
        <p><img recindex="00001"></p>
        <mbp:pagebreak/>
        <h2>Глава вторая</h2><p>Анна Павловна кашляла несколько дней.</p>
        </body></html>
    """.trimIndent()

    @Test
    fun mobiUncompressed() {
        val doc = MobiDocumentParser.parse(mobi(mobiHtml, compress = false), "file")
        assertEquals("Тест", doc.title)
        assertEquals(listOf("Глава первая", "Глава вторая"), doc.toc.map { it.title })
        assertEquals(3, doc.chapters.size)
        assertTrue(doc.chapters[1].blocks.any { it is TextBlock && it.text.startsWith("Ну, князь") })
        assertEquals(1, doc.images.size)
    }

    @Test
    fun mobiPalmdocWithTrailingEntries() {
        val doc = MobiDocumentParser.parse(mobi(mobiHtml.repeat(1), compress = true, trailing = true), "file")
        val text = doc.chapters.flatMap { it.blocks }.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
        assertTrue(text, text.contains("Анна Павловна кашляла несколько дней."))
    }

    @Test
    fun mobiMetadata() {
        val meta = MobiDocumentParser.metadata(mobi(mobiHtml, compress = false), "file")
        assertEquals("Тест", meta.title)
        assertEquals("Толстой", meta.authors.single().lastName)
        assertTrue(meta.cover != null)
    }

    @Test
    fun palmdocBackReference() {
        // "abcabcabc": literals a b c, then copy distance 3 length 6 → 0x80 | ((3 << 3) | (6 - 3)).
        val pair = 0x8000 or (3 shl 3) or 3
        val input = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), (pair shr 8).toByte(), pair.toByte(), 0xC1.toByte())
        assertArrayEquals("abcabcabc A".toByteArray(), MobiFile.palmdoc(input))
    }

    // ---- DOCX ----

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

    @Test
    fun docx() {
        val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main""""
        val document = """<?xml version="1.0"?><w:document $ns><w:body>
            <w:p><w:pPr><w:pStyle w:val="a1"/></w:pPr><w:r><w:t>Глава 1</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Обычный </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>жирный</w:t></w:r><w:r><w:t xml:space="preserve"> текст.</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="a1"/></w:pPr><w:r><w:t>Глава 2</w:t></w:r></w:p>
            <w:p><w:r><w:rPr><w:i w:val="0"/></w:rPr><w:t>Второй абзац.</w:t></w:r></w:p>
        </w:body></w:document>"""
        val styles = """<?xml version="1.0"?><w:styles $ns><w:style w:styleId="a1"><w:name w:val="heading 1"/></w:style></w:styles>"""
        val doc = DocxDocumentParser.parse(zip("word/document.xml" to document, "word/styles.xml" to styles), "Документ")
        assertEquals(listOf("Глава 1", "Глава 2"), doc.toc.map { it.title })
        val para = doc.chapters[0].blocks[1] as TextBlock
        assertEquals("Обычный жирный текст.", para.text)
        assertEquals(listOf(Span(8, 14, SpanStyle.Bold)), para.spans)
        assertEquals("Второй абзац.", (doc.chapters[1].blocks[1] as TextBlock).text)
        assertTrue((doc.chapters[1].blocks[1] as TextBlock).spans.isEmpty())
    }

    // ---- RTF ----

    @Test
    fun rtf() {
        val rtf = """{\rtf1\ansi\ansicpg1251\deff0{\fonttbl{\f0 Times New Roman;}}{\info{\title Скрыто}}
\pard Глава 1\par
\pard \'cf\'f0\'e8\'e2\'e5\'f2, {\b \'ec\'e8\'f0}!\par
\pard \u1044?\u1072? \line end\par
}"""
        val doc = RtfDocumentParser.parse(rtf.toByteArray(charset("windows-1251")), "Файл")
        val texts = doc.chapters.flatMap { it.blocks }.filterIsInstance<TextBlock>()
        assertEquals("Глава 1", texts[0].text)
        assertEquals(BlockKind.HEADING, texts[0].kind)
        assertEquals("Привет, мир!", texts[1].text)
        assertEquals(listOf(Span(8, 11, SpanStyle.Bold)), texts[1].spans)
        assertEquals("Да\nend", texts[2].text)
        assertTrue(texts.none { it.text.contains("Times") || it.text.contains("Скрыто") })
    }

    // ---- Search ----

    @Test
    fun searchIgnoresCaseAndYo() {
        val doc = TxtDocumentParser.parse("Глава 1\n\nОн шёл домой. ЕЩЁ раз шел.\n\nГлава 2\n\nНичего".toByteArray(), "t")
        val hits = BookSearch.search(doc, "шел")
        assertEquals(2, hits.size)
        assertEquals(Position(0, 1, 3), hits[0].position)
        assertEquals("шёл", hits[0].snippet.substring(hits[0].matchStart, hits[0].matchStart + hits[0].matchLength))
        assertEquals(1, BookSearch.search(doc, "ещё").size)
        assertTrue(BookSearch.search(doc, "x").isEmpty())
    }
}
