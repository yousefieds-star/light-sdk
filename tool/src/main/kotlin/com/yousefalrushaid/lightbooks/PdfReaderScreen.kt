package com.yousefalrushaid.lightbooks

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
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
import kotlin.math.sqrt

sealed interface PdfUi {
    data object Loading : PdfUi
    data class Failed(val message: String) : PdfUi
    data class Showing(
        val image: ImageBitmap,
        val pageIndex: Int,
        val pageCount: Int,
        val bookmarked: Boolean,
    ) : PdfUi {
        val progress: Float get() = if (pageCount <= 1) 1f else (pageIndex + 1).toFloat() / pageCount
    }
}

/** Opens a PDF, closing the file again if it can't be read (e.g. password-protected). */
internal fun openRenderer(file: java.io.File): PdfRenderer {
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    return try {
        PdfRenderer(descriptor)
    } catch (e: Throwable) {
        runCatching { descriptor.close() }
        throw e
    }
}

/** Renders pages a little larger than the screen so zooming in stays sharp. */
private const val RENDER_SCALE = 1.5f
private const val MAX_RENDER_PIXELS = 12_000_000

class PdfReaderViewModel(
    private val store: BookStore,
    val info: BookInfo,
    dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    val settings: StateFlow<ReaderSettings> =
        SettingsRepository.flow(dataStore).stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())

    private val _ui = MutableStateFlow<PdfUi>(PdfUi.Loading)
    val ui: StateFlow<PdfUi> = _ui.asStateFlow()

    private val _readingState = MutableStateFlow(ReadingState())
    val readingState: StateFlow<ReadingState> = _readingState.asStateFlow()

    private val lock = Mutex()
    private var renderer: PdfRenderer? = null
    private var pageIndex = 0
    private var targetWidthPx = 0

    val pageCount: Int get() = renderer?.pageCount ?: info.totalLength

    init {
        viewModelScope.launch {
            val state = store.updateState(info.fileName) { it.copy(lastOpened = System.currentTimeMillis()) }
            _readingState.value = state
            try {
                lock.withLock {
                    renderer = withContext(Dispatchers.IO) { openRenderer(store.fileFor(info)) }
                    pageIndex = state.position.offset.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                }
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.value = PdfUi.Failed("This PDF couldn't be opened.")
            }
        }
    }

    fun setTargetWidth(widthPx: Int) {
        if (widthPx <= 0 || widthPx == targetWidthPx) return
        targetWidthPx = widthPx
        viewModelScope.launch { render() }
    }

    fun next() = goTo(pageIndex + 1)

    fun previous() = goTo(pageIndex - 1)

    fun jumpTo(target: Position) = goTo(target.offset)

    fun jumpToFraction(fraction: Float) = goTo(((pageCount - 1) * fraction.coerceIn(0f, 1f) + 0.5f).toInt())

    private fun goTo(index: Int) {
        val clamped = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (clamped == pageIndex && _ui.value is PdfUi.Showing) return
        pageIndex = clamped
        viewModelScope.launch {
            render()
            persist()
        }
    }

    fun toggleBookmark() {
        val page = pageIndex
        viewModelScope.launch {
            _readingState.value = store.updateState(info.fileName) { state ->
                val onPage = state.bookmarks.filter { it.position.offset == page }
                if (onPage.isNotEmpty()) {
                    state.copy(bookmarks = state.bookmarks - onPage.toSet())
                } else {
                    val mark = Bookmark(
                        position = Position(0, page),
                        label = "Page ${page + 1}",
                        progress = (page + 1).toFloat() / pageCount.coerceAtLeast(1),
                        createdAt = System.currentTimeMillis(),
                    )
                    state.copy(bookmarks = (state.bookmarks + mark).sortedBy { it.position.offset })
                }
            }
            refreshBookmarkFlag()
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            _readingState.value = store.updateState(info.fileName) { it.copy(bookmarks = it.bookmarks - bookmark) }
            refreshBookmarkFlag()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE -> {
            next(); true
        }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
            previous(); true
        }
        else -> false
    }

    override fun onCleared() {
        super.onCleared()
        // The view model scope is cancelled now, so close on a fresh scope, once any page that is
        // still rendering has finished.
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            lock.withLock {
                runCatching { renderer?.close() }
                renderer = null
            }
        }
    }

    private suspend fun render() {
        lock.withLock {
            val r = renderer ?: return
            if (targetWidthPx <= 0) return
            val index = pageIndex.coerceIn(0, r.pageCount - 1)
            val image: ImageBitmap = try {
                withContext(Dispatchers.IO) { renderPage(r, index) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return // A damaged page: keep showing the previous one.
            }
            _ui.value = PdfUi.Showing(
                image = image,
                pageIndex = index,
                pageCount = r.pageCount,
                bookmarked = _readingState.value.bookmarks.any { it.position.offset == index },
            )
        }
    }

    private fun renderPage(r: PdfRenderer, index: Int): ImageBitmap = r.openPage(index).use { page ->
        var scale = targetWidthPx * RENDER_SCALE / page.width.coerceAtLeast(1)
        val pixels = page.width * scale * page.height * scale
        if (pixels > MAX_RENDER_PIXELS) scale *= sqrt(MAX_RENDER_PIXELS / pixels)
        val bitmap = Bitmap.createBitmap(
            (page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(0xFFFFFFFF.toInt()) // PDF pages are transparent; give them paper.
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap.asImageBitmap()
    }

    private fun refreshBookmarkFlag() {
        val shown = _ui.value as? PdfUi.Showing ?: return
        _ui.value = shown.copy(bookmarked = _readingState.value.bookmarks.any { it.position.offset == shown.pageIndex })
    }

    private fun persist() {
        val shown = _ui.value as? PdfUi.Showing ?: return
        viewModelScope.launch {
            _readingState.value = store.updateState(info.fileName) {
                it.copy(
                    position = Position(0, shown.pageIndex),
                    progress = shown.progress,
                    lastOpened = System.currentTimeMillis(),
                )
            }
        }
    }
}

/** In the dark theme, pages are shown white-on-black to match the phone. */
private val INVERT = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

class PdfReaderScreen(
    sealedActivity: SealedLightActivity,
    private val info: BookInfo,
) : LightScreen<Unit, PdfReaderViewModel>(sealedActivity) {

    override val viewModelClass: Class<PdfReaderViewModel>
        get() = PdfReaderViewModel::class.java

    override fun createViewModel(): PdfReaderViewModel =
        PdfReaderViewModel(BookStore.get(lightContext.filesDir), info, lightContext.dataStore)

    @Composable
    override fun Content() {
        val ui by viewModel.ui.collectAsState()
        val settings by viewModel.settings.collectAsState()
        var chromeVisible by remember { mutableStateOf(false) }
        var zoom by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        val shown = ui as? PdfUi.Showing

        // A new page always starts un-zoomed.
        LaunchedEffect(shown?.pageIndex) {
            zoom = 1f
            pan = Offset.Zero
        }

        BooksTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 1f.gridUnitsAsDp()),
                ) {
                    val widthPx = constraints.maxWidth
                    val heightPx = constraints.maxHeight
                    LaunchedEffect(widthPx) { viewModel.setTargetWidth(widthPx) }

                    when (val state = ui) {
                        PdfUi.Loading -> CenteredMessage("Opening…")
                        is PdfUi.Failed -> CenteredMessage(state.message)
                        is PdfUi.Showing -> Image(
                            bitmap = state.image,
                            contentDescription = "Page ${state.pageIndex + 1}",
                            contentScale = ContentScale.Fit,
                            colorFilter = if (settings.darkTheme) INVERT else null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = zoom,
                                    scaleY = zoom,
                                    translationX = pan.x,
                                    translationY = pan.y,
                                ),
                        )
                    }

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
                                // Pinch to zoom, drag to move around a zoomed page.
                                detectTransformGestures { _, panChange, zoomChange, _ ->
                                    zoom = (zoom * zoomChange).coerceIn(1f, 4f)
                                    val maxX = (zoom - 1f) * widthPx / 2f
                                    val maxY = (zoom - 1f) * heightPx / 2f
                                    pan = if (zoom <= 1f) {
                                        Offset.Zero
                                    } else {
                                        Offset(
                                            (pan.x + panChange.x).coerceIn(-maxX, maxX),
                                            (pan.y + panChange.y).coerceIn(-maxY, maxY),
                                        )
                                    }
                                }
                            },
                    )
                }
                ReaderFooter(
                    left = info.title,
                    right = shown?.let { "${it.pageIndex + 1} / ${it.pageCount}" }.orEmpty(),
                    bookmarked = shown?.bookmarked == true,
                )
            }

            if (chromeVisible) {
                ReaderChrome(
                    title = info.title,
                    subtitle = shown?.let { "Page ${it.pageIndex + 1} of ${it.pageCount}" }.orEmpty(),
                    progress = shown?.progress ?: 0f,
                    bookmarked = shown?.bookmarked == true,
                    onBack = { goBack() },
                    onDismiss = { chromeVisible = false },
                    onJumpToFraction = viewModel::jumpToFraction,
                    onContents = {
                        val entries = List(viewModel.pageCount) { TocEntry("Page ${it + 1}", 0, it) }
                        val current = shown?.pageIndex ?: 0
                        navigateTo({ ContentsScreen(it, entries, current, title = "Pages") }) { target ->
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
                                showTextOptions = false,
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
