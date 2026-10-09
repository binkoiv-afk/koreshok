package app.koreshok.ui.pages

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.koreshok.data.AnnotationKind
import app.koreshok.ui.reader.ReaderTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Reader for books made of fixed pages (PDF, CBZ): a zoomable vertical strip of pages. */
@Composable
fun PageReaderScreen(uri: String, onBack: () -> Unit) {
    val viewModel: PageReaderViewModel = viewModel(key = "pages:$uri", factory = PageReaderViewModel.factory(uri))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val theme = prefs?.theme ?: ReaderTheme.DAY

    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(theme.background)) {
        when (val current = state) {
            is PageBookState.Failed -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Не получилось открыть книгу", color = theme.text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(current.message, color = theme.secondary)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onBack) { Text("Назад") }
            }
            is PageBookState.Ready -> PageBook(current, theme, viewModel, onBack)
            PageBookState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = theme.accent)
        }
    }
}

@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
@Composable
private fun PageBook(book: PageBookState.Ready, theme: ReaderTheme, viewModel: PageReaderViewModel, onBack: () -> Unit) {
    val source = book.source
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = book.startPage)
    val scope = rememberCoroutineScope()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    var controls by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    // The page that fills most of the screen.
    val page by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) {
                listState.firstVisibleItemIndex
            } else {
                visible.maxByOrNull { item ->
                    val top = maxOf(item.offset, info.viewportStartOffset)
                    val bottom = minOf(item.offset + item.size, info.viewportEndOffset)
                    bottom - top
                }!!.index
            }
        }
    }
    // Paper pages are inverted at night; comic art would look wrong inverted, so it stays as is.
    val inverted = source.isPaper && (theme == ReaderTheme.NIGHT || theme == ReaderTheme.BLACK)
    val filter = remember(inverted) { if (inverted) ColorFilter.colorMatrix(INVERT) else null }

    LaunchedEffect(Unit) {
        snapshotFlow { page }.distinctUntilChanged().debounce(400).collect { viewModel.onPageShown(it) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth
        // Zoomed pages are redrawn sharper, up to twice the screen width.
        val quality = if (scale > 1.4f) 2f else 1f
        val renderWidth = (widthPx * quality).roundToInt()
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Two fingers zoom and pan sideways; one finger is left to the list for scrolling.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            // The Initial pass runs before the list, so a pinch never scrolls it.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                val maxOffset = widthPx * (newScale - 1) / 2
                                offsetX = (offsetX + event.calculatePan().x).coerceIn(-maxOffset, maxOffset)
                                scale = newScale
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                            } else {
                                scale = 2f
                                offsetX = ((widthPx / 2f) - it.x).coerceIn(-widthPx / 2f, widthPx / 2f)
                            }
                        },
                        onTap = { controls = !controls },
                    )
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                },
        ) {
            items(source.pageCount) { index ->
                PageImage(source, index, renderWidth, filter, theme)
            }
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            val marked = bookmarks.any { it.kind == AnnotationKind.BOOKMARK && it.chapter == page }
            Surface(color = theme.background.copy(alpha = 0.96f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = theme.text)
                    }
                    Text(
                        book.title,
                        color = theme.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showBookmarks = true }) {
                        Icon(Icons.Filled.CollectionsBookmark, "Закладки", tint = theme.text)
                    }
                    IconButton(onClick = { viewModel.toggleBookmark(page) }) {
                        Icon(
                            if (marked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            if (marked) "Убрать закладку" else "Закладка",
                            tint = if (marked) theme.accent else theme.text,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            val shown = dragging?.roundToInt() ?: page
            Surface(color = theme.background.copy(alpha = 0.96f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        "Страница ${shown + 1} из ${source.pageCount}",
                        color = theme.secondary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (source.pageCount > 1) {
                        Slider(
                            value = dragging ?: page.toFloat(),
                            onValueChange = { dragging = it },
                            onValueChangeFinished = {
                                val target = dragging?.roundToInt() ?: page
                                dragging = null
                                scope.launch { listState.scrollToItem(target) }
                            },
                            valueRange = 0f..(source.pageCount - 1).toFloat(),
                            colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent),
                        )
                    }
                }
            }
        }
    }

    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
            val marks = bookmarks.filter { it.kind == AnnotationKind.BOOKMARK }.sortedBy { it.chapter }
            Text("Закладки", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (marks.isEmpty()) {
                Text(
                    "Пока пусто. Значок закладки вверху отмечает текущую страницу.",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn {
                items(marks, key = { it.id }) { mark ->
                    ListItem(
                        headlineContent = { Text("Страница ${mark.chapter + 1}") },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteBookmark(mark.id) }) { Icon(Icons.Filled.Close, "Удалить") }
                        },
                        modifier = Modifier.clickable {
                            showBookmarks = false
                            scope.launch { listState.scrollToItem(mark.chapter.coerceIn(0, source.pageCount - 1)) }
                        },
                    )
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PageImage(source: PageSource, index: Int, width: Int, filter: ColorFilter?, theme: ReaderTheme) {
    val bitmap by produceState<Bitmap?>(null, source, index, width) {
        value = runCatching { source.render(index, width.coerceIn(1, MAX_RENDER_WIDTH)) }.getOrNull()
    }
    val current = bitmap
    if (current == null) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f / source.aspect(index)).background(theme.secondary.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("${index + 1}", color = theme.secondary)
        }
    } else {
        val image = remember(current) { current.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = "Страница ${index + 1}",
            contentScale = ContentScale.FillWidth,
            colorFilter = filter,
            modifier = Modifier.fillMaxWidth().aspectRatio(current.width.toFloat() / current.height),
        )
    }
}

private const val MAX_RENDER_WIDTH = 2600

/** Turns white paper black for night themes while keeping hues recognizable. */
private val INVERT = ColorMatrix(
    floatArrayOf(
        -0.85f, 0f, 0f, 0f, 235f,
        0f, -0.85f, 0f, 0f, 230f,
        0f, 0f, -0.85f, 0f, 220f,
        0f, 0f, 0f, 1f, 0f,
    ),
)
