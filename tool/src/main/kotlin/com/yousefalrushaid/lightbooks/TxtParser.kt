package com.yousefalrushaid.lightbooks

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Turns a plain-text file into chapters and paragraphs. */
internal object TxtParser {
    private val CHAPTER_LINE = Regex(
        """^((chapter|book|part|act|letter)\s+([0-9]+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|the\s+\w+)\b.{0,60}|prologue|epilogue|preface|introduction|foreword|afterword)\.?$""",
        RegexOption.IGNORE_CASE,
    )
    private val ROMAN_LINE = Regex("""^[IVXLC]{1,7}\.?$""")
    private val BLANK_LINES = Regex("""\n[ \t]*\n+""")
    private const val SECTION_LENGTH = 15_000

    fun parse(bytes: ByteArray, fallbackTitle: String): ParsedBook = parse(decode(bytes), fallbackTitle)

    fun parse(raw: String, fallbackTitle: String): ParsedBook {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

        // Project Gutenberg style headers give us a real title and author.
        val header = text.lineSequence().take(80).toList()
        val title = header.firstNotNullOfOrNull { line ->
            line.trim().takeIf { it.startsWith("Title:", ignoreCase = true) }?.substringAfter(':')?.trim()
        }?.takeIf { it.isNotEmpty() } ?: fallbackTitle
        val author = header.firstNotNullOfOrNull { line ->
            line.trim().takeIf { it.startsWith("Author:", ignoreCase = true) }?.substringAfter(':')?.trim()
        }.orEmpty()

        val body = stripGutenbergBoilerplate(text)

        // Paragraphs are separated by blank lines. Without blank lines, every line is a paragraph.
        val paragraphs: List<String> = (if (BLANK_LINES.containsMatchIn(body)) {
            body.split(BLANK_LINES).map { p -> p.lines().joinToString(" ") { it.trim() }.trim() }
        } else {
            body.lines().map { it.trim() }
        }).filter { it.isNotEmpty() }

        val blocks = paragraphs.flatMap { p ->
            Block(p, heading = isChapterHeading(p)).splitIfLong()
        }
        if (blocks.isEmpty()) {
            return ParsedBook(title, author, listOf(Chapter(title, listOf(Block("This file is empty.")))), listOf(TocEntry(title, 0)))
        }

        val headingCount = blocks.count { it.heading }
        val chapters = if (headingCount in 2..2000) splitAtHeadings(blocks) else splitBySize(blocks)
        val toc = chapters.mapIndexed { i, ch -> TocEntry(ch.title, i) }
        return ParsedBook(title = title, author = author, chapters = chapters, toc = toc)
    }

    /** Keeps only the book itself from Project Gutenberg files (drops the licence text). */
    private fun stripGutenbergBoilerplate(text: String): String {
        val startMarker = Regex("""^\*{3}\s*START OF (THE|THIS) PROJECT GUTENBERG.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        val endMarker = Regex("""^\*{3}\s*END OF (THE|THIS) PROJECT GUTENBERG.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        val start = startMarker.find(text)?.range?.last?.plus(1) ?: 0
        val end = endMarker.find(text, start)?.range?.first ?: text.length
        return if (end > start) text.substring(start, end) else text
    }

    private fun isChapterHeading(p: String): Boolean =
        p.length <= 80 && !p.endsWith(",") && (CHAPTER_LINE.matches(p) || ROMAN_LINE.matches(p))

    private fun splitAtHeadings(blocks: List<Block>): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var current = mutableListOf<Block>()
        var currentTitle: String? = null
        fun close() {
            if (current.isNotEmpty()) {
                chapters.add(Chapter(currentTitle ?: if (chapters.isEmpty()) "Opening" else "Section ${chapters.size + 1}", current))
            }
            current = mutableListOf()
        }
        for (block in blocks) {
            if (block.heading) {
                close()
                currentTitle = block.text
            }
            current.add(block)
        }
        close()
        return chapters
    }

    private fun splitBySize(blocks: List<Block>): List<Chapter> {
        val plain = blocks.map { if (it.heading) it.copy(heading = false) else it }
        val chapters = mutableListOf<Chapter>()
        var current = mutableListOf<Block>()
        var size = 0
        for (block in plain) {
            current.add(block)
            size += block.text.length
            if (size >= SECTION_LENGTH) {
                chapters.add(Chapter("Part ${chapters.size + 1}", current))
                current = mutableListOf()
                size = 0
            }
        }
        if (current.isNotEmpty()) chapters.add(Chapter("Part ${chapters.size + 1}", current))
        return chapters
    }

    /** UTF-8 if the bytes are valid UTF-8, otherwise Windows-1252 (common for older files). */
    private fun decode(bytes: ByteArray): String {
        val strictUtf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strictUtf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }
}
