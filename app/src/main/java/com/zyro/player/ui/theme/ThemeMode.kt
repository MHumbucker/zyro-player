package com.zyro.player.ui.theme

/**
 * Режим темы приложения.
 *
 * Хранится в [com.zyro.player.ui.components.SettingsPreferences] (ключ `theme_mode`),
 * применяется к `ZyroPlayerTheme` в `MainActivity`.
 */
enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM;

    companion object {
        /** Мягкий парсинг значения из SharedPreferences (неизвестное → DARK). */
        fun fromStorage(value: String?): ThemeMode = when (value?.lowercase()) {
            "light" -> LIGHT
            "system" -> SYSTEM
            else -> DARK
        }
    }
}
