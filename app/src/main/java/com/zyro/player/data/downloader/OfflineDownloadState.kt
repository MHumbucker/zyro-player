package com.zyro.player.data.downloader

/**
 * Состояние пакетного сохранения треков в офлайн-кеш.
 * Живёт в MainViewModel и одновременно питает кнопки в интерфейсе,
 * иконки в карточках треков и progress-уведомление.
 */
data class OfflineDownloadState(
    val isActive: Boolean = false,
    /** Коллекция, из которой запущено скачивание: likes/playlist/album/local. */
    val collectionKey: String? = null,
    val title: String = "",
    val completed: Int = 0,
    val total: Int = 0,
    val currentTrackTitle: String = "",
    val currentTrackUrl: String = "",
    val currentTrackProgress: Float = 0f,
    val failed: Int = 0
) {
    val progress: Float
        get() = if (total > 0) (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    /** Номер трека, который кешируется сейчас (для «10/300»). */
    val currentNumber: Int
        get() = if (isActive) (completed + 1).coerceAtMost(total.coerceAtLeast(1)) else completed
}

object OfflineCollectionKeys {
    const val LIKES = "likes"

    fun playlist(permalinkUrl: String): String = "playlist:$permalinkUrl"

    fun album(permalinkUrl: String): String = "album:$permalinkUrl"

    fun local(playlistId: Long): String = "local:$playlistId"

    const val TYPE_PLAYLIST = "PLAYLIST"
    const val TYPE_ALBUM = "ALBUM"
}
