package com.yousefalrushaid.lightbooks

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints

/** One piece of a paragraph shown on a page. */
class PagePiece(
    val text: AnnotatedString,
    val heading: Boolean,
    /** Space above this piece, in pixels. */
    val gapAbovePx: Int,
)

/** A screenful of text. [start]..[end] are chapter character offsets (end exclusive). */
class TextPage(
    val chapter: Int,
    val start: Int,
    val end: Int,
    val pieces: List<PagePiece>,
    /** The layout this page was measured with; it is drawn with the same styles. */
    val spec: PageSpec,
)

/** Everything that affects how text breaks into pages. Equal specs give equal pages. */
data class PageSpec(
    val widthPx: Int,
    val heightPx: Int,
    val paragraphGapPx: Int,
    val bodyStyle: TextStyle,
    val headingStyle: TextStyle,
)

/** Splits chapters into pages that exactly fit the screen, using the same text styles we draw with. */
class Paginator(private val measurer: TextMeasurer, val spec: PageSpec) {

    fun paginate(chapterIndex: Int, chapter: Chapter): List<TextPage> {
        val pages = mutableListOf<TextPage>()
        var pieces = mutableListOf<PagePiece>()
        var used = 0
        var pageStart = 0
        var blockStart = 0 // chapter offset of the current block

        fun finishPage(endOffset: Int) {
            pages.add(TextPage(chapterIndex, pageStart, endOffset.coerceAtLeast(pageStart + 1), pieces, spec))
            pieces = mutableListOf()
            used = 0
            pageStart = endOffset
        }

        for (block in chapter.blocks) {
            val full = block.toAnnotated()
            val style = if (block.heading) spec.headingStyle else spec.bodyStyle
            var from = 0
            while (from < full.length) {
                if (pieces.isEmpty()) pageStart = blockStart + from
                val gap = when {
                    pieces.isEmpty() -> 0
                    block.heading -> spec.paragraphGapPx * 2
                    else -> spec.paragraphGapPx
                }
                val available = spec.heightPx - used - gap
                val rest = if (from == 0) full else full.subSequence(from, full.length)
                val layout = measurer.measure(
                    text = rest,
                    style = style,
                    constraints = Constraints(maxWidth = spec.widthPx.coerceAtLeast(1)),
                    skipCache = true,
                )
                if (layout.size.height <= available) {
                    pieces.add(PagePiece(rest, block.heading, gap))
                    used += gap + layout.size.height
                    from = full.length
                    break
                }
                // Keep headings whole: move them to the next page.
                if (block.heading && pieces.isNotEmpty()) {
                    finishPage(blockStart + from)
                    continue
                }
                var lastFitting = -1
                for (line in 0 until layout.lineCount) {
                    if (layout.getLineBottom(line) <= available) lastFitting = line else break
                }
                if (lastFitting < 0) {
                    if (pieces.isNotEmpty()) {
                        finishPage(blockStart + from)
                        continue
                    }
                    lastFitting = 0 // A single line taller than the page: show it anyway.
                }
                val cut = layout.getLineEnd(lastFitting, visibleEnd = false).coerceIn(1, rest.length)
                // A word hyphenated across the page edge keeps its hyphen.
                val midWord = cut < rest.length && rest[cut - 1].isLetter() && rest[cut].isLetter()
                val piece = rest.subSequence(0, cut).trimEndSpaces()
                pieces.add(PagePiece(if (midWord) piece + AnnotatedString("\u2010") else piece, block.heading, gap))
                var next = from + cut
                while (next < full.length && full[next] == ' ') next++
                finishPage(blockStart + next)
                from = next
            }
            blockStart += block.text.length + 1
        }
        if (pieces.isNotEmpty() || pages.isEmpty()) {
            finishPage(chapter.length.coerceAtLeast(pageStart + 1))
        }
        return pages
    }
}

fun Block.toAnnotated(): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (span in spans) {
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (end > start) {
                addStyle(
                    SpanStyle(
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null,
                    ),
                    start,
                    end,
                )
            }
        }
    }
}

private fun AnnotatedString.trimEndSpaces(): AnnotatedString {
    var end = length
    while (end > 0 && (this[end - 1] == ' ' || this[end - 1] == '\n')) end--
    return if (end == length) this else subSequence(0, end)
}
