package com.zyro.player.ui.theme

/**
 * Шрифт интерфейса.
 *
 * Хранится в [com.zyro.player.ui.components.SettingsPreferences] (ключ `font_choice`),
 * применяется к `ZyroPlayerTheme` в `MainActivity`.
 *
 * [SYSTEM] — стандартный системный шрифт Android (по умолчанию),
 * [JURA] — фирменный шрифт Zyro из `res/font`,
 * [CUSTOM] — файл `.ttf`/`.otf`, выбранный пользователем.
 */
enum class FontMode {
    SYSTEM,
    JURA,
    CUSTOM;

    companion object {
        /** Мягкий парсинг значения из SharedPreferences (неизвестное → SYSTEM). */
        fun fromStorage(value: String?): FontMode = when (value?.lowercase()) {
            "jura" -> JURA
            "custom" -> CUSTOM
            else -> SYSTEM
        }
    }
}

/** Информация о пользовательском шрифте (для подписи в настройках). */
data class CustomFontInfo(
    val uri: String? = null,
    val name: String? = null
)
