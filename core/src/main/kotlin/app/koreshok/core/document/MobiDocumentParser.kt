package app.koreshok.core.document

import app.koreshok.core.model.Author
import app.koreshok.core.model.BookMetadata
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Kindle books: MOBI, AZW and AZW3 (KF8). The text is HTML packed into PalmDOC records,
 * compressed with PalmDOC LZ77 or Amazon's HUFF/CDIC; images are separate records.
 */
object MobiDocumentParser {

    fun parse(bytes: ByteArray, fallbackTitle: String): Document {
        val book = MobiFile(bytes)
        val html = Jsoup.parse(book.text())
        val images = mutableMapOf<String, ByteArray>()
        for (img in html.select("img")) {
            // Old MOBI: recindex="00012"; KF8: src="kindle:embed:000C?mime=image/jpeg" (base 32).
            val index = img.attr("recindex").toIntOrNull()
                ?: Regex("kindle:embed:([0-9A-Va-v]+)").find(img.attr("src"))?.groupValues?.get(1)?.toIntOrNull(32)
            val record = index?.let { book.image(it) }
            if (record == null) {
                img.remove()
            } else {
                val id = "img/$index"
                images[id] = record
                img.attr("src", id)
            }
        }
        // Page breaks between chapters are the only chapter marks some books have.
        html.select("mbp|pagebreak").forEach { it.replaceWith(org.jsoup.nodes.Element("hr")) }
        val title = book.metadata().title.ifBlank { fallbackTitle }
        return HtmlBook.parse(html, title, images, guessHeadings = true)
    }

    fun metadata(bytes: ByteArray, fallbackTitle: String): BookMetadata {
        val meta = MobiFile(bytes).metadata()
        return meta.copy(title = meta.title.ifBlank { fallbackTitle })
    }
}

internal class MobiFile(private val data: ByteArray) {
    private val recordCount = u16(data, 76)
    private val offsets = IntArray(recordCount) { u32(data, 78 + it * 8).toInt() }
    private val record0 = record(0)

