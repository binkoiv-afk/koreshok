package app.koreshok.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.luminance
import app.koreshok.ui.components.DarkShelf
import app.koreshok.ui.components.FinishedSeal
import app.koreshok.ui.components.LightShelf
import app.koreshok.ui.components.Ribbon
import app.koreshok.ui.components.ShelfBoard
import app.koreshok.ui.components.ShelfColors
import app.koreshok.ui.components.ShelfRow
import app.koreshok.ui.components.shelfWall
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.koreshok.core.document.Documents
import app.koreshok.core.format.Fb2Genres
import app.koreshok.core.library.GroupBy
import app.koreshok.core.library.ReadingStatus
import app.koreshok.core.library.SortOrder
import app.koreshok.core.model.BookFormat
import app.koreshok.data.BookEntity
import app.koreshok.ui.components.BookCover
import app.koreshok.ui.components.CoverCaption
import app.koreshok.ui.pages.PageSource
import app.koreshok.ui.theme.BookTitleStyle
import app.koreshok.ui.theme.Brand
import java.io.File
import kotlin.math.roundToInt

/** Opens a book in Корешок when it can, otherwise in another app on the phone. */
fun openBook(context: Context, book: BookEntity, onRead: (String) -> Unit) {
    val format = BookFormat.valueOf(book.format)
    if (format in Documents.supported || format in PageSource.formats) {
        onRead(book.uri)
        return
    }
    val parsed = Uri.parse(book.uri)
    // Downloaded books are plain files; other apps can only read them through the FileProvider.
    val shared = if (parsed.scheme == "file") {
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(parsed.path.orEmpty()))
    } else {
        parsed
    }
    val intent = Intent(Intent.ACTION_VIEW).setData(shared).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, book.title)) }
}

/** Callbacks of the shelf, so previews and screenshot tests can draw it without a ViewModel. */
class ShelfActions(
    val onOpen: (BookEntity) -> Unit = {},
    val onDetails: (BookEntity) -> Unit = {},
    val onAddFolder: () -> Unit = {},
    val onStatus: (ReadingStatus?) -> Unit = {},
    val onToggleList: () -> Unit = {},
    val onArrange: () -> Unit = {},
    val onUpdate: () -> Unit = {},
    /** "Не знаете, что почитать?": a random unread book from the shelf. */
    val onRandom: () -> Unit = {},
)

