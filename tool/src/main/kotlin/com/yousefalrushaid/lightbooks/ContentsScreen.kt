package com.yousefalrushaid.lightbooks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

private const val CONTENTS_ROW_UNITS = 3.5f

/** Chapter list (or page list for PDFs). Returns the chosen place. */
class ContentsScreen(
    sealedActivity: SealedLightActivity,
    private val entries: List<TocEntry>,
    private val currentIndex: Int,
    private val title: String = "Contents",
) : SimpleLightScreen<Position>(sealedActivity) {

    @Composable
    override fun Content() {
        BooksTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                BackTopBar(title = title, onBack = { goBack() })
                if (entries.isEmpty()) {
                    CenteredMessage("This book has no chapter list.")
                } else {
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
                LightLazyScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp()),
                    listState = listState,
                    uniformItemHeightGridUnits = CONTENTS_ROW_UNITS,
                ) {
                    itemsIndexed(entries) { index, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CONTENTS_ROW_UNITS.gridUnitsAsDp())
                                .lightClickable { goBack(Position(entry.chapter, entry.offset)) }
                                .padding(start = (entry.depth * 1.5f).gridUnitsAsDp(), end = 1f.gridUnitsAsDp()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LightText(
                                text = entry.title,
                                variant = if (entry.depth == 0) LightTextVariant.Copy else LightTextVariant.Paragraph,
                                underline = index == currentIndex,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                }
            }
        }
    }
}
