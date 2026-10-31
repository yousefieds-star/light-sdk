package com.yousefalrushaid.lightsnake

import kotlin.random.Random

/** The board, in cells. Twenty across like the old handsets; the height fits the Light Phone's screen. */
const val COLUMNS = 20
const val ROWS = 18

/** Where the snake is heading. The order matters: each entry is a quarter turn clockwise from the last. */
enum class Direction(val dx: Int, val dy: Int) {
    UP(0, -1), RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0);

    val opposite: Direction get() = entries[(ordinal + 2) % entries.size]
    val clockwise: Direction get() = entries[(ordinal + 1) % entries.size]
    val counterClockwise: Direction get() = entries[(ordinal + 3) % entries.size]
}

data class Cell(val x: Int, val y: Int)

/** What happened on one tick of the game. */
enum class Step { MOVED, ATE, ATE_BONUS, DIED }

/**
 * A snapshot of the board. Immutable, so the UI can hold on to one and redraw only when it changes.
 */
data class SnakeState(
    /** Head first. */
    val snake: List<Cell>,
    val direction: Direction,
    val food: Cell,
    val bonus: Cell? = null,
    /** Ticks until the bonus disappears; 0 when there is none. */
    val bonusTicksLeft: Int = 0,
    val score: Int = 0,
    /** Meals eaten so far. */
    val eaten: Int = 0,
    val over: Boolean = false,
    /** The board is full: there is nowhere left to grow. */
    val won: Boolean = false,
) {
    val head: Cell get() = snake.first()
}

/**
 * The rules of Snake, with no Android in sight.
 *
 * - The snake moves one cell per [step] and grows by one when it eats.
 * - With [walls] on, the edge ends the game. With them off, the snake comes back around the other side.
 * - Running into itself always ends the game. Moving into the tail's cell is fine: the tail leaves it.
 * - Food is worth [level] points. Every [BONUS_EVERY] meals a bonus appears for [BONUS_TICKS] ticks,
 *   worth up to [BONUS_MAX_MULTIPLIER] meals the moment it appears and less the longer it waits.
 */
class SnakeGame(
    val columns: Int,
    val rows: Int,
    val level: Int,
    val walls: Boolean,
    private val random: Random = Random.Default,
) {
    init {
        require(columns >= MIN_SIDE && rows >= MIN_SIDE) { "The board must be at least $MIN_SIDE by $MIN_SIDE" }
        require(level in 1..MAX_LEVEL) { "level must be between 1 and $MAX_LEVEL" }
    }

    var state: SnakeState = initialState()
        private set

    /** Turns asked for since the last step, applied one per step so a quick double turn still registers. */
    private val pendingTurns = ArrayDeque<Direction>()

    private fun initialState(): SnakeState {
        val y = rows / 2
        val snake = List(START_LENGTH) { i -> Cell(columns / 2 - i, y) }
        return SnakeState(snake = snake, direction = Direction.RIGHT, food = freeCell(snake) ?: Cell(0, 0))
    }

    /** Heads [direction] on the next step. Reversing straight back is ignored, as is a full queue. */
    fun turn(direction: Direction) {
        if (state.over) return
        val heading = pendingTurns.lastOrNull() ?: state.direction
        if (direction == heading || direction == heading.opposite) return
        if (pendingTurns.size >= MAX_PENDING_TURNS) return
        pendingTurns.addLast(direction)
    }

    /** Turns to the snake's left: counter-clockwise on screen. */
    fun turnLeft() = turn((pendingTurns.lastOrNull() ?: state.direction).counterClockwise)

    /** Turns to the snake's right: clockwise on screen. */
    fun turnRight() = turn((pendingTurns.lastOrNull() ?: state.direction).clockwise)

    /** Moves the snake one cell. Does nothing once the game is over. */
    fun step(): Step {
        val current = state
        if (current.over) return Step.DIED

        val direction = pendingTurns.removeFirstOrNull() ?: current.direction
        var next = Cell(current.head.x + direction.dx, current.head.y + direction.dy)
        if (next.x !in 0 until columns || next.y !in 0 until rows) {
            if (walls) return die(current, direction)
            next = Cell(next.x.mod(columns), next.y.mod(rows))
        }

        val ate = next == current.food
        val ateBonus = next == current.bonus
        // Unless the snake grows, its tail moves on this tick, so the tail's cell is safe to enter.
        val body = if (ate) current.snake else current.snake.dropLast(1)
        if (next in body) return die(current, direction)
        val snake = listOf(next) + body

        var score = current.score
        var eaten = current.eaten
        var food = current.food
        var bonus = current.bonus
        var bonusTicks = current.bonusTicksLeft

        if (ateBonus) {
            score += bonusValue(bonusTicks)
            bonus = null
            bonusTicks = 0
        }
        if (ate) {
            score += level
            eaten++
            food = freeCell(snake, bonus) ?: run {
                state = current.copy(snake = snake, direction = direction, score = score, eaten = eaten, bonus = null, bonusTicksLeft = 0, over = true, won = true)
                return Step.ATE
            }
        }
        if (bonus != null) {
            bonusTicks--
            if (bonusTicks <= 0) {
                bonus = null
                bonusTicks = 0
            }
        } else if (ate && eaten % BONUS_EVERY == 0) {
            bonus = freeCell(snake, food)
            bonusTicks = if (bonus != null) BONUS_TICKS else 0
        }

        state = SnakeState(snake, direction, food, bonus, bonusTicks, score, eaten)
        return when {
            ateBonus -> Step.ATE_BONUS
            ate -> Step.ATE
            else -> Step.MOVED
        }
    }

    /** What the bonus is worth with [ticksLeft] to go: all of [BONUS_MAX_MULTIPLIER] meals at first, one at the end. */
    fun bonusValue(ticksLeft: Int): Int {
        val share = (ticksLeft.coerceIn(0, BONUS_TICKS) * BONUS_MAX_MULTIPLIER + BONUS_TICKS - 1) / BONUS_TICKS
        return level * share.coerceAtLeast(1)
    }

    /** Starts again from [state]. For tests and, one day, resuming a saved game. */
    internal fun restore(state: SnakeState) {
        this.state = state
        pendingTurns.clear()
    }

    private fun die(current: SnakeState, direction: Direction): Step {
        state = current.copy(direction = direction, over = true)
        pendingTurns.clear()
        return Step.DIED
    }

    /** A random cell that is neither on the snake nor one of [taken]; null when the board is full. */
    private fun freeCell(snake: List<Cell>, vararg taken: Cell?): Cell? {
        val occupied = snake.toHashSet()
        taken.forEach { if (it != null) occupied += it }
        val free = ArrayList<Cell>(columns * rows - occupied.size)
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                val cell = Cell(x, y)
                if (cell !in occupied) free += cell
            }
        }
        return if (free.isEmpty()) null else free[random.nextInt(free.size)]
    }

    companion object {
        const val MAX_LEVEL = 9
        const val START_LENGTH = 3
        const val BONUS_EVERY = 5
        const val BONUS_TICKS = 40
        const val BONUS_MAX_MULTIPLIER = 5
        private const val MIN_SIDE = 5
        private const val MAX_PENDING_TURNS = 2

        /** Time between steps. Level 1 is a stroll, level 9 the classic frenzy. */
        fun tickMillis(level: Int): Long = 360L - 30L * level.coerceIn(1, MAX_LEVEL)
    }
}