@Composable
fun ShelfContent(state: LibraryState, update: UpdateState, actions: ShelfActions, modifier: Modifier = Modifier) {
    val wood = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DarkShelf else LightShelf
    if (state.loaded && state.totalBooks == 0 && state.scan == null) {
        Column(modifier.fillMaxSize().shelfWall(wood).padding(horizontal = 20.dp)) {
            ShelfHeader(state, actions, showTools = false)
            EmptyLibrary(actions.onAddFolder)
        }
        return
    }
    val list = state.prefs.list
    BoxWithConstraints(modifier.fillMaxSize().shelfWall(wood)) {
        // As many books per shelf as fit at about 100dp each, never fewer than three.
        val columns = ((maxWidth - 40.dp + 14.dp) / (100.dp + 14.dp)).toInt().coerceAtLeast(3)
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            padded("header") { ShelfHeader(state, actions, showTools = true) }
            if (update != UpdateState.None) padded("update") { UpdateBanner(update, actions.onUpdate) }
            state.scan?.let { scan ->
                padded("scan") {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "Ищу книги в «${scan.folder}»: ${scan.processed} из ${scan.found}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (scan.found > 0) {
                            LinearProgressIndicator(progress = { scan.processed.toFloat() / scan.found }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            val current = state.reading.firstOrNull()
            if (current != null && state.status == null) {
                padded("continue") { ContinueCard(current, actions) }
                val others = state.reading.drop(1)
                if (others.isNotEmpty()) {
                    item(key = "reading") { ReadingRow(others, actions, wood) }
                }
            }
            padded("status") { StatusChips(state, actions.onStatus) }
            if (state.groups.all { it.items.isEmpty() }) {
                padded("nothing") {
                    Text(
                        "Здесь пока ничего нет",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    )
                }
            }
            val series = state.prefs.groupBy == GroupBy.SERIES
            state.groups.forEach { group ->
                if (group.title.isNotEmpty() && group.items.isNotEmpty()) {
                    padded("group:${group.title}") { GroupTitle(group.title, group.items.size) }
                }
                if (list) {
                    // A book can sit in several genre groups, so keys include the group.
                    items(group.items, key = { "${group.title}:${it.uri}" }) { book ->
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            BookRow(book, series, onClick = { actions.onOpen(book) }, onLongClick = { actions.onDetails(book) })
                        }
                    }
                } else {
                    val rows = group.items.chunked(columns)
                    itemsIndexed(rows, key = { i, row -> "${group.title}:row$i:${row.first().uri}" }) { i, row ->
                        ShelfRow(
                            books = row,
                            columns = columns,
                            colors = wood,
                            seed = (group.title.hashCode() * 31) + i,
                            cover = { book, coverModifier -> ShelfBook(book, series, coverModifier) },
                            onClick = actions.onOpen,
                            onLongClick = actions.onDetails,
                            key = { it.uri },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

private fun LazyListScope.padded(key: String, content: @Composable () -> Unit) {
    item(key = key) { Box(Modifier.padding(horizontal = 20.dp)) { content() } }
}

@Composable
private fun GroupTitle(title: String, count: Int) {
    Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}

/** A book standing on the shelf: the cover, a ribbon while reading, a seal once finished. */
@Composable
private fun ShelfBook(book: BookEntity, showSeriesNumber: Boolean, modifier: Modifier) {
    Box(modifier.aspectRatio(2f / 3f)) {
        val title = if (showSeriesNumber) book.seriesIndex?.let { "${formatIndex(it)}. ${book.title}" } ?: book.title else book.title
        BookCover(title, book.authors, book.coverPath?.let(::File), Modifier.fillMaxSize(), elevation = 8.dp)
        when (ReadingStatus.of(book.progress)) {
            ReadingStatus.READING -> Ribbon(MaterialTheme.colorScheme.secondary, Modifier.align(Alignment.TopEnd).padding(end = 10.dp))
            ReadingStatus.FINISHED -> FinishedSeal(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.onPrimary,
                Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
            ReadingStatus.NEW -> Unit
        }
    }
}

@Composable
private fun ShelfHeader(state: LibraryState, actions: ShelfActions, showTools: Boolean) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Полка", style = MaterialTheme.typography.headlineMedium)
            if (state.totalBooks > 0) {
                val reading = state.statusCounts[ReadingStatus.READING] ?: 0
                Text(
                    listOfNotNull(booksCount(state.totalBooks), if (reading > 0) "читаю $reading" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showTools) {
            IconButton(onClick = actions.onRandom) { Icon(Icons.Default.Casino, "Книга наугад") }
            IconButton(onClick = actions.onToggleList) {
                if (state.prefs.list) {
                    Icon(Icons.Default.GridView, "Обложками")
                } else {
                    Icon(Icons.AutoMirrored.Filled.ViewList, "Списком")
                }
            }
            IconButton(onClick = actions.onArrange) { Icon(Icons.Default.Tune, "Сортировка и группы") }
        }
    }
}

fun booksCount(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "книг"
        n % 10 == 1 -> "книга"
        n % 10 in 2..4 -> "книги"
        else -> "книг"
    }
    return "$n $word"
}

/** The book being read right now, big, with one button to get back into it. */
@Composable
private fun ContinueCard(book: BookEntity, actions: ShelfActions) {
    // The brand's bottle green in both themes, so the card reads the same by day and by night.
    val ink = Color(0xFFFBF3E4)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(Brand.Green, Brand.GreenLight)))
            .clickable { actions.onOpen(book) }
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookCover(
                book.title,
                book.authors,
                book.coverPath?.let(::File),
                Modifier.width(84.dp).aspectRatio(2f / 3f),
                elevation = 10.dp,
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "ПРОДОЛЖИТЬ ЧТЕНИЕ",
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.authors.isNotBlank()) {
                    Text(
                        book.authors,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { book.progress },
                        color = ink,
                        trackColor = ink.copy(alpha = 0.25f),
                        strokeCap = StrokeCap.Round,
                        drawStopIndicator = {},
                        modifier = Modifier.weight(1f).height(5.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${(book.progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingRow(books: List<BookEntity>, actions: ShelfActions, wood: ShelfColors) {
    Column(Modifier.padding(top = 16.dp)) {
        Text("Читаю сейчас", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 26.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items(books, key = { it.uri }) { book ->
                Box(Modifier.width(72.dp).aspectRatio(2f / 3f).clickable { actions.onOpen(book) }) {
                    BookCover(book.title, book.authors, book.coverPath?.let(::File), Modifier.fillMaxSize(), compact = true, elevation = 6.dp)
                    Ribbon(MaterialTheme.colorScheme.secondary, Modifier.align(Alignment.TopEnd).padding(end = 8.dp))
                }
            }
        }
        ShelfBoard(wood, seed = 7, modifier = Modifier.offset(y = (-4).dp))
    }
}

@Composable
private fun ProgressLine(progress: Float) {
    LinearProgressIndicator(
        progress = { progress },
        strokeCap = StrokeCap.Round,
        drawStopIndicator = {},
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().height(3.dp),
    )
}

@Composable
private fun StatusChips(state: LibraryState, onStatus: (ReadingStatus?) -> Unit) {
    val options = listOf<Pair<ReadingStatus?, String>>(null to "Все") + ReadingStatus.entries.map { it to it.label }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
        items(options, key = { it.second }) { (status, label) ->
            val count = if (status == null) state.totalBooks else state.statusCounts[status] ?: 0
            FilterChip(
                selected = state.status == status,
                onClick = { onStatus(status) },
                label = { Text("$label  $count") },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(book: BookEntity, showSeriesNumber: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book.title, book.authors, book.coverPath?.let(::File), Modifier.width(52.dp).aspectRatio(2f / 3f), compact = true, elevation = 3.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            val prefix = if (showSeriesNumber) book.seriesIndex?.let { "${formatIndex(it)}. " }.orEmpty() else ""
            Text(prefix + book.title, style = BookTitleStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                book.authors.takeIf { it.isNotBlank() },
                book.series?.let { s -> s + (book.seriesIndex?.let { " #${formatIndex(it)}" } ?: "") }.takeIf { !showSeriesNumber },
            )
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatBadge(book.format)
                if (book.progress > 0f) {
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.width(72.dp)) { ProgressLine(book.progress) }
                    Spacer(Modifier.width(6.dp))
                    Text("${(book.progress * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun FormatBadge(format: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
        Text(
            format.replace('_', '.').uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

fun formatIndex(index: Float): String =
    if (index == index.toInt().toFloat()) index.toInt().toString() else index.toString()

@Composable
private fun EmptyLibrary(onAddFolder: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A small fan of books; the middle one sits on top.
        Row(horizontalArrangement = Arrangement.spacedBy((-12).dp), verticalAlignment = Alignment.Bottom) {
            BookCover("Мастер и Маргарита", "Булгаков", null, Modifier.width(72.dp).aspectRatio(2f / 3f).rotate(-8f), compact = true)
            BookCover("Война и мир", "Толстой", null, Modifier.zIndex(1f).width(84.dp).aspectRatio(2f / 3f), compact = true, elevation = 12.dp)
            BookCover("Дюна", "Херберт", null, Modifier.width(72.dp).aspectRatio(2f / 3f).rotate(8f), compact = true)
        }
        Spacer(Modifier.height(28.dp))
        Text("Полка пока пуста", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Покажите папку с книгами, и Корешок найдёт в ней EPUB, FB2, PDF, TXT и комиксы. Или найдите книгу во вкладке «Поиск».",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddFolder) {
            Icon(Icons.Default.CreateNewFolder, null)
            Spacer(Modifier.size(8.dp))
            Text("Выбрать папку")
        }
    }
}

/** Sorting, grouping and how the shelf looks, in one sheet instead of three menus. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArrangeSheet(
    sort: SortOrder,
    descending: Boolean,
    groupBy: GroupBy,
    onSort: (SortOrder) -> Unit,
    onGroup: (GroupBy) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Сортировка", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            SortOrder.entries.forEach { order ->
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onSort(order) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = order == sort, onClick = { onSort(order) })
                    Text(order.label, modifier = Modifier.weight(1f))
                    if (order == sort) {
                        Icon(
                            if (descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            if (descending) "По убыванию" else "По возрастанию",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Text(
                "Повторное касание меняет направление.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 16.dp),
            )
            Text("Группы", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GroupBy.entries.forEach { group ->
                    FilterChip(
                        selected = group == groupBy,
                        onClick = { onGroup(group) },
                        label = { Text(group.label) },
                        shape = CircleShape,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailsSheet(book: BookEntity, onRead: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        BookDetails(book, onRead)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookDetails(book: BookEntity, onRead: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row {
            BookCover(book.title, book.authors, book.coverPath?.let(::File), Modifier.width(110.dp).aspectRatio(2f / 3f), elevation = 10.dp)
            Spacer(Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.headlineSmall)
                if (book.authors.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(book.authors, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                }
                book.series?.let { series ->
                    Spacer(Modifier.height(8.dp))
                    val number = book.seriesIndex?.let { " · книга ${formatIndex(it)}" }.orEmpty()
                    Text("$series$number", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FormatBadge(book.format)
                    val facts = listOfNotNull(book.year?.toString(), book.language, book.publisher)
                    if (facts.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            facts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRead, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null)
            Spacer(Modifier.width(10.dp))
            Text(if (book.progress > 0f) "Продолжить · ${(book.progress * 100).roundToInt()}%" else "Читать")
        }
        if (book.genres.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                book.genres.distinct().take(6).forEach { genre ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                        Text(
                            Fb2Genres.displayName(genre),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        book.description?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun UpdateBanner(state: UpdateState, onUpdate: () -> Unit) {
    val (title, action, progress) = when (state) {
        UpdateState.None -> return
        is UpdateState.Available -> Triple("Вышла сборка ${state.update.build}", "Обновить", null)
        is UpdateState.Downloading -> Triple("Скачиваю сборку ${state.update.build}…", null, state.progress)
        is UpdateState.Ready -> Triple("Сборка ${state.update.build} скачана", "Установить", null)
        is UpdateState.Failed -> Triple("Не удалось скачать: ${state.message}", "Ещё раз", null)
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, null)
                Spacer(Modifier.size(12.dp))
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (action != null) TextButton(onClick = onUpdate) { Text(action) }
            }
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 6.dp))
            }
        }
    }
}
