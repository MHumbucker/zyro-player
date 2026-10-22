package com.zyro.player.data.remote

import android.util.Log
import com.zyro.player.data.local.LyricsDao
import com.zyro.player.data.local.LyricsEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class LrcWord(
    val text: String,
    val startMs: Long,
    val endMs: Long = 0L
)

data class LrcLine(
    val timestampMs: Long,
    val text: String,
    /** Таймкоды слов (enhanced LRC, теги вида <mm:ss.xx>). Пусто, если их нет. */
    val words: List<LrcWord> = emptyList()
)

sealed class LyricsSourceState {
    object Idle : LyricsSourceState()
    object Loading : LyricsSourceState()

    data class Success(
        val source: String,
        val syncedLines: List<LrcLine>,
        val plainText: String
    ) : LyricsSourceState()

    data class Error(
        val message: String
    ) : LyricsSourceState()
}

private const val PROVIDER_LRCLIB = "LRCLIB"

/**
 * Загрузка и кэширование текстов песен.
 *
 * Источники опрашиваются параллельно:
 *  - Musixmatch — обычный текст (и синхронизированный, если доступен);
 *  - LRCLIB — синхронизированный LRC и запасной обычный текст.
 *
 * Синхронизированный вариант приоритетнее, поэтому если Musixmatch отдал
 * только обычный текст, а LRCLIB — LRC, в плеере будет LRC.
 */
