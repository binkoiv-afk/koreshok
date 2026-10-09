package app.koreshok.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.koreshok.core.opds.OpdsEntry
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onBack: () -> Unit,
    onRead: (uri: String) -> Unit,
    viewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.Factory),
) {
    val catalogs by viewModel.catalogs.collectAsStateWithLifecycle()
    val stack by viewModel.stack.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val top = stack.lastOrNull()
    var showAdd by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CatalogLink?>(null) }

    BackHandler { if (!viewModel.back()) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(top?.title ?: "Каталоги", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.back()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
            )
        },
        floatingActionButton = {
            if (top == null) {
                FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Добавить каталог") }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (top == null) {
                CatalogList(
                    catalogs = catalogs,
                    onOpen = { viewModel.open(it.url, it.title) },
                    onRemove = { removing = it },
                )
            } else {
                FeedView(
                    page = top,
                    downloads = downloads,
                    onOpen = { entry -> entry.navigationUrl?.let { viewModel.open(it, entry.title) } },
                    onDownload = viewModel::download,
                    onRead = onRead,
                    onSearch = viewModel::search,
                    onMore = viewModel::loadMore,
                    onRetry = viewModel::retry,
                )
            }
        }
    }

    if (showAdd) {
        AddCatalogDialog(
            onAdd = { title, url ->
                viewModel.addCatalog(title, url)
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }
    removing?.let { catalog ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Убрать каталог?") },
            text = { Text(catalog.title) },
            confirmButton = {
                TextButton(onClick = {
                    catalog.id?.let(viewModel::removeCatalog)
                    removing = null
                }) { Text("Убрать") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatalogList(catalogs: List<CatalogLink>, onOpen: (CatalogLink) -> Unit, onRemove: (CatalogLink) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(catalogs, key = { it.url + it.id }) { catalog ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onOpen(catalog) },
                        onLongClick = { if (catalog.id != null) onRemove(catalog) },
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Public, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(catalog.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        catalog.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
        item {
            Text(
                "Каталоги в формате OPDS есть у многих библиотек. Добавьте свой кнопкой «+», убрать добавленный можно долгим нажатием.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

@Composable
private fun FeedView(
    page: FeedPage,
    downloads: Map<String, DownloadState>,
    onOpen: (OpdsEntry) -> Unit,
    onDownload: (OpdsEntry, app.koreshok.core.opds.OpdsDownload) -> Unit,
    onRead: (String) -> Unit,
    onSearch: (String) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        page.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        page.error != null && page.entries.isEmpty() -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Каталог не открылся", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(page.error, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRetry) { Text("Повторить") }
        }
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (page.searchTemplate != null) {
                item(key = "search") { SearchField(onSearch) }
            }
            if (page.entries.isEmpty()) {
                item {
                    Text("Здесь пусто", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Catalogs repeat entries across pages, so ids are not safe as keys.
            items(page.entries) { entry ->
                if (entry.isBook) {
                    BookEntry(entry, downloads, onDownload, onRead)
                } else {
                    NavigationEntry(entry) { onOpen(entry) }
                }
                HorizontalDivider()
            }
            if (page.nextUrl != null) {
                item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (page.loadingMore) CircularProgressIndicator() else OutlinedButton(onClick = onMore) { Text("Показать ещё") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(onSearch: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Название или автор") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun NavigationEntry(entry: OpdsEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.navigationUrl != null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge)
            entry.summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.navigationUrl != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookEntry(
    entry: OpdsEntry,
    downloads: Map<String, DownloadState>,
    onDownload: (OpdsEntry, app.koreshok.core.opds.OpdsDownload) -> Unit,
    onRead: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val shape = RoundedCornerShape(4.dp)
        Box(
            Modifier
                .size(width = 64.dp, height = 96.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            entry.coverUrl?.let { url ->
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall)
            if (entry.authors.isNotEmpty()) {
                Text(
                    entry.authors.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).clickable { expanded = !expanded },
                )
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                entry.downloads.forEach { file ->
                    when (val state = downloads[file.url]) {
                        is DownloadState.Running -> AssistChip(
                            onClick = {},
                            label = { Text("${file.label} ${(state.progress * 100).toInt()}%") },
                            leadingIcon = { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) },
                        )
                        is DownloadState.Done -> AssistChip(
                            onClick = { state.bookUri?.let(onRead) },
                            label = { Text(if (state.bookUri != null) "Читать ${file.label}" else "${file.label} скачан") },
                            leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) },
                        )
                        is DownloadState.Failed -> AssistChip(
                            onClick = { onDownload(entry, file) },
                            label = { Text("${file.label}: ошибка, ещё раз") },
                        )
                        null -> AssistChip(onClick = { onDownload(entry, file) }, label = { Text(file.label) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCatalogDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый каталог") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Адрес OPDS") },
                    placeholder = { Text("https://…/opds") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(title, url) }, enabled = url.isNotBlank()) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
