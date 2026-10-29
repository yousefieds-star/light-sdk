package com.yousefalrushaid.lightbooks

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlToBlocksTest {
    @Test
    fun `keeps paragraphs and headings, drops head and scripts`() {
        val html = """
            <html><head><title>Ignore me</title><style>p { color: red }</style></head>
            <body>
              <h1 id="start">Chapter  One</h1>
              <p>It was a <em>bright</em> cold day &amp; the clocks
                 were striking&nbsp;thirteen.</p>
              <script>alert('x')</script>
              <p>Second<br/>line</p>
            </body></html>
        """.trimIndent()
        val result = HtmlToBlocks.convert(html)
        assertEquals(3, result.blocks.size)
        assertEquals("Chapter One", result.blocks[0].text)
        assertTrue(result.blocks[0].heading)
        assertEquals("It was a bright cold day & the clocks were striking thirteen.", result.blocks[1].text)
        val italic = result.blocks[1].spans.single()
        assertTrue(italic.italic)
        assertEquals("bright", result.blocks[1].text.substring(italic.start, italic.end))
        assertEquals("Second\nline", result.blocks[2].text)
        assertEquals("Chapter One", result.firstHeading)
        assertEquals(0, result.anchors["start"])
    }

    @Test
    fun `decodes numeric entities and survives stray angle brackets`() {
        val result = HtmlToBlocks.convert("<p>5 &#60; 6 &#x2014; and 3 < 4</p>")
        assertEquals("5 < 6 — and 3 < 4", result.blocks.single().text)
    }

    @Test
    fun `splits very long paragraphs`() {
        val long = (1..400).joinToString(" ") { "Sentence number $it." }
        val pieces = Block(long).splitIfLong(1000)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.text.length <= 1000 })
        assertEquals(long.replace(" ", ""), pieces.joinToString("") { it.text.replace(" ", "") })
    }
}

class TxtParserTest {
    @Test
    fun `finds chapters and joins wrapped lines`() {
        val text = """
            Title: A Small Book
            Author: Some Writer

            CHAPTER I

            The first line
            continues here.

            Another paragraph.

            CHAPTER II

            The end.
        """.trimIndent()
        val book = TxtParser.parse(text, "fallback")
        assertEquals("A Small Book", book.title)
        assertEquals("Some Writer", book.author)
        val titles = book.chapters.map { it.title }
        assertEquals(listOf("Opening", "CHAPTER I", "CHAPTER II"), titles)
        assertEquals("The first line continues here.", book.chapters[1].blocks[1].text)
        assertEquals(3, book.toc.size)
    }

    @Test
    fun `splits books without chapters into parts`() {
        val text = (1..2000).joinToString("\n\n") { "Paragraph $it has a few words in it." }
        val book = TxtParser.parse(text, "Plain")
        assertEquals("Plain", book.title)
        assertTrue(book.chapters.size > 1)
        assertEquals("Part 1", book.chapters.first().title)
    }

    @Test
    fun `falls back to windows-1252 for non utf8 files`() {
        val bytes = byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())
        val book = TxtParser.parse(bytes, "x")
        assertEquals("café", book.chapters.single().blocks.single().text)
    }
}

class EpubParserTest {
    @Test
    fun `resolves relative hrefs`() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubParser.resolve("OEBPS/nav", "../text/ch1.xhtml"))
        assertEquals("OEBPS/My Chapter.xhtml#p2", EpubParser.resolve("OEBPS", "My%20Chapter.xhtml#p2"))
    }

    @Test
    fun `reads an epub3 book with a nav table of contents`() {
        val file = File.createTempFile("book", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            put(
                "OEBPS/content.opf",
                """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Test &amp; Book</dc:title><dc:creator>Ada Writer</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c1" href="text/one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/two.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="cover"/><itemref idref="c1"/><itemref idref="c2"/></spine>
                </package>
                """.trimIndent(),
            )
            put(
                "OEBPS/nav.xhtml",
                """
                <html><body><nav epub:type="toc"><ol>
                  <li><a href="text/one.xhtml">First</a></li>
                  <li><a href="text/two.xhtml">Second</a><ol><li><a href="text/two.xhtml#deep">Deeper</a></li></ol></li>
                </ol></nav></body></html>
                """.trimIndent(),
            )
            put("OEBPS/cover.xhtml", """<html><body><img src="cover.jpg"/></body></html>""")
            put("OEBPS/text/one.xhtml", """<html><body><h1>One</h1><p>Hello there.</p></body></html>""")
            put("OEBPS/text/two.xhtml", """<html><body><h1>Two</h1><p>Intro.</p><p id="deep">Deep part.</p></body></html>""")
        }
        val book = EpubParser.parse(file)
        file.delete()

        assertEquals("Test & Book", book.title)
        assertEquals("Ada Writer", book.author)
        assertEquals(2, book.chapters.size) // the image-only cover is skipped
        assertEquals(listOf("First", "Second"), book.chapters.map { it.title })
        assertEquals(listOf("First", "Second", "Deeper"), book.toc.map { it.title })
        val deeper = book.toc[2]
        assertEquals(1, deeper.chapter)
        assertEquals(1, deeper.depth)
        assertEquals("Two".length + 1 + "Intro.".length + 1, deeper.offset)
    }
}
