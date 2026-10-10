package app.koreshok.ui.reader

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.koreshok.R

enum class ReaderTheme(val label: String, val background: Color, val text: Color, val secondary: Color, val accent: Color) {
    DAY("День", Color(0xFFFBF8F2), Color(0xFF1F1B16), Color(0xFF8A8075), Color(0xFF8A4B1F)),
    SEPIA("Сепия", Color(0xFFF1E4C9), Color(0xFF3B2F22), Color(0xFF8C7A60), Color(0xFF8A4B1F)),
    NIGHT("Ночь", Color(0xFF1E1D1B), Color(0xFFCFC7BA), Color(0xFF7F786E), Color(0xFFD9A066)),
    BLACK("Чёрная", Color(0xFF000000), Color(0xFFB8B2A8), Color(0xFF6B665F), Color(0xFFD9A066)),
}

enum class ReaderFont(val label: String, val family: FontFamily) {
    PT_SERIF("PT Serif", ptFamily(R.font.pt_serif_regular, R.font.pt_serif_bold, R.font.pt_serif_italic, R.font.pt_serif_bold_italic)),
    PT_SANS("PT Sans", ptFamily(R.font.pt_sans_regular, R.font.pt_sans_bold, R.font.pt_sans_italic, R.font.pt_sans_bold_italic)),
    SERIF("Антиква", FontFamily.Serif),
    SANS("Гротеск", FontFamily.SansSerif),
    MONO("Моно", FontFamily.Monospace),
}

private fun ptFamily(regular: Int, bold: Int, italic: Int, boldItalic: Int) = FontFamily(
    Font(regular),
    Font(bold, FontWeight.Bold),
    Font(italic, style = FontStyle.Italic),
    Font(boldItalic, FontWeight.Bold, FontStyle.Italic),
)

data class ReaderPrefs(
    val theme: ReaderTheme = ReaderTheme.DAY,
    val font: ReaderFont = ReaderFont.SERIF,
    val fontSize: Float = 19f,
    val lineHeight: Float = 1.45f,
    val margin: Float = 22f,
    val justify: Boolean = true,
    val hyphenate: Boolean = true,
    val indent: Boolean = true,
    /** One continuous column instead of pages. */
    val scroll: Boolean = false,
    /** PDF: cut white margins so the text is bigger without zooming. */
    val cropMargins: Boolean = true,
    /** PDF and comics: one page per screen with swipes, instead of a vertical strip. */
    val pagedPages: Boolean = false,
    /** Screen brightness while reading, 0..1; below zero follows the system. */
    val brightness: Float = -1f,
    val keepScreenOn: Boolean = true,
    /** Volume buttons turn pages. */
    val volumeKeys: Boolean = true,
    /** Clock and battery in the page footer. */
    val showClock: Boolean = true,
    /** Read-aloud voice: "piper:<key>", "system:<engine>:<voice>", or empty for the phone's default. */
    val voice: String = "",
    val speechRate: Float = 1f,
    val speechPitch: Float = 1f,
)

private val Context.readerStore by preferencesDataStore("reader")

class ReaderSettings(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val font = stringPreferencesKey("font")
    private val fontSize = floatPreferencesKey("fontSize")
    private val lineHeight = floatPreferencesKey("lineHeight")
    private val margin = floatPreferencesKey("margin")
    private val justify = booleanPreferencesKey("justify")
    private val hyphenate = booleanPreferencesKey("hyphenate")
    private val indent = booleanPreferencesKey("indent")
    private val scroll = booleanPreferencesKey("scroll")
    private val cropMargins = booleanPreferencesKey("cropMargins")
    private val pagedPages = booleanPreferencesKey("pagedPages")
    private val brightness = floatPreferencesKey("brightness")
    private val keepScreenOn = booleanPreferencesKey("keepScreenOn")
    private val volumeKeys = booleanPreferencesKey("volumeKeys")
    private val showClock = booleanPreferencesKey("showClock")
    private val voice = stringPreferencesKey("voice")
    private val speechRate = floatPreferencesKey("speechRate")
    private val speechPitch = floatPreferencesKey("speechPitch")

    val prefs: Flow<ReaderPrefs> = context.readerStore.data.map { p ->
        val defaults = ReaderPrefs()
        ReaderPrefs(
            theme = p[theme]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() } ?: defaults.theme,
            font = p[font]?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() } ?: defaults.font,
            fontSize = p[fontSize] ?: defaults.fontSize,
            lineHeight = p[lineHeight] ?: defaults.lineHeight,
            margin = p[margin] ?: defaults.margin,
            justify = p[justify] ?: defaults.justify,
            hyphenate = p[hyphenate] ?: defaults.hyphenate,
            indent = p[indent] ?: defaults.indent,
            scroll = p[scroll] ?: defaults.scroll,
            cropMargins = p[cropMargins] ?: defaults.cropMargins,
            pagedPages = p[pagedPages] ?: defaults.pagedPages,
            brightness = p[brightness] ?: defaults.brightness,
            keepScreenOn = p[keepScreenOn] ?: defaults.keepScreenOn,
            volumeKeys = p[volumeKeys] ?: defaults.volumeKeys,
            showClock = p[showClock] ?: defaults.showClock,
            voice = p[voice] ?: defaults.voice,
            speechRate = p[speechRate] ?: defaults.speechRate,
            speechPitch = p[speechPitch] ?: defaults.speechPitch,
        )
    }

    suspend fun save(value: ReaderPrefs) {
        context.readerStore.edit { p ->
            p[theme] = value.theme.name
            p[font] = value.font.name
            p[fontSize] = value.fontSize
            p[lineHeight] = value.lineHeight
            p[margin] = value.margin
            p[justify] = value.justify
            p[hyphenate] = value.hyphenate
            p[indent] = value.indent
            p[scroll] = value.scroll
            p[cropMargins] = value.cropMargins
            p[pagedPages] = value.pagedPages
            p[brightness] = value.brightness
            p[keepScreenOn] = value.keepScreenOn
            p[volumeKeys] = value.volumeKeys
            p[showClock] = value.showClock
            p[voice] = value.voice
            p[speechRate] = value.speechRate
            p[speechPitch] = value.speechPitch
        }
    }
}