class LyricsRepository(
    private val lyricsDao: LyricsDao,
    private val musixmatch: MusixmatchClient
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getLyrics(
        trackUrl: String,
        trackTitle: String,
        artistName: String,
        forceRefresh: Boolean = false,
        durationMs: Long = 0L
    ): LyricsEntity = withContext(Dispatchers.IO) {

        if (!forceRefresh) {
            val cached = lyricsDao.getLyricsByUrl(trackUrl)
            if (cached != null && cached.hasAnyContent()) {
                return@withContext cached
            }
        }

        val cleanTitle = cleanTitle(trackTitle)
        val cleanArtist = cleanArtist(artistName)

        val (lrclib, mxm) = coroutineScope {
            val lrclibDeferred = async {
                try {
                    fetchLrclib(cleanArtist, cleanTitle)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "" to ""
                }
            }
            val musixmatchDeferred = async {
                try {
                    // Первый запрос за сутки может скачивать веб-бандл Musixmatch
                    // (app_id + секрет), поэтому ограничиваем ожидание: LRCLIB
                    // не должен страдать из-за медленного Musixmatch.
                    withTimeoutOrNull(8_000L) {
                        musixmatch.fetchLyrics(cleanArtist, cleanTitle, durationMs)
                    } ?: MusixmatchLyrics()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    MusixmatchLyrics()
                }
            }
            lrclibDeferred.await() to musixmatchDeferred.await()
        }

        val synced = mxm.syncedLrc.ifBlank { lrclib.first }
        val syncedProvider = when {
            mxm.syncedLrc.isNotBlank() -> MusixmatchClient.PROVIDER
            lrclib.first.isNotBlank() -> PROVIDER_LRCLIB
            else -> ""
        }

        val plain = mxm.plainText.ifBlank { lrclib.second }
        val plainProvider = when {
            mxm.plainText.isNotBlank() -> MusixmatchClient.PROVIDER
            lrclib.second.isNotBlank() -> PROVIDER_LRCLIB
            else -> ""
        }

        val old = lyricsDao.getLyricsByUrl(trackUrl)

        val entity = LyricsEntity(
            trackOriginalUrl = trackUrl,
            trackTitle = trackTitle,
            artistName = artistName,
            lrcContent = synced.ifBlank { old?.lrcContent ?: "" },
            plainTextContent = plain.ifBlank { old?.plainTextContent ?: "" },
            customUserContent = old?.customUserContent ?: "",
            activeSource = when {
                // Пользовательский текст не перебиваем.
                old?.activeSource == "CUSTOM" -> "CUSTOM"
                // Синхронизированный текст — приоритетный вариант.
                synced.isNotBlank() -> "LRC"
                plain.isNotBlank() -> "GENIUS"
                else -> old?.activeSource ?: "NONE"
            },
            syncedProvider = syncedProvider.ifBlank { old?.syncedProvider ?: "" },
            plainProvider = plainProvider.ifBlank { old?.plainProvider ?: "" }
        )

        lyricsDao.saveLyrics(entity)

        entity
    }

    // ------------------------------------------------------------ LRCLIB

    private fun fetchLrclib(artist: String, title: String): Pair<String, String> {
        val url = "https://lrclib.net/api/search?" +
                "artist_name=${URLEncoder.encode(artist, "UTF-8")}" +
                "&track_name=${URLEncoder.encode(title, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ZyroPlayerApp/1.0 (Android)")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(
                        "ZYRO_LYRICS",
                        "LRCLIB HTTP ${response.code}: ${response.message}"
                    )
                    return "" to ""
                }

                val body = response.body?.string() ?: return "" to ""
                val array = JSONArray(body)

                var fallbackPlain = ""

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val synced = item.optString("syncedLyrics")
                    val plain = item.optString("plainLyrics")

                    if (synced.isNotBlank()) {
                        return synced to plain
                    }

                    if (plain.isNotBlank() && fallbackPlain.isBlank()) {
                        fallbackPlain = plain
                    }
                }

                "" to fallbackPlain
            }
        } catch (e: Exception) {
            Log.e("ZYRO_LYRICS", "LRCLIB error", e)
            "" to ""
        }
    }

    // ------------------------------------------------------------- custom

    suspend fun saveCustomLyrics(
        trackUrl: String,
        trackTitle: String,
        artistName: String,
        customText: String
    ) = withContext(Dispatchers.IO) {

        val old = lyricsDao.getLyricsByUrl(trackUrl)

        lyricsDao.saveLyrics(
            LyricsEntity(
                trackOriginalUrl = trackUrl,
                trackTitle = trackTitle,
                artistName = artistName,
                lrcContent = old?.lrcContent ?: "",
                plainTextContent = old?.plainTextContent ?: "",
                customUserContent = customText,
                activeSource = "CUSTOM",
                syncedProvider = old?.syncedProvider ?: "",
                plainProvider = old?.plainProvider ?: ""
            )
        )
    }

    suspend fun updateActiveSource(
        trackUrl: String,
        source: String
    ) = withContext(Dispatchers.IO) {

        val old = lyricsDao.getLyricsByUrl(trackUrl)
            ?: return@withContext

        lyricsDao.saveLyrics(
            old.copy(activeSource = source)
        )
    }

    // -------------------------------------------------------------- utils

    private fun LyricsEntity.hasAnyContent(): Boolean =
        lrcContent.isNotBlank() ||
                plainTextContent.isNotBlank() ||
                customUserContent.isNotBlank()

    private fun cleanTitle(title: String): String {
        return title
            .replace(
                Regex(
                    "(?i)" +
                            "\\(official.*?\\)|" +
                            "\\[official.*?\\]|" +
                            "\\(lyrics.*?\\)|" +
                            "\\[lyrics.*?\\]|" +
                            "\\(audio.*?\\)|" +
                            "\\[audio.*?\\]"
                ),
                ""
            )
            .replace(
                Regex("(?i)\\s+(feat\\.|ft\\.).*$"),
                ""
            )
            .trim()
    }

    private fun cleanArtist(artist: String): String {
        return artist
            .replace(Regex("(?i)VEVO|Official|Topic"), "")
            .trim()
    }

    companion object {

        fun parseLrc(lrcText: String): List<LrcLine> {

            if (lrcText.isBlank()) return emptyList()

            val result = mutableListOf<LrcLine>()

            val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{1,3})\\](.*)")

            lrcText.lines().forEach { line ->

                val match = regex.find(line.trim())

                if (match != null) {

                    val min = match.groupValues[1].toLongOrNull() ?: 0

                    val sec = match.groupValues[2].toLongOrNull() ?: 0

                    val msRaw = match.groupValues[3]

                    val ms = when (msRaw.length) {
                        1 -> msRaw.toLong() * 100
                        2 -> msRaw.toLong() * 10
                        else -> msRaw.toLong()
                    }

                    val rawText = match.groupValues[4]

                    val words = parseWordTags(rawText)

                    val text =
                        if (words.isEmpty()) rawText.trim()
                        else words.joinToString("") { it.text }.trim()

                    if (text.isNotBlank()) {
                        result.add(
                            LrcLine(
                                timestampMs = (min * 60 + sec) * 1000 + ms,
                                text = text,
                                words = words
                            )
                        )
                    }
                }
            }

            val sorted = result.sortedBy { it.timestampMs }

            // Проставляем end у слов: до старта следующего слова, у последнего — до следующей строки.
            return sorted.mapIndexed { index, line ->

                if (line.words.isEmpty()) {
                    line
                } else {

                    val nextLineStart = sorted.getOrNull(index + 1)
                        ?.timestampMs
                        ?: (line.timestampMs + 3000L)

                    val wordsWithEnds = line.words.mapIndexed { wordIndex, word ->

                        val end = line.words.getOrNull(wordIndex + 1)
                            ?.startMs
                            ?: nextLineStart

                        word.copy(endMs = end.coerceAtLeast(word.startMs))
                    }

                    line.copy(words = wordsWithEnds)
                }
            }
        }

        private val WORD_TAG_REGEX = Regex("<(\\d{2}):(\\d{2})\\.(\\d{1,3})>")

        private fun parseWordTags(text: String): List<LrcWord> {

            val matches = WORD_TAG_REGEX.findAll(text).toList()

            if (matches.isEmpty()) return emptyList()

            val words = mutableListOf<LrcWord>()

            matches.forEachIndexed { index, match ->

                val min = match.groupValues[1].toLongOrNull() ?: 0

                val sec = match.groupValues[2].toLongOrNull() ?: 0

                val msRaw = match.groupValues[3]

                val ms = when (msRaw.length) {
                    1 -> msRaw.toLong() * 100
                    2 -> msRaw.toLong() * 10
                    else -> msRaw.toLong()
                }

                val from = match.range.last + 1

                val to = matches.getOrNull(index + 1)
                    ?.range
                    ?.first
                    ?: text.length

                if (to > from) {
                    words.add(
                        LrcWord(
                            text = text.substring(from, to),
                            startMs = (min * 60 + sec) * 1000 + ms
                        )
                    )
                }
            }

            return words
        }
    }
}
