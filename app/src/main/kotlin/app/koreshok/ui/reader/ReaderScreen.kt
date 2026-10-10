package app.koreshok.ui.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.koreshok.core.document.Document
import app.koreshok.core.document.ImageBlock
import app.koreshok.core.document.TextBlock
import app.koreshok.data.AnnotationEntity
import app.koreshok.data.AnnotationKind
import app.koreshok.core.document.Position
import app.koreshok.core.document.noteBlocks
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(uri: String, onBack: () -> Unit) {
    val viewModel: ReaderViewModel = viewModel(key = uri, factory = ReaderViewModel.factory(uri))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val theme = prefs?.theme ?: ReaderTheme.DAY

    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(theme.background)) {
        val ready = state as? ReaderState.Ready
        val currentPrefs = prefs
        when {
            state is ReaderState.Failed -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Не получилось открыть книгу", color = theme.text, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text((state as ReaderState.Failed).message, color = theme.secondary)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onBack) { Text("Назад") }
            }
            ready != null && currentPrefs != null -> Reader(ready, currentPrefs, viewModel, onBack)
            else -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = theme.accent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Reader(ready: ReaderState.Ready, prefs: ReaderPrefs, viewModel: ReaderViewModel, onBack: () -> Unit) {
    val document = ready.document
    val theme = prefs.theme
    val location by viewModel.location.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val annotations by viewModel.annotations.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAnnotations by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var highlight by remember { mutableStateOf<HighlightDraft?>(null) }

    ImmersiveMode(hideBars = !showMenu)

    val onLink: LinkHandler = remember(viewModel) {
        { target, isNote ->
            when {
                isNote && document.anchors.containsKey(target) -> {
                    note = target
                }
                viewModel.followLink(target) -> Unit
                target.contains(':') -> {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                }
            }
            Unit
        }
    }

    // The reader sizes text itself, so the system font scale must not apply twice.
    val systemDensity = LocalDensity.current
    val density = remember(systemDensity.density) { Density(systemDensity.density, 1f) }

    CompositionLocalProvider(LocalDensity provides density) {
        val insets = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val headerPx = with(density) { 28.dp.roundToPx() }
            val footerPx = with(density) { 28.dp.roundToPx() }
            val marginPx = with(density) { prefs.margin.dp.roundToPx() }
            val layout = LayoutSpec(
                widthPx = constraints.maxWidth - marginPx * 2,
                heightPx = constraints.maxHeight - headerPx - footerPx,
                density = density.density,
                prefs = prefs,
                language = ready.language,
            )
            if (prefs.scroll) {
                ScrollReader(
                    document = document,
                    location = location,
                    layout = layout,
                    marginPx = marginPx,
                    annotations = annotations,
                    onPositionShown = viewModel::onPageShown,
                    onToggleBookmark = viewModel::toggleBookmark,
                    onLink = onLink,
                    onCenterTap = { showMenu = !showMenu },
                    onHighlight = { highlight = it },
                )
            } else {
                val measurer = rememberTextMeasurer(cacheSize = 0)
                val paginator = remember(measurer) { Paginator(measurer) }
                val chapter = location.position.chapter
                val pages by produceState<List<Page>?>(null, document, chapter, layout) {
                    value = null
                    value = withContext(Dispatchers.Default) { paginator.paginate(document, chapter, layout) }
                }

                // Right after a chapter change the previous chapter's pages are still around for a frame.
                val current = pages?.takeIf { it.first().start.chapter == chapter }
                if (current == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = theme.accent)
                } else {
                    key(chapter, location.jump, layout) {
                        ChapterPager(
                            document = document,
                            pages = current,
                            chapter = chapter,
                            location = location,
                            layout = layout,
                            marginPx = marginPx,
                            headerPx = headerPx,
                            footerPx = footerPx,
                            annotations = annotations,
                            viewModel = viewModel,
                            onLink = onLink,
                            onCenterTap = { showMenu = !showMenu },
                            onHighlight = { highlight = it },
                        )
                    }
                }
            }
        }
    }

    AnimatedVisibility(visible = showMenu, enter = fadeIn(), exit = fadeOut()) {
        ReaderMenu(
            document = document,
            position = location.position,
            theme = theme,
            canGoBack = history.isNotEmpty(),
            onBack = onBack,
            onToc = { showToc = true },
            onAnnotations = { showAnnotations = true },
            onSettings = { showSettings = true },
            onSeek = viewModel::seek,
            onPreviousChapter = { viewModel.previousChapter(toEnd = false) },
            onNextChapter = viewModel::nextChapter,
            onReturn = viewModel::goBack,
            onDismiss = { showMenu = false },
        )
    }

    if (showToc) {
        TocSheet(document, location.position) { target ->
            showToc = false
            showMenu = false
            target?.let { viewModel.goTo(it) }
        }
    }
    if (showAnnotations) {
        AnnotationsSheet(
            annotations = annotations,
            onOpen = { item ->
                showAnnotations = false
                showMenu = false
                viewModel.goTo(item.position())
            },
            onDelete = { viewModel.deleteAnnotation(it.id) },
            onExport = {
                val markdown = exportMarkdown(document.title, ready.authors, annotations)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/markdown")
                    .putExtra(Intent.EXTRA_SUBJECT, document.title)
                    .putExtra(Intent.EXTRA_TEXT, markdown)
                runCatching { context.startActivity(Intent.createChooser(send, "Экспорт заметок")) }
            },
            onDismiss = { showAnnotations = false },
        )
    }
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ReaderSettingsPanel(prefs) { transform -> viewModel.updatePrefs(transform) }
        }
    }
    highlight?.let { draft ->
        HighlightSheet(
            draft = draft,
            document = document,
            onSave = {
                viewModel.saveHighlight(it)
                highlight = null
            },
            onDelete = {
                viewModel.deleteAnnotation(draft.id)
                highlight = null
            },
            onDismiss = { highlight = null },
        )
    }
    note?.let { target ->
        ModalBottomSheet(onDismissRequest = { note = null }) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("Примечание", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                document.noteBlocks(target).forEach { block ->
                    Text(block.text, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                }
                TextButton(onClick = {
                    note = null
                    viewModel.followLink(target)
                }) { Text("Перейти к примечанию") }
            }
        }
    }
}

