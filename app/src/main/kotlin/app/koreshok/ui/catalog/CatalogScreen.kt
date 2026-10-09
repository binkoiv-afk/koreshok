package app.koreshok.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import app.koreshok.core.opds.OpdsPresets
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

/** The libraries tab: the list of catalogs, and inside one of them its pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onRead: (uri: String) -> Unit,
    viewModel: CatalogViewModel = viewModel(factory = CatalogViewModel.Factory),
) {
    val catalogs by viewModel.catalogs.collectAsStateWithLifecycle()
    val stack by viewModel.stack.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val top = stack.lastOrNull()
    var showAdd by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CatalogLink?>(null) }

    BackHandler(enabled = top != null) { viewModel.back() }

    if (top == null) {
        CatalogList(
            catalogs = catalogs,
            onOpen = { viewModel.open(it.url, it.title) },
            onRemove = { removing = it },
            onAdd = { showAdd = true },
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(top.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                            val root = stack.first().title
                            if (stack.size > 1) {
                                Text(root, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                        }
                    },
                    actions = {
                        if (stack.size > 1) {
                            TextButton(onClick = { viewModel.closeAll() }) { Text("Все библиотеки") }
                        }
                    },
                )
            },
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
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

/** Monogram colors for catalog cards, so each library is recognizable at a glance. */
private val MonogramColors = listOf(
    Color(0xFF1E3A34), Color(0xFF8C3B2A), Color(0xFF1F3550), Color(0xFF5B2333), Color(0xFF7A4A1E), Color(0xFF4A3B5C),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CatalogList(
    catalogs: List<CatalogLink>,
    onOpen: (CatalogLink) -> Unit,
    onRemove: (CatalogLink) -> Unit,
    onAdd: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 16.dp, bottom = 8.dp)) {
                Text("Библиотеки", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Листайте каталоги и скачивайте книги прямо на полку",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(catalogs, key = { _, it -> it.url + it.id }) { index, catalog ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .combinedClickable(
                        onClick = { onOpen(catalog) },
                        onLongClick = { if (catalog.id != null) onRemove(catalog) },
                    ),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(MonogramColors[index % MonogramColors.size], RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            catalog.title.take(1).uppercase(),
                            color = Color(0xFFF3E3C3),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(catalog.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            OpdsPresets.description(catalog.url) ?: catalog.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Добавить свой каталог")
            }
        }
        item {
            Text(
                "Подойдёт любой каталог в формате OPDS. Свой каталог убирается долгим нажатием.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
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
                    CatalogBook(entry, downloads, onDownload, onRead)
                } else {
                    NavigationEntry(entry) { onOpen(entry) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
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
    TextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Искать в этой библиотеке") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
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
