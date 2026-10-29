package com.yousefalrushaid.lightbooks

import android.view.KeyEvent
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs

sealed interface ReaderUi {
    data object Loading : ReaderUi
    data class Failed(val message: String) : ReaderUi
    data class Showing(
        val page: TextPage,
        val pageIndex: Int,
        val pageCount: Int,
        val chapterTitle: String,
        val progress: Float,
        val bookmarked: Boolean,
    ) : ReaderUi
}

private val NEXT_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE,
)
private val PREVIOUS_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP,
)

class ReaderViewModel(
    private val store: BookStore,
    val info: BookInfo,
    dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    val settings: StateFlow<ReaderSettings> =
        SettingsRepository.flow(dataStore).stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    private val _ui = MutableStateFlow<ReaderUi>(ReaderUi.Loading)
    val ui: StateFlow<ReaderUi> = _ui.asStateFlow()

    private val _readingState = MutableStateFlow(ReadingState())
    val readingState: StateFlow<ReadingState> = _readingState.asStateFlow()

    // Everything below is guarded by [lock].
    private val lock = Mutex()
    private var book: ParsedBook? = null
    private var chapterStarts = IntArray(0)
    private var totalLength = 1
    private var paginator: Paginator? = null
    private val pageCache = HashMap<Int, List<TextPage>>()
    private var position = Position()
    private var pages: List<TextPage> = emptyList()
    private var pageIndex = 0

    var toc: List<TocEntry> = emptyList()
        private set

    init {
        viewModelScope.launch {
            val state = store.updateState(info.fileName) { it.copy(lastOpened = System.currentTimeMillis()) }
            _readingState.value = state
            try {
                val parsed = store.loadBook(info)
                lock.withLock {
                    book = parsed
                    toc = parsed.toc
                    var acc = 0
                    chapterStarts = IntArray(parsed.chapters.size) { i -> acc.also { acc += parsed.chapters[i].length } }
                    totalLength = acc.coerceAtLeast(1)
                    position = clamp(state.position)
                }
                showPosition()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.value = ReaderUi.Failed("This book couldn't be opened.")
            }
        }
    }

    /** Called by the screen with the exact page size and text styles. */
    fun setPaginator(newPaginator: Paginator) {
        viewModelScope.launch {
            val changed = lock.withLock {
                if (paginator?.spec == newPaginator.spec) {
                    false
                } else {
                    paginator = newPaginator
                    pageCache.clear()
                    true
                }
            }
            if (changed) showPosition()
        }
    }

    fun next() = turn(forward = true)

    fun previous() = turn(forward = false)

    private fun turn(forward: Boolean) {
        viewModelScope.launch {
            val moved = lock.withLock {
                val b = book ?: return@withLock false
                if (paginator == null || pages.isEmpty()) return@withLock false
                if (forward) {
                    if (pageIndex < pages.lastIndex) {
                        pageIndex++
                    } else if (position.chapter < b.chapters.lastIndex) {
                        pages = pagesFor(position.chapter + 1)
                        pageIndex = 0
                    } else {
                        return@withLock false
                    }
                } else {
                    if (pageIndex > 0) {
                        pageIndex--
                    } else if (position.chapter > 0) {
                        pages = pagesFor(position.chapter - 1)
                        pageIndex = pages.lastIndex
                    } else {
                        return@withLock false
                    }
                }
                val page = pages[pageIndex]
                position = Position(page.chapter, page.start)
                publish()
                true
            }
            if (moved) {
                persist()
                prefetchNeighbours()
            }
        }
    }

    fun jumpTo(target: Position) {
        viewModelScope.launch {
            lock.withLock { position = clamp(target) }
            showPosition()
            persist()
        }
    }

    fun jumpToFraction(fraction: Float) {
        viewModelScope.launch {
            lock.withLock {
                val target = (fraction.coerceIn(0f, 1f) * (totalLength - 1)).toInt()
                val chapter = chapterStarts.indexOfLast { it <= target }.coerceAtLeast(0)
                position = clamp(Position(chapter, target - (chapterStarts.getOrNull(chapter) ?: 0)))
            }
            showPosition()
            persist()
        }
    }

    /** Index of the contents entry for the current place, so the list opens there. */
    fun currentTocIndex(): Int {
        val p = position
        return toc.indexOfLast { it.chapter < p.chapter || (it.chapter == p.chapter && it.offset <= p.offset) }
            .coerceAtLeast(0)
    }

    fun toggleBookmark() {
        val shown = _ui.value as? ReaderUi.Showing ?: return
        val page = shown.page
        viewModelScope.launch {
            val updated = store.updateState(info.fileName) { state ->
                val onPage = state.bookmarks.filter { it.isOn(page) }
                if (onPage.isNotEmpty()) {
                    state.copy(bookmarks = state.bookmarks - onPage.toSet())
                } else {
                    val mark = Bookmark(
                        position = Position(page.chapter, page.start),
                        label = shown.chapterTitle,
                        snippet = page.pieces.firstOrNull()?.text?.text?.take(90).orEmpty(),
                        progress = shown.progress,
                        createdAt = System.currentTimeMillis(),
                    )
                    state.copy(bookmarks = (state.bookmarks + mark).sortedBy { it.progress })
                }
            }
            _readingState.value = updated
            refreshShown()
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            _readingState.value = store.updateState(info.fileName) { it.copy(bookmarks = it.bookmarks - bookmark) }
            refreshShown()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        in NEXT_KEYS -> { next(); true }
        in PREVIOUS_KEYS -> { previous(); true }
        else -> false
    }

    override fun onAppPause() {
        super.onAppPause()
        persist()
    }

    // --- internals ---

    private suspend fun showPosition() {
        lock.withLock {
            if (book == null || paginator == null) return
            pages = pagesFor(position.chapter)
            pageIndex = pages.indexOfLast { it.start <= position.offset }.coerceAtLeast(0)
            publish()
        }
        prefetchNeighbours()
    }

    private suspend fun refreshShown() {
        lock.withLock { if (pages.isNotEmpty()) publish() }
    }

    /** Requires [lock]. */
    private suspend fun pagesFor(chapter: Int): List<TextPage> {
        pageCache[chapter]?.let { return it }
        val b = book ?: return emptyList()
        val p = paginator ?: return emptyList()
        val result = withContext(Dispatchers.Default) { p.paginate(chapter, b.chapters[chapter]) }
        pageCache[chapter] = result
        // Keep only the chapters around the reader in memory.
        pageCache.keys.filter { abs(it - chapter) > 1 && abs(it - position.chapter) > 1 }.forEach { pageCache.remove(it) }
        return result
    }

    /** Lays out the next and previous chapters in the background, so turning into them is instant. */
    private fun prefetchNeighbours() {
        viewModelScope.launch {
            val (b, p, wanted) = lock.withLock {
                val b = book ?: return@launch
                val p = paginator ?: return@launch
                val c = position.chapter
                val wanted = listOfNotNull(
                    (c + 1).takeIf { pageIndex >= pages.lastIndex - 1 && it <= b.chapters.lastIndex },
                    (c - 1).takeIf { pageIndex <= 1 && it >= 0 },
                ).filter { it !in pageCache }
                Triple(b, p, wanted)
            }
            for (chapter in wanted) {
                val laidOut = withContext(Dispatchers.Default) { p.paginate(chapter, b.chapters[chapter]) }
                lock.withLock {
                    if (paginator?.spec == p.spec && chapter !in pageCache) pageCache[chapter] = laidOut
                }
            }
        }
    }

    /** Requires [lock]. */
    private fun publish() {
        val b = book ?: return
        val page = pages.getOrNull(pageIndex) ?: return
        val atEnd = page.chapter == b.chapters.lastIndex && pageIndex == pages.lastIndex
        _ui.value = ReaderUi.Showing(
            page = page,
            pageIndex = pageIndex,
            pageCount = pages.size,
            chapterTitle = chapterTitleAt(page.chapter, page.start),
            progress = if (atEnd) 1f else progressOf(page.chapter, page.end),
            bookmarked = _readingState.value.bookmarks.any { it.isOn(page) },
        )
    }

    private fun persist() {
        val shown = _ui.value as? ReaderUi.Showing ?: return
        val place = Position(shown.page.chapter, shown.page.start)
        viewModelScope.launch {
            _readingState.value = store.updateState(info.fileName) {
                it.copy(position = place, progress = shown.progress, lastOpened = System.currentTimeMillis())
            }
        }
    }

    private fun progressOf(chapter: Int, offset: Int): Float =
        ((chapterStarts.getOrNull(chapter) ?: 0) + offset).toFloat() / totalLength

    private fun chapterTitleAt(chapter: Int, offset: Int): String =
        toc.lastOrNull { it.chapter == chapter && it.offset <= offset }?.title
            ?: book?.chapters?.getOrNull(chapter)?.title.orEmpty()

    private fun clamp(p: Position): Position {
        val chapters = book?.chapters ?: return p
        val chapter = p.chapter.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        val length = chapters.getOrNull(chapter)?.length ?: 0
        return Position(chapter, p.offset.coerceIn(0, (length - 1).coerceAtLeast(0)))
    }

    private fun Bookmark.isOn(page: TextPage): Boolean =
        position.chapter == page.chapter && position.offset >= page.start && position.offset < page.end
}

class ReaderScreen(
    sealedActivity: SealedLightActivity,
    private val info: BookInfo,
) : LightScreen<Unit, ReaderViewModel>(sealedActivity) {

    override val viewModelClass: Class<ReaderViewModel>
        get() = ReaderViewModel::class.java

    override fun createViewModel(): ReaderViewModel =
        ReaderViewModel(BookStore.get(lightContext.filesDir), info, lightContext.dataStore)

    @Composable
    override fun Content() {
        val ui by viewModel.ui.collectAsState()
        val settings by viewModel.settings.collectAsState()
        var chromeVisible by remember { mutableStateOf(false) }

        BooksTheme {
            val colors = LightThemeTokens.colors
            val bodyStyle = readerBodyStyle(settings)
            val headingStyle = readerHeadingStyle(settings)
            val shown = ui as? ReaderUi.Showing

            Column(modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(
                            start = 1.5f.gridUnitsAsDp(),
                            end = 1.5f.gridUnitsAsDp(),
                            top = 1.5f.gridUnitsAsDp(),
                        ),
                ) {
                    val measurer = rememberTextMeasurer(cacheSize = 0)
                    val gap = 0.6f.gridUnitsAsDp()
                    val density = LocalDensity.current
                    val spec = PageSpec(
                        widthPx = constraints.maxWidth,
                        // A small safety margin so rounding never clips the last line.
                        heightPx = (constraints.maxHeight * 0.98f).toInt(),
                        paragraphGapPx = with(density) { gap.roundToPx() },
                        bodyStyle = bodyStyle,
                        headingStyle = headingStyle,
                    )
                    LaunchedEffect(spec) { viewModel.setPaginator(Paginator(measurer, spec)) }

                    when (val state = ui) {
                        ReaderUi.Loading -> CenteredMessage("Opening…")
                        is ReaderUi.Failed -> CenteredMessage(state.message)
                        is ReaderUi.Showing -> PageText(state.page, colors.content)
                    }

                    // Tap left third: back a page. Right third: forward. Middle: menu. Or swipe.
                    Spacer(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
                                detectTapGestures { tap ->
                                    val third = size.width / 3f
                                    when {
                                        tap.x < third -> viewModel.previous()
                                        tap.x > third * 2 -> viewModel.next()
                                        else -> chromeVisible = true
                                    }
                                }
                            }
                            .pointerInput(Unit) {
                                var dragged = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dragged = 0f },
                                    onDragEnd = {
                                        val threshold = size.width / 8f
                                        if (dragged < -threshold) viewModel.next()
                                        if (dragged > threshold) viewModel.previous()
                                    },
                                    onHorizontalDrag = { change, amount ->
                                        change.consume()
                                        dragged += amount
                                    },
                                )
                            },
                    )
                }
                ReaderFooter(
                    left = shown?.chapterTitle.orEmpty(),
                    right = shown?.progress?.asPercent().orEmpty(),
                    bookmarked = shown?.bookmarked == true,
                )
            }

            if (chromeVisible) {
                ReaderChrome(
                    title = info.title,
                    subtitle = shown?.let { "Page ${it.pageIndex + 1} of ${it.pageCount} · ${it.chapterTitle}" }.orEmpty(),
                    progress = shown?.progress ?: 0f,
                    bookmarked = shown?.bookmarked == true,
                    onBack = { goBack() },
                    onDismiss = { chromeVisible = false },
                    onJumpToFraction = viewModel::jumpToFraction,
                    onContents = {
                        val entries = viewModel.toc
                        val current = viewModel.currentTocIndex()
                        navigateTo({ ContentsScreen(it, entries, current) }) { target ->
                            chromeVisible = false
                            viewModel.jumpTo(target)
                        }
                    },
                    onToggleBookmark = viewModel::toggleBookmark,
                    onOptions = {
                        navigateTo({
                            ReaderOptionsScreen(
                                sealedActivity = it,
                                readingState = viewModel.readingState,
                                onRemoveBookmark = viewModel::removeBookmark,
                                showTextOptions = true,
                            )
                        }) { target ->
                            chromeVisible = false
                            viewModel.jumpTo(target)
                        }
                    },
                )
            }
        }
    }
}

/** Draws a page exactly as the [Paginator] measured it. */
@Composable
private fun PageText(page: TextPage, color: Color) {
    val density = LocalDensity.current
    Column(modifier = Modifier.fillMaxSize()) {
        for (piece in page.pieces) {
            if (piece.gapAbovePx > 0) {
                Spacer(modifier = Modifier.height(with(density) { piece.gapAbovePx.toDp() }))
            }
            Text(
                text = piece.text,
                style = if (piece.heading) page.spec.headingStyle else page.spec.bodyStyle,
                color = color,
            )
        }
    }
}
