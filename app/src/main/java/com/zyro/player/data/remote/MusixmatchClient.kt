package com.zyro.player.data.remote

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** Результат Musixmatch: либо синхронизированный LRC, либо обычный текст. */
data class MusixmatchLyrics(
    val syncedLrc: String = "",
    val plainText: String = ""
)

/**
 * Клиент лирики Musixmatch.
 *
 * У Musixmatch есть официальный API, но он требует ключ, а синхронизированный
 * текст отдаёт только на платном тарифе. Поэтому здесь используется тот же
 * публичный веб-эндпоинт, что и на musixmatch.com: запросы подписываются
 * HMAC-SHA256, а app_id и секрет берутся из актуального веб-бандла
 * (они периодически меняются, поэтому кэшируются на сутки).
 *
 * В бесплатном доступе стабильно работает обычный текст
 * (`track.lyrics.get`), синхронизированный (`track.subtitle.get`) может
 * отвечать 403 — тогда остаётся обычный, а синхронизацию даёт LRCLIB.
 */
class MusixmatchClient(context: Context) {

    companion object {
        const val PROVIDER = "MUSIXMATCH"

        private const val TAG = "ZYRO_MUSIXMATCH"
        private const val BASE_URL = "https://www.musixmatch.com/ws/1.1/"
        private const val SITE_URL = "https://www.musixmatch.com"

        private const val DEFAULT_APP_ID = "mxm-com-v1.0"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val PREFS = "musixmatch_lyrics"
        private const val KEY_APP_ID = "app_id"
        private const val KEY_SECRET = "client_secret"
        private const val KEY_DAY = "credentials_day"

        private val APP_CHUNK_REGEX =
            Regex("src=\"([^\"]*/_next/static/chunks/pages/_app-[^\"]+\\.js)\"")

        private val SECRET_REGEX =
            Regex("from\\(\\s*\"(.*?)\"\\s*\\.split")

        private val APP_ID_REGEX =
            Regex("APP_ID:\"(mxm[^\"]+)\"")

        private val LRC_LINE_REGEX = Regex("\\[\\d{1,3}:\\d{2}")
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private data class Credentials(val appId: String, val secret: String)

    private data class TrackCandidate(
        val id: Long,
        val title: String,
        val artist: String,
        val lengthMs: Long
    )

    // ---------------------------------------------------------------- public

    /**
     * Ищет трек на Musixmatch и забирает текст.
     * Никогда не бросает исключения: при любой ошибке возвращает пустой результат,
     * чтобы вызывающий код мог спокойно откатиться на LRCLIB.
     */
    suspend fun fetchLyrics(
        artist: String,
        title: String,
        durationMs: Long = 0L
    ): MusixmatchLyrics = withContext(Dispatchers.IO) {
        if (title.isBlank() && artist.isBlank()) return@withContext MusixmatchLyrics()

        try {
            val track = findTrack(artist, title, durationMs) ?: return@withContext MusixmatchLyrics()

            val synced = fetchSubtitle(track.id)
            val plain = fetchPlainText(track.id)

            MusixmatchLyrics(syncedLrc = synced, plainText = plain)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "lyrics request failed: ${e.message}")
            MusixmatchLyrics()
        }
    }

    // --------------------------------------------------------------- search

    private fun findTrack(artist: String, title: String, durationMs: Long): TrackCandidate? {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null

        val json = request(
            path = "track.search",
            params = listOf(
                "q" to query,
                "page_size" to "8",
                "page" to "1",
                "f_has_lyrics" to "true"
            )
        ) ?: return null

        val trackList = json.optJSONObject("message")
            ?.optJSONObject("body")
            ?.optJSONArray("track_list")
            ?: return null

        val candidates = mutableListOf<TrackCandidate>()

        for (i in 0 until trackList.length()) {
            val track = trackList.optJSONObject(i)?.optJSONObject("track") ?: continue
            val id = track.optLong("track_id", 0L)
            if (id <= 0L) continue

            candidates.add(
                TrackCandidate(
                    id = id,
                    title = track.optString("track_name", ""),
                    artist = track.optString("artist_name", ""),
                    lengthMs = track.optInt("track_length", 0) * 1000L
                )
            )
        }

        return pickBest(candidates, artist, title, durationMs)
    }

    private fun pickBest(
        candidates: List<TrackCandidate>,
        artist: String,
        title: String,
        durationMs: Long
    ): TrackCandidate? {
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)

        if (wantedTitle.isBlank() && wantedArtist.isBlank()) return null
        if (candidates.isEmpty()) return null

        return candidates
            .map { candidate -> candidate to score(candidate, wantedTitle, wantedArtist, durationMs) }
            .filter { (_, score) -> score >= 2 }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun score(
        candidate: TrackCandidate,
        wantedTitle: String,
        wantedArtist: String,
        durationMs: Long
    ): Int {
        val candidateTitle = normalize(candidate.title)
        val candidateArtist = normalize(candidate.artist)

        val titleMatches = wantedTitle.isNotBlank() && candidateTitle.isNotBlank() && (
                candidateTitle == wantedTitle ||
                        candidateTitle.contains(wantedTitle) ||
                        wantedTitle.contains(candidateTitle)
                )

        if (!titleMatches) return 0

        var result = if (candidateTitle == wantedTitle) 3 else 2

        val artistMatches = wantedArtist.isNotBlank() && candidateArtist.isNotBlank() && (
                candidateArtist == wantedArtist ||
                        candidateArtist.contains(wantedArtist) ||
                        wantedArtist.contains(candidateArtist)
                )

        if (artistMatches) {
            result += 2
        } else if (wantedArtist.isNotBlank() && candidateArtist.isNotBlank()) {
            // Разные исполнители при совпавшем названии — почти наверняка кавер.
            result -= 1
        }

        if (durationMs > 0L && candidate.lengthMs > 0L) {
            when (val diff = abs(durationMs - candidate.lengthMs)) {
                in 0..5_000 -> result += 2
                in 5_001..12_000 -> result += 1
                in 30_001..Long.MAX_VALUE -> result -= 3
            }
        }

        if (!artistMatches && durationMs <= 0L) {
            // Без артиста и длительности доверять нельзя.
            return 0
        }

        return result
    }

