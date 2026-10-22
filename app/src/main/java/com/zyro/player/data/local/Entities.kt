package com.zyro.player.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val originalUrl: String,
    val title: String,
    val artist: String,
    val artworkUrl: String,
    val streamUrl: String = "",
    val durationMs: Long = 0L,
    val isLiked: Boolean = false,
    val isOffline: Boolean = false,
    val localFilePath: String? = null,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val likesCount: Long = 0L,
    val playbackCount: Long = 0L,
    /** ID трека в SoundCloud. 0 — если трек не с SoundCloud или ID ещё не разрешён. */
    @ColumnInfo(defaultValue = "0")
    val soundcloudId: Long = 0L,
    /**
     * Реальное время лайка на SoundCloud (мс) — по нему сортируется список лайков,
     * чтобы порядок совпадал с сайтом. 0 — лайк поставлен локально/время неизвестно.
     */
    @ColumnInfo(defaultValue = "0")
    val likedTimestamp: Long = 0L
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0L,
    val title: String,
    val description: String = "",
    /**
     * Обложка, импортированная из SoundCloud в старых версиях. Для новых
     * пользовательских плейлистов остаётся пустой — см. [localCoverPath].
     */
    val coverUrl: String = "",
    /** Путь к выбранной пользователем обложке во внутреннем хранилище. */
    val localCoverPath: String = "",
    val createdTimestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_track_cross_ref",
    primaryKeys = ["playlistId", "trackOriginalUrl"],
    indices = [Index("trackOriginalUrl")]
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackOriginalUrl: String
)

@Entity(tableName = "listening_history")
data class ListeningHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackOriginalUrl: String,
    val title: String,
    val artist: String,
    val artworkUrl: String,
    val streamUrl: String = "",
    val durationMs: Long = 0L,
    val playedTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "cached_lyrics")
data class LyricsEntity(
    @PrimaryKey val trackOriginalUrl: String,
    val trackTitle: String,
    val artistName: String,
    val lrcContent: String = "",
    val plainTextContent: String = "",
    val customUserContent: String = "",
    val activeSource: String = "LRC", // "LRC", "GENIUS", "CUSTOM"
    /** Откуда взят синхронизированный текст: "LRCLIB", "MUSIXMATCH" или пусто. */
    val syncedProvider: String = "",
    /** Откуда взят обычный текст: "LRCLIB", "MUSIXMATCH" или пусто. */
    val plainProvider: String = "",
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "liked_artists")
data class LikedArtistEntity(
    @PrimaryKey val permalinkUrl: String,
    val username: String,
    val avatarUrl: String,
    val followersCount: Int = 0,
    val trackCount: Int = 0,
    val addedTimestamp: Long = System.currentTimeMillis(),
    /** ID пользователя в SoundCloud — нужен для подписки/отписки на сайте. */
    @ColumnInfo(defaultValue = "0")
    val soundcloudId: Long = 0L
)

@Entity(tableName = "liked_albums")
data class LikedAlbumEntity(
    @PrimaryKey val permalinkUrl: String,
    val title: String,
    val artist: String,
    val artworkUrl: String,
    val trackCount: Int = 0,
    val addedTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "liked_playlists")
data class LikedPlaylistEntity(
    @PrimaryKey val permalinkUrl: String,
    val title: String,
    val user: String,
    val artworkUrl: String,
    val trackCount: Int = 0,
    val addedTimestamp: Long = System.currentTimeMillis()
)

/**
 * Коллекция, которую пользователь полностью или частично сохранил в офлайн.
 * Нужна, чтобы вкладка «Офлайн» могла сгруппировать кеш по плейлистам и
 * альбомам без обращения к сети.
 */
@Entity(
    tableName = "offline_collections",
    indices = [Index("type")]
)
data class OfflineCollectionEntity(
    /** "playlist:<permalink>", "album:<permalink>" или "local:<id>". */
    @PrimaryKey val collectionKey: String,
    /** PLAYLIST | ALBUM */
    val type: String,
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String = "",
    val permalinkUrl: String = "",
    val trackCount: Int = 0,
    val addedTimestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "offline_collection_tracks",
    primaryKeys = ["collectionKey", "trackOriginalUrl"],
    indices = [Index("trackOriginalUrl")]
)
data class OfflineCollectionTrackCrossRef(
    val collectionKey: String,
    val trackOriginalUrl: String,
    val position: Int = 0
)

/** Журнал действий пользователя для рекомендаций (локально, приватно). */
@Entity(
    tableName = "interaction_events",
    indices = [Index("trackOriginalUrl"), Index("timestamp"), Index("eventType")]
)
data class InteractionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackOriginalUrl: String,
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String = "",
    val genre: String = "",
    /** PLAY_COMPLETE | PLAY_PARTIAL | SKIP | LIKE | UNLIKE | REPEAT | ADD_QUEUE */
    val eventType: String,
    /** 0.0–1.0 доля прослушанного трека */
    val progressRatio: Float = 0f,
    val durationMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val hourOfDay: Int = 0,
    val dayOfWeek: Int = 0,
    val isOffline: Boolean = false
)

/** Агрегированный профиль предпочтений (кэш на устройстве). */
@Entity(tableName = "preference_profile")
data class PreferenceProfileEntity(
    @PrimaryKey val key: String,
    val weight: Float,
    val updatedAt: Long = System.currentTimeMillis()
)
