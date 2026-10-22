package com.zyro.player.domain

import com.zyro.player.data.local.InteractionDao
import com.zyro.player.data.local.InteractionEventEntity
import com.zyro.player.data.local.PreferenceDao
import com.zyro.player.data.local.PreferenceProfileEntity
import com.zyro.player.data.remote.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Локальный журнал действий: full play / skip / like / repeat.
 * Только на устройстве — без персональных данных на сервер.
 */
class InteractionLogger(
    private val interactionDao: InteractionDao,
    private val preferenceDao: PreferenceDao
) {
    companion object {
        const val PLAY_COMPLETE = "PLAY_COMPLETE"
        const val PLAY_PARTIAL = "PLAY_PARTIAL"
        const val SKIP = "SKIP"
        const val LIKE = "LIKE"
        const val UNLIKE = "UNLIKE"
        const val REPEAT = "REPEAT"
        const val ADD_QUEUE = "ADD_QUEUE"

        /** Полное прослушивание — ≥ 70% длительности или ≥ 90 сек */
        fun isCompleteListen(positionMs: Long, durationMs: Long): Boolean {
            if (durationMs <= 0L) return positionMs >= 90_000L
            val ratio = positionMs.toFloat() / durationMs
            return ratio >= 0.70f || positionMs >= 90_000L
        }

        /** Скип — ушли с трека раньше 30% и раньше 40 сек */
        fun isSkip(positionMs: Long, durationMs: Long): Boolean {
            if (durationMs <= 0L) return positionMs in 1 until 40_000L
            val ratio = positionMs.toFloat() / durationMs
            return ratio < 0.30f && positionMs < 40_000L
        }
    }

    suspend fun log(
        track: TrackInfo,
        eventType: String,
        positionMs: Long = 0L,
        isOffline: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance()
        val ratio = if (track.durationMs > 0) {
            (positionMs.toFloat() / track.durationMs).coerceIn(0f, 1f)
        } else 0f

        interactionDao.insert(
            InteractionEventEntity(
                trackOriginalUrl = track.originalUrl,
                title = track.title,
                artist = track.artist,
                artworkUrl = track.artworkUrl,
                genre = track.genre,
                eventType = eventType,
                progressRatio = ratio,
                durationMs = track.durationMs,
                timestamp = System.currentTimeMillis(),
                hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
                dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
                isOffline = isOffline
            )
        )

        // Инкрементально обновляем профиль предпочтений
        updatePreferences(track, eventType)

        // Чистим события старше 90 дней
        val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        interactionDao.pruneOlderThan(cutoff)
    }

    suspend fun logPlayEnd(
        track: TrackInfo,
        positionMs: Long,
        isOffline: Boolean = false
    ) {
        when {
            isCompleteListen(positionMs, track.durationMs) ->
                log(track, PLAY_COMPLETE, positionMs, isOffline)
            isSkip(positionMs, track.durationMs) ->
                log(track, SKIP, positionMs, isOffline)
            positionMs > 5_000L ->
                log(track, PLAY_PARTIAL, positionMs, isOffline)
        }
    }

    private suspend fun updatePreferences(track: TrackInfo, eventType: String) {
        val delta = when (eventType) {
            PLAY_COMPLETE, REPEAT -> 1.0f
            LIKE -> 1.5f
            PLAY_PARTIAL -> 0.35f
            ADD_QUEUE -> 0.5f
            SKIP -> -1.2f
            UNLIKE -> -1.5f
            else -> 0f
        }
        if (delta == 0f) return

        fun keyArtist() = "artist:${track.artist.lowercase().trim()}"
        fun keyGenre() = "genre:${track.genre.lowercase().trim()}"
        fun keyTrack() = "track:${track.originalUrl}"

        suspend fun bump(key: String, d: Float) {
            if (key.endsWith(":")) return
            val cur = preferenceDao.getWeight(key) ?: 0f
            preferenceDao.upsert(
                PreferenceProfileEntity(
                    key = key,
                    weight = (cur + d).coerceIn(-20f, 40f)
                )
            )
        }

        if (track.artist.isNotBlank()) bump(keyArtist(), delta)
        if (track.genre.isNotBlank()) bump(keyGenre(), delta * 0.8f)
        bump(keyTrack(), delta * 0.5f)

        // Контекст времени
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val slot = when (hour) {
            in 5..11 -> "time:morning"
            in 12..17 -> "time:day"
            in 18..22 -> "time:evening"
            else -> "time:night"
        }
        if (delta > 0) bump(slot, delta * 0.15f)
    }

    /** Обезличенная агрегация для будущей отправки по Wi‑Fi (без URL/имён). */
    suspend fun buildAnonymousAggregate(): Map<String, Float> = withContext(Dispatchers.IO) {
        preferenceDao.getAll()
            .filter { it.key.startsWith("genre:") || it.key.startsWith("time:") }
            .associate { it.key to it.weight }
    }
}
