package com.yousefalrushaid.lightsnake

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

private val HOW_TO_PLAY = listOf(
    "Eat to grow. The game ends when the snake runs into itself, or into the edge while walls are on. " +
        "With walls off it comes back around the other side.",
    "Swipe on the board to steer. Or tap the left or right half of the board to turn that way.",
    "The scroll wheel steers too: roll up to turn left, roll down to turn right, and press it to pause.",
    "Each meal is worth the level number in points, and higher levels are faster.",
    "Every fifth meal brings a bonus that blinks away after a while. The sooner you catch it, the more it is worth.",
)

class HowToPlayScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        SnakeTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                BackTopBar(title = "How to play", onBack = { goBack() })
                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp()),
                ) {
                    for (paragraph in HOW_TO_PLAY) {
                        LightText(
                            text = paragraph,
                            variant = LightTextVariant.Paragraph,
                            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                        )
                    }
                }
            }
        }
    }
}
