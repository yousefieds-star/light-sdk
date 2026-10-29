package com.yousefalrushaid.lightbooks

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
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.StateFlow

private const val BOOKMARK_ROW_UNITS = 4.5f

/** Saved pages. Tap one to go there; EDIT to remove. */
class BookmarksScreen(
    sealedActivity: SealedLightActivity,
    private val readingState: StateFlow<ReadingState>,
    private val onRemove: (Bookmark) -> Unit,
) : SimpleLightScreen<Position>(sealedActivity) {

    @Composable
    override fun Content() {
        val state by readingState.collectAsState()
        var editing by remember { mutableStateOf(false) }
        val bookmarks = state.bookmarks

        BooksTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                BackTopBar(
                    title = "Bookmarks",
                    onBack = { goBack() },
                    rightButton = if (bookmarks.isEmpty()) null else LightBarButton.Text(
                        text = if (editing) "DONE" else "EDIT",
                        onClick = { editing = !editing },
                    ),
                )
                if (bookmarks.isEmpty()) {
                    CenteredMessage(
                        message = "No bookmarks yet",
                        detail = "While reading, tap the middle of the page, then the star.",
                    )
                } else {
                LightLazyScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp()),
                    uniformItemHeightGridUnits = BOOKMARK_ROW_UNITS,
                ) {
                    items(bookmarks) { mark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(BOOKMARK_ROW_UNITS.gridUnitsAsDp())
                                .lightClickable(enabled = !editing) { goBack(mark.position) }
                                .padding(end = 1f.gridUnitsAsDp()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                LightText(
                                    text = mark.label.ifBlank { "Bookmark" },
                                    variant = LightTextVariant.Copy,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                LightText(
                                    text = listOf(mark.progress.asPercent(), mark.snippet)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (editing) {
                                LightIcon(
                                    icon = LightIcons.TRASH,
                                    contentDescription = "Remove bookmark",
                                    modifier = Modifier.lightClickable { onRemove(mark) },
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}
