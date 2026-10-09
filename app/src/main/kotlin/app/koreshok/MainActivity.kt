package app.koreshok

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
                val book = openBook
                if (book == null) {
                    LibraryScreen(onRead = { openBook = it })
                } else {
                    ReaderScreen(book, onBack = { openBook = null })
                }
            }
        }
    }
}