@Composable
private fun Header(title: String, theme: ReaderTheme, heightPx: Int, bookmarked: Boolean, onBookmark: () -> Unit) {
    ReaderHeader(title, theme, with(LocalDensity.current) { heightPx.toDp() }, bookmarked, onBookmark)
}

/** Chapter title over the text, and the page corner that holds the bookmark. */
@Composable
internal fun ReaderHeader(title: String, theme: ReaderTheme, height: androidx.compose.ui.unit.Dp, bookmarked: Boolean, onBookmark: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(height).padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = theme.secondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 24.dp),
            textAlign = TextAlign.Center,
        )
        // The page corner: one tap adds or removes a bookmark.
        Box(
            Modifier
                .size(height + 8.dp, height)
                .clickable(onClick = onBookmark),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = if (bookmarked) "Убрать закладку" else "Добавить закладку",
                tint = if (bookmarked) theme.accent else theme.secondary.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Where each text block of a page is on screen, so a press can be turned into a character offset. */
private class PageHits {
    class Entry(var slice: LayoutCoordinates? = null, var text: LayoutCoordinates? = null, var layout: TextLayoutResult? = null)

    val blocks = HashMap<Int, Entry>()

    fun entry(block: Int) = blocks.getOrPut(block) { Entry() }

    /** Block index and character offset under [point], given in [from]'s coordinates. */
    fun hit(from: LayoutCoordinates, point: Offset): Pair<Int, Int>? {
        for ((block, entry) in blocks) {
            val slice = entry.slice?.takeIf { it.isAttached } ?: continue
            val text = entry.text?.takeIf { it.isAttached } ?: continue
            val layout = entry.layout ?: continue
            val inSlice = slice.localPositionOf(from, point)
            if (inSlice.x < 0 || inSlice.y < 0 || inSlice.x > slice.size.width || inSlice.y > slice.size.height) continue
            val inText = text.localPositionOf(from, point)
            return block to layout.getOffsetForPosition(inText)
        }
        return null
    }
}

@Composable
private fun ChapterPager(
    document: Document,
    pages: List<Page>,
    chapter: Int,
    location: Location,
    layout: LayoutSpec,
    marginPx: Int,
    headerPx: Int,
    footerPx: Int,
    annotations: List<AnnotationEntity>,
    viewModel: ReaderViewModel,
    onLink: LinkHandler,
    onCenterTap: () -> Unit,
    onHighlight: (HighlightDraft) -> Unit,
) {
    val theme = layout.prefs.theme
    // Edge pages that stand for the neighbouring chapters; landing on one switches chapter.
    val hasPrevious = chapter > 0
    val hasNext = chapter < document.chapters.lastIndex
    val first = if (hasPrevious) 1 else 0
    val count = pages.size + first + if (hasNext) 1 else 0
    val initial = first + if (location.toChapterEnd) {
        pages.lastIndex
    } else {
        pages.indexOfLast { it.start <= location.position }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initial) { count }
    val scope = rememberCoroutineScope()
    val hits = remember { HashMap<Int, PageHits>() }
    // Plain holder, not state: it changes on every layout and nothing needs to recompose for it.
    val pagerCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }

    val chapterHighlights = remember(annotations, chapter) {
        annotations.filter { it.kind == AnnotationKind.HIGHLIGHT && it.chapter == chapter }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            when {
                hasPrevious && page == 0 -> viewModel.previousChapter(toEnd = true)
                hasNext && page == count - 1 -> viewModel.nextChapter()
                else -> pages.getOrNull(page - first)?.let { viewModel.onPageShown(it.start) }
            }
        }
    }

    fun hitAt(offset: Offset): Pair<TextBlock, Pair<Int, Int>>? {
        val from = pagerCoordinates[0] ?: return null
        val (block, charOffset) = hits[pagerState.currentPage]?.hit(from, offset) ?: return null
        val textBlock = document.chapters[chapter].blocks.getOrNull(block) as? TextBlock ?: return null
        return textBlock to (block to charOffset)
    }

    fun highlightAt(block: Int, offset: Int) = chapterHighlights.firstOrNull {
        it.block == block && offset >= it.start && offset < it.end
    }

    val pageIndex = (pagerState.currentPage - first).coerceIn(0, pages.lastIndex)
    val pageStart = pages[pageIndex].start
    val nextStart = pages.getOrNull(pageIndex + 1)?.start
    val bookmarked = annotations.any { it.kind == AnnotationKind.BOOKMARK && it.isOnPage(pageStart, nextStart) }

    val density = LocalDensity.current
    Column(Modifier.fillMaxSize()) {
        Header(
            title = document.chapters[chapter].title ?: document.title,
            theme = theme,
            heightPx = headerPx,
            bookmarked = bookmarked,
            onBookmark = { viewModel.toggleBookmark(pageStart, nextStart) },
        )
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { pagerCoordinates[0] = it }
                .pointerInput(count, chapterHighlights) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            val (block, at) = hitAt(offset) ?: return@detectTapGestures
                            val existing = highlightAt(at.first, at.second)
                            if (existing != null) {
                                onHighlight(existing.toDraft())
                            } else {
                                val range = sentenceAt(block, at.second, layout.language)
                                if (!range.isEmpty()) {
                                    onHighlight(
                                        HighlightDraft(
                                            chapter = chapter,
                                            block = at.first,
                                            start = range.first,
                                            end = range.last + 1,
                                            text = block.text.substring(range.first, range.last + 1),
                                        ),
                                    )
                                }
                            }
                        },
                        onTap = { offset ->
                            val existing = hitAt(offset)?.let { (_, at) -> highlightAt(at.first, at.second) }
                            val third = size.width / 3f
                            when {
                                existing != null -> onHighlight(existing.toDraft())
                                offset.x < third -> scope.launch {
                                    pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                                }
                                offset.x > third * 2 -> scope.launch {
                                    pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(count - 1))
                                }
                                else -> onCenterTap()
                            }
                        },
                    )
                },
        ) { index ->
            val page = pages.getOrNull(index - first)
            Box(Modifier.fillMaxSize().padding(horizontal = with(density) { marginPx.toDp() })) {
                if (page != null) {
                    val pageHits = remember(page) { PageHits().also { hits[index] = it } }
                    PageView(document, chapter, page, layout, chapterHighlights, pageHits, onLink)
                }
            }
        }
        Footer(
            left = "${pageIndex + 1} / ${pages.size}",
            right = "${(document.progressOf(pageStart) * 100).roundToInt()}%",
            theme = theme,
            heightPx = footerPx,
        )
    }
}

