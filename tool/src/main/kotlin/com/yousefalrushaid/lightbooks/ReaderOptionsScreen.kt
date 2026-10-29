package com.yousefalrushaid.lightbooks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Reading settings plus the way into bookmarks. Returns a place if a bookmark was chosen. */
class ReaderOptionsScreen(
    sealedActivity: SealedLightActivity,
    private val readingState: StateFlow<ReadingState>,
    private val onRemoveBookmark: (Bookmark) -> Unit,
    private val showTextOptions: Boolean,
) : SimpleLightScreen<Position>(sealedActivity) {

    @Composable
    override fun Content() {
        val dataStore = lightContext.dataStore
        val settingsFlow = remember { SettingsRepository.flow(dataStore) }
        val settings by settingsFlow.collectAsState(initial = ReaderSettings())
        val state by readingState.collectAsState()
        val scope = rememberCoroutineScope()

        fun change(transform: (ReaderSettings) -> ReaderSettings) {
            val updated = transform(settings)
            updated.applyTheme()
            scope.launch { SettingsRepository.update(dataStore) { transform(it) } }
        }

        BooksTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                BackTopBar(title = "Reading", onBack = { goBack() })
                Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp())) {
                    OptionRow("BOOKMARKS", state.bookmarks.size.toString()) {
                        navigateTo({ BookmarksScreen(it, readingState, onRemoveBookmark) }) { target ->
                            goBack(target)
                        }
                    }
                    if (showTextOptions) {
                        OptionRow("TEXT SIZE", settings.textSize.label) {
                            change { it.copy(textSize = it.textSize.next()) }
                        }
                        OptionRow("FONT", settings.font.label) {
                            change { it.copy(font = it.font.next()) }
                        }
                    }
                    OptionRow("THEME", if (settings.darkTheme) "DARK" else "LIGHT") {
                        change { it.copy(darkTheme = !it.darkTheme) }
                    }
                }
            }
        }
    }
}
