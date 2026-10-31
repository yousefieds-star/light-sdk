package com.yousefalrushaid.lightsnake

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MenuViewModel(private val dataStore: DataStore<Preferences>) : LightViewModel<Unit>() {

    val settings: StateFlow<SnakeSettings> =
        SettingsRepository.flow(dataStore).stateIn(viewModelScope, SharingStarted.Eagerly, SnakeSettings())

    init {
        viewModelScope.launch { settings.collect { it.applyTheme() } }
    }

    fun change(transform: (SnakeSettings) -> SnakeSettings) {
        viewModelScope.launch { SettingsRepository.update(dataStore, transform) }
    }
}

/** The start screen: level, walls and theme, the best score so far, and PLAY. */
@InitialScreen
class MenuScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, MenuViewModel>(sealedActivity) {

    override val viewModelClass: Class<MenuViewModel>
        get() = MenuViewModel::class.java

    override fun createViewModel(): MenuViewModel = MenuViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        val settings by viewModel.settings.collectAsState()

        SnakeTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Snake"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    OptionRow("LEVEL", settings.level.toString()) {
                        viewModel.change { it.copy(level = it.level % SnakeGame.MAX_LEVEL + 1) }
                    }
                    OptionRow("WALLS", if (settings.walls) "ON" else "OFF") {
                        viewModel.change { it.copy(walls = !it.walls) }
                    }
                    OptionRow("THEME", if (settings.darkTheme) "DARK" else "LIGHT") {
                        viewModel.change { it.copy(darkTheme = !it.darkTheme) }
                    }
                    OptionRow("HIGH SCORE", settings.best().asScore())
                    OptionRow("HOW TO PLAY", null) { navigateTo(::HowToPlayScreen) }
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(text = "PLAY", onClick = { play(settings) }),
                    ),
                )
            }
        }
    }

    private fun play(settings: SnakeSettings) {
        navigateTo({ GameScreen(it, settings.level, settings.walls) })
    }
}
