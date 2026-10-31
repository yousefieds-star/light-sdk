package com.yousefalrushaid.lightsnake

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Always picks the first free cell (row by row), so food lands somewhere predictable. */
private val firstFree: Random = object : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}

private fun game(level: Int = 1, walls: Boolean = true, random: Random = firstFree) =
    SnakeGame(COLUMNS, ROWS, level, walls, random)

/** A snake heading right along the middle row, head at ([headX], 9), [length] long. */
private fun rightward(headX: Int, length: Int, food: Cell = Cell(0, 0)) = SnakeState(
    snake = List(length) { Cell(headX - it, 9) },
    direction = Direction.RIGHT,
    food = food,
)

class SnakeGameTest {

    @Test
    fun `starts in the middle, heading right, with food off the snake`() {
        val state = game(random = Random(7)).state
        assertEquals(SnakeGame.START_LENGTH, state.snake.size)
        assertEquals(Cell(COLUMNS / 2, ROWS / 2), state.head)
        assertEquals(Direction.RIGHT, state.direction)
        assertFalse(state.food in state.snake)
        assertEquals(0, state.score)
        assertFalse(state.over)
    }

    @Test
    fun `a step moves the head one cell and the tail follows`() {
        val g = game()
        val before = g.state.snake
        assertEquals(Step.MOVED, g.step())
        val after = g.state.snake
        assertEquals(before.size, after.size)
        assertEquals(Cell(before.first().x + 1, before.first().y), after.first())
        assertEquals(before.dropLast(1), after.drop(1))
    }

    @Test
    fun `turning takes effect on the next step`() {
        val g = game()
        g.turn(Direction.UP)
        g.step()
        assertEquals(Direction.UP, g.state.direction)
        assertEquals(Cell(COLUMNS / 2, ROWS / 2 - 1), g.state.head)
    }

    @Test
    fun `reversing into yourself and repeating the heading are ignored`() {
        val g = game()
        g.turn(Direction.LEFT)
        g.turn(Direction.RIGHT)
        g.step()
        assertEquals(Direction.RIGHT, g.state.direction)
        assertFalse(g.state.over)
    }

    @Test
    fun `two quick turns both register, a third is dropped`() {
        val g = game()
        g.turn(Direction.UP)
        g.turn(Direction.LEFT)
        g.turn(Direction.DOWN)
        g.step()
        assertEquals(Direction.UP, g.state.direction)
        g.step()
        assertEquals(Direction.LEFT, g.state.direction)
        g.step()
        assertEquals(Direction.LEFT, g.state.direction)
    }

    @Test
    fun `relative turns follow the turn already queued`() {
        val g = game()
        g.turnLeft()  // heading RIGHT, so UP
        g.turnLeft()  // relative to UP, so LEFT
        g.step()
        g.step()
        assertEquals(Direction.LEFT, g.state.direction)
        g.turnRight() // relative to LEFT, so UP
        g.step()
        assertEquals(Direction.UP, g.state.direction)
    }

    @Test
    fun `eating grows the snake, scores the level, and moves the food off the snake`() {
        val g = game(level = 4)
        g.restore(rightward(headX = 10, length = 3, food = Cell(11, 9)))
        assertEquals(Step.ATE, g.step())
        assertEquals(4, g.state.snake.size)
        assertEquals(4, g.state.score)
        assertEquals(1, g.state.eaten)
        assertFalse(g.state.food in g.state.snake)
        assertEquals(Cell(0, 0), g.state.food)
    }

    @Test
    fun `with walls on, the edge ends the game`() {
        val g = game(walls = true)
        g.restore(rightward(headX = COLUMNS - 1, length = 3))
        assertEquals(Step.DIED, g.step())
        assertTrue(g.state.over)
        assertFalse(g.state.won)
        assertEquals(Cell(COLUMNS - 1, 9), g.state.head)
    }

    @Test
    fun `with walls off, the snake comes back around`() {
        val g = game(walls = false)
        g.restore(rightward(headX = COLUMNS - 1, length = 3))
        assertEquals(Step.MOVED, g.step())
        assertEquals(Cell(0, 9), g.state.head)
        assertFalse(g.state.over)
    }