    private fun normalize(value: String): String {
        if (value.isBlank()) return ""
        return value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    // ---------------------------------------------------------------- text

    private fun fetchSubtitle(trackId: Long): String {
        val json = request(
            path = "track.subtitle.get",
            params = listOf(
                "track_id" to trackId.toString(),
                "subtitle_format" to "lrc"
            )
        ) ?: return ""

        val header = json.optJSONObject("message")?.optJSONObject("header")
        if (header?.optInt("status_code", 0) != 200) return ""

        val body = json.optJSONObject("message")
            ?.optJSONObject("body")
            ?.optJSONObject("subtitle")
            ?.optString("subtitle_body")
            .orEmpty()
            .trim()

        // Нас интересует именно LRC, а не JSON-subtitle.
        return if (LRC_LINE_REGEX.containsMatchIn(body)) body else ""
    }

    private fun fetchPlainText(trackId: Long): String {
        val json = request(
            path = "track.lyrics.get",
            params = listOf("track_id" to trackId.toString())
        ) ?: return ""

        val lyrics = json.optJSONObject("message")
            ?.optJSONObject("body")
            ?.optJSONObject("lyrics")
            ?: return ""

        return lyrics.optString("lyrics_body", "")
            .replace(
                Regex(
                    "(?i)\\**\\s*This Lyrics is NOT for Commercial use\\s*\\**"
                ),
                ""
            )
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    // --------------------------------------------------------------- http

    private fun request(
        path: String,
        params: List<Pair<String, String>>,
        allowRetry: Boolean = true
    ): JSONObject? {
        val credentials = getCredentials() ?: return null
        val url = buildSignedUrl(credentials, path, params)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Referer", "$SITE_URL/")
            .header("Origin", SITE_URL)
            .build()

        val json = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "$path → HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                JSONObject(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "$path → ${e.message}")
            return null
        }

        val header = json.optJSONObject("message")?.optJSONObject("header")
        val status = header?.optInt("status_code", 0) ?: 0
        val hint = header?.optString("hint", "").orEmpty()

        // Подпись устарела — сбрасываем секрет и пробуем ещё раз.
        if (allowRetry && status == 401 && hint.contains("signature", ignoreCase = true)) {
            prefs.edit().clear().apply()
            return request(path, params, allowRetry = false)
        }

        return json
    }

    private fun buildSignedUrl(
        credentials: Credentials,
        path: String,
        params: List<Pair<String, String>>
    ): String {
        val query = params.joinToString("&") { (key, value) ->
            "$key=${encode(value)}"
        }

        // Подпись считается по URL ровно в том виде, в котором он уйдёт на сервер.
        var url = BASE_URL + path
        url += if (query.isNotBlank()) "?$query&" else "?"
        url += "app_id=${credentials.appId}&format=json"
        url = url.replace("%20", "+").replace(" ", "+")

        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(credentials.secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.encodeToString(
            mac.doFinal((url + day).toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )

        return "$url&signature=${encode(signature)}&signature_protocol=sha256"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    // ------------------------------------------------------------- secret

    private fun getCredentials(): Credentials? {
        val today = dayStamp()

        if (prefs.getString(KEY_DAY, null) == today) {
            val appId = prefs.getString(KEY_APP_ID, null)
            val secret = prefs.getString(KEY_SECRET, null)
            if (!appId.isNullOrBlank() && !secret.isNullOrBlank()) {
                return Credentials(appId, secret)
            }
        }

        val fetched = fetchCredentials()
        if (fetched != null) {
            prefs.edit()
                .putString(KEY_APP_ID, fetched.appId)
                .putString(KEY_SECRET, fetched.secret)
                .putString(KEY_DAY, today)
                .apply()
            return fetched
        }

        // Сеть недоступна — используем прошлые значения, если они есть.
        val appId = prefs.getString(KEY_APP_ID, null)
        val secret = prefs.getString(KEY_SECRET, null)
        if (!appId.isNullOrBlank() && !secret.isNullOrBlank()) {
            return Credentials(appId, secret)
        }

        return null
    }

    private fun dayStamp(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun fetchCredentials(): Credentials? {
        return try {
            val html = httpGet("$SITE_URL/search", cookie = "mxm_bab=AB") ?: return null

            val chunkUrl = APP_CHUNK_REGEX.findAll(html)
                .lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
                ?.let { if (it.startsWith("/")) SITE_URL + it else it }
                ?: return null

            val chunk = httpGet(chunkUrl) ?: return null

            val secretBase64 = SECRET_REGEX.find(chunk)
                ?.groupValues
                ?.getOrNull(1)
                ?: return null

            val secret = String(
                Base64.decode(secretBase64.reversed(), Base64.DEFAULT),
                Charsets.UTF_8
            ).trim()

            if (secret.length < 16) return null

            val appId = APP_ID_REGEX.find(chunk)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_APP_ID

            Credentials(appId, secret)
        } catch (e: Exception) {
            Log.w(TAG, "credentials: ${e.message}")
            null
        }
    }

    private fun httpGet(url: String, cookie: String? = null): String? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/javascript,*/*")

        if (cookie != null) {
            builder.header("Cookie", cookie)
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GET $url → HTTP ${response.code}")
                    return null
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url → ${e.message}")
            null
        }
    }
}
