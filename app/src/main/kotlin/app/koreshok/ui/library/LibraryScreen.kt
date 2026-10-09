package app.koreshok.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.koreshok.core.document.Documents
import app.koreshok.core.format.Fb2Genres
import app.koreshok.core.model.BookFormat
import app.koreshok.core.library.GroupBy
import app.koreshok.core.library.SortOrder
import app.koreshok.data.BookEntity
import app.koreshok.update.Updates
import app.koreshok.ui.pages.PageSource
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onRead: (uri: String) -> Unit,
    onCatalogs: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val context = LocalContext.current
    val open: (BookEntity) -> Unit = { book ->
        if (BookFormat.valueOf(book.format).let { it in Documents.supported || it in PageSource.formats }) {
            onRead(book.uri)
        } else {
            // Until their engines land, other formats open in whatever app the phone has for them.
            val parsed = Uri.parse(book.uri)
            // Downloaded books are plain files; other apps can only read them through the FileProvider.
            val shared = if (parsed.scheme == "file") {
                FileProvider.getUriForFile(context, "${context.packageName}.files", File(parsed.path.orEmpty()))
            } else {
                parsed
            }
            val intent = Intent(Intent.ACTION_VIEW)
                .setData(shared)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(intent, book.title)) }
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val checkMessage by viewModel.checkMessage.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<BookEntity?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text("Название, автор, серия") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Column {
                            Text("Библиотека")
                            if (state.totalBooks > 0) {
                                Text(
                                    booksCount(state.totalBooks),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) viewModel.setQuery("")
                        searching = !searching
                    }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Поиск")
                    }
                    IconButton(onClick = onCatalogs) { Icon(Icons.Default.Public, "Каталоги") }
                    SortMenu(state.prefs.sort, state.prefs.descending, viewModel::setSort)
                    GroupMenu(state.prefs.groupBy, viewModel::setGroupBy)
                    IconButton(onClick = { showFolders = true }) {
                        Icon(Icons.Default.Folder, "Папки")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            UpdateBanner(update) { viewModel.downloadUpdate(context) }
            state.scan?.let { scan ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Сканирую «${scan.folder}»: ${scan.processed} из ${scan.found}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (scan.found > 0) {
                        LinearProgressIndicator(
                            progress = { scan.processed.toFloat() / scan.found },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            when {
                !state.loaded -> Unit
                state.totalBooks == 0 && state.scan == null -> EmptyLibrary { pickFolder.launch(null) }
                else -> Shelf(state, onOpen = open, onDetails = { selected = it })
            }
        }
    }

    if (showFolders) {
        FoldersSheet(
            state = state,
            onAdd = { pickFolder.launch(null) },
            onRemove = viewModel::removeFolder,
            onRescan = viewModel::rescan,
            checkMessage = checkMessage,
            onCheckUpdate = { viewModel.checkForUpdate() },
            onDismiss = { showFolders = false },
        )
    }
    selected?.let { book ->
        BookDetailsSheet(book, onRead = { selected = null; open(book) }) { selected = null }
    }
}

private fun booksCount(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "книг"
        n % 10 == 1 -> "книга"
        n % 10 in 2..4 -> "книги"
        else -> "книг"
    }
    return "$n $word"
}

@Composable
private fun SortMenu(current: SortOrder, descending: Boolean, onSelect: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Сортировка") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    onClick = { onSelect(order); open = false },
                    trailingIcon = {
                        if (order == current) {
                            Icon(if (descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GroupMenu(current: GroupBy, onSelect: (GroupBy) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.ViewModule, "Группировка") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GroupBy.entries.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.label) },
                    onClick = { onSelect(group); open = false },
                    trailingIcon = { if (group == current) Icon(Icons.Default.Check, null) },
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onAddFolder: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Здесь пока пусто", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Выберите папку с книгами. Корешок найдёт EPUB, FB2, PDF, DjVu, MOBI и другие файлы во всех вложенных папках.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onAddFolder) {
            Icon(Icons.Default.CreateNewFolder, null)
            Spacer(Modifier.size(8.dp))
            Text("Добавить папку")
        }
    }
}

