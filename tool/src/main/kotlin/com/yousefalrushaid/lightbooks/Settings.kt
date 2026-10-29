package com.yousefalrushaid.lightbooks

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.thelightphone.sdk.ui.LightThemeController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Reading text sizes, in LightOS design pixels (Copy text is 30). */
enum class TextSize(val label: String, val designPx: Float) {
    SMALL("SMALL", 24f),
    MEDIUM("MEDIUM", 30f),
    LARGE("LARGE", 38f);

    fun next(): TextSize = entries[(ordinal + 1) % entries.size]
}

enum class ReaderFont(val label: String) {
    SANS("SANS"),
    SERIF("SERIF");

    fun next(): ReaderFont = entries[(ordinal + 1) % entries.size]
}

data class ReaderSettings(
    val textSize: TextSize = TextSize.MEDIUM,
    val font: ReaderFont = ReaderFont.SANS,
    val darkTheme: Boolean = true,
)

object SettingsRepository {
    private val TEXT_SIZE = intPreferencesKey("lightbooks_text_size")
    private val FONT = intPreferencesKey("lightbooks_font")
    private val DARK_THEME = booleanPreferencesKey("lightbooks_dark_theme")

    fun flow(dataStore: DataStore<Preferences>): Flow<ReaderSettings> =
        dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun update(dataStore: DataStore<Preferences>, transform: (ReaderSettings) -> ReaderSettings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[TEXT_SIZE] = updated.textSize.ordinal
            prefs[FONT] = updated.font.ordinal
            prefs[DARK_THEME] = updated.darkTheme
        }
    }

    private fun Preferences.toSettings() = ReaderSettings(
        textSize = TextSize.entries.getOrElse(this[TEXT_SIZE] ?: TextSize.MEDIUM.ordinal) { TextSize.MEDIUM },
        font = ReaderFont.entries.getOrElse(this[FONT] ?: 0) { ReaderFont.SANS },
        darkTheme = this[DARK_THEME] ?: true,
    )
}

fun ReaderSettings.applyTheme() {
    if (darkTheme) LightThemeController.setDarkTheme() else LightThemeController.setLightTheme()
}
