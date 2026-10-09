package app.koreshok.ui.reader

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ReaderTheme(val label: String, val background: Color, val text: Color, val secondary: Color, val accent: Color) {
    DAY("День", Color(0xFFFBF8F2), Color(0xFF1F1B16), Color(0xFF8A8075), Color(0xFF8A4B1F)),
    SEPIA("Сепия", Color(0xFFF1E4C9), Color(0xFF3B2F22), Color(0xFF8C7A60), Color(0xFF8A4B1F)),
    NIGHT("Ночь", Color(0xFF1E1D1B), Color(0xFFCFC7BA), Color(0xFF7F786E), Color(0xFFD9A066)),
    BLACK("Чёрная", Color(0xFF000000), Color(0xFFB8B2A8), Color(0xFF6B665F), Color(0xFFD9A066)),
}

enum class ReaderFont(val label: String) {
    SERIF("Антиква"),
    SANS("Гротеск"),
    MONO("Моно"),
}

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
        }
    }
}
