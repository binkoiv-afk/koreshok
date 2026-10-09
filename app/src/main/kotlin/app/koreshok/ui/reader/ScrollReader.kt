package app.koreshok.ui.reader

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.koreshok.core.document.Document
import app.koreshok.core.document.ImageBlock
import app.koreshok.core.document.Position
import app.koreshok.core.document.TextBlock
import app.koreshok.data.AnnotationEntity
import app.koreshok.data.AnnotationKind
import coil.compose.AsyncImage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The whole book as one continuous column. Blocks use the same styles as pages, so switching
 * modes keeps the look; a tap in the upper or lower third scrolls by almost a screen.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun ScrollReader(
    document: Document,
    location: Location,
    layout: LayoutSpec,
    marginPx: Int,
    annotations: List<AnnotationEntity>,
    viewModel: ReaderViewModel,
    onLink: LinkHandler,
    onCenterTap: () -> Unit,
    onHighlight: (HighlightDraft) -> Unit,
) {
    val theme = layout.prefs.theme
    val density = LocalDensity.current
    // Item index of each chapter's first block; blocks of all chapters form one list.
    val chapterStarts = remember(document) {
        IntArray(document.chapters.size + 1).also { starts ->
            for (c in document.chapters.indices) starts[c + 1] = starts[c] + document.chapters[c].blocks.size
        }
    }
    val total = chapterStarts.last()
    fun indexOf(position: Position): Int =
        (chapterStarts[position.chapter.coerceIn(0, document.chapters.lastIndex)] + position.block).coerceIn(0, (total - 1).coerceAtLeast(0))
    fun positionOf(index: Int): Position {
        var chapter = chapterStarts.indexOfLast { it <= index }.coerceIn(0, document.chapters.lastIndex)
        while (chapter > 0 && document.chapters[chapter].blocks.isEmpty()) chapter--
        return Position(chapter, (index - chapterStarts[chapter]).coerceAtLeast(0), 0)
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = indexOf(location.position))
    val scope = rememberCoroutineScope()

    // Outside jumps (TOC, links, slider) move the list; scrolling itself only reports where it is.
    LaunchedEffect(location.jump) {
        val target = indexOf(location.position)
        if (listState.firstVisibleItemIndex != target) listState.scrollToItem(target)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(300)
            .collect { viewModel.onPageShown(positionOf(it)) }
    }

    val current by remember { derivedStateOf { positionOf(listState.firstVisibleItemIndex) } }
    val next by remember { derivedStateOf { positionOf(listState.firstVisibleItemIndex + 1) } }
    val bookmarked = annotations.any { it.kind == AnnotationKind.BOOKMARK && it.isOnPage(current, next) }
    val viewportPx = remember { mutableStateOf(0) }

    fun tap(yInViewport: Float) {
        val height = viewportPx.value.toFloat()
        val step = height * 0.85f
        when {
            yInViewport < height / 3 -> scope.launch { listState.animateScrollBy(-step) }
            yInViewport > height * 2 / 3 -> scope.launch { listState.animateScrollBy(step) }
            else -> onCenterTap()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ReaderHeader(
            title = document.chapters[current.chapter].title ?: document.title,
            theme = theme,
            height = 28.dp,
            bookmarked = bookmarked,
            onBookmark = { viewModel.toggleBookmark(current, next) },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewportPx.value = it.height },
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = with(density) { marginPx.toDp() }, vertical = 12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(total, key = { it }) { index ->
                    val position = positionOf(index)
                    val chapter = document.chapters[position.chapter]
                    val block = chapter.blocks.getOrNull(position.block)
                    if (block is ImageBlock) {
                        val size = Paginator.imageSize(document, block, layout)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .pointerInput(Unit) { detectTapGestures { tap(it.y + viewportOffset(listState, index)) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (size != null) {
                                AsyncImage(
                                    model = document.images[block.imageId],
                                    contentDescription = block.alt,
                                    modifier = Modifier.size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() }),
                                )
                            }
                        }
                    } else if (block != null) {
                        val highlights = annotations.filter {
                            it.kind == AnnotationKind.HIGHLIGHT && it.chapter == position.chapter && it.block == position.block
                        }
                        val colored = highlights.map { (it.start until it.end) to highlightColor(it.color) }
                        val spec = remember(block, layout, colored) { BlockStyles.spec(block, layout, onLink, colored) }
                        if (spec != null) {
                            var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                            fun charAt(offset: Offset): Int? = textLayout?.getOffsetForPosition(offset)
                            fun highlightAt(char: Int) = highlights.firstOrNull { char >= it.start && char < it.end }
                            Text(
                                text = spec.text,
                                style = spec.style,
                                onTextLayout = { textLayout = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = with(density) { spec.startPad.toDp() },
                                        end = with(density) { spec.endPad.toDp() },
                                        top = with(density) { spec.topPad.toDp() },
                                        bottom = with(density) { spec.bottomPad.toDp() },
                                    )
                                    .pointerInput(block, highlights) {
                                        detectTapGestures(
                                            onTap = { offset ->
                                                val existing = charAt(offset)?.let(::highlightAt)
                                                if (existing != null) {
                                                    onHighlight(existing.toDraft())
                                                } else {
                                                    tap(offset.y + spec.topPad + viewportOffset(listState, index))
                                                }
                                            },
                                            onLongPress = { offset ->
                                                val char = charAt(offset) ?: return@detectTapGestures
                                                val existing = highlightAt(char)
                                                val text = block as? TextBlock
                                                when {
                                                    existing != null -> onHighlight(existing.toDraft())
                                                    text != null -> {
                                                        val range = sentenceAt(text, char, layout.language)
                                                        if (!range.isEmpty()) {
                                                            onHighlight(
                                                                HighlightDraft(
                                                                    chapter = position.chapter,
                                                                    block = position.block,
                                                                    start = range.first,
                                                                    end = range.last + 1,
                                                                    text = text.text.substring(range.first, range.last + 1),
                                                                ),
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                    },
                            )
                        }
                    }
                }
            }
        }
        val progress = document.progressOf(current)
        Box(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
            Text("${(progress * 100).roundToInt()}%", color = theme.secondary, fontSize = 12.sp)
        }
    }
}

/** Where item [index] starts inside the viewport, to turn an item-local tap into a screen position. */
private fun viewportOffset(state: androidx.compose.foundation.lazy.LazyListState, index: Int): Float =
    state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.toFloat() ?: 0f