@Composable
private fun Shelf(state: LibraryState, onOpen: (BookEntity) -> Unit, onDetails: (BookEntity) -> Unit) {
    val nothingFound = state.groups.all { it.items.isEmpty() }
    if (nothingFound) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Ничего не найдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        state.groups.forEach { group ->
            if (group.title.isNotEmpty()) {
                item(key = "header:${group.title}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "${group.title} · ${group.items.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            // A book can sit in several genre groups, so keys include the group.
            items(group.items, key = { "${group.title}:${it.uri}" }) { book ->
                BookCard(
                    book,
                    showSeriesNumber = state.prefs.groupBy == GroupBy.SERIES,
                    onClick = { onOpen(book) },
                    onLongClick = { onDetails(book) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(book: BookEntity, showSeriesNumber: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Cover(book, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
        if (book.progress > 0f) {
            LinearProgressIndicator(
                progress = { book.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        val prefix = if (showSeriesNumber) book.seriesIndex?.let { "${formatIndex(it)}. " }.orEmpty() else ""
        Text(
            prefix + book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.authors.isNotBlank()) {
            Text(
                book.authors,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatIndex(index: Float): String =
    if (index == index.toInt().toFloat()) index.toInt().toString() else index.toString()

@Composable
private fun Cover(book: BookEntity, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    if (book.coverPath != null) {
        AsyncImage(
            model = File(book.coverPath),
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    } else {
        // Generated cover: title on a tinted card, so books without art are still easy to tell apart.
        Box(
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    book.format.replace('_', '.').lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoldersSheet(
    state: LibraryState,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onRescan: () -> Unit,
    checkMessage: String?,
    onCheckUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Папки с книгами", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if (state.folders.isEmpty()) {
                Text("Папок пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.folders.forEach { folder ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Folder, null)
                    Spacer(Modifier.size(12.dp))
                    Text(folder.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onRemove(folder.uri) }) { Text("Убрать") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAdd) {
                    Icon(Icons.Default.CreateNewFolder, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Добавить")
                }
                TextButton(onClick = onRescan, enabled = state.folders.isNotEmpty() && state.scan == null) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Обновить")
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Корешок, сборка ${Updates.currentBuild}", style = MaterialTheme.typography.bodyMedium)
                    checkMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = onCheckUpdate) { Text("Проверить обновления") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookDetailsSheet(book: BookEntity, onRead: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row {
                Cover(book, Modifier.size(width = 96.dp, height = 144.dp))
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge)
                    if (book.authors.isNotBlank()) {
                        Text(book.authors, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    book.series?.let { series ->
                        Spacer(Modifier.height(4.dp))
                        val number = book.seriesIndex?.let { " #${formatIndex(it)}" }.orEmpty()
                        Text("Серия: $series$number", style = MaterialTheme.typography.bodyMedium)
                    }
                    val facts = listOfNotNull(
                        book.year?.toString(),
                        book.language,
                        book.format.replace('_', '.').lowercase(),
                        book.publisher,
                    )
                    Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (book.genres.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    book.genres.take(3).forEach { genre ->
                        AssistChip(onClick = {}, label = { Text(Fb2Genres.displayName(genre)) })
                    }
                }
            }
            book.description?.let {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
                Text(if (book.progress > 0f) "Продолжить чтение" else "Читать")
            }
        }
    }
}

@Composable
private fun UpdateBanner(state: UpdateState, onUpdate: () -> Unit) {
    val (title, action, progress) = when (state) {
        UpdateState.None -> return
        is UpdateState.Available -> Triple("Вышла сборка ${state.update.build}", "Обновить", null)
        is UpdateState.Downloading -> Triple("Скачиваю сборку ${state.update.build}…", null, state.progress)
        is UpdateState.Ready -> Triple("Сборка ${state.update.build} скачана", "Установить", null)
        is UpdateState.Failed -> Triple("Не удалось скачать: ${state.message}", "Ещё раз", null)
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, null)
                Spacer(Modifier.size(12.dp))
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (action != null) TextButton(onClick = onUpdate) { Text(action) }
            }
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
