package com.yousefalrushaid.lightbooks

import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** Reads EPUB 2 and EPUB 3 files: metadata, reading order (spine) and table of contents. */
internal object EpubParser {
    private val ATTR = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val ITEM = Regex("""<(?:[\w-]+:)?item\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ITEMREF = Regex("""<(?:[\w-]+:)?itemref\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val SPINE = Regex("""<(?:[\w-]+:)?spine\b([^>]*)>(.*?)</(?:[\w-]+:)?spine>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ROOTFILE = Regex("""<(?:[\w-]+:)?rootfile\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("<[^>]*>")

    private class Item(val id: String, val href: String, val mediaType: String, val properties: String)

    fun parse(file: File): ParsedBook = ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().associateBy { it.name }
        val lowerEntries = entries.values.associateBy { it.name.lowercase() }
        fun read(path: String): String? {
            val entry = entries[path] ?: lowerEntries[path.lowercase()] ?: return null
            return zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }

        val container = read("META-INF/container.xml") ?: error("Missing META-INF/container.xml")
        val opfPath = ROOTFILE.findAll(container)
            .map { attrs(it.value)["full-path"].orEmpty() }
            .firstOrNull { it.isNotEmpty() }
            ?: error("No package file listed in container.xml")
        val opf = read(opfPath) ?: error("Missing package file $opfPath")
        val opfDir = opfPath.substringBeforeLast('/', "")

        val title = elementText(opf, "title")?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension.prettyFileTitle()
        val author = elementText(opf, "creator").orEmpty()

        val items = ITEM.findAll(opf).map { m ->
            val a = attrs(m.value)
            Item(
                id = a["id"].orEmpty(),
                href = resolve(opfDir, a["href"].orEmpty()),
                mediaType = a["media-type"].orEmpty(),
                properties = a["properties"].orEmpty(),
            )
        }.filter { it.id.isNotEmpty() && it.href.isNotEmpty() }.toList()
        val itemsById = items.associateBy { it.id }

        val spineMatch = SPINE.find(opf) ?: error("Book has no reading order (spine)")
        val spineAttrs = attrs(spineMatch.groupValues[1])
        val spineItems = ITEMREF.findAll(spineMatch.groupValues[2])
            .map { attrs(it.value) }
            .filter { it["linear"] != "no" }
            .mapNotNull { ref -> ref["idref"]?.let { itemsById[it] } }
            .filter { it.mediaType.contains("html", ignoreCase = true) || it.href.endsWith("htm", ignoreCase = true) || it.href.endsWith("html", ignoreCase = true) }
            .toList()

        // Convert every chapter file. Files with no text (cover images, blank pages) are dropped.
        val chapters = mutableListOf<Chapter>()
        val chapterForHref = HashMap<String, Int>()
        val anchorsForHref = HashMap<String, Map<String, Int>>()
        val pendingHrefs = mutableListOf<String>()
        for (item in spineItems) {
            val html = read(item.href) ?: continue
            val converted = HtmlToBlocks.convert(html)
            if (converted.blocks.isEmpty()) {
                pendingHrefs.add(item.href)
                continue
            }
            val index = chapters.size
            chapters.add(Chapter(title = converted.firstHeading.orEmpty(), blocks = converted.blocks))
            chapterForHref[item.href] = index
            anchorsForHref[item.href] = converted.anchors
            // Links to dropped files point at the next real chapter.
            pendingHrefs.forEach { chapterForHref[it] = index }
            pendingHrefs.clear()
        }
        if (chapters.isEmpty()) error("This book has no readable text")

        // Table of contents: EPUB 3 nav document first, then EPUB 2 NCX.
        val navItem = items.firstOrNull { it.properties.split(' ').contains("nav") }
        val ncxItem = spineAttrs["toc"]?.let { itemsById[it] } ?: items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        val rawToc: List<Triple<String, String, Int>> = navItem?.let { nav ->
            read(nav.href)?.let { parseNav(it, nav.href.substringBeforeLast('/', "")) }
        }?.takeIf { it.isNotEmpty() } ?: ncxItem?.let { ncx ->
            read(ncx.href)?.let { parseNcx(it, ncx.href.substringBeforeLast('/', "")) }
        }.orEmpty()

        val toc = rawToc.mapNotNull { (label, target, depth) ->
            val path = target.substringBefore('#')
            val fragment = target.substringAfter('#', "")
            val chapter = chapterForHref[path] ?: return@mapNotNull null
            val offset = if (fragment.isEmpty()) 0 else anchorsForHref[path]?.get(fragment) ?: 0
            TocEntry(title = label, chapter = chapter, offset = offset, depth = depth.coerceIn(0, 3))
        }

        // Give each chapter a title: its first TOC entry, else its first heading, else "Section N".
        val titled = chapters.mapIndexed { i, ch ->
            val fromToc = toc.firstOrNull { it.chapter == i }?.title
            ch.copy(title = fromToc ?: ch.title.ifBlank { "Section ${i + 1}" })
        }
        val finalToc = toc.ifEmpty { titled.mapIndexed { i, ch -> TocEntry(ch.title, i) } }

        ParsedBook(title = title.trim(), author = author.trim(), chapters = titled, toc = finalToc)
    }

    /** EPUB 3 navigation document: the <nav epub:type="toc"> list. */
    private fun parseNav(html: String, baseDir: String): List<Triple<String, String, Int>> {
        val navStart = Regex("""<nav\b[^>]*(?:epub:)?type\s*=\s*["'][^"']*\btoc\b""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<nav\b""", RegexOption.IGNORE_CASE).find(html)
            ?: return emptyList()
        val navEnd = html.indexOf("</nav", navStart.range.first, ignoreCase = true).let { if (it < 0) html.length else it }
        val section = html.substring(navStart.range.first, navEnd)
        val token = Regex("""<(/?)ol\b[^>]*>|<a\b([^>]*)>(.*?)</a\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val result = mutableListOf<Triple<String, String, Int>>()
        var depth = -1
        for (m in token.findAll(section)) {
            if (m.value.startsWith("<a", ignoreCase = true)) {
                val href = attrs(m.groupValues[2])["href"] ?: continue
                val label = cleanLabel(m.groupValues[3])
                if (label.isNotEmpty()) result.add(Triple(label, resolve(baseDir, href), depth.coerceAtLeast(0)))
            } else if (m.groupValues[1] == "/") {
                depth--
            } else {
                depth++
            }
        }
        return result
    }

    /** EPUB 2 NCX navMap. */
    private fun parseNcx(xml: String, baseDir: String): List<Triple<String, String, Int>> {
        val token = Regex(
            """<(/?)navPoint\b[^>]*>|<text\b[^>]*>(.*?)</text\s*>|<content\b([^>]*)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val result = mutableListOf<Triple<String, String, Int>>()
        var depth = -1
        var label: String? = null
        for (m in token.findAll(xml)) {
            val v = m.value
            when {
                v.startsWith("<navPoint", ignoreCase = true) -> { depth++; label = null }
                v.startsWith("</navPoint", ignoreCase = true) -> depth--
                v.startsWith("<text", ignoreCase = true) -> label = cleanLabel(m.groupValues[2])
                v.startsWith("<content", ignoreCase = true) -> {
                    val src = attrs(m.groupValues[3])["src"]
                    val l = label
                    if (src != null && !l.isNullOrEmpty() && depth >= 0) {
                        result.add(Triple(l, resolve(baseDir, src), depth))
                    }
                    label = null
                }
            }
        }
        return result
    }

    private fun cleanLabel(raw: String): String =
        decodeEntities(TAGS.replace(raw, " ")).replace(Regex("\\s+"), " ").trim()

    /** Text of the first <dc:name> (or <name>) element in the package metadata. */
    private fun elementText(opf: String, name: String): String? {
        val m = Regex("""<(?:dc:)?$name\b[^>]*>(.*?)</(?:dc:)?$name\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(opf) ?: return null
        return cleanLabel(m.groupValues[1])
    }

    private fun attrs(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { m ->
            m.groupValues[1].substringAfter(':').lowercase() to decodeEntities(m.groupValues[2].ifEmpty { m.groupValues[3] })
        }

    /** Resolves [href] (URL-encoded, maybe with ../) against [baseDir] inside the zip. */
    internal fun resolve(baseDir: String, href: String): String {
        val fragment = href.substringAfter('#', "")
        val pathPart = href.substringBefore('#')
        if (pathPart.isEmpty()) return if (fragment.isEmpty()) "" else "#$fragment"
        val decoded = runCatching { URLDecoder.decode(pathPart.replace("+", "%2B"), "UTF-8") }.getOrDefault(pathPart)
        val segments = ArrayDeque<String>()
        val combined = if (decoded.startsWith("/") || baseDir.isEmpty()) decoded else "$baseDir/$decoded"
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        val path = segments.joinToString("/")
        return if (fragment.isEmpty()) path else "$path#$fragment"
    }
}

/** "the_great_gatsby-fitzgerald" -> "The great gatsby fitzgerald" */
internal fun String.prettyFileTitle(): String =
    replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()
        .replaceFirstChar { it.uppercaseChar() }
        .ifEmpty { "Untitled" }
