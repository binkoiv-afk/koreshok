package app.koreshok.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.koreshok.core.discover.Discover
import app.koreshok.core.opds.OpdsEntry
import app.koreshok.data.TastePrefs
import app.koreshok.ui.components.BookCover
import app.koreshok.ui.components.CoverCaption
import app.koreshok.ui.theme.BookTitleStyle
import app.koreshok.ui.theme.Brand

/** Everything the Обзор page shows before a search is typed. */
data class DiscoverUi(
    val prefs: TastePrefs?,
    val rows: List<DiscoverRow>,
    val fresh: FreshState,
)

class DiscoverActions(
    val onRandom: () -> Unit = {},
    val onQuiz: () -> Unit = {},
    val onDismissQuiz: () -> Unit = {},
    val onBook: (OpdsEntry) -> Unit = {},
    val onRetry: () -> Unit = {},
)

fun LazyListScope.discoverItems(ui: DiscoverUi, actions: DiscoverActions) {
    item(key = "random") { RandomCard(actions.onRandom) }

    val prefs = ui.prefs
    if (prefs != null && !prefs.asked) {
        item(key = "invite") { TasteInvite(actions) }
    }

    when (val fresh = ui.fresh) {
        FreshState.Off -> Unit
        FreshState.Loading -> item(key = "fresh") {
            SectionHeader("Новое у ваших авторов", "Смотрю во Флибусте…", loading = true)
        }
        is FreshState.Loaded -> item(key = "fresh") {
            Column {
                val subtitle = when {
                    fresh.books.isEmpty() -> "Пока ничего нового. Корешок проверяет при каждом запуске"
                    else -> "Вышло в этом и прошлом году"
                }
                SectionHeader("Новое у ваших авторов", subtitle, icon = Icons.Default.NewReleases)
                if (fresh.books.isNotEmpty()) {
                    CoverRow(fresh.books.map { it.entry }, isNew = fresh.books.filter { it.isNew }.map { it.entry.id }.toSet(), showYear = true, onBook = actions.onBook)
                }
                if (fresh.missing.isNotEmpty()) {
                    Text(
                        "Не нашёл во Флибусте: ${fresh.missing.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    ui.rows.forEach { row ->
        item(key = "row:${row.key}") {
            Column {
                SectionHeader(row.title, row.subtitle, loading = row.state is RowState.Loading)
                when (val state = row.state) {
                    RowState.Loading -> PlaceholderRow()
                    is RowState.Failed -> Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Флибуста не ответила",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = actions.onRetry) { Text("Повторить") }
                    }
                    is RowState.Loaded -> if (state.books.isEmpty()) {
                        Text(
                            "Всё отсюда уже на вашей полке",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    } else {
                        CoverRow(state.books, emptySet(), showYear = false, onBook = actions.onBook)
                    }
                }
            }
        }
    }

    if (prefs?.asked == true) {
        item(key = "taste") {
            TextButton(onClick = actions.onQuiz, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (prefs.isEmpty) "Рассказать о своих вкусах" else "Изменить вкусы и авторов")
            }
        }
    }
}

/** Quiet by design: one line with a die, for the evenings when nothing on the shelf calls. */
@Composable
private fun RandomCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(Brand.Amber.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Casino, null, tint = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Не знаете, что почитать?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Корешок вытянет книгу наугад",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TasteInvite(actions: DiscoverActions) {
    val ink = Color(0xFFFBF3E4)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(Brand.Green, Brand.GreenLight)))
            .padding(20.dp),
    ) {
        Icon(Icons.Default.AutoAwesome, null, tint = Brand.Amber)
        Spacer(Modifier.height(10.dp))
        Text("Подборка под ваш вкус", style = MaterialTheme.typography.titleLarge, color = ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Отметьте любимые жанры и авторов. Корешок соберёт книги для вас и скажет, когда у ваших авторов выйдет новая.",
            style = MaterialTheme.typography.bodyMedium,
            color = ink.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = actions.onQuiz,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Amber, contentColor = Brand.Ink),
            ) { Text("Рассказать о вкусах") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = actions.onDismissQuiz) { Text("Не сейчас", color = ink.copy(alpha = 0.8f)) }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String?,
    loading: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun CoverRow(books: List<OpdsEntry>, isNew: Set<String>, showYear: Boolean, onBook: (OpdsEntry) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(books, key = { it.id + it.title }) { book ->
            DiscoverCover(book, book.id in isNew, showYear) { onBook(book) }
        }
    }
}

@Composable
private fun DiscoverCover(book: OpdsEntry, isNew: Boolean, showYear: Boolean, onClick: () -> Unit) {
    val title = Discover.cleanTitle(book.title)
    val author = book.authors.firstOrNull()?.let(Discover::displayAuthor)
    Column(Modifier.width(100.dp).clickable(onClick = onClick)) {
        Box {
            BookCover(title, author, book.coverUrl, Modifier.fillMaxWidth().aspectRatio(2f / 3f), compact = true, elevation = 4.dp)
            val badge = if (isNew) "новое" else book.year?.takeIf { showYear }?.toString()
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isNew) Brand.Ink else Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .background(if (isNew) Brand.Amber else Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        CoverCaption(
            title,
            author,
            titleStyle = BookTitleStyle.copy(fontSize = BookTitleStyle.fontSize * 0.9f),
            authorColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaceholderRow() {
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(4) {
            Box(
                Modifier
                    .width(100.dp)
                    .aspectRatio(2f / 3f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 8.dp, bottomEnd = 8.dp)),
            )
        }
    }
}
