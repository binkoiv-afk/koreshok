package app.koreshok.core.document

import org.jsoup.Jsoup
import java.nio.charset.Charset

/**
 * Rich Text Format. Only what books use is kept: paragraphs, line breaks, bold and italic,
 * Cyrillic in any code page or as \u escapes. Fonts, colours, pictures and headers are skipped.
 */
object RtfDocumentParser {

    fun parse(bytes: ByteArray, fallbackTitle: String): Document {
        val html = toHtml(bytes)
        return HtmlBook.parse(Jsoup.parse(html), fallbackTitle, emptyMap(), guessHeadings = true)
    }

    internal fun toHtml(bytes: ByteArray): String {
        val src = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder("<html><body><p>")
        var charset: Charset = Charset.forName("windows-1252")
        val pendingBytes = java.io.ByteArrayOutputStream()

        data class State(var skip: Boolean = false, var bold: Boolean = false, var italic: Boolean = false, var uc: Int = 1)
        val stack = ArrayDeque<State>()
        var state = State()
        var openBold = false
        var openItalic = false
        var skipChars = 0

        fun flushBytes() {
            if (pendingBytes.size() > 0) {
                out.append(escape(String(pendingBytes.toByteArray(), charset)))
                pendingBytes.reset()
            }
        }
        fun syncStyle() {
            flushBytes()
            if (openItalic && !state.italic) { out.append("</i>"); openItalic = false }
            if (openBold && !state.bold) { out.append("</b>"); openBold = false }
            if (state.bold && !openBold) { out.append("<b>"); openBold = true }
            if (state.italic && !openItalic) { out.append("<i>"); openItalic = true }
        }
        fun text(c: Char) {
            if (state.skip) return
            if (skipChars > 0) { skipChars--; return }
            syncStyle()
            out.append(escape(c.toString()))
        }
        fun rawByte(b: Int) {
            if (state.skip) return
            if (skipChars > 0) { skipChars--; return }
            if (pendingBytes.size() == 0) syncStyle()
            pendingBytes.write(b)
        }
        fun closeStyles() {
            flushBytes()
            if (openItalic) out.append("</i>")
            if (openBold) out.append("</b>")
            openItalic = false
            openBold = false
        }

        var i = 0
        var groupStart = false
        while (i < src.length) {
            val c = src[i]
            when (c) {
                '{' -> {
                    flushBytes()
                    stack.addLast(state.copy())
                    groupStart = true
                    i++
                    continue
                }
                '}' -> {
                    flushBytes()
                    state = stack.removeLastOrNull() ?: state
                    i++
                }
                '\\' -> {
                    if (i + 1 >= src.length) break
                    val next = src[i + 1]
                    when {
                        next == '\'' -> {
                            val hex = src.substring(i + 2, minOf(i + 4, src.length)).toIntOrNull(16)
                            if (hex != null) rawByte(hex)
                            i += 4
                        }
                        next == '*' -> {
                            state.skip = true
                            i += 2
                        }
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < src.length && src[j].isLetter()) j++
                            val word = src.substring(i + 1, j)
                            var k = j
                            if (k < src.length && (src[k] == '-' || src[k].isDigit())) {
                                k++
                                while (k < src.length && src[k].isDigit()) k++
                            }
                            val param = src.substring(j, k).toIntOrNull()
                            if (k < src.length && src[k] == ' ') k++
                            i = k
                            if (groupStart && word in DESTINATIONS) state.skip = true
                            when (word) {
                                "ansicpg" -> param?.let { cp -> runCatching { charset = Charset.forName("windows-$cp") } }
                                "par", "sect", "page" -> if (!state.skip) {
                                    closeStyles()
                                    out.append("</p>\n<p>")
                                }
                                "line" -> if (!state.skip) { flushBytes(); out.append("<br>") }
                                "tab" -> text(' ')
                                "emdash" -> text('—')
                                "endash" -> text('–')
                                "lquote" -> text('‘')
                                "rquote" -> text('’')
                                "ldblquote" -> text('«')
                                "rdblquote" -> text('»')
                                "bullet" -> text('•')
                                "b" -> state.bold = param != 0
                                "i" -> state.italic = param != 0
                                "plain" -> { state.bold = false; state.italic = false }
                                "uc" -> state.uc = param ?: 1
                                "u" -> if (param != null && !state.skip) {
                                    flushBytes()
                                    val code = if (param < 0) param + 65536 else param
                                    syncStyle()
                                    out.append(escape(code.toChar().toString()))
                                    skipChars = state.uc
                                }
                            }
                        }
                        else -> {
                            // Escaped symbol: \\ \{ \} \~ (non-breaking space) \- (soft hyphen)
                            when (next) {
                                '\\', '{', '}' -> text(next)
                                '~' -> text(' ')
                                '\n', '\r' -> if (!state.skip) { closeStyles(); out.append("</p>\n<p>") }
                            }
                            i += 2
                        }
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (c.code < 128) text(c) else rawByte(c.code)
                    i++
                }
            }
            groupStart = false
        }
        closeStyles()
        out.append("</p></body></html>")
        return out.toString()
    }

    private val DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr",
        "footerl", "footerr", "headerf", "footerf", "listtable", "listoverridetable", "rsidtbl",
        "generator", "xmlnstbl", "themedata", "colorschememapping", "latentstyles", "datastore",
        "object", "fldinst", "filetbl", "revtbl", "pgdsctbl", "footnote", "shp", "shpinst", "nonshppict",
    )

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
