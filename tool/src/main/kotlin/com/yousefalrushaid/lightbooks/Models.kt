package com.yousefalrushaid.lightbooks

import kotlinx.serialization.Serializable

enum class BookFormat(val label: String) {
    EPUB("EPUB"),
    TXT("TXT"),
    PDF("PDF");

    companion object {
        fun fromFileName(name: String): BookFormat? = when (name.substringAfterLast('.', "").lowercase()) {
            "epub" -> EPUB
            "txt", "text" -> TXT
            "pdf" -> PDF
            else -> null
        }
    }
}

/** Bold / italic run inside a [Block]; [end] is exclusive. */
@Serializable
data class Span(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/** One paragraph (or heading) of reflowable text. */
@Serializable
data class Block(
    val text: String,
    val heading: Boolean = false,
    val spans: List<Span> = emptyList(),
)

@Serializable
data class Chapter(
    val title: String,
    val blocks: List<Block>,
) {
    /**
     * Character length of the chapter. Positions inside a chapter are measured as if every
     * block were joined with a single separator character.
     */
    val length: Int get() = blocks.sumOf { it.text.length + 1 }
}

@Serializable
data class TocEntry(
    val title: String,
    val chapter: Int,
    val offset: Int = 0,
    val depth: Int = 0,
)

/** A fully parsed EPUB or TXT book, cached on disk as JSON. */
@Serializable
data class ParsedBook(
    val title: String,
    val author: String,
    val chapters: List<Chapter>,
    val toc: List<TocEntry>,
)

@Serializable
data class BookInfo(
    /** The file name inside the Books folder. Unique, so it doubles as the id. */
    val fileName: String,
    val format: BookFormat,
    val title: String,
    val author: String = "",
    val fileSize: Long,
    val fileModified: Long,
    /** Characters for EPUB/TXT, pages for PDF. */
    val totalLength: Int,
    /** Set when the file could not be opened. */
    val error: String? = null,
)

/**
 * A place in a book. For EPUB/TXT: chapter index + character offset in that chapter.
 * For PDF: chapter is always 0 and offset is the page index.
 */
@Serializable
data class Position(
    val chapter: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class Bookmark(
    val position: Position,
    val label: String,
    val snippet: String = "",
    val progress: Float = 0f,
    val createdAt: Long = 0,
)

@Serializable
data class ReadingState(
    val position: Position = Position(),
    val progress: Float = 0f,
    val lastOpened: Long = 0,
    val bookmarks: List<Bookmark> = emptyList(),
)

data class LibraryEntry(
    val info: BookInfo,
    val state: ReadingState,
)

fun Float.asPercent(): String = "${(this * 100).toInt().coerceIn(0, 100)}%"
