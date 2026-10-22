package com.zyro.player.data.remote

import android.util.Log
import com.zyro.player.data.auth.ScConfig
import com.zyro.player.data.auth.SessionManager
import com.zyro.player.data.auth.SoundCloudAuthRecoveryInterceptor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

// ======================== Data Models ========================

data class TrackInfo(
    val originalUrl: String,
    val streamUrl: String = "",
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String = "",
    val durationMs: Long = 0L,
    val likesCount: Long = 0L,
    val playbackCount: Long = 0L,
    val commentCount: Long = 0L,
    /** Жанр, если пришёл из API */
    val genre: String = "",
    /** Описание трека */
    val description: String = "",
    /** Название альбома (если трек привязан к альбому) */
    val albumTitle: String = "",
    /** Permalink альбома — если пусто, кнопки «К альбому» не показывать */
    val albumPermalinkUrl: String = "",
    /** ID трека в SoundCloud (для лайков и других операций API) */
    val id: Long = 0L,
    /** Лайкнут ли текущим авторизованным пользователем */
    val isLiked: Boolean = false,
    /** Время лайка на SoundCloud (мс), если пришло из списка лайков. */
    val likedAt: Long = 0L,
    /**
     * Аватар исполнителя (круглая мини-аватарка в плеере и сид для экрана
     * артиста). Пусто, если трек пришёл из локальной базы без этой информации.
     */
    val artistAvatarUrl: String = "",
    /**
     * Трек из каталога SoundCloud Go+: без подписки Go+ доступен только
     * короткий фрагмент. Помечается бейджем «GO+» во всех списках треков,
     * а в полноэкранном плеере показывает пояснение вместо полной обложки.
     */
    val isGoPlus: Boolean = false
)

data class UserInfo(
    val permalinkUrl: String,
    val username: String,
    val avatarUrl: String,
    val followersCount: Int,
    val trackCount: Int,
    val description: String = "",
    val bannerUrl: String = "",
    val country: String = "",
    val city: String = "",
    val id: Long = 0L,
    /** ISO-код страны (US, DE, …). Нужен форме редактирования профиля. */
    val countryCode: String = "",
    /** Настоящее имя в профиле SoundCloud (отдельно от username). */
    val firstName: String = "",
    val lastName: String = ""
)

data class ArtistFullData(
    val user: UserInfo,
    val description: String = "",
    val topTracks: List<TrackInfo> = emptyList(),
    val albums: List<AlbumInfo> = emptyList(),
    val tracks: List<TrackInfo> = emptyList(),
    val playlists: List<PlaylistInfo> = emptyList(),
    val reposts: List<TrackInfo> = emptyList()
)

data class AlbumInfo(
    val id: Long,
    val permalinkUrl: String,
    val title: String,
    val artist: String,
    val artworkUrl: String,
    val trackCount: Int
)

data class PlaylistInfo(
    val id: Long,
    val permalinkUrl: String,
    val title: String,
    val user: String,
    val artworkUrl: String,
    val trackCount: Int,
    /** Описание плейлиста (если пришло из API) */
    val description: String = "",
    /** Лайкнут ли текущим авторизованным пользователем */
    val isLiked: Boolean = false,
    /** Жанр из SoundCloud (для формы редактирования). */
    val genre: String = "",
    /** Теги SoundCloud в виде строки через запятую. */
    val tagList: String = "",
    /** Публичный ли плейлист (sharing == "public"). */
    val isPublic: Boolean = false,
    /** Суммарная длительность плейлиста в миллисекундах (если пришла из API). */
    val durationMs: Long = 0L,
    /** Дата создания в миллисекундах (0, если API её не прислал). */
    val createdAtMs: Long = 0L,
    /**
     * URN плейлиста-источника. Для системных плейлистов SoundCloud
     * (soundcloud:system-playlists:...) — единственный надёжный идентификатор.
     */
    val urn: String = ""
)

data class SearchResults(
    val tracks: List<TrackInfo>,
    val users: List<UserInfo>,
    val albums: List<AlbumInfo> = emptyList(),
    val playlists: List<PlaylistInfo> = emptyList(),
    val hasMore: Boolean = false
)

/**
 * Одна секция официального фида главной («mixed-selections»):
 * «Миксы для …», «Больше из того, что вам нравится», «В тренде по жанрам» и т.д.
 */
data class ScHomeSelection(
    val id: String,
    val urn: String,
    val title: String,
    val description: String,
    val tracks: List<TrackInfo> = emptyList(),
    val playlists: List<PlaylistInfo> = emptyList(),
    val users: List<UserInfo> = emptyList()
)

/** Действие в ленте подписок SoundCloud. */
enum class ScFeedAction { UPLOAD, REPOST, LIKE }

/**
 * Одна запись ленты подписок (`GET /stream`): новый трек/плейлист,
 * репост или лайк артиста, на которого подписан пользователь.
 */
data class ScFeedActivity(
    val id: String,
    val action: ScFeedAction,
    val actor: UserInfo? = null,
    val track: TrackInfo? = null,
    val playlist: PlaylistInfo? = null,
    val createdAtMs: Long = 0L
)

/** Страница ленты подписок + курсор для следующей страницы. */
data class ScFeedPage(
    val activities: List<ScFeedActivity>,
    val nextHref: String? = null
)

/** Системный плейлист SoundCloud (микс/станция), загруженный с его треками. */
data class SystemPlaylistDetail(
    val playlist: PlaylistInfo,
    val tracks: List<TrackInfo>
)

data class CommentInfo(
    val id: Long,
    val body: String,
    val username: String,
    val userAvatar: String,
    val userPermalink: String,
    val timestampMs: Int = 0,
    val createdAt: java.util.Date? = null
)

data class CommentsResult(
    val comments: List<CommentInfo>,
    val nextHref: String?,
    val hasMore: Boolean
)

// ======================== Result Wrapper ========================

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val cause: Throwable? = null) : Result<Nothing>()
    object Loading : Result<Nothing>()
}

// ======================== SoundCloudRepository ========================

