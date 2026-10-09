package app.koreshok.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/** Wood and wall colors; the dark set is walnut in lamplight. */
class ShelfColors(
    val wallTop: Color,
    val wallBottom: Color,
    val boardTop: Color,
    val boardTopFar: Color,
    val boardFront: Color,
    val boardFrontDark: Color,
    val grain: Color,
    val shadow: Color,
)

val LightShelf = ShelfColors(
    wallTop = Color(0xFFF4EBDD),
    wallBottom = Color(0xFFEADCC6),
    boardTop = Color(0xFFD7AE7E),
    boardTopFar = Color(0xFFB98A5C),
    boardFront = Color(0xFFA9774B),
    boardFrontDark = Color(0xFF835834),
    grain = Color(0xFF6E4527),
    shadow = Color(0xFF4A2E17),
)

val DarkShelf = ShelfColors(
    wallTop = Color(0xFF1F1915),
    wallBottom = Color(0xFF16120F),
    boardTop = Color(0xFF7A5536),
    boardTopFar = Color(0xFF5A3D26),
    boardFront = Color(0xFF5C3E27),
    boardFrontDark = Color(0xFF3B271A),
    grain = Color(0xFF26180F),
    shadow = Color(0xFF000000),
)

/** The warm wall behind the shelves. */
fun Modifier.shelfWall(colors: ShelfColors): Modifier =
    background(Brush.verticalGradient(listOf(colors.wallTop, colors.wallBottom)))

/**
 * A wooden board seen slightly from above: a lit top surface, the front edge with grain, and a
 * soft shadow on the wall below. [seed] varies the grain from board to board.
 */
@Composable
fun ShelfBoard(colors: ShelfColors, seed: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(30.dp)) {
        val top = 8.dp.toPx()
        val front = 13.dp.toPx()
        val w = size.width
        // Top surface, narrowing towards the wall.
        val inset = 6.dp.toPx()
        val surface = Path().apply {
            moveTo(inset, 0f)
            lineTo(w - inset, 0f)
            lineTo(w, top)
            lineTo(0f, top)
            close()
        }
        drawPath(surface, Brush.verticalGradient(listOf(colors.boardTopFar, colors.boardTop), endY = top))
        // Front edge.
        drawRect(
            Brush.verticalGradient(listOf(colors.boardFront, colors.boardFrontDark), startY = top, endY = top + front),
            topLeft = Offset(0f, top),
            size = Size(w, front),
        )
        // A thin highlight where the light catches the edge.
        drawRect(Color.White.copy(alpha = 0.18f), topLeft = Offset(0f, top), size = Size(w, 1.dp.toPx()))
        // Grain: long faint strokes, a little different on every board.
        val random = Random(seed)
        repeat(7) {
            val y = top + 2.dp.toPx() + random.nextFloat() * (front - 4.dp.toPx())
            val start = random.nextFloat() * w * 0.3f
            val length = w * (0.4f + random.nextFloat() * 0.6f)
            drawLine(colors.grain.copy(alpha = 0.12f + random.nextFloat() * 0.12f), Offset(start, y), Offset((start + length).coerceAtMost(w), y + random.nextFloat() * 2f - 1f), strokeWidth = 0.8.dp.toPx())
        }
        // Shadow cast on the wall.
        drawRect(
            Brush.verticalGradient(listOf(colors.shadow.copy(alpha = 0.28f), Color.Transparent), startY = top + front, endY = size.height),
            topLeft = Offset(0f, top + front),
            size = Size(w, size.height - top - front),
        )
    }
}

/** One shelf: books standing on a board, bottoms on the wood, slightly different heights. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> ShelfRow(
    books: List<T>,
    columns: Int,
    colors: ShelfColors,
    seed: Int,
    cover: @Composable (book: T, modifier: Modifier) -> Unit,
    onClick: (T) -> Unit,
    onLongClick: (T) -> Unit,
    key: (T) -> String,
    horizontalPadding: Dp = 20.dp,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = horizontalPadding + 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            books.forEach { book ->
                // Real books differ in height; the cover keeps its proportions, only scale changes.
                val height = 0.92f + (key(book).hashCode() and 0xFF) / 255f * 0.08f
                Box(
                    Modifier
                        .weight(1f)
                        .combinedClickable(onClick = { onClick(book) }, onLongClick = { onLongClick(book) }),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    cover(book, Modifier.fillMaxWidth(height))
                }
            }
            repeat(columns - books.size) { Spacer(Modifier.weight(1f)) }
        }
        // The board starts right under the books, which seem to stand on it.
        ShelfBoard(colors, seed, Modifier.offset(y = (-4).dp))
    }
}

/** A ribbon bookmark hanging from the top of a cover being read. */
@Composable
fun Ribbon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 12.dp, height = 26.dp)) {
        val notch = 5.dp.toPx()
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(size.width / 2, size.height - notch)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, color)
        drawRect(Color.Black.copy(alpha = 0.18f), size = Size(1.dp.toPx(), size.height - notch))
    }
}

/** A small round seal on finished books. */
@Composable
fun FinishedSeal(background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(22.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Check, "Прочитано", tint = foreground, modifier = Modifier.size(14.dp))
    }
}
