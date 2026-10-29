package com.yousefalrushaid.lightbooks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val BOOK_ROW_UNITS = 4.5f

class LibraryViewModel(
    private val store: BookStore,
    dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {

    /** null while the first scan is running. */
    private val _books = MutableStateFlow<List<LibraryEntry>?>(null)
    val books: StateFlow<List<LibraryEntry>?> = _books.asStateFlow()

    init {
        viewModelScope.launch { SettingsRepository.flow(dataStore).collect { it.applyTheme() } }
        // Re-scan whenever the Tool Manager reports new or removed files.
        viewModelScope.launch { BookStore.changes.collect { refresh() } }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _books.value = store.scan() }
    }

    fun remove(info: BookInfo) {
        viewModelScope.launch {
            store.delete(info)
            _books.value = store.scan()
        }
    }
}

@InitialScreen
class LibraryScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LibraryViewModel>(sealedActivity) {

    override val viewModelClass: Class<LibraryViewModel>
        get() = LibraryViewModel::class.java

    override fun createViewModel(): LibraryViewModel =
        LibraryViewModel(BookStore.get(lightContext.filesDir), lightContext.dataStore)

    @Composable
    override fun Content() {
        val books by viewModel.books.collectAsState()
        var editing by remember { mutableStateOf(false) }
        val list = books

        BooksTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Books"),
                    rightButton = if (list.isNullOrEmpty()) null else LightBarButton.Text(
                        text = if (editing) "DONE" else "EDIT",
                        onClick = { editing = !editing },
                    ),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
                when {
                    list == null -> CenteredMessage("Loading…")
                    list.isEmpty() -> CenteredMessage(
                        message = "No books yet",
                        detail = "On your computer, open the Tool Manager link shown on your phone, " +
                            "choose Books, and upload EPUB, PDF or TXT files.",
                    )
                    else -> LightLazyScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1f.gridUnitsAsDp()),
                        uniformItemHeightGridUnits = BOOK_ROW_UNITS,
                    ) {
                        items(list, key = { it.info.fileName }) { entry ->
                            BookRow(
                                entry = entry,
                                editing = editing,
                                onOpen = { open(entry.info) },
                                onRemove = {
                                    navigateTo({ RemoveBookScreen(it, entry.info) }) { removed ->
                                        if (removed) viewModel.remove(entry.info)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun open(info: BookInfo) {
        if (info.error != null) return
        if (info.format == BookFormat.PDF) {
            navigateTo({ PdfReaderScreen(it, info) })
        } else {
            navigateTo({ ReaderScreen(it, info) })
        }
    }
}

@Composable
private fun BookRow(entry: LibraryEntry, editing: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val info = entry.info
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BOOK_ROW_UNITS.gridUnitsAsDp())
            .lightClickable(enabled = !editing && info.error == null, onClick = onOpen)
            .padding(end = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = info.title,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = entry.detailLine(),
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (editing) {
            LightIcon(
                icon = LightIcons.TRASH,
                contentDescription = "Remove ${info.title}",
                modifier = Modifier.lightClickable(onClick = onRemove),
            )
        }
    }
}

/** "Jane Austen · 42%", "PDF · 120 pages · New", "Can't open this file". */
private fun LibraryEntry.detailLine(): String {
    info.error?.let { return it }
    val status = when {
        state.lastOpened == 0L -> "New"
        state.progress >= 0.995f -> "Finished"
        else -> state.progress.asPercent()
    }
    val parts = when (info.format) {
        BookFormat.PDF -> listOf("PDF", "${info.totalLength} pages", status)
        else -> listOf(info.author, status)
    }
    return parts.filter { it.isNotBlank() }.joinToString(" · ")
}

/** "Remove this book?" confirmation, in the same style as LightOS. Returns true to remove. */
class RemoveBookScreen(
    sealedActivity: SealedLightActivity,
    private val info: BookInfo,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        BooksTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                BackTopBar(title = info.title, onBack = { goBack(false) })
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "Remove this book and its bookmarks from your phone?",
                        variant = LightTextVariant.Copy,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(text = "REMOVE", onClick = { goBack(true) }),
                    ),
                )
            }
        }
    }
}
