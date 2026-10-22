package com.zyro.player.domain

import android.net.Uri

/**
 * Данные формы создания/редактирования плейлиста.
 *
 * [coverUri] — выбранная пользователем картинка. Для серверного плейлиста
 * она загружается в SoundCloud, для локального — копируется во внутреннее
 * хранилище приложения.
 */
data class PlaylistDraft(
    val title: String,
    val description: String = "",
    val isPublic: Boolean = false,
    val genre: String = "",
    val tagList: String = "",
    val coverUri: Uri? = null
)
