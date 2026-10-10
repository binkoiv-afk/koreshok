package app.koreshok.ui.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** A short value shown while a gesture changes it: "Яркость 40%", "Шрифт 22". */
@Composable
fun GestureHint(text: String, theme: ReaderTheme, modifier: Modifier = Modifier) {
    Surface(color = theme.text.copy(alpha = 0.85f), contentColor = theme.background, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

/** Speed and stop for automatic page turning or scrolling. */
@Composable
fun AutoScrollBar(speed: Float, paged: Boolean, theme: ReaderTheme, onSpeed: (Float) -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = theme.background,
        contentColor = theme.text,
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSpeed((speed - 1).coerceAtLeast(1f)) }) { Icon(Icons.Default.Remove, "Медленнее") }
            Text(
                if (paged) "Страница каждые ${autoPageSeconds(speed).roundToInt()} с" else "Скорость ${speed.roundToInt()}",
                fontSize = 14.sp,
            )
            IconButton(onClick = { onSpeed((speed + 1).coerceAtMost(20f)) }) { Icon(Icons.Default.Add, "Быстрее") }
            IconButton(onClick = onStop) { Icon(Icons.Default.Close, "Выключить автолистание") }
        }
    }
}

/** Speed 1..20 as seconds per page: 1 is two minutes, 20 is six seconds. */
fun autoPageSeconds(speed: Float): Float = 120f / speed

/** Speed 1..20 as scrolling in dp per second. */
fun autoScrollDpPerSecond(speed: Float): Float = speed * 2f
