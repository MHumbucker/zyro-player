package com.zyro.player.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY addedTimestamp DESC")
    fun getAllSavedTracks(): Flow<List<TrackEntity>>

    @Query(
        "SELECT * FROM tracks WHERE isLiked = 1 " +
            "ORDER BY CASE WHEN likedTimestamp > 0 THEN likedTimestamp ELSE addedTimestamp END DESC"
    )
    fun getLikedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isOffline = 1 ORDER BY addedTimestamp DESC")
    fun getOfflineTracks(): Flow<List<TrackEntity>>

    /** Однократный снимок офлайн-записей — для проверки целостности файлов. */
    @Query("SELECT * FROM tracks WHERE isOffline = 1")
    suspend fun getOfflineTracksSnapshot(): List<TrackEntity>

    /** Все пути, которые ещё используются треками (для поиска осиротевших файлов). */
    @Query("SELECT localFilePath FROM tracks WHERE localFilePath IS NOT NULL")
    suspend fun getAllLocalFilePaths(): List<String>

    @Query("SELECT * FROM tracks WHERE originalUrl = :url LIMIT 1")
    suspend fun getTrackByUrl(url: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTrack(track: TrackEntity)

    /** Пакетная запись: Room выполняет её в одной транзакции и один раз обновляет Flow. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTracks(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET isLiked = :isLiked WHERE originalUrl = :url")
    suspend fun setLikedStatus(url: String, isLiked: Boolean)

    @Query("UPDATE tracks SET isOffline = :isOffline, localFilePath = :filePath WHERE originalUrl = :url")
    suspend fun setOfflineStatus(url: String, isOffline: Boolean, filePath: String?)

    @Delete
    suspend fun deleteTrack(track: TrackEntity)
}

@Dao
interface OfflineCollectionDao {
    @Query("SELECT * FROM offline_collections ORDER BY addedTimestamp DESC")
    fun getCollections(): Flow<List<OfflineCollectionEntity>>

    @Query("SELECT * FROM offline_collection_tracks ORDER BY position ASC")
    fun getAllCollectionTracks(): Flow<List<OfflineCollectionTrackCrossRef>>

    @Query("SELECT * FROM offline_collection_tracks")
    suspend fun getAllCollectionTracksOnce(): List<OfflineCollectionTrackCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: OfflineCollectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollectionTracks(refs: List<OfflineCollectionTrackCrossRef>)

    @Query("DELETE FROM offline_collection_tracks WHERE collectionKey = :key")
    suspend fun clearCollectionTracks(key: String)

    @Query("DELETE FROM offline_collection_tracks WHERE trackOriginalUrl = :url")
    suspend fun removeTrackRefs(url: String)

    /** Треки конкретной офлайн-коллекции (для «удалить из офлайн»). */
    @Query("SELECT * FROM offline_collection_tracks WHERE collectionKey = :key ORDER BY position ASC")
    suspend fun getCollectionTracks(key: String): List<OfflineCollectionTrackCrossRef>

    @Query("DELETE FROM offline_collection_tracks")
    suspend fun clearAllCollectionTracks()

    @Query("DELETE FROM offline_collections")
    suspend fun clearAllCollections()

    @Query("DELETE FROM offline_collections WHERE collectionKey = :key")
    suspend fun deleteCollection(key: String)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdTimestamp DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE playlistId = :id LIMIT 1")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistTrack(ref: PlaylistTrackCrossRef)

    @Query("DELETE FROM playlist_track_cross_ref WHERE playlistId = :playlistId AND trackOriginalUrl = :trackUrl")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackUrl: String)

    @Query("DELETE FROM playlist_track_cross_ref WHERE playlistId = :playlistId")
    suspend fun deletePlaylistTracks(playlistId: Long)

    @Query("""
        SELECT t.* FROM tracks t 
        INNER JOIN playlist_track_cross_ref ref ON t.originalUrl = ref.trackOriginalUrl 
        WHERE ref.playlistId = :playlistId
        ORDER BY t.addedTimestamp DESC
    """)
    fun getTracksForPlaylist(playlistId: Long): Flow<List<TrackEntity>>

    /** Одноразовая загрузка для открытия плейлиста (без подписки на Flow). */
    @Query("""
        SELECT t.* FROM tracks t 
        INNER JOIN playlist_track_cross_ref ref ON t.originalUrl = ref.trackOriginalUrl 
        WHERE ref.playlistId = :playlistId
        ORDER BY t.addedTimestamp DESC
    """)
    suspend fun getTracksForPlaylistOnce(playlistId: Long): List<TrackEntity>

    /**
     * Обложка первого добавленного трека для каждого локального плейлиста —
     * фолбэк, когда у самого плейлиста обложки нет. Bare-column + MIN()
     * в SQLite гарантированно берёт значение из строки с минимальным временем.
     */
    @Query("""
        SELECT ref.playlistId AS playlistId,
               t.artworkUrl AS artworkUrl,
               MIN(t.addedTimestamp) AS addedTimestamp
        FROM playlist_track_cross_ref ref
        INNER JOIN tracks t ON t.originalUrl = ref.trackOriginalUrl
        WHERE t.artworkUrl != '' AND t.artworkUrl != 'null'
        GROUP BY ref.playlistId
    """)
    fun observeFirstTrackArtworks(): Flow<List<PlaylistArtwork>>
}