@Composable
private fun Footer(left: String, right: String, theme: ReaderTheme, heightPx: Int) {
    val height = with(LocalDensity.current) { heightPx.toDp() }
    Row(
        Modifier.fillMaxWidth().height(height).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(left, color = theme.secondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(right, color = theme.secondary, fontSize = 12.sp)
    }
}

@Composable
private fun PageView(
    document: Document,
    chapter: Int,
    page: Page,
    layout: LayoutSpec,
    highlights: List<AnnotationEntity>,
    hits: PageHits,
    onLink: LinkHandler,
) {
    val blocks = document.chapters[chapter].blocks
    val density = LocalDensity.current
    Column(Modifier.width(with(density) { layout.widthPx.toDp() })) {
        for (slice in page.slices) {
            val block = blocks[slice.block]
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { slice.height.toDp() })
                    .clipToBounds()
                    .onGloballyPositioned { hits.entry(slice.block).slice = it },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .offset { IntOffset(0, -slice.top) },
                ) {
                    if (block is ImageBlock) {
                        val size = Paginator.imageSize(document, block, layout)
                        if (size != null) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                AsyncImage(
                                    model = document.images[block.imageId],
                                    contentDescription = block.alt,
                                    modifier = Modifier.size(
                                        with(density) { size.width.toDp() },
                                        with(density) { size.height.toDp() },
                                    ),
                                )
                            }
                        }
                    } else {
                        val blockHighlights = highlights
                            .filter { it.block == slice.block }
                            .map { (it.start until it.end) to highlightColor(it.color) }
                        val spec = remember(block, layout, blockHighlights) {
                            BlockStyles.spec(block, layout, onLink, blockHighlights)
                        }
                        if (spec != null) {
                            Text(
                                text = spec.text,
                                style = spec.style,
                                onTextLayout = { hits.entry(slice.block).layout = it },
                                modifier = Modifier
                                    .padding(
                                        start = with(density) { spec.startPad.toDp() },
                                        end = with(density) { spec.endPad.toDp() },
                                        top = with(density) { spec.topPad.toDp() },
                                        bottom = with(density) { spec.bottomPad.toDp() },
                                    )
                                    .onGloballyPositioned { hits.entry(slice.block).text = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImmersiveMode(hideBars: Boolean) {
    val view = LocalView.current
    DisposableEffect(hideBars) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hideBars) controller?.hide(WindowInsetsCompat.Type.systemBars())
        else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun ReaderMenu(
    document: Document,
    position: Position,
    theme: ReaderTheme,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onToc: () -> Unit,
    onAnnotations: () -> Unit,
    onSettings: () -> Unit,
    onSeek: (Float) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onReturn: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
        Surface(
            color = theme.background,
            contentColor = theme.text,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "К библиотеке") }
                Text(
                    document.title,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onToc) { Icon(Icons.AutoMirrored.Filled.List, "Оглавление") }
                IconButton(onClick = onAnnotations) { Icon(Icons.Default.Bookmarks, "Закладки и цитаты") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.FormatSize, "Оформление") }
            }
        }
        Surface(
            color = theme.background,
            contentColor = theme.text,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (canGoBack) {
                    AssistChip(
                        onClick = onReturn,
                        label = { Text("Вернуться туда, где был") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, null) },
                    )
                }
                var dragging by remember { mutableStateOf(false) }
                var sliderValue by remember { mutableFloatStateOf(0f) }
                val progress = document.progressOf(position)
                val shown = if (dragging) sliderValue else progress
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPreviousChapter) { Icon(Icons.Default.SkipPrevious, "Предыдущая глава") }
                    Slider(
                        value = shown,
                        onValueChange = {
                            dragging = true
                            sliderValue = it
                        },
                        onValueChangeFinished = {
                            dragging = false
                            onSeek(sliderValue)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onNextChapter) { Icon(Icons.Default.SkipNext, "Следующая глава") }
                }
                val chapterAtSlider = if (dragging) document.positionAt(sliderValue).chapter else position.chapter
                Text(
                    "${(shown * 100).roundToInt()}% · ${document.chapters[chapterAtSlider].title ?: "Глава ${chapterAtSlider + 1}"}",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                // Labelled, not just icons up top: this is where people look for fonts and themes.
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MenuAction(Icons.AutoMirrored.Filled.List, "Оглавление", onToc)
                    MenuAction(Icons.Default.Bookmarks, "Закладки", onAnnotations)
                    MenuAction(Icons.Default.FormatSize, "Оформление", onSettings)
                }
            }
        }
    }
}

