package com.yousefalrushaid.lightbooks

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay

/** LightOS theme + background, shared by every Books screen. */
@Composable
fun BooksTheme(content: @Composable BoxScope.() -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
            content = content,
        )
    }
}

/** Standard top bar with a back arrow. */
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, rightButton: LightBarButton? = null) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
        center = LightTopBarCenter.Text(title),
        rightButton = rightButton,
        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
    )
}

@Composable
fun CenteredMessage(message: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 2f.gridUnitsAsDp()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
        if (detail != null) {
            LightText(
                text = detail,
                variant = LightTextVariant.Paragraph,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
    }
}

/** A tappable "LABEL ........ VALUE" row, like the LightOS settings lists. */
@Composable
fun OptionRow(label: String, value: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
        if (value != null) LightText(text = value, variant = LightTextVariant.Copy, lighten = true)
    }
}

/** Reading text style. Built from LightOS type tokens so it matches the rest of the phone. */
@Composable
fun readerBodyStyle(settings: ReaderSettings): TextStyle {
    val px = settings.textSize.designPx
    return TextStyle(
        fontFamily = readerFontFamily(settings),
        fontWeight = FontWeight.Normal,
        fontSize = px.designVerticalPxToSp(),
        lineHeight = (px * 1.45f).designVerticalPxToSp(),
        hyphens = Hyphens.Auto,
        // Greedy breaking: a paragraph cut at a page edge wraps exactly like the whole paragraph.
        lineBreak = LineBreak.Simple,
    )
}

@Composable
fun readerHeadingStyle(settings: ReaderSettings): TextStyle {
    val px = settings.textSize.designPx * 1.25f
    return TextStyle(
        fontFamily = readerFontFamily(settings),
        fontWeight = FontWeight.Medium,
        fontSize = px.designVerticalPxToSp(),
        lineHeight = (px * 1.3f).designVerticalPxToSp(),
        lineBreak = LineBreak.Simple,
    )
}

@Composable
private fun readerFontFamily(settings: ReaderSettings): FontFamily? =
    if (settings.font == ReaderFont.SERIF) FontFamily.Serif else LightThemeTokens.typography.copy.fontFamily

/** The quiet line under the page: where you are in the book. */
@Composable
fun ReaderFooter(left: String, right: String, bookmarked: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(2f.gridUnitsAsDp())
            .padding(horizontal = 1.5f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = left,
            variant = LightTextVariant.Superfine,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (bookmarked) {
            LightIcon(
                icon = LightIcons.STAR,
                size = 0.9f,
                contentDescription = "Bookmarked",
                modifier = Modifier.padding(horizontal = 0.5f.gridUnitsAsDp()),
            )
        }
        LightText(text = right, variant = LightTextVariant.Superfine, lighten = true)
    }
}

/**
 * Menu shown over the page after tapping its middle: back, where you are, a progress bar you can
 * drag to jump through the book, and the contents / bookmark / settings buttons.
 */
@Composable
fun ReaderChrome(
    title: String,
    subtitle: String,
    progress: Float,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onJumpToFraction: (Float) -> Unit,
    onContents: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOptions: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    var scrub by remember { mutableStateOf<Float?>(null) }
    val jump by rememberUpdatedState(onJumpToFraction)

    // Jump once the finger has rested on the bar for a moment.
    LaunchedEffect(scrub) {
        val target = scrub ?: return@LaunchedEffect
        delay(350)
        jump(target)
        scrub = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().background(colors.background).swallowTaps()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
                center = LightTopBarCenter.TwoLineDetail(title, subtitle),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.content))
        }
        Spacer(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
        Column(modifier = Modifier.fillMaxWidth().background(colors.background).swallowTaps()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.content))
            LightTouchableProgressBar(
                colors = colors,
                progress = scrub ?: progress,
                onValueChange = { scrub = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
            )
            LightText(
                text = scrub?.let { "Go to ${it.asPercent()}" } ?: "${progress.asPercent()} read",
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(icon = LightIcons.LIST, onClick = onContents, contentDescription = "Contents"),
                    LightBarButton.LightIcon(
                        icon = if (bookmarked) LightIcons.STAR else LightIcons.STAR_OUTLINE,
                        onClick = onToggleBookmark,
                        contentDescription = if (bookmarked) "Remove bookmark" else "Add bookmark",
                    ),
                    LightBarButton.LightIcon(icon = LightIcons.SETTINGS, onClick = onOptions, contentDescription = "Reading settings"),
                ),
            )
        }
    }
}

/** Stops taps on the menu from reaching the page underneath (which would turn pages). */
private fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }
