package app.koreshok.ui.reader

import android.graphics.BitmapFactory
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import app.koreshok.core.document.Block
import app.koreshok.core.document.BlockKind
import app.koreshok.core.document.Document
import app.koreshok.core.document.ImageBlock
import app.koreshok.core.document.Position
import app.koreshok.core.document.TextBlock
import kotlin.math.min

/** A vertical piece of one block shown on a page: pixels [top, top + height) of the block's own layout. */
data class PageSlice(val block: Int, val top: Int, val height: Int)

data class Page(val slices: List<PageSlice>, val start: Position)

data class ImageSize(val width: Int, val height: Int)

/**
 * Splits a chapter into pages. Each block is laid out once at full width; a page then shows a
 * clipped window of that layout, cut only between lines. A paragraph broken across pages therefore
 * keeps exactly the line breaks and justification it would have had in one piece.
 */
class Paginator(private val measurer: TextMeasurer) {

    private class Measured(
        val height: Int,
        /** Pixel positions inside the block where a page may end, in increasing order. */
        val cuts: IntArray,
        /** Character offset of the line that starts at each cut. */
        val cutOffsets: IntArray,
        val isHeading: Boolean,
        val firstLineHeight: Int,
        /** Spacing after the block; it may be cut off at the bottom of a page. */
        val bottomPad: Int = 0,
    )

    fun paginate(document: Document, chapter: Int, layout: LayoutSpec): List<Page> {
        val blocks = document.chapters[chapter].blocks
        val pageHeight = layout.heightPx
        val pages = mutableListOf<Page>()
        var slices = mutableListOf<PageSlice>()
        var pageStart = Position(chapter, 0, 0)
        var used = 0

        fun newPage(next: Position) {
            if (slices.isNotEmpty()) pages += Page(slices, pageStart)
            slices = mutableListOf()
            pageStart = next
            used = 0
        }

        blocks.forEachIndexed { index, block ->
            val m = measure(document, block, layout) ?: return@forEachIndexed
            // Keep a heading together with the start of what follows.
            if (m.isHeading && slices.isNotEmpty() && used + m.height + m.firstLineHeight * 3 > pageHeight) {
                newPage(Position(chapter, index, 0))
            }
            var y = 0
            while (y < m.height) {
                if (slices.isEmpty()) pageStart = Position(chapter, index, offsetAt(m, y))
                val remaining = pageHeight - used
                val rest = m.height - y
                if (rest - m.bottomPad <= remaining) {
                    slices += PageSlice(index, y, min(rest, remaining))
                    used += min(rest, remaining)
                    break
                }
                val cut = m.cuts.lastOrNull { it > y && it - y <= remaining }
                when {
                    cut != null -> {
                        slices += PageSlice(index, y, cut - y)
                        y = cut
                        newPage(Position(chapter, index, offsetAt(m, y)))
                    }
                    slices.isEmpty() -> {
                        // A single line taller than the page: show what fits rather than loop forever.
                        val piece = min(rest, remaining)
                        slices += PageSlice(index, y, piece)
                        y += piece
                        newPage(Position(chapter, index, offsetAt(m, y)))
                    }
                    else -> newPage(Position(chapter, index, offsetAt(m, y)))
                }
            }
        }
        if (slices.isNotEmpty()) pages += Page(slices, pageStart)
        if (pages.isEmpty()) pages += Page(emptyList(), Position(chapter, 0, 0))
        return pages
    }

    private fun offsetAt(m: Measured, y: Int): Int {
        val i = m.cuts.indexOfFirst { it >= y }
        return if (i < 0) 0 else m.cutOffsets[i]
    }

    private fun measure(document: Document, block: Block, layout: LayoutSpec): Measured? {
        if (block is ImageBlock) {
            val size = imageSize(document, block, layout) ?: return null
            return Measured(size.height, intArrayOf(size.height), intArrayOf(1), false, size.height)
        }
        val spec = BlockStyles.spec(block, layout, onLink = null) ?: return null
        val width = (layout.widthPx - spec.startPad - spec.endPad).coerceAtLeast(1)
        val result = measurer.measure(
            text = spec.text,
            style = spec.style,
            constraints = Constraints(maxWidth = width),
        )
        val lines = result.lineCount
        val cuts = IntArray(lines)
        val offsets = IntArray(lines)
        for (line in 0 until lines) {
            // A page may end after any line; the next page starts at the following line.
            cuts[line] = spec.topPad + result.getLineBottom(line).toInt()
            offsets[line] = if (line + 1 < lines) result.getLineStart(line + 1) else (block as? TextBlock)?.length ?: 0
        }
        val height = spec.topPad + result.size.height + spec.bottomPad
        if (lines > 0) cuts[lines - 1] = height
        val firstLine = if (lines > 0) result.getLineBottom(0).toInt() else result.size.height
        return Measured(
            height = height,
            cuts = cuts,
            cutOffsets = offsets,
            isHeading = (block as? TextBlock)?.kind == BlockKind.HEADING,
            firstLineHeight = firstLine,
            bottomPad = spec.bottomPad,
        )
    }

    companion object {
        /** Images fit the column width and at most most of a page; small ones are not blown up past 2x. */
        fun imageSize(document: Document, block: ImageBlock, layout: LayoutSpec): ImageSize? {
            val bytes = document.images[block.imageId] ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            val maxWidth = min(layout.widthPx.toFloat(), options.outWidth * layout.density * 2f)
            val maxHeight = layout.heightPx * 0.92f
            val scale = min(maxWidth / options.outWidth, maxHeight / options.outHeight)
            return ImageSize((options.outWidth * scale).toInt(), (options.outHeight * scale).toInt())
        }
    }
}