@Composable
private fun MenuAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocSheet(document: Document, position: Position, onSelect: (Position?) -> Unit) {
    val entries = document.toc.ifEmpty {
        document.chapters.mapIndexed { i, chapter ->
            app.koreshok.core.document.TocEntry(chapter.title ?: "Глава ${i + 1}", 1, Position(i, 0))
        }
    }
    val current = entries.indexOfLast { it.position <= position }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = { onSelect(null) }) {
        Text(
            "Оглавление",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.padding(bottom = 16.dp)) {
            itemsIndexed(entries) { index, entry ->
                val isCurrent = index == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(entry.position) }
                        .padding(start = (20 + 16 * (entry.level - 1).coerceIn(0, 4)).dp, end = 20.dp)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.title,
                        modifier = Modifier.weight(1f),
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${(document.progressOf(entry.position) * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HighlightSheet(
    draft: HighlightDraft,
    document: Document,
    onSave: (HighlightDraft) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var current by remember(draft) { mutableStateOf(draft) }
    val block = document.chapters[draft.chapter].blocks.getOrNull(draft.block) as? TextBlock
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (draft.id == 0L) "Новая цитата" else "Цитата", style = MaterialTheme.typography.titleLarge)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(highlightColor(current.color), MaterialTheme.shapes.small)
                    .padding(12.dp),
            ) {
                Text(current.text, style = MaterialTheme.typography.bodyLarge)
            }
            if (block != null && (current.start > 0 || current.end < block.text.length)) {
                AssistChip(
                    onClick = { current = current.copy(start = 0, end = block.text.length, text = block.text) },
                    label = { Text("Весь абзац") },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HighlightColors.forEachIndexed { index, color ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(color, CircleShape)
                            .border(
                                width = if (index == current.color) 3.dp else 0.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = CircleShape,
                            )
                            .clickable { current = current.copy(color = index) },
                    )
                }
            }
            OutlinedTextField(
                value = current.note,
                onValueChange = { current = current.copy(note = it) },
                label = { Text("Заметка") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onSave(current) }) { Text("Сохранить") }
                IconButton(onClick = { clipboard.setText(AnnotatedString(current.text)) }) {
                    Icon(Icons.Default.ContentCopy, "Копировать")
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "«${current.text}»\n— ${document.title}")
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                }) {
                    Icon(Icons.Default.Share, "Поделиться")
                }
                Spacer(Modifier.weight(1f))
                if (draft.id != 0L) {
                    TextButton(onClick = onDelete) { Text("Удалить") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnotationsSheet(
    annotations: List<AnnotationEntity>,
    onOpen: (AnnotationEntity) -> Unit,
    onDelete: (AnnotationEntity) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf<AnnotationKind?>(null) }
    val shown = annotations.filter { filter == null || it.kind == filter }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Закладки и цитаты", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onExport, enabled = annotations.isNotEmpty()) { Text("Экспорт") }
        }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Все") })
            FilterChip(
                selected = filter == AnnotationKind.BOOKMARK,
                onClick = { filter = AnnotationKind.BOOKMARK },
                label = { Text("Закладки") },
            )
            FilterChip(
                selected = filter == AnnotationKind.HIGHLIGHT,
                onClick = { filter = AnnotationKind.HIGHLIGHT },
                label = { Text("Цитаты") },
            )
        }
        if (shown.isEmpty()) {
            Text(
                "Пока пусто. Коснитесь уголка страницы, чтобы поставить закладку, или подержите палец на тексте, чтобы сохранить цитату.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(Modifier.padding(bottom = 16.dp)) {
            items(shown, key = { it.id }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(item) }
                        .padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (item.kind == AnnotationKind.BOOKMARK) {
                        Icon(Icons.Default.Bookmark, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    } else {
                        Box(Modifier.padding(top = 3.dp).size(12.dp).background(HighlightColors[item.color.coerceIn(0, HighlightColors.lastIndex)], CircleShape))
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        item.chapterTitle?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(item.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        item.note?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { onDelete(item) }) { Icon(Icons.Default.Close, "Удалить") }
                }
            }
        }
    }
}
