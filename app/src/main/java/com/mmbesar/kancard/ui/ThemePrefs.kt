package com.mmbesar.kancard.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Remembers the look the user chose. Theme choices are not secret, so plain preferences are fine. */
class ThemePrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read(prefs))

    /** Screens watch this; it changes the moment the user taps a choice. */
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    fun update(change: (ThemeSettings) -> ThemeSettings) {
        val updated = change(_settings.value)
        _settings.value = updated
        prefs.edit()
            .putString(KEY_THEME, updated.theme.name)
            .putString(KEY_ACCENT, updated.accent)
            .putInt(KEY_CUSTOM, updated.customAccent)
            .putBoolean(KEY_AMOLED, updated.amoled)
            .putBoolean(KEY_DESCRIPTIONS, updated.showDescriptions)
            .apply()
    }

    companion object {
        private const val FILE = "kancard_theme"
        private const val KEY_THEME = "theme"
        private const val KEY_ACCENT = "accent"
        private const val KEY_CUSTOM = "custom_accent"
        private const val KEY_AMOLED = "amoled"
        private const val KEY_DESCRIPTIONS = "show_descriptions"

        private fun read(prefs: SharedPreferences): ThemeSettings {
            val defaults = ThemeSettings()
            return ThemeSettings(
                theme = runCatching { ThemeChoice.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
                    .getOrDefault(defaults.theme),
                accent = prefs.getString(KEY_ACCENT, defaults.accent) ?: defaults.accent,
                customAccent = prefs.getInt(KEY_CUSTOM, defaults.customAccent),
                amoled = prefs.getBoolean(KEY_AMOLED, defaults.amoled),
                showDescriptions = prefs.getBoolean(KEY_DESCRIPTIONS, defaults.showDescriptions),
            )
        }

        /** One-off read, for the widget (which has no running app to ask). */
        fun load(context: Context): ThemeSettings =
            read(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
    }
}
