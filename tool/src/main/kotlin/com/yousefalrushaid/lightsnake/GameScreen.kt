package com.yousefalrushaid.lightsnake

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.lp3Keyboard.ui.LightDeviceKeys
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightVibrationUsage
import com.thelightphone.sdk.LightVibrationWaveform
import com.thelightphone.sdk.LightVibrator
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.vibrationAmplitudesOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

enum class Phase { READY, PLAYING, PAUSED, OVER }

data class GameUi(
    val board: SnakeState,
    val phase: Phase = Phase.READY,
    /** The best score on this level before this game. */
    val best: Int = 0,
    val newBest: Boolean = false,
)

/**
 * Keys the game answers to: the scroll wheel steers and pauses. Volume keys steer too, which is how
 * the game is played on a plain Android emulator, whose stock framework keeps the wheel's key codes
 * for itself.
 */
private val TURN_LEFT_KEYS = setOf(LightDeviceKeys.RotaryTurnUp.keyCode, KeyEvent.KEYCODE_VOLUME_UP)
private val TURN_RIGHT_KEYS = setOf(LightDeviceKeys.RotaryTurnDown.keyCode, KeyEvent.KEYCODE_VOLUME_DOWN)
private val HANDLED_KEYS = TURN_LEFT_KEYS + TURN_RIGHT_KEYS + LightDeviceKeys.RotaryButtonPress.keyCode