    @Test
    fun `running into yourself ends the game`() {
        val g = game()
        g.restore(rightward(headX = 10, length = 5))
        g.turn(Direction.UP)
        g.step()
        g.turn(Direction.LEFT)
        g.step()
        g.turn(Direction.DOWN)
        assertEquals(Step.DIED, g.step())
        assertTrue(g.state.over)
    }

    @Test
    fun `moving into the tail's cell is allowed because the tail leaves it`() {
        val g = game()
        // A tight loop: head at (1,0) heading up, tail at (0,0) right where the head wants to go.
        g.restore(
            SnakeState(
                snake = listOf(Cell(1, 0), Cell(1, 1), Cell(0, 1), Cell(0, 0)),
                direction = Direction.UP,
                food = Cell(5, 5),
            )
        )
        g.turn(Direction.LEFT)
        assertEquals(Step.MOVED, g.step())
        assertEquals(Cell(0, 0), g.state.head)
        assertFalse(g.state.over)
    }

    @Test
    fun `after the game is over nothing moves`() {
        val g = game(walls = true)
        g.restore(rightward(headX = COLUMNS - 1, length = 3))
        g.step()
        val frozen = g.state
        g.turn(Direction.UP)
        assertEquals(Step.DIED, g.step())
        assertEquals(frozen, g.state)
    }

    @Test
    fun `every fifth meal brings a bonus that later expires`() {
        val g = game(walls = false)
        g.restore(rightward(headX = 10, length = 3, food = Cell(11, 9)).copy(eaten = SnakeGame.BONUS_EVERY - 1))
        assertEquals(Step.ATE, g.step())
        val bonus = g.state.bonus
        assertNotNull(bonus)
        assertEquals(SnakeGame.BONUS_TICKS, g.state.bonusTicksLeft)
        assertFalse(bonus in g.state.snake)
        assertTrue(bonus != g.state.food)

        // Keep going straight along the middle row: nothing there to eat.
        repeat(SnakeGame.BONUS_TICKS - 1) {
            assertEquals(Step.MOVED, g.step())
            assertNotNull(g.state.bonus)
        }
        assertEquals(Step.MOVED, g.step())
        assertNull(g.state.bonus)
        assertEquals(0, g.state.bonusTicksLeft)
    }

    @Test
    fun `the bonus is worth more the sooner it is caught`() {
        val level = 3
        val fresh = game(level = level)
        fresh.restore(rightward(headX = 10, length = 3).copy(bonus = Cell(11, 9), bonusTicksLeft = SnakeGame.BONUS_TICKS))
        assertEquals(Step.ATE_BONUS, fresh.step())
        assertEquals(level * SnakeGame.BONUS_MAX_MULTIPLIER, fresh.state.score)
        assertNull(fresh.state.bonus)
        assertEquals(3, fresh.state.snake.size)

        val late = game(level = level)
        late.restore(rightward(headX = 10, length = 3).copy(bonus = Cell(11, 9), bonusTicksLeft = 1))
        assertEquals(Step.ATE_BONUS, late.step())
        assertEquals(level, late.state.score)
    }

    @Test
    fun `filling the board wins`() {
        // A 5 by 5 board, the snake snaking through every cell but the last one.
        val path = buildList {
            for (y in 0 until 5) {
                val xs = if (y % 2 == 0) 0 until 5 else 4 downTo 0
                for (x in xs) add(Cell(x, y))
            }
        }
        val g = SnakeGame(columns = 5, rows = 5, level = 1, walls = true, random = firstFree)
        val body = path.dropLast(1).reversed()
        val last = path.last()
        g.restore(
            SnakeState(
                snake = body,
                direction = Direction.entries.first { it.dx == last.x - body.first().x && it.dy == last.y - body.first().y },
                food = last,
            )
        )
        assertEquals(Step.ATE, g.step())
        assertTrue(g.state.over)
        assertTrue(g.state.won)
        assertEquals(25, g.state.snake.size)
    }

    @Test
    fun `higher levels tick faster`() {
        val ticks = (1..SnakeGame.MAX_LEVEL).map { SnakeGame.tickMillis(it) }
        assertEquals(ticks.sortedDescending(), ticks)
        assertTrue(ticks.last() > 0)
        assertEquals(SnakeGame.tickMillis(1), SnakeGame.tickMillis(0))
        assertEquals(SnakeGame.tickMillis(SnakeGame.MAX_LEVEL), SnakeGame.tickMillis(99))
    }
}
