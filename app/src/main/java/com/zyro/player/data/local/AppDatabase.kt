package com.zyro.player.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        ListeningHistoryEntity::class,
        LyricsEntity::class,
        LikedArtistEntity::class,
        LikedAlbumEntity::class,
        LikedPlaylistEntity::class,
        InteractionEventEntity::class,
        PreferenceProfileEntity::class,
        OfflineCollectionEntity::class,
        OfflineCollectionTrackCrossRef::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun historyDao(): HistoryDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun interactionDao(): InteractionDao
    abstract fun preferenceDao(): PreferenceDao
    abstract fun offlineCollectionDao(): OfflineCollectionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v4: у кэша текстов появились провайдеры (LRCLIB / Musixmatch).
         *
         * Старые строки не знают, откуда пришёл текст, и были загружены только
         * через LRCLIB. Сбрасываем их (кроме пользовательских текстов), чтобы
         * при следующем открытии трека текст перезагрузился уже через новый
         * пайплайн с Musixmatch.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cached_lyrics ADD COLUMN syncedProvider TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE cached_lyrics ADD COLUMN plainProvider TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL("DELETE FROM cached_lyrics WHERE customUserContent = ''")
            }
        }

        /**
         * v5: храним SoundCloud ID и реальное время лайка.
         *
         * ID нужен, чтобы лайк из локальной библиотеки уходил на сайт, а не
         * терялся из-за `id = 0`. `likedTimestamp` сохраняет порядок лайков
         * таким же, как на сайте SoundCloud (сверху — недавние).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN soundcloudId INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN likedTimestamp INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE liked_artists ADD COLUMN soundcloudId INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v6: чистим дубликаты серверных плейлистов.
         *
         * Раньше `loadPlaylistsLazy()`/`importSoundCloudLibrary()` при каждой
         * синхронизации вставляли плейлисты SoundCloud в локальную таблицу
         * (`coverUrl` заполнялся только этими путями). За несколько запусков
         * накапливались десятки одинаковых строк. Теперь серверные плейлисты
         * показываются напрямую из снапшота SoundCloud, а созданные
         * пользователем локальные плейлисты (coverUrl = '') остаются.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM playlists WHERE coverUrl != ''")
                db.execSQL(
                    "DELETE FROM playlist_track_cross_ref " +
                        "WHERE playlistId NOT IN (SELECT playlistId FROM playlists)"
                )
            }
        }

        /**
         * v7: собственная обложка локального плейлиста.
         *
         * Нельзя хранить её в `coverUrl`: по этому полю старые версии
         * определяли импортированные серверные копии. Выносим путь в
         * отдельную колонку, чтобы фильтр `coverUrl = ''` остался рабочим.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE playlists ADD COLUMN localCoverPath TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v8: офлайн-коллекции.
         *
         * Чтобы вкладка «Офлайн» могла показать «Лайки / Плейлисты / Альбомы /
         * Другое», запоминаем, в какую коллекцию входил скачанный трек.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Таблицы появились только в v8. DROP на всякий случай: если
                // промежуточная dev-сборка успела создать их с другим DDL,
                // миграция не должна падать на валидации Room.
                db.execSQL("DROP TABLE IF EXISTS `offline_collection_tracks`")
                db.execSQL("DROP TABLE IF EXISTS `offline_collections`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offline_collections` (" +
                        "`collectionKey` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`subtitle` TEXT NOT NULL, " +
                        "`artworkUrl` TEXT NOT NULL, " +
                        "`permalinkUrl` TEXT NOT NULL, " +
                        "`trackCount` INTEGER NOT NULL, " +
                        "`addedTimestamp` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`collectionKey`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_offline_collections_type` " +
                        "ON `offline_collections` (`type`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `offline_collection_tracks` (" +
                        "`collectionKey` TEXT NOT NULL, " +
                        "`trackOriginalUrl` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`collectionKey`, `trackOriginalUrl`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_offline_collection_tracks_trackOriginalUrl` " +
                        "ON `offline_collection_tracks` (`trackOriginalUrl`)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "zyro_player.db"
                )
                    .addMigrations(
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