class GameViewModel(
    val level: Int,
    val walls: Boolean,
    private val dataStore: DataStore<Preferences>,
    private val vibrator: LightVibrator,
) : LightViewModel<Unit>() {

    private var game = SnakeGame(COLUMNS, ROWS, level, walls)
    private val _ui = MutableStateFlow(GameUi(board = game.state))
    val ui: StateFlow<GameUi> = _ui.asStateFlow()
    private var loop: Job? = null

    /** The user's LightOS haptics preference; the screen keeps it current. */
    var hapticsEnabled: Boolean = false

    private val phase: Phase get() = _ui.value.phase

    init {
        viewModelScope.launch {
            val best = SettingsRepository.flow(dataStore).first().best(level)
            _ui.update { it.copy(best = best) }
        }
    }

    // --- Input ---

    /** A swipe: head that way. Starts the game if it has not begun. */
    fun steer(direction: Direction) = steerWith { game.turn(direction) }

    fun turnLeft() = steerWith { game.turnLeft() }

    fun turnRight() = steerWith { game.turnRight() }

    private fun steerWith(turn: () -> Unit) {
        when (phase) {
            Phase.READY -> {
                turn()
                start()
            }
            Phase.PLAYING -> turn()
            Phase.PAUSED, Phase.OVER -> Unit
        }
    }

    /** A tap on the board: starts, resumes, or turns toward the tapped side. */
    fun tap(leftSide: Boolean) {
        when (phase) {
            Phase.READY -> start()
            Phase.PLAYING -> if (leftSide) game.turnLeft() else game.turnRight()
            Phase.PAUSED -> resume()
            Phase.OVER -> Unit
        }
    }

    /** The scroll wheel's button. */
    fun pressWheel() {
        when (phase) {
            Phase.READY -> start()
            Phase.PLAYING -> pause()
            Phase.PAUSED -> resume()
            Phase.OVER -> restart()
        }
    }

    fun pause() {
        if (phase != Phase.PLAYING) return
        loop?.cancel()
        loop = null
        _ui.update { it.copy(phase = Phase.PAUSED) }
    }

    fun resume() {
        if (phase == Phase.PAUSED) start()
    }

    fun restart() {
        loop?.cancel()
        loop = null
        game = SnakeGame(COLUMNS, ROWS, level, walls)
        _ui.update { GameUi(board = game.state, best = maxOf(it.best, it.board.score)) }
    }

    // --- Lifecycle ---

    /** Back while playing pauses instead of leaving; a second back leaves. */
    override fun onBackPressed(): Boolean {
        if (phase != Phase.PLAYING) return false
        pause()
        return true
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) = pause()

    override fun onAppPause() = pause()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in TURN_LEFT_KEYS -> turnLeft()
            in TURN_RIGHT_KEYS -> turnRight()
            LightDeviceKeys.RotaryButtonPress.keyCode -> if (event.repeatCount == 0) pressWheel()
            else -> return false
        }
        return true
    }

    /** Swallow the matching key-ups too, so LightOS never sees half a press. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = keyCode in HANDLED_KEYS

    // --- The game loop ---

    private fun start() {
        _ui.update { it.copy(phase = Phase.PLAYING) }
        loop?.cancel()
        loop = viewModelScope.launch {
            val tick = SnakeGame.tickMillis(level)
            while (isActive) {
                delay(tick)
                val result = game.step()
                _ui.update { it.copy(board = game.state) }
                when (result) {
                    Step.MOVED -> Unit
                    Step.ATE, Step.ATE_BONUS -> buzz(Buzz.EAT)
                    Step.DIED -> {
                        gameOver()
                        return@launch
                    }
                }
            }
        }
    }

    private fun gameOver() {
        loop = null
        buzz(Buzz.CRASH)
        val score = game.state.score
        val newBest = score > _ui.value.best
        _ui.update { it.copy(phase = Phase.OVER, newBest = newBest) }
        if (newBest) viewModelScope.launch { SettingsRepository.recordScore(dataStore, level, score) }
    }

    private enum class Buzz { EAT, CRASH }

    private fun buzz(kind: Buzz) {
        if (!hapticsEnabled) return
        when (kind) {
            Buzz.EAT -> vibrator.click()
            Buzz.CRASH -> vibrator.vibrate(
                LightVibrationWaveform(
                    durations = listOf(80.milliseconds, 60.milliseconds, 160.milliseconds),
                    amplitudes = vibrationAmplitudesOf(180, 0, 255),
                ),
                LightVibrationUsage.Touch,
            )
        }
    }
}

class GameScreen(
    private val sealedActivity: SealedLightActivity,
    private val level: Int,
    private val walls: Boolean,
) : LightScreen<Unit, GameViewModel>(sealedActivity) {

    override val viewModelClass: Class<GameViewModel>
        get() = GameViewModel::class.java

    override fun createViewModel(): GameViewModel =
        GameViewModel(level, walls, lightContext.dataStore, sealedActivity.vibrator)

    @Composable
    override fun Content() {
        val ui by viewModel.ui.collectAsState()
        val haptics = LocalHapticsEnabled.current
        SideEffect { viewModel.hapticsEnabled = haptics }

        SnakeTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text(ui.board.score.asScore()),
                )
                Board(
                    state = ui.board,
                    walls = walls,
                    onSwipe = viewModel::steer,
                    onTap = viewModel::tap,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    // Low on the board, so the snake in the middle stays in view before the first move.
                    Caption(
                        ui = ui,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 2.5f.gridUnitsAsDp()),
                    )
                }
                LightBottomBar(items = bottomItems(ui.phase))
            }
        }
    }

    private fun bottomItems(phase: Phase): List<LightBarButton> = when (phase) {
        Phase.READY -> emptyList()
        Phase.PLAYING -> listOf(LightBarButton.Text(text = "PAUSE", onClick = viewModel::pause))
        Phase.PAUSED -> listOf(
            LightBarButton.Text(text = "QUIT", onClick = { goBack() }),
            LightBarButton.Text(text = "RESUME", onClick = viewModel::resume),
        )
        Phase.OVER -> listOf(
            LightBarButton.Text(text = "MENU", onClick = { goBack() }),
            LightBarButton.Text(text = "PLAY AGAIN", onClick = viewModel::restart),
        )
    }
}

/** What the player needs to know right now, on a patch of background so it reads over the snake. */
@Composable
private fun Caption(ui: GameUi, modifier: Modifier = Modifier) {
    val (title, detail) = when (ui.phase) {
        Phase.PLAYING -> return
        Phase.READY -> "Swipe to start" to "Tap a side or roll the wheel to turn"
        Phase.PAUSED -> "PAUSED" to "Tap to resume"
        Phase.OVER -> (if (ui.board.won) "YOU WIN" else "GAME OVER") to
            if (ui.newBest) "New high score ${ui.board.score.asScore()}"
            else "Score ${ui.board.score.asScore()} · Best ${ui.best.asScore()}"
    }
    Column(
        modifier = modifier
            .background(LightThemeTokens.colors.background)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText(text = title, variant = LightTextVariant.Copy, align = TextAlign.Center)
        LightText(text = detail, variant = LightTextVariant.Detail, lighten = true, align = TextAlign.Center)
    }
}
