package app.koreshok

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.koreshok.ui.library.LibraryScreen
import app.koreshok.ui.theme.KoreshokTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KoreshokTheme {
                LibraryScreen()
            }
        }
    }
}
