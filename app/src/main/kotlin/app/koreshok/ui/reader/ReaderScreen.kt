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
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
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
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

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
            val measurer = rememberTextMeasurer(cacheSize = 0)
            val paginator = remember(measurer) { Paginator(measurer) }
            val chapter = location.position.chapter
            val pages by produceState<List<Page>?>(null, document, chapter, layout) {
                value = null
                value = withContext(Dispatchers.Default) { paginator.paginate(document, chapter, layout) }
            }

            Column(Modifier.fillMaxSize()) {
                Header(document.chapters[chapter].title ?: document.title, theme, headerPx)
                Box(Modifier.weight(1f).fillMaxWidth()) {
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
                                viewModel = viewModel,
                                onLink = onLink,
                                onCenterTap = { showMenu = !showMenu },
                                footerPx = footerPx,
                            )
                        }
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
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ReaderSettingsPanel(prefs) { transform -> viewModel.updatePrefs(transform) }
        }
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
private fun Header(title: String, theme: ReaderTheme, heightPx: Int) {
    val height = with(LocalDensity.current) { heightPx.toDp() }
    Box(Modifier.fillMaxWidth().height(height).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(title, color = theme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    viewModel: ReaderViewModel,
    onLink: LinkHandler,
    onCenterTap: () -> Unit,
    footerPx: Int,
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

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            when {
                hasPrevious && page == 0 -> viewModel.previousChapter(toEnd = true)
                hasNext && page == count - 1 -> viewModel.nextChapter()
                else -> pages.getOrNull(page - first)?.let { viewModel.onPageShown(it.start) }
            }
        }
    }

    val density = LocalDensity.current
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(count) {
                    detectTapGestures { offset ->
                        val third = size.width / 3f
                        when {
                            offset.x < third -> scope.launch {
                                pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
                            }
                            offset.x > third * 2 -> scope.launch {
                                pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(count - 1))
                            }
                            else -> onCenterTap()
                        }
                    }
                },
        ) { index ->
            val page = pages.getOrNull(index - first)
            Box(Modifier.fillMaxSize().padding(horizontal = with(density) { marginPx.toDp() })) {
                if (page != null) PageView(document, chapter, page, layout, onLink)
            }
        }
        val pageIndex = (pagerState.currentPage - first).coerceIn(0, pages.lastIndex)
        Footer(
            left = "${pageIndex + 1} / ${pages.size}",
            right = "${(document.progressOf(pages[pageIndex].start) * 100).roundToInt()}%",
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
private fun PageView(document: Document, chapter: Int, page: Page, layout: LayoutSpec, onLink: LinkHandler) {
    val blocks = document.chapters[chapter].blocks
    val density = LocalDensity.current
    Column(Modifier.width(with(density) { layout.widthPx.toDp() })) {
        for (slice in page.slices) {
            val block = blocks[slice.block]
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { slice.height.toDp() })
                    .clipToBounds(),
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
                        val spec = remember(block, layout) { BlockStyles.spec(block, layout, onLink) }
                        if (spec != null) {
                            Text(
                                text = spec.text,
                                style = spec.style,
                                modifier = Modifier.padding(
                                    start = with(density) { spec.startPad.toDp() },
                                    end = with(density) { spec.endPad.toDp() },
                                    top = with(density) { spec.topPad.toDp() },
                                    bottom = with(density) { spec.bottomPad.toDp() },
                                ),
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
            }
        }
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

@Composable
private fun ReaderSettingsPanel(prefs: ReaderPrefs, update: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Оформление", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReaderTheme.entries.forEach { theme ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(theme.background, CircleShape)
                            .border(
                                width = if (theme == prefs.theme) 3.dp else 1.dp,
                                color = if (theme == prefs.theme) MaterialTheme.colorScheme.primary else theme.secondary,
                                shape = CircleShape,
                            )
                            .clickable { update { it.copy(theme = theme) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Аа", color = theme.text)
                    }
                    Text(theme.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderFont.entries.forEach { font ->
                FilterChip(
                    selected = font == prefs.font,
                    onClick = { update { it.copy(font = font) } },
                    label = { Text(font.label) },
                )
            }
        }
        LabeledSlider("Размер шрифта", "${prefs.fontSize.roundToInt()}", prefs.fontSize, 12f..36f, 23) { v ->
            update { it.copy(fontSize = v) }
        }
        LabeledSlider("Межстрочный интервал", "%.2f".format(prefs.lineHeight), prefs.lineHeight, 1.1f..2.2f, 21) { v ->
            update { it.copy(lineHeight = v) }
        }
        LabeledSlider("Поля", "${prefs.margin.roundToInt()}", prefs.margin, 4f..48f, 21) { v ->
            update { it.copy(margin = v) }
        }
        SwitchRow("Выравнивание по ширине", prefs.justify) { v -> update { it.copy(justify = v) } }
        SwitchRow("Переносы слов", prefs.hyphenate) { v -> update { it.copy(hyphenate = v) } }
        SwitchRow("Красная строка", prefs.indent) { v -> update { it.copy(indent = v) } }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    // Layout reruns on every saved change, so the slider only commits when released.
    var local by remember(current) { mutableFloatStateOf(current) }
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text(if (local == current) value else "…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
