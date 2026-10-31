package com.yousefalrushaid.lightsnake

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** The bonus blinks for its last few ticks. */
private const val BLINK_TICKS = 10

/**
 * The playing field, drawn in the two colours of the theme like the snake on an old handset:
 * a thin frame, a snake of chunky blocks, a square of food, and a hollow bonus.
 *
 * Swipes call [onSwipe] with the direction. Taps call [onTap] with whether the left half was touched.
 * [overlay] is drawn on top; it receives no touches of its own.
 */
@Composable
fun Board(
    state: SnakeState,
    walls: Boolean,
    onSwipe: (Direction) -> Unit,
    onTap: (leftSide: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val content = LightThemeTokens.colors.content
    val background = LightThemeTokens.colors.background
    val frameInset = 0.5f.gridUnitsAsDp()
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentOnTap by rememberUpdatedState(onTap)

    Box(
        modifier = modifier.pointerInput(Unit) {
            boardGestures(onSwipe = { currentOnSwipe(it) }, onTap = { currentOnTap(it) })
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawBoard(state, walls, content, background, inset = frameInset.toPx(), stroke = 1.5.dp.toPx())
        }
        overlay()
    }
}

private fun DrawScope.drawBoard(
    state: SnakeState,
    walls: Boolean,
    color: Color,
    background: Color,
    inset: Float,
    stroke: Float,
) {
    // Square cells, as large as fit with room for the frame; the board sits centred.
    val cell = floor(min((size.width - 2 * inset) / COLUMNS, (size.height - 2 * inset) / ROWS))
    if (cell < 2f) return
    val boardWidth = cell * COLUMNS
    val boardHeight = cell * ROWS
    val originX = ((size.width - boardWidth) / 2f).roundToInt().toFloat()
    val originY = ((size.height - boardHeight) / 2f).roundToInt().toFloat()
    fun left(c: Cell) = originX + c.x * cell
    fun top(c: Cell) = originY + c.y * cell

    // The frame. Dashed when the edges are open.
    val pad = inset / 2f
    drawRect(
        color = color,
        topLeft = Offset(originX - pad, originY - pad),
        size = Size(boardWidth + 2 * pad, boardHeight + 2 * pad),
        style = Stroke(
            width = stroke,
            pathEffect = if (walls) null else PathEffect.dashPathEffect(floatArrayOf(cell / 2f, cell / 2f)),
        ),
    )

    // The snake: a block per cell with a thin gap between them, pixel-aligned like an old handset.
    val gap = (cell * 0.1f).roundToInt().coerceAtLeast(1).toFloat()
    val block = cell - 2 * gap
    for (c in state.snake) {
        drawRect(color, Offset(left(c) + gap, top(c) + gap), Size(block, block))
    }

    // Two eyes on the head, looking where it is going.
    val head = state.head
    val d = state.direction
    val centreX = left(head) + cell / 2f
    val centreY = top(head) + cell / 2f
    val eye = (cell * 0.16f).roundToInt().toFloat()
    val forward = cell * 0.15f
    val aside = cell * 0.18f
    for (side in intArrayOf(-1, 1)) {
        val x = (centreX + d.dx * forward - d.dy * aside * side - eye / 2f).roundToInt().toFloat()
        val y = (centreY + d.dy * forward + d.dx * aside * side - eye / 2f).roundToInt().toFloat()
        drawRect(background, Offset(x, y), Size(eye, eye))
    }

    // Food.
    val foodSize = (cell * 0.5f).roundToInt().toFloat()
    val foodInset = ((cell - foodSize) / 2f).roundToInt().toFloat()
    drawRect(color, Offset(left(state.food) + foodInset, top(state.food) + foodInset), Size(foodSize, foodSize))

    // The bonus: a hollow square that blinks before it goes, with its time left shown above the frame.
    val bonus = state.bonus ?: return
    val ticks = state.bonusTicksLeft
    if (ticks > BLINK_TICKS || ticks % 2 == 0) {
        val ring = cell * 0.76f
        drawRect(
            color,
            Offset(left(bonus) + (cell - ring) / 2f, top(bonus) + (cell - ring) / 2f),
            Size(ring, ring),
            style = Stroke(width = cell * 0.14f),
        )
    }
    val frameTop = originY - pad - stroke / 2f
    drawRect(
        color,
        Offset(originX, frameTop - 2f - stroke),
        Size(boardWidth * ticks / SnakeGame.BONUS_TICKS, stroke),
    )
}

/**
 * One touch is either a swipe (the finger travels past the slop, in whichever direction it set off)
 * or a tap (it lifts before that). The rest of the gesture is ignored either way.
 */
private suspend fun PointerInputScope.boardGestures(
    onSwipe: (Direction) -> Unit,
    onTap: (leftSide: Boolean) -> Unit,
) {
    val threshold = viewConfiguration.touchSlop * 1.5f
    awaitEachGesture {
        val down = awaitFirstDown()
        var decided = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!decided) {
                val travel = change.position - down.position
                if (travel.getDistance() >= threshold) {
                    decided = true
                    onSwipe(travel.toDirection())
                } else if (!change.pressed) {
                    decided = true
                    onTap(down.position.x < size.width / 2f)
                }
            }
            change.consume()
            if (!change.pressed) break
        }
    }
}

private fun Offset.toDirection(): Direction = when {
    abs(x) >= abs(y) -> if (x > 0) Direction.RIGHT else Direction.LEFT
    else -> if (y > 0) Direction.DOWN else Direction.UP
}
