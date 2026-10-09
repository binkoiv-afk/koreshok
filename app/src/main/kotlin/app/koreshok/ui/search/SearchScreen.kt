package app.koreshok.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.koreshok.core.opds.OpdsDownload
import app.koreshok.core.opds.OpdsEntry
import app.koreshok.data.BookEntity
import app.koreshok.ui.catalog.CatalogBook
import app.koreshok.ui.catalog.CatalogLink
import app.koreshok.ui.catalog.DownloadState
import app.koreshok.ui.components.BookCover
import app.koreshok.ui.components.CoverCaption
import app.koreshok.ui.library.openBook
import app.koreshok.ui.theme.BookTitleStyle
import java.io.File

/** How many books one catalog shows before "Все результаты". */
private const val PREVIEW = 6

@Composable
fun SearchScreen(
    onRead: (String) -> Unit,
    onOpenFeed: (url: String, title: String) -> Unit,
    viewModel: SearchViewModel = viewModel(factory = SearchViewModel.Factory),
) {
    val context = LocalContext.current
    val query by viewModel.query.collectAsStateWithLifecycle()
    val submitted by viewModel.submitted.collectAsStateWithLifecycle()
    val catalogs by viewModel.catalogs.collectAsStateWithLifecycle()
    val disabled by viewModel.disabled.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val local by viewModel.local.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    SearchContent(
        query = query,
        submitted = submitted,
        catalogs = catalogs,
        disabled = disabled,
        sections = sections,
        local = local,
        downloads = downloads,
        onQuery = viewModel::setQuery,
        onSubmit = viewModel::submit,
        onToggleCatalog = viewModel::toggleCatalog,
        onOpenLocal = { openBook(context, it, onRead) },
        onDownload = viewModel::download,
        onRead = onRead,
        onOpenFeed = onOpenFeed,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchContent(
    query: String,
    submitted: String,
    catalogs: List<CatalogLink>,
    disabled: Set<String>,
    sections: List<SearchSection>,
    local: List<BookEntity>,
    downloads: Map<String, DownloadState>,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onToggleCatalog: (String) -> Unit,
    onOpenLocal: (BookEntity) -> Unit,
    onDownload: (OpdsEntry, OpdsDownload) -> Unit,
    onRead: (String) -> Unit,
    onOpenFeed: (String, String) -> Unit,
) {
    val focus = LocalFocusManager.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp).padding(top = 16.dp)) {
                Text("Поиск", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = { Text("Книга или автор") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Очистить") }
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focus.clearFocus()
                        onSubmit()
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Где искать",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    catalogs.forEach { catalog ->
                        FilterChip(
                            selected = catalog.url !in disabled,
                            onClick = { onToggleCatalog(catalog.url) },
                            label = { Text(catalog.title) },
                            shape = CircleShape,
                        )
                    }
                }
            }
        }

        if (query.isBlank()) {
            item(key = "hint") { SearchHint() }
            return@LazyColumn
        }

        if (local.isNotEmpty()) {
            item(key = "local") {
                Column(Modifier.padding(top = 20.dp)) {
                    SectionTitle("На полке", "${local.size}")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(local.take(20), key = { it.uri }) { book ->
                            Column(Modifier.width(92.dp).clickable { onOpenLocal(book) }) {
                                BookCover(book.title, book.authors, book.coverPath?.let(::File), Modifier.fillMaxWidth().aspectRatio(2f / 3f), compact = true, elevation = 4.dp)
                                Spacer(Modifier.height(6.dp))
                                CoverCaption(book.title, book.authors, titleStyle = BookTitleStyle.copy(fontSize = BookTitleStyle.fontSize * 0.9f), authorColor = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (submitted != query.trim()) {
            item(key = "go") {
                Surface(
                    onClick = {
                        focus.clearFocus()
                        onSubmit()
                    },
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Искать «${query.trim()}» в библиотеках",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                }
            }
        }

        sections.forEach { section ->
            item(key = "section:${section.catalog.url}") {
                Column(Modifier.padding(top = 20.dp)) {
                    val count = (section.state as? CatalogSearch.Found)?.results?.books?.size
                    SectionTitle(section.catalog.title, count?.toString(), loading = section.state is CatalogSearch.Loading)
                }
            }
            when (val state = section.state) {
                CatalogSearch.Loading -> Unit
                is CatalogSearch.Failed -> item(key = "failed:${section.catalog.url}") {
                    Text(
                        "Не ответил: ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                is CatalogSearch.Found -> {
                    val results = state.results
                    if (results.books.isEmpty() && results.folders.isEmpty()) {
                        item(key = "empty:${section.catalog.url}") {
                            Text(
                                "Ничего не нашлось",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }
                    if (results.folders.isNotEmpty()) {
                        item(key = "folders:${section.catalog.url}") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 4.dp),
                            ) {
                                items(results.folders.take(12)) { folder ->
                                    Surface(
                                        onClick = { folder.navigationUrl?.let { onOpenFeed(it, folder.title) } },
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ) {
                                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(folder.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                            Spacer(Modifier.width(4.dp))
                                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val shown = results.books.take(PREVIEW)
                    items(shown, key = { "${section.catalog.url}:${it.id}:${it.title}" }) { entry ->
                        CatalogBook(entry, downloads, onDownload, onRead)
                    }
                    if (results.books.size > PREVIEW || results.moreUrl != null) {
                        item(key = "more:${section.catalog.url}") {
                            TextButton(
                                onClick = { onOpenFeed(searchFeedUrl(section, submitted), "${section.catalog.title}: $submitted") },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                Text("Все результаты в «${section.catalog.title}»")
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            }
                        }
                    }
                }
            }
            item(key = "divider:${section.catalog.url}") {
                HorizontalDivider(Modifier.padding(top = 8.dp, start = 20.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** The feed with every result, opened in the catalog browser. */
private fun searchFeedUrl(section: SearchSection, @Suppress("UNUSED_PARAMETER") query: String): String =
    (section.state as? CatalogSearch.Found)?.results?.feedUrl ?: section.catalog.url

@Composable
private fun SectionTitle(title: String, count: String?, loading: Boolean = false) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun SearchHint() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Одна строка на все библиотеки", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Корешок ищет сразу на вашей полке и во всех отмеченных каталогах. Найденную книгу можно скачать и открыть одним касанием.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