class SoundCloudRepository(
    private val cacheDir: File,
    private val clientIdFallbacks: List<String> = listOf(
        ScConfig.OFFICIAL_CLIENT_ID
    ),
    private var authToken: String? = null,  // OAuth-токен (передаётся после авторизации)
    /**
     * Динамический источник OAuth-токена. Позволяет держать заголовок
     * Authorization актуальным без ручных вызовов updateAuthToken().
     */
    private val authTokenProvider: (() -> String?)? = null,
    /** Стабильный UDID для заголовков официального мобильного клиента. */
    private val deviceIdProvider: (() -> String?)? = null,
    /**
     * Блокирующее обновление OAuth-токена для обработки 401/403 внутри
     * OkHttp-интерцептора (см. SoundCloudAuthRecoveryInterceptor).
     */
    private val authTokenRefresher: ((String?) -> String?)? = null
) {

    // ---------- HTTP Client with Cache and DNS-over-HTTPS ----------
    private val httpCache = Cache(cacheDir, 50 * 1024 * 1024)

    private val dnsOverHttps by lazy {
        val dnsClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        okhttp3.dnsoverhttps.DnsOverHttps.Builder()
            .client(dnsClient)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .build()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cache(httpCache)
        .dns(dnsOverHttps)
        .addInterceptor { chain ->
            val original = chain.request()
            // API SoundCloud должен видеть UA официального Android-клиента:
            // браузерный UA вместе с OAuth-токеном DataDome часто отбрасывает
            // с 403. Браузерный UA оставляем только для scraping soundcloud.com.
            val isApiHost = original.url.host == "api-v2.soundcloud.com" ||
                original.url.host == "api-mobile.soundcloud.com"
            val builder = original.newBuilder()
                .header(
                    "User-Agent",
                    if (isApiHost) ScConfig.API_USER_AGENT else ScConfig.USER_AGENT
                )

            val token = authTokenProvider?.invoke() ?: authToken
            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "OAuth $token")
                if (isApiHost) {
                    builder.header("App-Version", "330120")
                    deviceIdProvider?.invoke()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { builder.header("UDID", it) }
                }
            }

            val request = builder.method(original.method, original.body).build()
            chain.proceed(request)
        }
        // 401/403: повтор без cookies и при необходимости с новым токеном.
        .addInterceptor(
            SoundCloudAuthRecoveryInterceptor(
                refreshTokenBlocking = { staleToken ->
                    authTokenRefresher?.invoke(staleToken)
                }
            )
        )
        .build()

    // ---------- Client ID management ----------
    private var cachedClientId: String? = null
    private var clientIdExpiryTime: Long = 0L
    private val clientIdLock = Any()

    // ---------- Throttling / 429 ----------
    // SoundCloud быстро отвечает 429 на пакетные resolve-запросы. Все
    // запросы через executeApiRequest идут последовательно с небольшой
    // паузой, а при 429 выдерживают общий cooldown.
    private val apiGateMutex = Mutex()
    private var nextApiRequestAtMs = 0L
    private var rateLimitUntilMs = 0L
    private val apiRequestIntervalMs = 400L

    // ---------- Обновление OAuth-токена (вызывается из AuthManager) ----------
    fun updateAuthToken(token: String?) {
        authToken = token
    }

    // ======================== PUBLIC METHODS (для других репозиториев) ========================

    /**
     * Приводит URL аватара к максимальному качеству.
     *
     * В SoundCloud размер задаётся суффиксом (`-large`, `-t300x300`,
     * `-original`), а не query-параметром — поэтому старый `?size=300x300`
     * просто игнорировался и аватар оставался 100×100.
     */
    fun formatAvatar(url: String, size: Int = 300): String {
        if (url.isBlank() || url == "null") return ""
        return SoundCloudArtwork.toOriginal(url)
    }

    fun parseArtistName(item: JSONObject, userObj: JSONObject?): String {
        val pubArtist = item.optJSONObject("publisher_metadata")?.optString("artist", "")?.trim() ?: ""
        if (pubArtist.isNotBlank()) return pubArtist

        val title = item.optString("title", "")
        if (title.contains(" - ")) {
            val parts = title.split(" - ")
            if (parts[0].trim().isNotBlank()) {
                return parts[0].trim()
            }
        }

        val fullName = userObj?.optString("full_name", "")?.trim() ?: ""
        if (fullName.isNotBlank()) return fullName

        val username = userObj?.optString("username", "")?.trim() ?: ""
        if (username.isNotBlank()) return username

        return "SoundCloud Artist"
    }

    /**
     * Приводит обложку SoundCloud к максимальному доступному качеству.
     *
     * Сетевой HEAD-профилактики здесь больше нет: URL сразу поднимается до
     * `-original`, а откат на `t500x500` при отсутствии оригинала делает
     * [SoundCloudArtworkInterceptor] непосредственно при загрузке картинки.
     */
    /**
     * Обложка первого трека из вложенного массива `tracks` — фолбэк для
     * плейлистов и альбомов без собственной обложки. SoundCloud сам поступает
     * так же: если у коллекции нет картинки, показывается обложка её трека.
     */
    fun getFirstTrackArtwork(item: JSONObject): String = Mapper.firstTrackArtwork(item)

    fun getBestArtworkUrl(url: String): String = SoundCloudArtwork.toOriginal(url)

    /**
     * Трек из каталога Go+ (в API — `monetization_model=SUB_HIGH_TIER`)
     * либо доступный текущему аккаунту только фрагментом (`policy=SNIP`).
     * Второй признак важен для аккаунтов без подписки: SoundCloud отдаёт
     * Go+-треки как 30-секундные превью и помечает их SNIP.
     */
    fun isGoPlusTrack(item: JSONObject): Boolean = Mapper.isGoPlus(item)

    fun getCachedClientId(): String? = cachedClientId

    // ======================== INTERNAL HELPERS ========================

    private object Mapper {
        /**
         * Обложка первого трека из вложенного массива `tracks` — фолбэк для
         * плейлистов и альбомов без собственной обложки (как в SoundCloud).
         */
        fun firstTrackArtwork(item: JSONObject): String {
            val tracks = item.optJSONArray("tracks") ?: return ""
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                val art = SoundCloudArtwork.toOriginal(track.optString("artwork_url", ""))
                    .ifBlank {
                        SoundCloudArtwork.toOriginal(track.optString("calculated_artwork_url", ""))
                    }
                if (art.isNotBlank()) return art
            }
            return ""
        }

        fun isGoPlus(item: JSONObject): Boolean {
            val model = item.optString("monetization_model", "")
            if (model.equals("SUB_HIGH_TIER", ignoreCase = true)) return true
            val policy = item.optString("policy", "")
            return policy.equals("SNIP", ignoreCase = true)
        }

        fun parseTrack(item: JSONObject, includeStats: Boolean = true): TrackInfo? {
            return try {
                val permalink = item.optString("permalink_url", "")
                if (permalink.isEmpty()) return null
                val userObj = item.optJSONObject("user")
                // optString() на JSON null возвращает строку "null" — чистим её,
                // иначе она считается «обложкой» и ломает все фолбэки.
                val artRaw = SoundCloudArtwork.toOriginal(item.optString("artwork_url", ""))
                val avatarRaw = SoundCloudArtwork.toOriginal(
                    userObj?.optString("avatar_url", "") ?: ""
                )
                val rawImg = if (artRaw.isNotBlank()) artRaw else avatarRaw

                val albumObj = item.optJSONObject("album")
                    ?: item.optJSONObject("publisher_metadata")
                val albumTitle = albumObj?.optString("album_title", "")?.takeIf { it.isNotBlank() }
                    ?: albumObj?.optString("title", "")?.takeIf { it.isNotBlank() }
                    ?: ""
                val albumPermalink = albumObj?.optString("permalink_url", "")?.takeIf { it.isNotBlank() }
                    ?: ""
                TrackInfo(
                    originalUrl = permalink,
                    streamUrl = "",
                    title = item.optString("title", "Без названия"),
                    artist = parseArtistName(item, userObj),
                    artworkUrl = rawImg,
                    durationMs = item.optLong("duration", 0L),
                    likesCount = if (includeStats) item.optLong("likes_count", item.optLong("favoritings_count", 0L)) else 0L,
                    playbackCount = if (includeStats) item.optLong("playback_count", 0L) else 0L,
                    commentCount = if (includeStats) item.optLong("comment_count", 0L) else 0L,
                    genre = item.optString("genre", ""),
                    description = item.optString("description", ""),
                    albumTitle = albumTitle,
                    albumPermalinkUrl = albumPermalink,
                    id = item.optLong("id", 0L),
                    isLiked = item.optBoolean("user_favorite", false),
                    artistAvatarUrl = avatarRaw,
                    isGoPlus = isGoPlus(item)
                )
            } catch (e: Exception) {
                null
            }
        }

        fun parseUser(item: JSONObject): UserInfo? {
            return try {
                val permalink = item.optString("permalink_url", "")
                if (permalink.isEmpty()) return null
                var banner = item.optString("visual_url", "")
                if (banner.isBlank()) {
                    val visuals = item.optJSONObject("visuals")?.optJSONArray("visuals")
                    if (visuals != null && visuals.length() > 0) {
                        banner = visuals.optJSONObject(0)?.optString("visual_url", "") ?: ""
                    }
                }
                UserInfo(
                    permalinkUrl = permalink,
                    username = item.optString("username", ""),
                    avatarUrl = SoundCloudArtwork.toOriginal(item.optString("avatar_url", "")),
                    followersCount = item.optInt("followers_count", 0),
                    trackCount = item.optInt("track_count", 0),
                    description = item.optString("description", ""),
                    bannerUrl = SoundCloudArtwork.toOriginal(banner),
                    country = item.optString("country", "")
                        .ifBlank { item.optString("country_code", "") },
                    city = item.optString("city", ""),
                    id = item.optLong("id", 0L),
                    countryCode = item.optString("country_code", ""),
                    firstName = item.optString("first_name", ""),
                    lastName = item.optString("last_name", "")
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * SoundCloud отдаёт альбомы как плейлисты с is_album = true
         * (или set_type = album/single), а не как отдельный kind = album.
         * Без этой проверки поиск альбомов всегда оставался пустым.
         */
        fun isAlbumItem(item: JSONObject): Boolean {
            if (item.optString("kind") == "album") return true
            if (item.optBoolean("is_album", false)) return true
            return item.optString("set_type") in setOf("album", "single")
        }

        fun parseAlbum(item: JSONObject, defaultUser: String = ""): AlbumInfo? {
            return try {
                val permalink = item.optString("permalink_url", "")
                if (permalink.isEmpty()) return null
                val userObj = item.optJSONObject("user")
                val artist = userObj?.optString("username", "") ?: defaultUser
                AlbumInfo(
                    id = item.optLong("id", 0L),
                    permalinkUrl = permalink,
                    title = item.optString("title", ""),
                    artist = artist,
                    // Своя обложка → обложка первого трека → аватар автора.
                    artworkUrl = SoundCloudArtwork.toOriginal(item.optString("artwork_url", ""))
                        .ifBlank {
                            SoundCloudArtwork.toOriginal(
                                item.optString("calculated_artwork_url", "")
                            )
                        }
                        .ifBlank { firstTrackArtwork(item) }
                        .ifBlank {
                            SoundCloudArtwork.toOriginal(
                                userObj?.optString("avatar_url", "") ?: ""
                            )
                        },
                    trackCount = item.optInt("track_count", 0)
                )
            } catch (e: Exception) { null }
        }

        fun parsePlaylist(item: JSONObject, defaultUser: String = ""): PlaylistInfo? {
            return try {
                val permalink = item.optString("permalink_url", "")
                val urn = item.optString("urn", "")
                // Системные плейлисты (миксы, станции) не имеют permalink_url —
                // permalink_url — их идентифицируем по urn.
                if (permalink.isEmpty() && !urn.startsWith("soundcloud:system-playlists:")) return null
                val userObj = item.optJSONObject("user")
                val username = userObj?.optString("username", "") ?: defaultUser
                PlaylistInfo(
                    id = item.optLong("id", 0L),
                    permalinkUrl = permalink,
                    title = item.optString("title", ""),
                    user = username,
                    artworkUrl = SoundCloudArtwork.toOriginal(item.optString("artwork_url", ""))
                        .ifBlank {
                            SoundCloudArtwork.toOriginal(
                                item.optString("calculated_artwork_url", "")
                            )
                        }
                        .ifBlank { firstTrackArtwork(item) },
                    trackCount = item.optInt("track_count", 0),
                    description = item.optString("description", ""),
                    isLiked = item.optBoolean("user_favorite", false),
                    genre = item.optString("genre", ""),
                    tagList = item.optString("tag_list", ""),
                    isPublic = item.optString("sharing", "private") == "public",
                    durationMs = item.optLong("duration", 0L),
                    createdAtMs = Formatter.parseSoundCloudDate(
                        item.optString("created_at", "")
                    )?.time ?: 0L,
                    urn = item.optString("urn", "")
                )
            } catch (e: Exception) { null }
        }

        fun parseArtistName(item: JSONObject, userObj: JSONObject?): String {
            val pubArtist = item.optJSONObject("publisher_metadata")?.optString("artist", "")?.trim() ?: ""
            if (pubArtist.isNotBlank()) return pubArtist

            val title = item.optString("title", "")
            if (title.contains(" - ")) {
                val parts = title.split(" - ")
                if (parts[0].trim().isNotBlank()) {
                    return parts[0].trim()
                }
            }

            val fullName = userObj?.optString("full_name", "")?.trim() ?: ""
            if (fullName.isNotBlank()) return fullName

            val username = userObj?.optString("username", "")?.trim() ?: ""
            if (username.isNotBlank()) return username

            return "SoundCloud Artist"
        }
    }

    private object Formatter {
        fun formatCount(count: Long): String {
            return when {
                count < 1_000L -> count.toString()
                count < 10_000L -> {
                    val value = count / 1000.0
                    String.format("%.1fK", value).replace(".0", "")
                }
                count < 1_000_000L -> "${count / 1000}K"
                count < 10_000_000L -> {
                    val value = count / 1_000_000.0
                    String.format("%.1fM", value).replace(".0", "")
                }
                count < 1_000_000_000L -> "${count / 1_000_000}M"
                else -> {
                    val value = count / 1_000_000_000.0
                    String.format("%.1fB", value).replace(".0", "")
                }
            }
        }

        fun parseSoundCloudDate(value: String): java.util.Date? {
            if (value.isBlank()) return null
            return try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(value)
            } catch (_: Exception) {
                try {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .parse(value)
                } catch (_: Exception) { null }
            }
        }
    }

    // ---------- Retry & Logging ----------
    private suspend fun <T> retryOnFailure(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        block: suspend () -> T
    ): T {
        var attempts = 0
        var delay = initialDelayMs
        while (attempts < maxAttempts) {
            try {
                return block()
            } catch (e: Exception) {
                attempts++
                if (attempts >= maxAttempts) throw e
                delay(delay)
                delay *= 2
            }
        }
        throw IllegalStateException("Max retries exceeded")
    }

    private class SoundCloudRateLimitException : Exception("HTTP 429")

    /**
     * Единая точка для API-запросов, которые идут пачками (resolve трека,
     * получение stream URL). Держит минимальный интервал между запросами
     * и пережидает общий cooldown после 429.
     */
    private suspend fun executeApiRequest(
        request: Request,
        maxAttempts: Int = 4
    ): Response {
        var attempts = 0
        var lastException: Exception? = null

        while (attempts < maxAttempts) {
            waitForRateLimitCooldown()
            throttleApiRequest()

            try {
                val response = client.newCall(request).execute()
                if (response.code != 429) return response

                val retryAfterMs = response.header("Retry-After")
                    ?.toLongOrNull()
                    ?.times(1_000L)
                response.close()

                val cooldown = (retryAfterMs ?: 60_000L).coerceAtLeast(15_000L)
                apiGateMutex.withLock {
                    rateLimitUntilMs = maxOf(
                        rateLimitUntilMs,
                        System.currentTimeMillis() + cooldown
                    )
                }
                Log.w(
                    "ZYRO_PLAYER",
                    "SoundCloud API 429, cooldown ${cooldown}ms"
                )
                attempts++
            } catch (e: Exception) {
                lastException = e
                attempts++
                if (attempts < maxAttempts) {
                    delay(500L * attempts * 2)
                }
            }
        }

        throw lastException ?: SoundCloudRateLimitException()
    }

    private suspend fun waitForRateLimitCooldown() {
        while (true) {
            val waitMs = apiGateMutex.withLock {
                (rateLimitUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
            }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

    private suspend fun throttleApiRequest() {
        apiGateMutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = (nextApiRequestAtMs - now).coerceAtLeast(0L)
            if (waitMs > 0L) delay(waitMs)
            nextApiRequestAtMs = System.currentTimeMillis() + apiRequestIntervalMs
        }
    }
    private val clientIdMutex = Mutex()
    // ---------- Улучшенное получение client_id ----------
// ---------- Улучшенное получение client_id с повторными попытками ----------
    suspend fun fetchClientId(): String? = withContext(Dispatchers.IO) {
        clientIdMutex.withLock {
            val now = System.currentTimeMillis()

            // Авторизованы — используем официальный client_id (он парный к OAuth-токену).
            // Это важно для получения ссылки на поток: с OAuth-токеном нужен именно он.
            val effectiveToken = authTokenProvider?.invoke() ?: authToken
            if (!effectiveToken.isNullOrBlank()) {
                cachedClientId = ScConfig.OFFICIAL_CLIENT_ID
                clientIdExpiryTime = now + 24 * 60 * 60 * 1000L
                return@withLock ScConfig.OFFICIAL_CLIENT_ID
            }

            // client_id, подсмотренный в реальной сессии SoundCloud (SessionManager)
            SessionManager.getCapturedClientId()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    cachedClientId = it
                    clientIdExpiryTime = now + 24 * 60 * 60 * 1000L
                    return@withLock it
                }

            // Возвращаем актуальный закэшированный client_id
            cachedClientId
                ?.takeIf { it.isNotBlank() && now < clientIdExpiryTime }
                ?.let { return@withLock it }

            // Пытаемся получить новый client_id через scraping с повторами
            try {
                val result = retryOnFailure(
                    maxAttempts = 5,
                    initialDelayMs = 1000
                ) {
                    performClientIdScraping()
                }

                if (!result.isNullOrBlank()) {
                    cachedClientId = result
                    clientIdExpiryTime =
                        System.currentTimeMillis() + 24 * 60 * 60 * 1000L

                    Log.d("ZYRO_PLAYER", "Successfully fetched new client_id")
                    return@withLock result
                }
            } catch (e: Exception) {
                Log.e(
                    "ZYRO_PLAYER",
                    "fetchClientId failed after retries",
                    e
                )
            }

            // Используем fallback-ключи
            val fallbackKey = clientIdFallbacks.firstOrNull {
                it.isNotBlank()
            }

            if (fallbackKey != null) {
                cachedClientId = fallbackKey
                clientIdExpiryTime =
                    System.currentTimeMillis() + 12 * 60 * 60 * 1000L

                Log.d(
                    "ZYRO_PLAYER",
                    "Using fallback client_id"
                )

                return@withLock fallbackKey
            }

            null
        }
    }
    private suspend fun performClientIdScraping(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://soundcloud.com").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val html = response.body?.string() ?: return@withContext null

            val patterns = listOf(
                Pattern.compile("client_id:\"([a-zA-Z0-9]{32})\""),
                Pattern.compile("client_id=([a-zA-Z0-9]{32})"),
                Pattern.compile("clientId:\"([a-zA-Z0-9]{32})\""),
                Pattern.compile("CLIENT_ID:\"([a-zA-Z0-9]{32})\"")
            )

            // Ищем в HTML
            for (pattern in patterns) {
                val matcher = pattern.matcher(html)
                if (matcher.find()) {
                    return@withContext matcher.group(1)
                }
            }

            // Ищем в подгружаемых скриптах
            val scriptMatcher = Pattern.compile("src=\"(https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js)\"").matcher(html)
            val scriptUrls = mutableListOf<String>()
            while (scriptMatcher.find()) {
                scriptMatcher.group(1)?.let { scriptUrls.add(it) }
            }

            for (scriptUrl in scriptUrls.reversed()) {
                val scriptRequest = Request.Builder().url(scriptUrl).build()
                try {
                    val scriptResponse = client.newCall(scriptRequest).execute()
                    if (scriptResponse.isSuccessful) {
                        val scriptContent = scriptResponse.body?.string() ?: ""
                        for (pattern in patterns) {
                            val matcher = pattern.matcher(scriptContent)
                            if (matcher.find()) {
                                return@withContext matcher.group(1)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // игнорируем ошибки отдельных скриптов
                }
            }
            null
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "performClientIdScraping error", e)
            null
        }
    }
    // ---------- Enhance helpers ----------
    private suspend fun enhanceTrack(track: TrackInfo): TrackInfo {
        return track.copy(
            artworkUrl = getBestArtworkUrl(track.artworkUrl),
            artistAvatarUrl = getBestArtworkUrl(track.artistAvatarUrl)
        )
    }

    private fun enhanceUser(user: UserInfo): UserInfo {
        return user.copy(avatarUrl = formatAvatar(user.avatarUrl, 300))
    }

    private suspend fun enhanceAlbum(album: AlbumInfo): AlbumInfo {
        return album.copy(artworkUrl = getBestArtworkUrl(album.artworkUrl))
    }

    private suspend fun enhancePlaylist(playlist: PlaylistInfo): PlaylistInfo {
        return playlist.copy(artworkUrl = getBestArtworkUrl(playlist.artworkUrl))
    }

    // ======================== PUBLIC API ========================

    suspend fun getTrackInfo(trackUrl: String): Result<TrackInfo> = withContext(Dispatchers.IO) {
        try {
            if (!isValidSoundCloudUrl(trackUrl)) {
                return@withContext Result.Error("Invalid SoundCloud URL")
            }
            val clientId = fetchClientId() ?: return@withContext Result.Error("Could not obtain client ID")
            val resolveUrl = "https://api-v2.soundcloud.com/resolve?url=${URLEncoder.encode(trackUrl, "UTF-8")}&client_id=$clientId"

            val response = executeApiRequest(
                Request.Builder().url(resolveUrl).build()
            )
            if (!response.isSuccessful) {
                return@withContext Result.Error("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return@withContext Result.Error("Empty response")
            val json = JSONObject(body)

            val track = Mapper.parseTrack(json, includeStats = true)
                ?: return@withContext Result.Error("Failed to parse track")

            // ---- Получение streamUrl через transcodings (рабочий способ) ----
            var streamUrl = ""
            val media = json.optJSONObject("media")
            if (media != null) {
                val transcodings = media.optJSONArray("transcodings")
                if (transcodings != null) {
                    val progressive = (0 until transcodings.length())
                        .mapNotNull { transcodings.optJSONObject(it) }
                        .firstOrNull {
                            it.optJSONObject("format")?.optString("protocol") == "progressive"
                        }
                    val chosen = progressive ?: (0 until transcodings.length())
                        .mapNotNull { transcodings.optJSONObject(it) }
                        .firstOrNull()
                    if (chosen != null) {
                        val streamApiUrl = chosen.optString("url", "")
                        if (streamApiUrl.isNotEmpty()) {
                            val streamResponse = executeApiRequest(
                                Request.Builder()
                                    .url("$streamApiUrl?client_id=$clientId")
                                    .build()
                            )
                            if (streamResponse.isSuccessful) {
                                val streamJson = JSONObject(streamResponse.body?.string() ?: "")
                                streamUrl = streamJson.optString("url", "")
                            }
                        }
                    }
                }
            }
            // ---- Конец получения streamUrl ----

            val enhanced = enhanceTrack(track).copy(streamUrl = streamUrl)
            Result.Success(enhanced)
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "getTrackInfo error", e)
            Result.Error(
                if (e is SoundCloudRateLimitException) "HTTP 429"
                else "Failed to fetch track info",
                e
            )
        }
    }
    suspend fun resolveTrackId(trackUrl: String): Result<Long> = withContext(Dispatchers.IO) {
        if (!isValidSoundCloudUrl(trackUrl)) {
            return@withContext Result.Error("Invalid SoundCloud URL")
        }
        try {
            val clientId = fetchClientId() ?: return@withContext Result.Error("No client ID")
            val resolveUrl = "https://api-v2.soundcloud.com/resolve?url=${URLEncoder.encode(trackUrl, "UTF-8")}&client_id=$clientId"
            val response = executeApiRequest(
                Request.Builder().url(resolveUrl).build()
            )
            if (!response.isSuccessful) {
                return@withContext Result.Error("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return@withContext Result.Error("Empty response")
            val id = JSONObject(body).optLong("id", 0L)
            if (id == 0L) Result.Error("ID not found")
            else Result.Success(id)
        } catch (e: Exception) {
            Result.Error("Resolve error", e)
        }
    }

    suspend fun fetchTrackComments(
        trackId: Long,
        offset: Int = 0,
        limit: Int = 10
    ): Result<CommentsResult> = withContext(Dispatchers.IO) {
        try {
            val clientId = fetchClientId() ?: return@withContext Result.Error("No client ID")
            val url = "https://api-v2.soundcloud.com/tracks/$trackId/comments" +
                    "?client_id=$clientId&limit=$limit&offset=$offset&threaded=0" +
                    "&linked_partitioning=1"

            val response = retryOnFailure {
                client.newCall(Request.Builder().url(url).build()).execute()
            }
            if (!response.isSuccessful) {
                return@withContext Result.Error("HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return@withContext Result.Error("Empty response")
            val json = JSONObject(body)
            val collection = json.optJSONArray("collection") ?: JSONArray()
            val comments = mutableListOf<CommentInfo>()

            for (i in 0 until collection.length()) {
                val item = collection.optJSONObject(i) ?: continue
                val id = item.optLong("id", 0L)
                if (id == 0L) continue
                val commentBody = item.optString("body", "").trim()
                if (commentBody.isBlank()) continue
                val user = item.optJSONObject("user")
                val username = user?.optString("username", "Unknown")?.takeIf { it.isNotBlank() } ?: "Unknown"
                val avatarUrl = user?.optString("avatar_url", "") ?: ""
                val userPermalink = user?.optString("permalink_url", "") ?: ""
                val createdAt = Formatter.parseSoundCloudDate(item.optString("created_at", ""))
                val timestampMs = item.optLong("timestamp", 0L).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

                comments.add(CommentInfo(
                    id = id,
                    body = commentBody,
                    username = username,
                    userAvatar = avatarUrl,
                    userPermalink = userPermalink,
                    timestampMs = timestampMs,
                    createdAt = createdAt
                ))
            }

            val nextHref = json.optString("next_href", "").takeIf { it.isNotBlank() }
            // Даже если сервер не прислал next_href, полная страница означает,
            // что дальше могут быть ещё комментарии — не обрезаем список на 20.
            val hasMore = nextHref != null || collection.length() >= limit
            Result.Success(CommentsResult(comments, nextHref, hasMore))
        } catch (e: Exception) {
            Result.Error("Comments fetch error", e)
        }
    }

    suspend fun search(
        query: String,
        offset: Int = 0,
        limit: Int = 20
    ): Result<SearchResults> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.Success(SearchResults(emptyList(), emptyList(), emptyList(), emptyList(), false))
        try {
            val clientId = fetchClientId() ?: return@withContext Result.Error("No client ID")
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api-v2.soundcloud.com/search?q=$encoded&offset=$offset&limit=$limit&client_id=$clientId"

            val response = retryOnFailure {
                client.newCall(Request.Builder().url(url).build()).execute()
            }
            if (!response.isSuccessful) return@withContext Result.Error("HTTP ${response.code}")
            val body = response.body?.string() ?: return@withContext Result.Error("Empty response")
            val json = JSONObject(body)
            val collection = json.optJSONArray("collection") ?: JSONArray()

            val tracks = mutableListOf<TrackInfo>()
            val users = mutableListOf<UserInfo>()
            val albums = mutableListOf<AlbumInfo>()
            val playlists = mutableListOf<PlaylistInfo>()

            for (i in 0 until collection.length()) {
                val item = collection.optJSONObject(i) ?: continue
                when (item.optString("kind")) {
                    "track" -> {
                        Mapper.parseTrack(item, includeStats = true)?.let { tracks.add(it) }
                    }
                    "user" -> {
                        Mapper.parseUser(item)?.let { users.add(it) }
                    }
                    "album" -> {
                        Mapper.parseAlbum(item)?.let { albums.add(it) }
                    }
                    "playlist" -> {
                        if (Mapper.isAlbumItem(item)) {
                            Mapper.parseAlbum(item)?.let { albums.add(it) }
                        } else {
                            Mapper.parsePlaylist(item)?.let { playlists.add(it) }
                        }
                    }
                }
            }

            val enhancedTracks = tracks.map { enhanceTrack(it) }
            val enhancedUsers = users.map { enhanceUser(it) }
            val enhancedAlbums = albums.map { enhanceAlbum(it) }
            val enhancedPlaylists = playlists.map { enhancePlaylist(it) }

            val hasMore = collection.length() >= limit
            Result.Success(SearchResults(enhancedTracks, enhancedUsers, enhancedAlbums, enhancedPlaylists, hasMore))
        } catch (e: Exception) {
            Result.Error("Search error", e)
        }
    }

    suspend fun getTrackLikes(trackUrl: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val clientId = fetchClientId() ?: return@withContext Result.Error("No client ID")
            val resolveUrl = "https://api-v2.soundcloud.com/resolve?url=${URLEncoder.encode(trackUrl, "UTF-8")}&client_id=$clientId"
            val response = retryOnFailure {
                client.newCall(Request.Builder().url(resolveUrl).build()).execute()
            }
            if (!response.isSuccessful) return@withContext Result.Error("HTTP ${response.code}")
            val body = response.body?.string() ?: return@withContext Result.Error("Empty response")
            val likes = JSONObject(body).optLong("likes_count", 0L)
            Result.Success(likes)
        } catch (e: Exception) {
            Result.Error("Failed to get likes", e)
        }
    }

    suspend fun getTrendingTracks(genre: String = "pop", limit: Int = 20): Result<List<TrackInfo>> {
        val query = if (genre.lowercase() == "all" || genre.isBlank()) "hits 2024" else genre
        val result = search(query, limit = limit)
        return when (result) {
            is Result.Success -> Result.Success(result.data.tracks)
            is Result.Error -> Result.Error(result.message, result.cause)
            Result.Loading -> Result.Loading
        }
    }

    suspend fun getAlbumTracks(permalinkUrl: String): Result<List<TrackInfo>> = withContext(Dispatchers.IO) {
        try {
            val clientId = fetchClientId() ?: return@withContext Result.Error("No client ID")
            val resolveUrl = "https://api-v2.soundcloud.com/resolve?url=${URLEncoder.encode(permalinkUrl, "UTF-8")}&client_id=$clientId"
            val response = retryOnFailure {
                client.newCall(Request.Builder().url(resolveUrl).build()).execute()
            }
            if (!response.isSuccessful) return@withContext Result.Error("HTTP ${response.code}")
            val body = response.body?.string() ?: return@withContext Result.Error("Empty response")
            val json = JSONObject(body)
            val tracksArr = json.optJSONArray("tracks") ?: JSONArray()

            val trackMap = ConcurrentHashMap<Long, TrackInfo>()
            val trackOrder = mutableListOf<Long>()
            val missingIds = mutableListOf<Long>()

            for (i in 0 until tracksArr.length()) {
                val item = tracksArr.optJSONObject(i) ?: continue
                val id = item.optLong("id", 0L)
                if (id == 0L) continue
                trackOrder.add(id)

                val track = Mapper.parseTrack(item, includeStats = true)
                if (track != null) {
                    trackMap[id] = track
                } else {
                    missingIds.add(id)
                }
            }

            if (missingIds.isNotEmpty()) {
                missingIds.chunked(50).forEach { chunk ->
                    val idsParam = chunk.joinToString(",")
                    val tracksUrl = "https://api-v2.soundcloud.com/tracks?ids=$idsParam&client_id=$clientId"
                    try {
                        val tResp = retryOnFailure {
                            client.newCall(Request.Builder().url(tracksUrl).build()).execute()
                        }
                        if (tResp.isSuccessful) {
                            val tBody = tResp.body?.string() ?: ""
                            val tArr = JSONArray(tBody)
                            for (j in 0 until tArr.length()) {
                                val item = tArr.optJSONObject(j) ?: continue
                                val id = item.optLong("id", 0L)
                                if (id != 0L) {
                                    Mapper.parseTrack(item, includeStats = true)?.let {
                                        trackMap[id] = it
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ZYRO_PLAYER", "Failed to fetch missing tracks batch", e)
                    }
                }
            }

            val resultTracks = trackOrder.mapNotNull { trackMap[it] }
            val enhanced = resultTracks.map { enhanceTrack(it) }
            Result.Success(enhanced)
        } catch (e: Exception) {
            Result.Error("Album tracks error", e)
        }
    }

    suspend fun getPlaylistTracks(permalinkUrl: String): Result<List<TrackInfo>> = getAlbumTracks(permalinkUrl)

    /**
     * Официальный фид главной SoundCloud ("mixed-selections"):
     * «Миксы для …», «Больше из того, что вам нравится», «В тренде по жанрам»,
     * «Артисты, за которыми стоит следить» и другие блоки официального приложения.
     */
    suspend fun getHomeSelections(limit: Int = 12): Result<List<ScHomeSelection>> = withContext(Dispatchers.IO) {
        try {
            val clientId = fetchClientId() ?: return@withContext Result.Error("No client ID")
            val url = "https://api-v2.soundcloud.com/mixed-selections?limit=$limit&linked_partitioning=1&client_id=$clientId"
            val response = retryOnFailure {
                client.newCall(Request.Builder().url(url).build()).execute()
            }
            if (!response.isSuccessful) return@withContext Result.Error("HTTP ${response.code}")
            val body = response.body?.string() ?: return@withContext Result.Error("Empty response")
            val collection = JSONObject(body).optJSONArray("collection") ?: JSONArray()

            val selections = mutableListOf<ScHomeSelection>()
            for (i in 0 until collection.length()) {
                val sel = collection.optJSONObject(i) ?: continue
                val urn = sel.optString("urn", "")
                val id = sel.optString("id", "")
                val title = sel.optString("title", "")
                // «Недавно прослушанное» рендерим сами из локальной истории.
                if (urn.contains("recently-played", ignoreCase = true) ||
                    id.contains("recently-played", ignoreCase = true) ||
                    title.equals("Recently Played", ignoreCase = true)
                ) continue

                val items = sel.optJSONObject("items")?.optJSONArray("collection") ?: JSONArray()
                val tracks = mutableListOf<TrackInfo>()
                val playlists = mutableListOf<PlaylistInfo>()
                val users = mutableListOf<UserInfo>()

                for (j in 0 until items.length()) {
                    val wrapper = items.optJSONObject(j) ?: continue
                    val item = wrapper.optJSONObject("item") ?: wrapper
                    when (item.optString("kind")) {
                        "track" -> Mapper.parseTrack(item, includeStats = true)?.let { tracks.add(it) }
                        "playlist", "system-playlist" -> Mapper.parsePlaylist(item)?.let { playlists.add(it) }
                        "user" -> Mapper.parseUser(item)?.let { users.add(it) }
                    }
                }

                if (tracks.isEmpty() && playlists.isEmpty() && users.isEmpty()) continue
                selections.add(
                    ScHomeSelection(
                        id = id.ifBlank { urn },
                        urn = urn,
                        title = title,
                        description = sel.optString("description", ""),
                        tracks = tracks.map { enhanceTrack(it) },
                        playlists = playlists.map { enhancePlaylist(it) },
                        users = users.map { enhanceUser(it) }
                    )
                )
            }
            Result.Success(selections)
        } catch (e: Exception) {
            Result.Error("Mixed selections error", e)
        }
    }

    /**
     * Системный плейлист SoundCloud по urn (микс «Mixed for …», станция).
     * Обычный resolve по permalink для них не работает.
     */
    suspend fun getSystemPlaylist(urn: String): Result<SystemPlaylistDetail> = withContext(Dispatchers.IO) {
        try {
            if (!urn.startsWith("soundcloud:system-playlists:")) {
                return@withContext Result.Error("Not a system playlist")
            }
            val clientId = fetchClientId() ?: return@withContext Result.Error("No client ID")
            val url = "https://api-v2.soundcloud.com/system-playlists/$urn?client_id=$clientId"
            val response = retryOnFailure {
                client.newCall(Request.Builder().url(url).build()).execute()
            }
            if (!response.isSuccessful) return@withContext Result.Error("HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: return@withContext Result.Error("Empty response"))

            val tracks = parsePlaylistTracksJson(json)
            val playlist = PlaylistInfo(
                id = json.optLong("id", 0L),
                permalinkUrl = json.optString("permalink_url", ""),
                title = json.optString("title", "").ifBlank { "Микс" },
                user = json.optJSONObject("user")?.optString("username", "") ?: "",
                artworkUrl = SoundCloudArtwork.toOriginal(json.optString("artwork_url", ""))
                    .ifBlank {
                        SoundCloudArtwork.toOriginal(
                            json.optString("calculated_artwork_url", "")
                        )
                    }
                    .ifBlank { getFirstTrackArtwork(json) }
                    .ifBlank {
                        tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl ?: ""
                    },
                trackCount = json.optInt("track_count", 0),
                description = json.optString("description", ""),
                durationMs = json.optLong("duration", 0L),
                createdAtMs = Formatter.parseSoundCloudDate(json.optString("created_at", ""))?.time ?: 0L,
                urn = urn
            )

            Result.Success(SystemPlaylistDetail(playlist, tracks))
        } catch (e: Exception) {
            Result.Error("System playlist error", e)
        }
    }

    /**
     * Стрим авторизованного пользователя («Новое от артистов, на которых
     * вы подписаны»): треки и репосты тех, на кого оформлена подписка.
     */
    suspend fun getFollowedStreamTracks(limit: Int = 25): Result<List<TrackInfo>> = withContext(Dispatchers.IO) {
        try {
            val clientId = fetchClientId() ?: return@withContext Result.Error("No client ID")
            val url = "https://api-v2.soundcloud.com/stream?limit=$limit&linked_partitioning=1&client_id=$clientId"
            val response = retryOnFailure {
                client.newCall(Request.Builder().url(url).build()).execute()
            }
            if (!response.isSuccessful) return@withContext Result.Error("HTTP ${response.code}")
            val body = response.body?.string() ?: return@withContext Result.Error("Empty response")
            val collection = JSONObject(body).optJSONArray("collection") ?: JSONArray()

            val seen = mutableSetOf<Long>()
            val tracks = mutableListOf<TrackInfo>()
            for (i in 0 until collection.length()) {
                val item = collection.optJSONObject(i) ?: continue
                val type = item.optString("type", "track")
                if (!type.startsWith("track", ignoreCase = true)) continue
                val trackJson = item.optJSONObject("track") ?: continue
                val parsed = Mapper.parseTrack(trackJson, includeStats = true) ?: continue
                if (parsed.id != 0L && !seen.add(parsed.id)) continue
                tracks.add(parsed)
            }
            Result.Success(tracks.map { enhanceTrack(it) })
        } catch (e: Exception) {
            Result.Error("Stream error", e)
        }
    }

    /**
     * Официальные чарты SoundCloud (kind: top/trending) — используются как
     * гостевой fallback, когда персональный фид недоступен.
     */
    suspend fun getChartTracks(
        kind: String = "top",
        genre: String = "soundcloud:genres:all-music",
        limit: Int = 20
    ): Result<List<TrackInfo>> = withContext(Dispatchers.IO) {
        try {
            val clientId = fetchClientId() ?: return@withContext Result.Error("No client ID")
            val encodedGenre = URLEncoder.encode(genre, "UTF-8")
            val url = "https://api-v2.soundcloud.com/charts?kind=$kind&genre=$encodedGenre&limit=$limit&client_id=$clientId"
            val response = retryOnFailure {
                client.newCall(Request.Builder().url(url).build()).execute()
            }
            if (!response.isSuccessful) return@withContext Result.Error("HTTP ${response.code}")
            val body = response.body?.string() ?: return@withContext Result.Error("Empty response")
            val collection = JSONObject(body).optJSONArray("collection") ?: JSONArray()

            val tracks = mutableListOf<TrackInfo>()
            for (i in 0 until collection.length()) {
                val item = collection.optJSONObject(i) ?: continue
                val trackJson = item.optJSONObject("track") ?: continue
                Mapper.parseTrack(trackJson, includeStats = true)?.let { tracks.add(it) }
            }
            Result.Success(tracks.map { enhanceTrack(it) })
        } catch (e: Exception) {
            Result.Error("Charts error", e)
        }
    }

    /**
     * Разбор JSON плейлиста в список треков: сначала inline-объекты, а для
     * «пустышек» (только id, как в системных плейлистах) — батч-дозагрузка
     * через /tracks?ids=. Тот же подход, что и в getAlbumTracks().
     */
    private suspend fun parsePlaylistTracksJson(json: JSONObject): List<TrackInfo> {
        val tracksArr = json.optJSONArray("tracks") ?: return emptyList()

        val trackMap = ConcurrentHashMap<Long, TrackInfo>()
        val trackOrder = mutableListOf<Long>()
        val missingIds = mutableListOf<Long>()

        for (i in 0 until tracksArr.length()) {
            val item = tracksArr.optJSONObject(i) ?: continue
            val id = item.optLong("id", 0L)
            if (id == 0L) continue
            trackOrder.add(id)

            val track = Mapper.parseTrack(item, includeStats = true)
            if (track != null) {
                trackMap[id] = track
            } else {
                missingIds.add(id)
            }
        }

        if (missingIds.isNotEmpty()) {
            missingIds.chunked(50).forEach { chunk ->
                val idsParam = chunk.joinToString(",")
                val tracksUrl = "https://api-v2.soundcloud.com/tracks?ids=$idsParam&client_id=${fetchClientId()}"
                try {
                    val tResp = retryOnFailure {
                        client.newCall(Request.Builder().url(tracksUrl).build()).execute()
                    }
                    if (tResp.isSuccessful) {
                        val tArr = JSONArray(tResp.body?.string() ?: "")
                        for (j in 0 until tArr.length()) {
                            val item = tArr.optJSONObject(j) ?: continue
                            val id = item.optLong("id", 0L)
                            if (id != 0L) {
                                Mapper.parseTrack(item, includeStats = true)?.let { trackMap[id] = it }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ZYRO_PLAYER", "Failed to fetch missing tracks batch", e)
                }
            }
        }

        return trackOrder.mapNotNull { trackMap[it] }.map { enhanceTrack(it) }
    }

    suspend fun getUserInfo(userUrl: String): Result<UserInfo> = withContext(Dispatchers.IO) {
        try {
            if (!isValidSoundCloudUrl(userUrl)) return@withContext Result.Error("Invalid URL")
            val clientId = fetchClientId() ?: return@withContext Result.Error("No client ID")
            val resolveUrl = "https://api-v2.soundcloud.com/resolve?url=${URLEncoder.encode(userUrl, "UTF-8")}&client_id=$clientId"
            val response = retryOnFailure {
                client.newCall(Request.Builder().url(resolveUrl).build()).execute()
            }
            if (!response.isSuccessful) return@withContext Result.Error("HTTP ${response.code}")
            val body = response.body?.string() ?: return@withContext Result.Error("Empty response")
            val json = JSONObject(body)
            val user = Mapper.parseUser(json)
                ?: return@withContext Result.Error("Failed to parse user")
            val enhanced = enhanceUser(user)
            Result.Success(enhanced)
        } catch (e: Exception) {
            Result.Error("User info error", e)
        }
    }

    suspend fun getArtistFullData(
        userUrl: String,
        loadTopTracks: Boolean = true,
        loadTracks: Boolean = true,
        loadAlbums: Boolean = true,
        loadPlaylists: Boolean = true,
        loadReposts: Boolean = true,
        limit: Int = 50
    ): Result<ArtistFullData> = withContext(Dispatchers.IO) {
        try {
            val userResult = getUserInfo(userUrl)
            if (userResult !is Result.Success) {
                val errorMsg = when (userResult) {
                    is Result.Error -> userResult.message
                    else -> "Unknown error"
                }
                return@withContext Result.Error("Failed to get user: $errorMsg")
            }
            val user = userResult.data
            val userId = user.id
            val clientId = fetchClientId() ?: return@withContext Result.Error("No client ID")

            // «Лучшие треки» и «Все треки» — разные коллекции SoundCloud.
            // Раньше в оба поля клался один и тот же список /tracks (лимит 50),
            // поэтому счётчик «Все треки» и «Репосты» упирался в размер страницы.
            val allTracksLimit = maxOf(
                limit,
                200,
                user.trackCount.coerceAtMost(1_000)
            )

            val topTracksDeferred = async {
                if (loadTopTracks && userId != 0L) {
                    fetchUserTopTracks(userId, clientId, limit)
                } else emptyList()
            }

            val tracksDeferred = async {
                if (loadTracks && userId != 0L) {
                    fetchUserTracks(userId, clientId, allTracksLimit)
                } else emptyList()
            }

            val albumsDeferred = async {
                if (loadAlbums && userId != 0L) fetchUserAlbums(userId, clientId) else emptyList()
            }

            val playlistsDeferred = async {
                if (loadPlaylists && userId != 0L) fetchUserPlaylists(userId, clientId) else emptyList()
            }

            val repostsDeferred = async {
                if (loadReposts && userId != 0L) fetchUserReposts(userId, clientId) else emptyList()
            }

            val topTracks = topTracksDeferred.await()
            val allTracks = tracksDeferred.await()
            val albums = albumsDeferred.await()
            val playlists = playlistsDeferred.await()
            val reposts = repostsDeferred.await()

            Result.Success(
                ArtistFullData(
                    user = user,
                    description = user.description,
                    topTracks = topTracks,
                    albums = albums,
                    tracks = allTracks,
                    playlists = playlists,
                    reposts = reposts
                )
            )
        } catch (e: Exception) {
            Result.Error("Artist full data error", e)
        }
    }

    suspend fun getUserTopTracks(userUrl: String, limit: Int = 50): Result<List<TrackInfo>> = withContext(Dispatchers.IO) {
        try {
            val clientId = fetchClientId() ?: return@withContext Result.Error("No client ID")
            val userId = resolveUserId(userUrl, clientId)
                ?: return@withContext Result.Error("User ID not found")
            Result.Success(fetchUserTopTracks(userId, clientId, limit))
        } catch (e: Exception) {
            Result.Error("Top tracks error", e)
        }
    }

    private suspend fun resolveUserId(userUrl: String, clientId: String): Long? {
        val resolveUrl = "https://api-v2.soundcloud.com/resolve?url=${URLEncoder.encode(userUrl, "UTF-8")}&client_id=$clientId"
        val resolveResponse = retryOnFailure {
            client.newCall(Request.Builder().url(resolveUrl).build()).execute()
        }
        if (!resolveResponse.isSuccessful) return null
        return JSONObject(resolveResponse.body?.string() ?: "").optLong("id", 0L).takeIf { it != 0L }
    }

    // ---------- Private helpers for artist data ----------
    private suspend fun fetchUserTopTracks(
        userId: Long,
        clientId: String,
        limit: Int
    ): List<TrackInfo> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api-v2.soundcloud.com/users/$userId/toptracks?limit=$limit&client_id=$clientId"
            val response = retryOnFailure {
                client.newCall(Request.Builder().url(url).build()).execute()
            }
            if (!response.isSuccessful) return@withContext emptyList()
            val collection = JSONObject(response.body?.string() ?: "")
                .optJSONArray("collection") ?: JSONArray()
            buildList {
                for (i in 0 until collection.length()) {
                    val item = collection.optJSONObject(i) ?: continue
                    Mapper.parseTrack(item, includeStats = true)?.let { add(it) }
                }
            }.map { enhanceTrack(it) }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "fetchUserTopTracks error", e)
            emptyList()
        }
    }

    private suspend fun fetchUserTracks(
        userId: Long,
        clientId: String,
        maxItems: Int
    ): List<TrackInfo> = withContext(Dispatchers.IO) {
        val tracks = LinkedHashMap<String, TrackInfo>()
        var nextUrl: String? =
            "https://api-v2.soundcloud.com/users/$userId/tracks?limit=200&linked_partitioning=1&client_id=$clientId"
        var pages = 0

        try {
            while (nextUrl != null && tracks.size < maxItems && pages < 20) {
                val response = retryOnFailure {
                    client.newCall(Request.Builder().url(nextUrl!!).build()).execute()
                }
                if (!response.isSuccessful) break
                val json = JSONObject(response.body?.string() ?: "")
                val collection = json.optJSONArray("collection") ?: break
                if (collection.length() == 0) break

                for (i in 0 until collection.length()) {
                    if (tracks.size >= maxItems) break
                    val item = collection.optJSONObject(i) ?: continue
                    val parsed = Mapper.parseTrack(item, includeStats = true) ?: continue
                    tracks[parsed.originalUrl] = parsed
                }

                nextUrl = nextCollectionUrl(json.optString("next_href", ""), clientId)
                pages++
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "fetchUserTracks error", e)
        }

        tracks.values.toList().map { enhanceTrack(it) }
    }

    private fun nextCollectionUrl(nextHref: String, clientId: String): String? {
        if (nextHref.isBlank() || nextHref == "null") return null
        val absolute = if (nextHref.startsWith("http")) {
            nextHref
        } else {
            "https://api-v2.soundcloud.com/${nextHref.trimStart('/')}"
        }
        return if (absolute.contains("client_id=")) {
            absolute
        } else {
            "$absolute${if (absolute.contains('?')) "&" else "?"}client_id=$clientId"
        }
    }

    private suspend fun fetchUserAlbums(userId: Long, clientId: String): List<AlbumInfo> = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://api-v2.soundcloud.com/users/$userId/albums_and_playlists?limit=50&client_id=$clientId",
            "https://api-v2.soundcloud.com/users/$userId/albums?limit=50&client_id=$clientId"
        )
        val albumsMap = ConcurrentHashMap<String, AlbumInfo>()
        for (endpoint in endpoints) {
            try {
                val response = retryOnFailure {
                    client.newCall(Request.Builder().url(endpoint).build()).execute()
                }
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val json = JSONObject(body)
                    val collection = json.optJSONArray("collection") ?: continue
                    for (i in 0 until collection.length()) {
                        val item = collection.optJSONObject(i) ?: continue
                        if (!Mapper.isAlbumItem(item)) continue
                        val album = Mapper.parseAlbum(item)
                        album?.let { albumsMap[it.permalinkUrl] = it }
                    }
                }
            } catch (e: Exception) {
                Log.e("ZYRO_PLAYER", "fetchUserAlbums error for $endpoint", e)
            }
        }
        albumsMap.values.toList().map { enhanceAlbum(it) }
    }

    private suspend fun fetchUserPlaylists(userId: Long, clientId: String): List<PlaylistInfo> = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://api-v2.soundcloud.com/users/$userId/playlists_without_albums?limit=50&client_id=$clientId",
            "https://api-v2.soundcloud.com/users/$userId/playlists?limit=50&client_id=$clientId"
        )
        val playlistsMap = ConcurrentHashMap<String, PlaylistInfo>()
        for (endpoint in endpoints) {
            try {
                val response = retryOnFailure {
                    client.newCall(Request.Builder().url(endpoint).build()).execute()
                }
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val json = JSONObject(body)
                    val collection = json.optJSONArray("collection") ?: continue
                    for (i in 0 until collection.length()) {
                        val item = collection.optJSONObject(i) ?: continue
                        // Альбомы SoundCloud тоже приходят как playlist — не дублируем их в плейлистах.
                        if (Mapper.isAlbumItem(item)) continue
                        val playlist = Mapper.parsePlaylist(item)
                        playlist?.let { playlistsMap[it.permalinkUrl] = it }
                    }
                }
            } catch (e: Exception) {
                Log.e("ZYRO_PLAYER", "fetchUserPlaylists error for $endpoint", e)
            }
        }
        playlistsMap.values.toList().map { enhancePlaylist(it) }
    }

    private suspend fun fetchUserReposts(userId: Long, clientId: String): List<TrackInfo> = withContext(Dispatchers.IO) {
        val reposts = LinkedHashMap<String, TrackInfo>()
        var nextUrl: String? =
            "https://api-v2.soundcloud.com/stream/users/$userId/reposts?limit=200&client_id=$clientId"
        var pages = 0

        try {
            while (nextUrl != null && reposts.size < 1_000 && pages < 20) {
                val response = retryOnFailure {
                    client.newCall(Request.Builder().url(nextUrl!!).build()).execute()
                }
                if (!response.isSuccessful) break
                val json = JSONObject(response.body?.string() ?: "")
                val collection = json.optJSONArray("collection") ?: break
                if (collection.length() == 0) break

                for (i in 0 until collection.length()) {
                    val obj = collection.optJSONObject(i) ?: continue
                    val item = obj.optJSONObject("track")
                        ?: obj.optJSONObject("item")
                        ?: obj.optJSONObject("target")
                        ?: obj
                    val permalink = item.optString("permalink_url", "")
                    if (permalink.isEmpty() || !permalink.contains("soundcloud.com/")) continue
                    Mapper.parseTrack(item, includeStats = true)?.let {
                        reposts[permalink] = it
                    }
                }

                nextUrl = nextCollectionUrl(json.optString("next_href", ""), clientId)
                pages++
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "fetchUserReposts error", e)
        }

        reposts.values.toList().map { enhanceTrack(it) }
    }

    // ---------- Utility: URL validation ----------
    private fun isValidSoundCloudUrl(url: String): Boolean {
        // Разрешаем m.soundcloud.com и короткие on.soundcloud.com: последние
        // приходят из «Поделиться» в приложении SoundCloud и резолвятся
        // через api-v2/resolve так же, как обычные ссылки.
        return Regex(
            "^https?://([a-z0-9-]+\\.)?soundcloud\\.com/.*$",
            RegexOption.IGNORE_CASE
        ).matches(url)
    }

    // ---------- Public formatting ----------
    fun formatCount(count: Long): String = Formatter.formatCount(count)

    // ---------- Flow ----------
    fun searchFlow(query: String, limit: Int = 20): Flow<SearchResults> = flow {
        var offset = 0
        var hasMore = true
        while (hasMore) {
            val result = search(query, offset, limit)
            when (result) {
                is Result.Success -> {
                    emit(result.data)
                    offset += limit
                    hasMore = result.data.hasMore
                }
                is Result.Error -> {
                    Log.e("ZYRO_PLAYER", "Search flow error: ${result.message}")
                    break
                }
                Result.Loading -> { /* ignore */ }
            }
        }
    }

    // ---------- Clear cache ----------
    fun clearCache() {
        httpCache.evictAll()
    }
}
