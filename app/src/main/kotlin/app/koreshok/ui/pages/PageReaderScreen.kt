package app.koreshok.ui.pages

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.runtime.rememberUpdatedState
import app.koreshok.ui.reader.ReaderPrefs
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
            is PageBookState.Ready -> {
                val currentPrefs = prefs ?: ReaderPrefs()
                PageBook(current, currentPrefs, viewModel, onBack)
            }
            PageBookState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = theme.accent)
        }
    }
}

@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
@Composable
private fun PageBook(book: PageBookState.Ready, prefs: ReaderPrefs, viewModel: PageReaderViewModel, onBack: () -> Unit) {
    val theme = prefs.theme
    val source = book.source
    val crop = prefs.cropMargins && source.isPaper
    val paged = prefs.pagedPages
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = book.startPage)
    val pagerState = rememberPagerState(initialPage = book.startPage) { source.pageCount }
    val scope = rememberCoroutineScope()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    var controls by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // In the strip, the page that fills most of the screen; in paged mode, the page on screen.
    val stripPage by remember {
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
    val page = if (paged) pagerState.currentPage else stripPage
    fun goTo(target: Int) {
        val index = target.coerceIn(0, source.pageCount - 1)
        scope.launch { if (paged) pagerState.scrollToPage(index) else listState.scrollToItem(index) }
    }
    // Switching between strip and pages keeps the place.
    LaunchedEffect(paged) {
        if (paged) pagerState.scrollToPage(stripPage) else listState.scrollToItem(pagerState.currentPage)
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }
    // Paper pages are inverted at night; comic art would look wrong inverted, so it stays as is.
    val inverted = source.isPaper && (theme == ReaderTheme.NIGHT || theme == ReaderTheme.BLACK)
    val filter = remember(inverted) { if (inverted) ColorFilter.colorMatrix(INVERT) else null }

    val currentPage by rememberUpdatedState(page)
    LaunchedEffect(Unit) {
        snapshotFlow { currentPage }.distinctUntilChanged().debounce(400).collect { viewModel.onPageShown(it) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        // Zoomed pages are redrawn sharper, up to twice the screen width.
        val quality = if (scale > 1.4f) 2f else 1f
        val renderWidth = (widthPx * quality).roundToInt()
        val gestures = Modifier
            .pointerInput(paged) {
                // Two fingers zoom and pan; one finger is left to the list or pager.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        // The Initial pass runs before the list, so a pinch never scrolls it.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val maxX = widthPx * (newScale - 1) / 2
                            val maxY = if (paged) heightPx * (newScale - 1) / 2 else 0f
                            val pan = event.calculatePan()
                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            scale = newScale
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(paged) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2f
                            offsetX = ((widthPx / 2f) - it.x).coerceIn(-widthPx / 2f, widthPx / 2f)
                            if (paged) offsetY = ((heightPx / 2f) - it.y).coerceIn(-heightPx / 2f, heightPx / 2f)
                        }
                    },
                    onTap = {
                        val third = widthPx / 3f
                        when {
                            paged && scale == 1f && it.x < third -> goTo(page - 1)
                            paged && scale == 1f && it.x > third * 2 -> goTo(page + 1)
                            else -> controls = !controls
                        }
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            }

        if (paged) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = scale == 1f,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize().then(gestures),
            ) { index ->
                Box(Modifier.fillMaxSize().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    PageImage(source, index, renderWidth, crop, filter, theme, fit = true)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize().then(gestures),
            ) {
                items(source.pageCount) { index ->
                    PageImage(source, index, renderWidth, crop, filter, theme, fit = false)
                }
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
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Tune, "Вид страниц", tint = theme.text)
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
                                goTo(target)
                            },
                            valueRange = 0f..(source.pageCount - 1).toFloat(),
                            colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent),
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            PageSettings(prefs, source.isPaper) { transform -> viewModel.updatePrefs(transform) }
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
                            goTo(mark.chapter)
                        },
                    )
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** How fixed pages are shown: strip or one at a time, margins, theme. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageSettings(prefs: ReaderPrefs, isPaper: Boolean, update: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Вид страниц", style = MaterialTheme.typography.titleLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "Лентой", true to "По одной").forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = prefs.pagedPages == value,
                    onClick = { update { it.copy(pagedPages = value) } },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(label, maxLines = 1) }
            }
        }
        if (isPaper) {
            Row(
                Modifier.fillMaxWidth().clickable { update { it.copy(cropMargins = !it.cropMargins) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Обрезать поля", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Белые края страницы убираются, текст становится крупнее",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = prefs.cropMargins, onCheckedChange = { v -> update { it.copy(cropMargins = v) } })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReaderTheme.entries.forEach { theme ->
                val selected = theme == prefs.theme
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(theme.background, MaterialTheme.shapes.medium)
                        .border(
                            if (selected) 2.5.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            MaterialTheme.shapes.medium,
                        )
                        .clickable { update { it.copy(theme = theme) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(theme.label, color = theme.text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PageImage(source: PageSource, index: Int, width: Int, crop: Boolean, filter: ColorFilter?, theme: ReaderTheme, fit: Boolean) {
    val bitmap by produceState<Bitmap?>(null, source, index, width, crop) {
        value = runCatching { source.render(index, width.coerceIn(1, MAX_RENDER_WIDTH), crop) }.getOrNull()
    }
    val current = bitmap
    // In paged mode a page fits the screen whole; in the strip it fills the width.
    val sizing = if (fit) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
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
            contentScale = if (fit) ContentScale.Fit else ContentScale.FillWidth,
            colorFilter = filter,
            modifier = if (fit) sizing else sizing.aspectRatio(current.width.toFloat() / current.height),
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