    private val compression = u16(record0, 0)
    private val textRecords = u16(record0, 8)
    private val encryption = u16(record0, 12)
    private val hasMobiHeader = record0.size >= 20 && String(record0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
    private val headerLength = if (hasMobiHeader) u32(record0, 20).toInt() else 0
    private val encoding = if (hasMobiHeader) u32(record0, 28).toInt() else 1252
    private val firstImage = if (hasMobiHeader && record0.size >= 112) u32(record0, 108).toInt() else -1
    private val extraFlags = if (hasMobiHeader && headerLength >= 0xE4 && record0.size >= 0xF4) u16(record0, 0xF2) else 0

    init {
        require(String(data, 60, 8, Charsets.ISO_8859_1) in setOf("BOOKMOBI", "TEXtREAd")) { "Not a MOBI file" }
    }

    fun record(index: Int): ByteArray {
        val start = offsets[index]
        val end = if (index + 1 < recordCount) offsets[index + 1] else data.size
        return data.copyOfRange(start, end.coerceAtLeast(start))
    }

    fun text(): String {
        if (encryption != 0) throw UnsupportedOperationException("Книга защищена DRM")
        val huff = if (compression == HUFF) Huffcdic(this, u32(record0, 0x70).toInt(), u32(record0, 0x74).toInt()) else null
        val out = ByteArrayOutputStream()
        for (i in 1..textRecords) {
            if (i >= recordCount) break
            val raw = record(i)
            val body = raw.copyOf(raw.size - trailingSize(raw))
            out.write(
                when (compression) {
                    NONE -> body
                    PALMDOC -> palmdoc(body)
                    HUFF -> huff!!.unpack(body)
                    else -> throw UnsupportedOperationException("Неизвестное сжатие MOBI: $compression")
                },
            )
        }
        val charset = if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        return String(out.toByteArray(), charset)
    }

    /** Image record [n] counted from 1, as recindex and kindle:embed count them. */
    fun image(n: Int): ByteArray? {
        if (firstImage < 0 || n < 1) return null
        val index = firstImage + n - 1
        if (index >= recordCount) return null
        return record(index).takeIf { isImage(it) }
    }

    fun metadata(): BookMetadata {
        var title = ""
        if (hasMobiHeader && record0.size >= 92) {
            val offset = u32(record0, 84).toInt()
            val length = u32(record0, 88).toInt()
            if (offset + length <= record0.size) title = decode(record0.copyOfRange(offset, offset + length))
        }
        val authors = mutableListOf<Author>()
        var description: String? = null
        var publisher: String? = null
        var language: String? = null
        var year: Int? = null
        var cover: ByteArray? = null
        val exth = exth()
        for ((type, value) in exth) {
            val text = decode(value).trim()
            when (type) {
                100 -> if (text.isNotEmpty()) authors += Author.parse(text)
                101 -> publisher = text.ifEmpty { null }
                103 -> description = Jsoup.parse(text).text().ifEmpty { null }
                106 -> year = Regex("\\d{4}").find(text)?.value?.toIntOrNull()
                503 -> if (text.isNotEmpty()) title = text
                524 -> language = text.ifEmpty { null }
                201 -> if (value.size >= 4) cover = image(u32(value, 0).toInt() + 1)
            }
        }
        if (cover == null) {
            exth.firstOrNull { it.first == 202 }?.let { cover = image(u32(it.second, 0).toInt() + 1) }
        }
        return BookMetadata(
            title = title.trim(),
            authors = authors,
            language = language,
            year = year,
            publisher = publisher,
            description = description,
            cover = cover,
        )
    }

    private fun exth(): List<Pair<Int, ByteArray>> {
        if (!hasMobiHeader || record0.size < 132 || (u32(record0, 128) and 0x40L) == 0L) return emptyList()
        var pos = 16 + headerLength
        if (pos + 12 > record0.size || String(record0, pos, 4, Charsets.ISO_8859_1) != "EXTH") return emptyList()
        val count = u32(record0, pos + 8).toInt()
        pos += 12
        val result = mutableListOf<Pair<Int, ByteArray>>()
        repeat(count) {
            if (pos + 8 > record0.size) return result
            val type = u32(record0, pos).toInt()
            val length = u32(record0, pos + 4).toInt()
            if (length < 8 || pos + length > record0.size) return result
            result += type to record0.copyOfRange(pos + 8, pos + length)
            pos += length
        }
        return result
    }

    private fun decode(bytes: ByteArray) =
        String(bytes, if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252"))

    /** Bytes at the end of a text record that are index data, not text. */
    private fun trailingSize(record: ByteArray): Int {
        var size = 0
        var flags = extraFlags shr 1
        while (flags != 0) {
            if (flags and 1 != 0) size += trailingEntry(record, record.size - size)
            flags = flags shr 1
        }
        if (extraFlags and 1 != 0 && record.size - size - 1 >= 0) size += (record[record.size - size - 1].toInt() and 3) + 1
        return size.coerceAtMost(record.size)
    }

    private fun trailingEntry(record: ByteArray, end: Int): Int {
        var result = 0
        var bit = 0
        var pos = end
        while (pos > 0) {
            val v = record[pos - 1].toInt() and 0xFF
            result = result or ((v and 0x7F) shl bit)
            bit += 7
            pos--
            if (v and 0x80 != 0 || bit >= 28) break
        }
        return result
    }

    companion object {
        const val NONE = 1
        const val PALMDOC = 2
        const val HUFF = 17480

        fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

        fun u32(b: ByteArray, at: Int): Long =
            ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
                ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

        fun palmdoc(input: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(input.size * 2)
            var buffer = ByteArray(input.size * 8 + 16)
            var size = 0
            fun put(b: Int) {
                if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                buffer[size++] = b.toByte()
            }
            var i = 0
            while (i < input.size) {
                val c = input[i++].toInt() and 0xFF
                when {
                    c in 1..8 -> repeat(c) { if (i < input.size) put(input[i++].toInt()) }
                    c < 0x80 -> put(c)
                    c >= 0xC0 -> {
                        put(' '.code)
                        put(c xor 0x80)
                    }
                    else -> {
                        if (i >= input.size) break
                        val pair = (c shl 8) or (input[i++].toInt() and 0xFF)
                        val distance = (pair shr 3) and 0x7FF
                        val length = (pair and 7) + 3
                        if (distance in 1..size) repeat(length) { put(buffer[size - distance].toInt()) }
                    }
                }
            }
            out.write(buffer, 0, size)
            return out.toByteArray()
        }

        private fun isImage(b: ByteArray): Boolean = b.size > 4 && (
            (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||
                (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) ||
                (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()) ||
                (b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte())
            )
    }
}

/** Amazon's HUFF/CDIC text compression, after Calibre's and KindleUnpack's readers. */
internal class Huffcdic(file: MobiFile, first: Int, count: Int) {
    private val dict1 = LongArray(256)
    private val mincode = LongArray(33)
    private val maxcode = LongArray(33)
    private val dictionary = mutableListOf<ByteArray?>()
    private val done = mutableListOf<Boolean>()

    init {
        val huff = file.record(first)
        require(String(huff, 0, 4, Charsets.ISO_8859_1) == "HUFF") { "Bad HUFF record" }
        val off1 = MobiFile.u32(huff, 8).toInt()
        val off2 = MobiFile.u32(huff, 12).toInt()
        for (i in 0 until 256) dict1[i] = MobiFile.u32(huff, off1 + i * 4)
        for (len in 1..32) {
            mincode[len] = MobiFile.u32(huff, off2 + (len - 1) * 8) shl (32 - len)
            maxcode[len] = ((MobiFile.u32(huff, off2 + (len - 1) * 8 + 4) + 1) shl (32 - len)) - 1
        }
        for (r in first + 1 until first + count) {
            val cdic = file.record(r)
            require(String(cdic, 0, 4, Charsets.ISO_8859_1) == "CDIC") { "Bad CDIC record" }
            val phrases = MobiFile.u32(cdic, 8).toInt()
            val bits = MobiFile.u32(cdic, 12).toInt()
            val n = minOf(1 shl bits, phrases - dictionary.size)
            for (k in 0 until n) {
                val off = MobiFile.u16(cdic, 16 + k * 2)
                val blen = MobiFile.u16(cdic, 16 + off)
                val start = 18 + off
                dictionary += cdic.copyOfRange(start, (start + (blen and 0x7FFF)).coerceAtMost(cdic.size))
                done += blen and 0x8000 != 0
            }
        }
    }

    fun unpack(input: ByteArray): ByteArray {
        val data = input.copyOf(input.size + 8)
        var bitsLeft = input.size * 8L
        var pos = 0
        var x = u64(data, pos)
        var n = 32
        val out = ByteArrayOutputStream()
        while (true) {
            if (n <= 0) {
                pos += 4
                x = u64(data, pos)
                n += 32
            }
            val code = (x ushr n) and 0xFFFFFFFFL
            val entry = dict1[(code ushr 24).toInt()]
            var codelen = (entry and 0x1F).toInt()
            val term = entry and 0x80 != 0L
            var max = (((entry ushr 8) + 1) shl (32 - codelen)) - 1
            if (!term) {
                while (codelen < 32 && code < mincode[codelen]) codelen++
                max = maxcode[codelen]
            }
            n -= codelen
            bitsLeft -= codelen
            if (bitsLeft < 0) break
            val r = ((max - code) ushr (32 - codelen)).toInt()
            if (r !in dictionary.indices) break
            if (!done[r]) {
                val raw = dictionary[r]!!
                dictionary[r] = ByteArray(0)
                done[r] = true
                dictionary[r] = unpack(raw)
            }
            out.write(dictionary[r]!!)
        }
        return out.toByteArray()
    }

    private fun u64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b.getOrElse(at + i) { 0 }.toLong() and 0xFF)
        return v
    }
}
