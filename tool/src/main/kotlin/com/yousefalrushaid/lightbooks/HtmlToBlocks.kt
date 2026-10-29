package com.yousefalrushaid.lightbooks

/** Result of turning one (X)HTML document into reader blocks. */
internal class HtmlConversion(
    val blocks: List<Block>,
    /** Element id -> character offset inside the chapter (for table-of-contents links). */
    val anchors: Map<String, Int>,
    val firstHeading: String?,
)

/**
 * A small, forgiving HTML-to-text converter for ebook chapters.
 * Keeps paragraphs, headings, line breaks, bold and italic. Drops images, scripts and styling.
 */
internal object HtmlToBlocks {
    private val SKIP_TAGS = setOf("head", "script", "style", "title", "svg", "math", "noscript", "template")
    private val BLOCK_TAGS = setOf(
        "p", "div", "li", "ul", "ol", "dl", "dt", "dd", "blockquote", "section", "article",
        "header", "footer", "aside", "nav", "figure", "figcaption", "table", "tr", "pre", "hr",
        "body", "main", "address", "center", "caption",
    )
    private val CELL_TAGS = setOf("td", "th")
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val BOLD_TAGS = setOf("b", "strong")
    private val ITALIC_TAGS = setOf("i", "em", "cite", "dfn", "var")
    private val ID_REGEX = Regex("""(?:^|\s)(?:id|name)\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val BODY_REGEX = Regex("""<body\b""", RegexOption.IGNORE_CASE)
    private val closeTagRegexes = HashMap<String, Regex>()

    fun convert(html: String): HtmlConversion {
        val out = BlockBuilder()
        val n = html.length
        var i = BODY_REGEX.find(html)?.range?.first ?: 0
        while (i < n) {
            if (html[i] != '<') {
                val next = html.indexOf('<', i).let { if (it < 0) n else it }
                out.text(html.substring(i, next))
                i = next
                continue
            }
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            if (html.startsWith("<![CDATA[", i)) {
                val end = html.indexOf("]]>", i + 9)
                out.text(html.substring(i + 9, if (end < 0) n else end))
                i = if (end < 0) n else end + 3
                continue
            }
            val after = html.getOrNull(i + 1)
            if (after == null || !(after.isLetter() || after == '/' || after == '!' || after == '?')) {
                // A literal "<" in sloppy HTML.
                out.text("<")
                i++
                continue
            }
            val close = findTagEnd(html, i)
            if (close < 0) break
            val tag = html.substring(i + 1, close).trim()
            i = close + 1
            if (tag.isEmpty() || tag[0] == '!' || tag[0] == '?') continue

            val closing = tag[0] == '/'
            val body = if (closing) tag.substring(1).trimStart() else tag
            val selfClosing = body.endsWith("/")
            val name = body
                .takeWhile { !it.isWhitespace() && it != '/' }
                .lowercase()
                .substringAfter(':')
            if (name.isEmpty()) continue

            if (!closing && !selfClosing && name in SKIP_TAGS) {
                i = skipElement(html, i, name)
                continue
            }
            // Finish the previous paragraph first, so a link to this element lands on it exactly.
            if (!closing && (name in BLOCK_TAGS || name in HEADING_TAGS)) out.flush()
            if (!closing) {
                ID_REGEX.find(body)?.let { m ->
                    out.anchor(m.groupValues[1].ifEmpty { m.groupValues[2] })
                }
            }
            when (name) {
                "br" -> out.lineBreak()
                in HEADING_TAGS -> when {
                    closing -> out.endHeading()
                    !selfClosing -> out.startHeading()
                }
                in BLOCK_TAGS -> {
                    out.flush()
                    if (name == "pre") out.pre = !closing && !selfClosing
                }
                in CELL_TAGS -> out.text(" ")
                in BOLD_TAGS -> if (!selfClosing) out.bold(if (closing) -1 else 1)
                in ITALIC_TAGS -> if (!selfClosing) out.italic(if (closing) -1 else 1)
            }
        }
        out.flush()
        return HtmlConversion(out.blocks, out.anchors, out.firstHeading)
    }

    /** Index of the '>' that ends the tag starting at [start], honouring quoted attributes. */
    private fun findTagEnd(html: String, start: Int): Int {
        var quote: Char? = null
        var j = start + 1
        while (j < html.length) {
            val c = html[j]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return j
            }
            j++
        }
        return -1
    }

    private fun skipElement(html: String, from: Int, name: String): Int {
        val regex = closeTagRegexes.getOrPut(name) {
            Regex("""</(?:[\w-]+:)?${Regex.escape(name)}\s*>""", RegexOption.IGNORE_CASE)
        }
        return regex.find(html, from)?.range?.last?.plus(1) ?: html.length
    }
}

private class BlockBuilder {
    val blocks = mutableListOf<Block>()
    val anchors = HashMap<String, Int>()
    var firstHeading: String? = null
    var pre = false

    private val sb = StringBuilder()
    private val spans = mutableListOf<Span>()
    private var runStart = 0
    private var boldDepth = 0
    private var italicDepth = 0
    private var headingDepth = 0

    /** Chapter offset at which the block currently being built starts. */
    private var offset = 0

    fun text(raw: String) {
        var t = if (pre) decodeEntities(raw.replace("\r", "")) else decodeEntities(WHITESPACE.replace(raw, " "))
        if (!pre && (sb.isEmpty() || sb.last() == ' ' || sb.last() == '\n')) t = t.trimStart(' ')
        if (t.isNotEmpty()) sb.append(t)
    }

    fun anchor(id: String) {
        if (id.isNotEmpty()) anchors.putIfAbsent(id, offset + sb.length)
    }

    fun lineBreak() {
        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        runStart = runStart.coerceAtMost(sb.length)
    }

    fun bold(delta: Int) {
        closeRun()
        boldDepth = (boldDepth + delta).coerceAtLeast(0)
    }

    fun italic(delta: Int) {
        closeRun()
        italicDepth = (italicDepth + delta).coerceAtLeast(0)
    }

    fun startHeading() {
        flush()
        headingDepth++
    }

    fun endHeading() {
        flush()
        headingDepth = (headingDepth - 1).coerceAtLeast(0)
    }

    private fun closeRun() {
        val bold = boldDepth > 0
        val italic = italicDepth > 0
        if ((bold || italic) && sb.length > runStart) {
            spans.add(Span(runStart, sb.length, bold = bold, italic = italic))
        }
        runStart = sb.length
    }

    fun flush() {
        closeRun()
        var end = sb.length
        while (end > 0 && (sb[end - 1] == ' ' || sb[end - 1] == '\n' || sb[end - 1] == '\u00A0')) end--
        var start = 0
        while (start < end && (sb[start] == ' ' || sb[start] == '\n' || sb[start] == '\u00A0')) start++
        if (end > start) {
            val text = sb.substring(start, end)
            val adjusted = spans.mapNotNull { s ->
                val a = (s.start - start).coerceIn(0, text.length)
                val b = (s.end - start).coerceIn(0, text.length)
                if (b > a) s.copy(start = a, end = b) else null
            }
            val heading = headingDepth > 0
            val pieces = Block(text, heading, adjusted).splitIfLong()
            blocks.addAll(pieces)
            offset += pieces.sumOf { it.text.length + 1 }
            if (heading && firstHeading == null) firstHeading = text.replace('\n', ' ')
        }
        sb.setLength(0)
        spans.clear()
        runStart = 0
    }

    companion object {
        val WHITESPACE = Regex("[ \t\r\n\u000C]+")
    }
}

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to "\u00A0", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
    "mdash" to "—", "ndash" to "–", "hellip" to "…", "minus" to "−",
    "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚",
    "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
    "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›",
    "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°",
    "middot" to "·", "bull" to "•", "times" to "×", "divide" to "÷",
    "shy" to "", "zwnj" to "", "zwj" to "", "lrm" to "", "rlm" to "",
    "sect" to "§", "para" to "¶", "dagger" to "†", "Dagger" to "‡",
    "prime" to "′", "Prime" to "″", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
    "euro" to "€", "pound" to "£", "cent" to "¢", "yen" to "¥",
    "iexcl" to "¡", "iquest" to "¿", "szlig" to "ß",
    "aacute" to "á", "agrave" to "à", "acirc" to "â", "auml" to "ä", "atilde" to "ã", "aring" to "å",
    "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë",
    "iacute" to "í", "igrave" to "ì", "icirc" to "î", "iuml" to "ï",
    "oacute" to "ó", "ograve" to "ò", "ocirc" to "ô", "ouml" to "ö", "otilde" to "õ", "oslash" to "ø",
    "uacute" to "ú", "ugrave" to "ù", "ucirc" to "û", "uuml" to "ü",
    "ccedil" to "ç", "ntilde" to "ñ", "yacute" to "ý", "yuml" to "ÿ",
    "Aacute" to "Á", "Agrave" to "À", "Auml" to "Ä", "Eacute" to "É", "Egrave" to "È",
    "Ouml" to "Ö", "Uuml" to "Ü", "Ccedil" to "Ç", "Ntilde" to "Ñ",
    "aelig" to "æ", "AElig" to "Æ", "oelig" to "œ", "OElig" to "Œ",
)

/** Decodes named and numeric HTML entities. Unknown entities are left as-is. */
internal fun decodeEntities(s: String): String {
    if ('&' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '&') {
            val semi = s.indexOf(';', i + 1)
            if (semi > i + 1 && semi - i <= 12) {
                val name = s.substring(i + 1, semi)
                val decoded: String? = if (name.startsWith("#")) {
                    val code = if (name.length > 1 && (name[1] == 'x' || name[1] == 'X')) {
                        name.substring(2).toIntOrNull(16)
                    } else {
                        name.substring(1).toIntOrNull()
                    }
                    if (code != null && code in 1..0x10FFFF) {
                        StringBuilder().appendCodePoint(code).toString()
                    } else {
                        null
                    }
                } else {
                    NAMED_ENTITIES[name]
                }
                if (decoded != null) {
                    out.append(decoded)
                    i = semi + 1
                    continue
                }
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/** Very long paragraphs are split (at sentence ends) so pages can be laid out quickly. */
internal fun Block.splitIfLong(maxLength: Int = 3000): List<Block> {
    if (text.length <= maxLength) return listOf(this)
    val result = mutableListOf<Block>()
    var start = 0
    while (text.length - start > maxLength) {
        val window = text.substring(start, start + maxLength)
        var cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("? "), window.lastIndexOf("! "), window.lastIndexOf('\n'))
        if (cut < maxLength / 3) cut = window.lastIndexOf(' ')
        if (cut <= 0) cut = maxLength - 1
        val end = start + cut + 1
        result.add(slice(start, end))
        start = end
        while (start < text.length && text[start] == ' ') start++
    }
    if (start < text.length) result.add(slice(start, text.length))
    return result
}

private fun Block.slice(from: Int, to: Int): Block {
    val piece = text.substring(from, to).trimEnd()
    val pieceSpans = spans.mapNotNull { s ->
        val a = (s.start - from).coerceIn(0, piece.length)
        val b = (s.end - from).coerceIn(0, piece.length)
        if (b > a) s.copy(start = a, end = b) else null
    }
    return Block(piece, heading, pieceSpans)
}
