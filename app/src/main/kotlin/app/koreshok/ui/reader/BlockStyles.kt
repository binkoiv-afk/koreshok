package app.koreshok.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.koreshok.core.document.Block
import app.koreshok.core.document.BlockKind
import app.koreshok.core.document.SeparatorBlock
import app.koreshok.core.document.SpanStyle as BookSpan
import app.koreshok.core.document.TextBlock
import androidx.compose.ui.text.SpanStyle as ComposeSpan

/** How one text block is drawn. Paddings are whole pixels so measuring and drawing agree exactly. */
data class TextBlockSpec(
    val text: AnnotatedString,
    val style: TextStyle,
    val startPad: Int,
    val endPad: Int,
    val topPad: Int,
    val bottomPad: Int,
)

/** Everything layout depends on; a change to any of it means re-paginating. */
data class LayoutSpec(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val prefs: ReaderPrefs,
    val language: String?,
)

typealias LinkHandler = (target: String, isNote: Boolean) -> Unit

object BlockStyles {

    /** [highlights] only add backgrounds, so pages measured without them still line up. */
    fun spec(
        block: Block,
        layout: LayoutSpec,
        onLink: LinkHandler?,
        highlights: List<Pair<IntRange, Color>> = emptyList(),
    ): TextBlockSpec? {
        val textBlock = when (block) {
            is TextBlock -> block
            is SeparatorBlock -> TextBlock(BlockKind.SUBTITLE, "*  *  *")
            else -> return null
        }
        val prefs = layout.prefs
        val theme = prefs.theme
        val emPx = prefs.fontSize * layout.density // sp == dp at font scale 1; the reader has its own size control
        fun em(value: Float) = (value * emPx).toInt()

        val family = when (prefs.font) {
            ReaderFont.SERIF -> FontFamily.Serif
            ReaderFont.SANS -> FontFamily.SansSerif
            ReaderFont.MONO -> FontFamily.Monospace
        }
        val base = TextStyle(
            color = theme.text,
            fontFamily = family,
            fontSize = prefs.fontSize.sp,
            lineHeight = prefs.lineHeight.em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            textAlign = if (prefs.justify) TextAlign.Justify else TextAlign.Start,
            hyphens = if (prefs.hyphenate) Hyphens.Auto else Hyphens.None,
            lineBreak = LineBreak.Paragraph,
            localeList = layout.language?.let { LocaleList(it) },
        )
        val paragraphGap = if (prefs.indent) em(0.15f) else em(0.6f)
        val width = layout.widthPx

        val (style, pads) = when (textBlock.kind) {
            BlockKind.PARAGRAPH -> base.copy(
                textIndent = if (prefs.indent) TextIndent(firstLine = 1.5.em) else null,
            ) to Pads(bottom = paragraphGap)
            BlockKind.HEADING -> base.copy(
                fontSize = (prefs.fontSize * headingScale(textBlock.level)).sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                hyphens = Hyphens.None,
                lineHeight = 1.25.em,
            ) to Pads(top = em(1.2f), bottom = em(0.9f))
            BlockKind.SUBTITLE -> base.copy(
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                hyphens = Hyphens.None,
            ) to Pads(top = em(0.6f), bottom = em(0.6f))
            BlockKind.QUOTE -> base.copy(
                textIndent = if (prefs.indent) TextIndent(firstLine = 1.5.em) else null,
            ) to Pads(start = em(1.5f), end = em(0.5f), bottom = paragraphGap)
            BlockKind.POEM -> base.copy(
                textAlign = TextAlign.Start,
                hyphens = Hyphens.None,
            ) to Pads(start = em(2f), bottom = em(0.1f))
            BlockKind.EPIGRAPH -> base.copy(
                fontStyle = FontStyle.Italic,
                fontSize = (prefs.fontSize * 0.92f).sp,
                textAlign = TextAlign.Start,
            ) to Pads(start = (width * 0.35f).toInt(), bottom = em(0.2f))
            BlockKind.TEXT_AUTHOR -> base.copy(
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.End,
                hyphens = Hyphens.None,
            ) to Pads(bottom = em(0.8f))
            BlockKind.CODE -> base.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = (prefs.fontSize * 0.8f).sp,
                textAlign = TextAlign.Start,
                hyphens = Hyphens.None,
                lineBreak = LineBreak.Simple,
            ) to Pads(start = em(0.5f), bottom = paragraphGap)
            BlockKind.LIST_ITEM -> base.copy(textAlign = TextAlign.Start) to
                Pads(start = em(1.2f) * textBlock.level.coerceAtLeast(1), bottom = em(0.2f))
        }
        return TextBlockSpec(
            text = annotate(textBlock, theme.accent, onLink, highlights),
            style = style,
            startPad = pads.start,
            endPad = pads.end,
            topPad = pads.top,
            bottomPad = pads.bottom,
        )
    }

    private data class Pads(val start: Int = 0, val end: Int = 0, val top: Int = 0, val bottom: Int = 0)

    private fun headingScale(level: Int): Float = when (level) {
        1 -> 1.45f
        2 -> 1.3f
        3 -> 1.18f
        else -> 1.08f
    }

    fun annotate(
        block: TextBlock,
        accent: Color,
        onLink: LinkHandler?,
        highlights: List<Pair<IntRange, Color>> = emptyList(),
    ): AnnotatedString {
        val builder = AnnotatedString.Builder(block.text)
        for ((range, color) in highlights) {
            val start = range.first.coerceIn(0, block.text.length)
            val end = (range.last + 1).coerceIn(start, block.text.length)
            if (end > start) builder.addStyle(ComposeSpan(background = color), start, end)
        }
        for (span in block.spans) {
            when (val style = span.style) {
                BookSpan.Bold -> builder.addStyle(ComposeSpan(fontWeight = FontWeight.Bold), span.start, span.end)
                BookSpan.Italic -> builder.addStyle(ComposeSpan(fontStyle = FontStyle.Italic), span.start, span.end)
                BookSpan.Strike -> builder.addStyle(ComposeSpan(textDecoration = TextDecoration.LineThrough), span.start, span.end)
                BookSpan.Superscript -> builder.addStyle(
                    ComposeSpan(baselineShift = BaselineShift.Superscript, fontSize = 0.7.em),
                    span.start,
                    span.end,
                )
                BookSpan.Subscript -> builder.addStyle(
                    ComposeSpan(baselineShift = BaselineShift.Subscript, fontSize = 0.7.em),
                    span.start,
                    span.end,
                )
                BookSpan.Code -> builder.addStyle(ComposeSpan(fontFamily = FontFamily.Monospace), span.start, span.end)
                is BookSpan.Link -> {
                    val linkStyle = TextLinkStyles(ComposeSpan(color = accent))
                    builder.addLink(
                        LinkAnnotation.Clickable(style.target, linkStyle) { onLink?.invoke(style.target, style.isNote) },
                        span.start,
                        span.end,
                    )
                }
            }
        }
        return builder.toAnnotatedString()
    }
}
