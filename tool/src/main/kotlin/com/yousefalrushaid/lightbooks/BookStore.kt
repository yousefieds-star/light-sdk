package com.yousefalrushaid.lightbooks

import com.thelightphone.toolmanager.LightFileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
private data class LibraryIndex(val books: List<BookInfo> = emptyList())

/**
 * Owns everything on disk:
 * - the books themselves (in the Tool Manager "books" folder),
 * - a parsed copy of each EPUB/TXT (so books open fast),
 * - reading state (position, bookmarks) per book.
 */
class BookStore private constructor(filesDir: File) {
    private val booksDir = File(filesDir, "${LightFileProvider.SHARED_DIR}/$BOOKS_DIR")
    private val dataDir = File(filesDir, "lightbooks")
    private val cacheDir = File(dataDir, "cache")
    private val stateDir = File(dataDir, "state")
    private val indexFile = File(dataDir, "library.json")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** The most recently opened parsed book, kept in memory. */
    private var memo: Pair<String, ParsedBook>? = null

    fun fileFor(info: BookInfo): File = File(booksDir, info.fileName)

    /** Finds new, changed and removed books, preparing new ones for reading. */
    suspend fun scan(): List<LibraryEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            booksDir.mkdirs()
            cacheDir.mkdirs()
            stateDir.mkdirs()
            val known = readIndex().associateBy { it.fileName }
            val files = booksDir.listFiles().orEmpty().filter {
                it.isFile && !it.name.startsWith(".") && BookFormat.fromFileName(it.name) != null
            }
            val infos = files.map { file ->
                known[file.name]?.takeIf {
                    it.fileSize == file.length() && it.fileModified == file.lastModified()
                } ?: index(file)
            }
            val present = files.map { it.name }.toSet()
            known.keys.filter { it !in present }.forEach { name ->
                cacheFile(name).delete()
                stateFile(name).delete()
            }
            writeIndex(infos)
            infos.map { LibraryEntry(it, readStateUnlocked(it.fileName)) }
                .sortedWith(
                    compareByDescending<LibraryEntry> { it.state.lastOpened }
                        .thenBy { it.info.title.lowercase() }
                )
        }
    }

    /** Parsed text of an EPUB/TXT book. */
    suspend fun loadBook(info: BookInfo): ParsedBook = withContext(Dispatchers.IO) {
        mutex.withLock {
            memo?.takeIf { it.first == info.fileName }?.second
                ?: (readCache(info.fileName) ?: parse(fileFor(info), info.format).also { writeCache(info.fileName, it) })
                    .also { memo = info.fileName to it }
        }
    }

    suspend fun readState(fileName: String): ReadingState = withContext(Dispatchers.IO) {
        mutex.withLock { readStateUnlocked(fileName) }
    }

    suspend fun updateState(fileName: String, transform: (ReadingState) -> ReadingState): ReadingState =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = transform(readStateUnlocked(fileName))
                writeAtomically(stateFile(fileName), json.encodeToString(ReadingState.serializer(), updated))
                updated
            }
        }

    suspend fun delete(info: BookInfo) = withContext(Dispatchers.IO) {
        mutex.withLock {
            fileFor(info).delete()
            cacheFile(info.fileName).delete()
            stateFile(info.fileName).delete()
            if (memo?.first == info.fileName) memo = null
            writeIndex(readIndex().filter { it.fileName != info.fileName })
        }
    }

    // --- internals (call with the mutex held) ---

    private fun index(file: File): BookInfo {
        val format = BookFormat.fromFileName(file.name) ?: BookFormat.TXT
        val fallbackTitle = file.nameWithoutExtension.prettyFileTitle()
        return try {
            if (format == BookFormat.PDF) {
                val pages = openRenderer(file).use { it.pageCount }
                BookInfo(file.name, format, fallbackTitle, "", file.length(), file.lastModified(), pages)
            } else {
                val book = parse(file, format)
                writeCache(file.name, book)
                memo = file.name to book
                BookInfo(
                    fileName = file.name,
                    format = format,
                    title = book.title.ifBlank { fallbackTitle },
                    author = book.author,
                    fileSize = file.length(),
                    fileModified = file.lastModified(),
                    totalLength = book.chapters.sumOf { it.length }.coerceAtLeast(1),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            BookInfo(
                fileName = file.name,
                format = format,
                title = fallbackTitle,
                fileSize = file.length(),
                fileModified = file.lastModified(),
                totalLength = 0,
                error = "Can't open this file",
            )
        }
    }

    private fun parse(file: File, format: BookFormat): ParsedBook = when (format) {
        BookFormat.EPUB -> EpubParser.parse(file)
        BookFormat.TXT -> TxtParser.parse(file.readBytes(), file.nameWithoutExtension.prettyFileTitle())
        BookFormat.PDF -> error("PDF books are not parsed as text")
    }

    private fun readIndex(): List<BookInfo> = runCatching {
        if (!indexFile.isFile) emptyList()
        else json.decodeFromString(LibraryIndex.serializer(), indexFile.readText()).books
    }.getOrDefault(emptyList())

    private fun writeIndex(books: List<BookInfo>) {
        writeAtomically(indexFile, json.encodeToString(LibraryIndex.serializer(), LibraryIndex(books)))
    }

    private fun readCache(fileName: String): ParsedBook? = runCatching {
        val file = cacheFile(fileName)
        if (file.isFile) json.decodeFromString(ParsedBook.serializer(), file.readText()) else null
    }.getOrNull()

    private fun writeCache(fileName: String, book: ParsedBook) {
        writeAtomically(cacheFile(fileName), json.encodeToString(ParsedBook.serializer(), book))
    }

    private fun readStateUnlocked(fileName: String): ReadingState = runCatching {
        val file = stateFile(fileName)
        if (file.isFile) json.decodeFromString(ReadingState.serializer(), file.readText()) else ReadingState()
    }.getOrDefault(ReadingState())

    private fun cacheFile(fileName: String) = File(cacheDir, "${key(fileName)}.json")
    private fun stateFile(fileName: String) = File(stateDir, "${key(fileName)}.json")

    private fun key(fileName: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(fileName.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        @Volatile
        var current: BookStore? = null
            private set

        fun get(filesDir: File): BookStore =
            current ?: synchronized(this) { current ?: BookStore(filesDir).also { current = it } }

        private val _changes = MutableStateFlow(0L)

        /** Ticks whenever books are added or removed through the Tool Manager. */
        val changes: StateFlow<Long> = _changes.asStateFlow()

        fun notifyChanged() {
            _changes.value = System.currentTimeMillis()
        }
    }
}