/** Строка результата [PlaylistDao.observeFirstTrackArtworks]. */
data class PlaylistArtwork(
    val playlistId: Long,
    val artworkUrl: String,
    val addedTimestamp: Long = 0L
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM listening_history ORDER BY playedTimestamp DESC LIMIT 100")
    fun getListeningHistory(): Flow<List<ListeningHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryRecord(record: ListeningHistoryEntity)

    @Query("SELECT artist, COUNT(*) as cnt FROM listening_history GROUP BY artist ORDER BY cnt DESC LIMIT 5")
    suspend fun getTopArtists(): List<ArtistCount>

    @Query("SELECT * FROM listening_history ORDER BY playedTimestamp DESC LIMIT 30")
    suspend fun getRecentHistory(): List<ListeningHistoryEntity>

    @Query("SELECT DISTINCT artist FROM listening_history ORDER BY playedTimestamp DESC LIMIT 10")
    suspend fun getRecentArtists(): List<String>

    @Query("DELETE FROM listening_history")
    suspend fun clearHistory()
}

data class ArtistCount(
    val artist: String,
    val cnt: Int
)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM cached_lyrics WHERE trackOriginalUrl = :url LIMIT 1")
    suspend fun getLyricsByUrl(url: String): LyricsEntity?

    @Query("SELECT * FROM cached_lyrics WHERE trackOriginalUrl = :url LIMIT 1")
    fun observeLyricsByUrl(url: String): Flow<LyricsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLyrics(lyrics: LyricsEntity)
}

@Dao
interface FavoritesDao {
    @Query("SELECT * FROM liked_artists ORDER BY addedTimestamp DESC")
    fun getLikedArtists(): Flow<List<LikedArtistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLikedArtist(artist: LikedArtistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLikedArtists(artists: List<LikedArtistEntity>)

    @Query("SELECT * FROM liked_artists WHERE permalinkUrl = :url LIMIT 1")
    suspend fun getLikedArtistByUrl(url: String): LikedArtistEntity?

    @Delete
    suspend fun deleteLikedArtist(artist: LikedArtistEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_artists WHERE permalinkUrl = :url)")
    fun isArtistLiked(url: String): Flow<Boolean>

    @Query("SELECT * FROM liked_albums ORDER BY addedTimestamp DESC")
    fun getLikedAlbums(): Flow<List<LikedAlbumEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLikedAlbum(album: LikedAlbumEntity)

    @Delete
    suspend fun deleteLikedAlbum(album: LikedAlbumEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_albums WHERE permalinkUrl = :url)")
    fun isAlbumLiked(url: String): Flow<Boolean>

    @Query("SELECT * FROM liked_playlists ORDER BY addedTimestamp DESC")
    fun getLikedPlaylists(): Flow<List<LikedPlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLikedPlaylist(playlist: LikedPlaylistEntity)

    @Delete
    suspend fun deleteLikedPlaylist(playlist: LikedPlaylistEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_playlists WHERE permalinkUrl = :url)")
    fun isPlaylistLiked(url: String): Flow<Boolean>
}

@Dao
interface InteractionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: InteractionEventEntity): Long

    @Query("SELECT * FROM interaction_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 500): List<InteractionEventEntity>

    @Query("SELECT * FROM interaction_events WHERE eventType = :type ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 200): List<InteractionEventEntity>

    @Query("""
        SELECT trackOriginalUrl, COUNT(*) as cnt FROM interaction_events
        WHERE eventType = 'SKIP' GROUP BY trackOriginalUrl HAVING cnt >= :minSkips
    """)
    suspend fun getFrequentlySkipped(minSkips: Int = 2): List<UrlCount>

    @Query("""
        SELECT genre, COUNT(*) as cnt FROM interaction_events
        WHERE genre != '' AND eventType IN ('PLAY_COMPLETE', 'LIKE', 'REPEAT')
        GROUP BY genre ORDER BY cnt DESC LIMIT 10
    """)
    suspend fun getTopGenres(): List<GenreCount>

    @Query("""
        SELECT artist, COUNT(*) as cnt FROM interaction_events
        WHERE eventType IN ('PLAY_COMPLETE', 'LIKE', 'REPEAT') AND artist != ''
        GROUP BY artist ORDER BY cnt DESC LIMIT 15
    """)
    suspend fun getTopArtistsFromEvents(): List<ArtistCount>

    @Query("DELETE FROM interaction_events WHERE timestamp < :before")
    suspend fun pruneOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM interaction_events")
    suspend fun count(): Int
}

data class UrlCount(val trackOriginalUrl: String, val cnt: Int)
data class GenreCount(val genre: String, val cnt: Int)

@Dao
interface PreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PreferenceProfileEntity)

    @Query("SELECT * FROM preference_profile")
    suspend fun getAll(): List<PreferenceProfileEntity>

    @Query("SELECT weight FROM preference_profile WHERE key = :key LIMIT 1")
    suspend fun getWeight(key: String): Float?

    @Query("DELETE FROM preference_profile")
    suspend fun clear()
}
