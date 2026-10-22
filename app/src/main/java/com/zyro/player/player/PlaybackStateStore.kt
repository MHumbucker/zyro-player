package com.zyro.player.player

import android.content.Context
import android.util.Log
import com.player.player.RepeatMode
import com.zyro.player.data.remote.TrackInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Снимок последнего состояния плеера для восстановления очереди после
 * перезапуска приложения или смерти процесса.
 */
data class PlaybackSnapshot(
    val queue: List<TrackInfo>,
    val currentIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
    val wasPlaying: Boolean
)

/**
 * Хранит очередь и позицию в SharedPreferences.
 *
 * Очередь пишется только при её изменении (`includeQueue = true`), а позиция
 * и режимы — небольшим набором скалярных ключей. Так progress tracker не
 * пересобирает JSON всей очереди каждые несколько секунд.
 */
class PlaybackStateStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(
        snapshot: PlaybackSnapshot,
        includeQueue: Boolean = true,
        synchronous: Boolean = false
    ) {
        if (snapshot.queue.isEmpty()) {
            clear(synchronous)
            return
        }

        try {
            val editor = prefs.edit()

            if (includeQueue || !prefs.contains(KEY_QUEUE)) {
                val queueJson = JSONArray().apply {
                    snapshot.queue.forEach { put(it.toJson()) }
                }
                editor.putString(KEY_QUEUE, queueJson.toString())
            }

            editor.putInt(KEY_VERSION, 1)
                .putInt(KEY_INDEX, snapshot.currentIndex.coerceIn(0, snapshot.queue.lastIndex))
                .putLong(KEY_POSITION, snapshot.positionMs.coerceAtLeast(0L))
                .putLong(KEY_DURATION, snapshot.durationMs.coerceAtLeast(0L))
                .putBoolean(KEY_SHUFFLE, snapshot.shuffle)
                .putString(KEY_REPEAT, snapshot.repeatMode.name)
                .putBoolean(KEY_WAS_PLAYING, snapshot.wasPlaying)

            if (synchronous) editor.commit() else editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save playback state", e)
        }
    }

    fun load(): PlaybackSnapshot? {
        val rawQueue = prefs.getString(KEY_QUEUE, null) ?: return null
        return try {
            val queueJson = JSONArray(rawQueue)
            val queue = buildList {
                for (index in 0 until queueJson.length()) {
                    queueJson.optJSONObject(index)?.toTrackInfo()?.let(::add)
                }
            }
            if (queue.isEmpty()) return null

            PlaybackSnapshot(
                queue = queue,
                currentIndex = prefs.getInt(KEY_INDEX, 0).coerceIn(0, queue.lastIndex),
                positionMs = prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L),
                durationMs = prefs.getLong(KEY_DURATION, 0L).coerceAtLeast(0L),
                shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
                repeatMode = runCatching {
                    RepeatMode.valueOf(
                        prefs.getString(KEY_REPEAT, RepeatMode.NONE.name) ?: RepeatMode.NONE.name
                    )
                }.getOrDefault(RepeatMode.NONE),
                wasPlaying = prefs.getBoolean(KEY_WAS_PLAYING, false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load playback state", e)
            null
        }
    }

    fun clear(synchronous: Boolean = false) {
        val editor = prefs.edit().clear()
        if (synchronous) editor.commit() else editor.apply()
    }

    private fun TrackInfo.toJson(): JSONObject = JSONObject().apply {
        put("originalUrl", originalUrl)
        put("title", title)
        put("artist", artist)
        put("artworkUrl", artworkUrl)
        put("durationMs", durationMs)
        put("likesCount", likesCount)
        put("playbackCount", playbackCount)
        put("commentCount", commentCount)
        put("genre", genre)
        put("description", description)
        put("albumTitle", albumTitle)
        put("albumPermalinkUrl", albumPermalinkUrl)
        put("id", id)
        put("isLiked", isLiked)
        put("likedAt", likedAt)
        put("artistAvatarUrl", artistAvatarUrl)
        put("isGoPlus", isGoPlus)
    }

    private fun JSONObject.toTrackInfo(): TrackInfo? {
        val url = optString("originalUrl").trim()
        if (url.isBlank()) return null

        return TrackInfo(
            originalUrl = url,
            // Подписанные stream-URL быстро истекают. После восстановления
            // резолвим поток заново, чтобы не получить ошибку 403.
            streamUrl = "",
            title = optString("title"),
            artist = optString("artist"),
            artworkUrl = optString("artworkUrl"),
            durationMs = optLong("durationMs", 0L).coerceAtLeast(0L),
            likesCount = optLong("likesCount", 0L).coerceAtLeast(0L),
            playbackCount = optLong("playbackCount", 0L).coerceAtLeast(0L),
            commentCount = optLong("commentCount", 0L).coerceAtLeast(0L),
            genre = optString("genre"),
            description = optString("description"),
            albumTitle = optString("albumTitle"),
            albumPermalinkUrl = optString("albumPermalinkUrl"),
            id = optLong("id", 0L),
            isLiked = optBoolean("isLiked", false),
            likedAt = optLong("likedAt", 0L),
            artistAvatarUrl = optString("artistAvatarUrl"),
            isGoPlus = optBoolean("isGoPlus", false)
        )
    }

    companion object {
        private const val TAG = "ZYRO_PLAYBACK_STATE"
        private const val PREFS = "zyro_playback_state"
        private const val KEY_VERSION = "version"
        private const val KEY_QUEUE = "queue"
        private const val KEY_INDEX = "currentIndex"
        private const val KEY_POSITION = "positionMs"
        private const val KEY_DURATION = "durationMs"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeatMode"
        private const val KEY_WAS_PLAYING = "wasPlaying"
    }
}
