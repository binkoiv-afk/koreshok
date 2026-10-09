package app.bookreader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Paper = lightColorScheme(
    primary = Color(0xFF7A4E2D),
    secondary = Color(0xFF8C7B6B),
    background = Color(0xFFFBF7F0),
    surface = Color(0xFFFBF7F0),
)

private val Night = darkColorScheme(
    primary = Color(0xFFE0B48A),
    secondary = Color(0xFFC9B8A6),
    background = Color(0xFF16130F),
    surface = Color(0xFF16130F),
)

@Composable
fun BookReaderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> Night
        else -> Paper
    }
    MaterialTheme(colorScheme = colors, content = content)
}
