package com.yousefalrushaid.lightsnake

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.thelightphone.sdk.ui.LightThemeController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class SnakeSettings(
    val level: Int = DEFAULT_LEVEL,
    val walls: Boolean = true,
    val darkTheme: Boolean = true,
    /** Best score on each level, keyed by level. Levels never played are absent. */
    val bests: Map<Int, Int> = emptyMap(),
) {
    fun best(level: Int = this.level): Int = bests[level] ?: 0

    companion object {
        const val DEFAULT_LEVEL = 5
    }
}

/** Everything Snake remembers, in the tool's DataStore. Nothing leaves the phone. */
object SettingsRepository {
    private val LEVEL = intPreferencesKey("lightsnake_level")
    private val WALLS = booleanPreferencesKey("lightsnake_walls")
    private val DARK_THEME = booleanPreferencesKey("lightsnake_dark_theme")
    private fun bestKey(level: Int) = intPreferencesKey("lightsnake_best_$level")

    fun flow(dataStore: DataStore<Preferences>): Flow<SnakeSettings> =
        dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun update(dataStore: DataStore<Preferences>, transform: (SnakeSettings) -> SnakeSettings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[LEVEL] = updated.level.coerceIn(1, SnakeGame.MAX_LEVEL)
            prefs[WALLS] = updated.walls
            prefs[DARK_THEME] = updated.darkTheme
        }
    }

    /** Keeps [score] as the best for [level] if it beats the one stored. */
    suspend fun recordScore(dataStore: DataStore<Preferences>, level: Int, score: Int) {
        dataStore.edit { prefs ->
            val key = bestKey(level)
            if (score > (prefs[key] ?: 0)) prefs[key] = score
        }
    }

    private fun Preferences.toSettings() = SnakeSettings(
        level = (this[LEVEL] ?: SnakeSettings.DEFAULT_LEVEL).coerceIn(1, SnakeGame.MAX_LEVEL),
        walls = this[WALLS] ?: true,
        darkTheme = this[DARK_THEME] ?: true,
        bests = (1..SnakeGame.MAX_LEVEL).mapNotNull { level -> this[bestKey(level)]?.let { level to it } }.toMap(),
    )
}

fun SnakeSettings.applyTheme() {
    if (darkTheme) LightThemeController.setDarkTheme() else LightThemeController.setLightTheme()
}
