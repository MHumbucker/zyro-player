package com.zyro.player.ui.components

import android.content.Context
import android.content.SharedPreferences
import com.zyro.player.ui.theme.FontMode
import com.zyro.player.ui.theme.ThemeMode

/**
 * Локальные настройки приложения (не аккаунт SoundCloud).
 */
class SettingsPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var crossfadeEnabled: Boolean
        get() = prefs.getBoolean(KEY_CROSSFADE, false)
        set(v) = prefs.edit().putBoolean(KEY_CROSSFADE, v).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(v) = prefs.edit().putBoolean(KEY_HAPTICS, v).apply()

    var highQualityStream: Boolean
        get() = prefs.getBoolean(KEY_HQ, true)
        set(v) = prefs.edit().putBoolean(KEY_HQ, v).apply()

    var autoDownloadLiked: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DL, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_DL, v).apply()

    var gaplessPlayback: Boolean
        get() = prefs.getBoolean(KEY_GAPLESS, true)
        set(v) = prefs.edit().putBoolean(KEY_GAPLESS, v).apply()

    var normalizeVolume: Boolean
        get() = prefs.getBoolean(KEY_NORMALIZE, false)
        set(v) = prefs.edit().putBoolean(KEY_NORMALIZE, v).apply()

    /** Показывать в плеере маленькие комментарии, привязанные к текущему времени. */
    var onScreenComments: Boolean
        get() = prefs.getBoolean(KEY_ON_SCREEN_COMMENTS, false)
        set(v) = prefs.edit().putBoolean(KEY_ON_SCREEN_COMMENTS, v).apply()

    // ---------- Офлайн-скачивание ----------

    /** Сохранять обложки треков в кеш изображений, чтобы они были видны без сети. */
    var offlineArtwork: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_ARTWORK, true)
        set(v) = prefs.edit().putBoolean(KEY_OFFLINE_ARTWORK, v).apply()

    /** Подтягивать и сохранять текст песни вместе с треком. */
    var offlineLyrics: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_LYRICS, false)
        set(v) = prefs.edit().putBoolean(KEY_OFFLINE_LYRICS, v).apply()

    /** Сохранять комментарии трека для просмотра без сети. */
    var offlineComments: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_COMMENTS, false)
        set(v) = prefs.edit().putBoolean(KEY_OFFLINE_COMMENTS, v).apply()

    /** Максимум комментариев на один трек (5..200). */
    var offlineMaxComments: Int
        get() = prefs.getInt(KEY_OFFLINE_MAX_COMMENTS, DEFAULT_MAX_COMMENTS)
            .coerceIn(MIN_MAX_COMMENTS, MAX_MAX_COMMENTS)
        set(v) = prefs.edit()
            .putInt(KEY_OFFLINE_MAX_COMMENTS, v.coerceIn(MIN_MAX_COMMENTS, MAX_MAX_COMMENTS))
            .apply()

    /** Режим темы: тёмная / светлая / системная. */
    var themeMode: ThemeMode
        get() = ThemeMode.fromStorage(prefs.getString(KEY_THEME, null))
        set(v) = prefs.edit().putString(KEY_THEME, v.name.lowercase()).apply()

    /** Шрифт интерфейса: системный / Jura / пользовательский. */
    var fontMode: FontMode
        get() = FontMode.fromStorage(prefs.getString(KEY_FONT, null))
        set(v) = prefs.edit().putString(KEY_FONT, v.name.lowercase()).apply()

    /** URI исходного файла пользовательского шрифта (для подписи в настройках). */
    var customFontUri: String?
        get() = prefs.getString(KEY_CUSTOM_FONT_URI, null)
        set(v) = prefs.edit().putString(KEY_CUSTOM_FONT_URI, v).apply()

    /** Отображаемое имя пользовательского шрифта. */
    var customFontName: String?
        get() = prefs.getString(KEY_CUSTOM_FONT_NAME, null)
        set(v) = prefs.edit().putString(KEY_CUSTOM_FONT_NAME, v).apply()

    companion object {
        private const val PREFS = "zyro_settings"
        private const val KEY_CROSSFADE = "crossfade"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_HQ = "hq_stream"
        private const val KEY_AUTO_DL = "auto_dl_liked"
        private const val KEY_GAPLESS = "gapless"
        private const val KEY_NORMALIZE = "normalize"
        private const val KEY_ON_SCREEN_COMMENTS = "on_screen_comments"
        private const val KEY_OFFLINE_ARTWORK = "offline_artwork"
        private const val KEY_OFFLINE_LYRICS = "offline_lyrics"
        private const val KEY_OFFLINE_COMMENTS = "offline_comments"
        private const val KEY_OFFLINE_MAX_COMMENTS = "offline_max_comments"

        const val MIN_MAX_COMMENTS = 5
        const val MAX_MAX_COMMENTS = 200
        const val COMMENT_STEP = 5
        const val DEFAULT_MAX_COMMENTS = 20
        private const val KEY_THEME = "theme_mode"
        private const val KEY_FONT = "font_choice"
        private const val KEY_CUSTOM_FONT_URI = "custom_font_uri"
        private const val KEY_CUSTOM_FONT_NAME = "custom_font_name"
    }
}
