package com.yousefalrushaid.lightsnake

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** LightOS theme + background, shared by every Snake screen. */
@Composable
fun SnakeTheme(content: @Composable BoxScope.() -> Unit) {
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
fun BackTopBar(title: String, onBack: () -> Unit) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
        center = LightTopBarCenter.Text(title),
        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
    )
}

/** A "LABEL ........ VALUE" row like the LightOS settings lists. Leave [onClick] out for a read-only row. */
@Composable
fun OptionRow(label: String, value: String?, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.lightClickable(onClick = onClick) else it }
            .padding(vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
        if (value != null) LightText(text = value, variant = LightTextVariant.Copy, lighten = true)
    }
}

/** Scores read like the old handsets: always four digits. */
fun Int.asScore(): String = coerceAtLeast(0).toString().padStart(4, '0')
