package app.koreshok

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.koreshok.core.model.BookFormat
import app.koreshok.ui.catalog.CatalogScreen
import app.koreshok.ui.pages.PageSource
import app.koreshok.ui.pages.PageReaderScreen
import app.koreshok.ui.library.LibraryScreen
import app.koreshok.ui.reader.ReaderScreen
import app.koreshok.ui.theme.KoreshokTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KoreshokTheme {
                var openBook by rememberSaveable { mutableStateOf<String?>(null) }
                var inCatalogs by rememberSaveable { mutableStateOf(false) }
                val book = openBook
                when {
                    // Back from a book opened in a catalog returns to that catalog.
                    book != null -> BookScreen(book, onBack = { openBook = null })
                    inCatalogs -> CatalogScreen(onBack = { inCatalogs = false }, onRead = { openBook = it })
                    else -> LibraryScreen(onRead = { openBook = it }, onCatalogs = { inCatalogs = true })
                }
            }
        }
    }
}

/** Opens a book in the reader that suits its format: reflowed text or fixed pages. */
@Composable
private fun BookScreen(uri: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as KoreshokApp
    val format by produceState<BookFormat?>(null, uri) {
        value = app.library.book(uri)?.format?.let(BookFormat::valueOf) ?: BookFormat.EPUB
    }
    when (format) {
        null -> Unit
        in PageSource.formats -> PageReaderScreen(uri, onBack)
        else -> ReaderScreen(uri, onBack)
    }
}
