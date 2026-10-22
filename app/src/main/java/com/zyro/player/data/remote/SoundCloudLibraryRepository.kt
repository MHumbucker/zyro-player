package com.zyro.player.data.remote

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zyro.player.data.auth.ScConfig
import com.zyro.player.data.auth.SoundCloudAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ScLibraryData(
    val likedTracks: List<TrackInfo> = emptyList(),
    val playlists: List<PlaylistInfo> = emptyList(),
    val likedPlaylists: List<PlaylistInfo> = emptyList(),
    val followings: List<UserInfo> = emptyList(),
    val playHistory: List<TrackInfo> = emptyList(),
    val currentUser: UserInfo? = null
)

/** Дисковый кеш треков серверного плейлиста (по permalink URL). */
data class CachedPlaylistTracks(
    val tracks: List<TrackInfo>,
    val timestamp: Long
)

class SoundCloudLibraryRepository(
    private val client: OkHttpClient,
    private val authManager: SoundCloudAuthManager,
    private val baseRepo: SoundCloudRepository,
    private val prefs: SharedPreferences
) {

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()
    private val LIBRARY_CACHE_KEY = "cached_library_data"
    private val CACHE_TIMESTAMP_KEY = "cache_timestamp"
    private val CACHE_TTL_MS = 5 * 60 * 1000L
    private val PLAYLIST_TRACKS_CACHE_KEY = "cached_playlist_tracks"
    private val PLAYLIST_TRACKS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private var playlistTracksMemoryCache: Map<String, CachedPlaylistTracks>? = null

    /**
     * Полный JSON плейлиста нужен, чтобы добавить трек: PUT отправляет весь
     * список треков и метаданные. Кеш на пару минут убирает повторный GET,
     * если пользователь добавляет несколько треков подряд.
     */
    private data class CachedPlaylistJson(val json: JSONObject, val timestamp: Long)

    private val playlistJsonCache = LinkedHashMap<Long, CachedPlaylistJson>()
    private val PLAYLIST_JSON_TTL_MS = 2 * 60 * 1000L
    /** Мутации одного плейлиста выполняются последовательно, чтобы PUT не терял треки. */
    private val playlistMutationMutex = Mutex()

    companion object {
        /**
         * Р Р°Р·РјРµСЂ СЃС‚СЂР°РЅРёС†С‹ РґР»СЏ РїР°РіРёРЅР°С†РёРё SoundCloud. РњР°РєСЃРёРјСѓРј вЂ” 200.
         * Р§РµРј Р±РѕР»СЊС€Рµ СЃС‚СЂР°РЅРёС†Р°, С‚РµРј РјРµРЅСЊС€Рµ СЃРµС‚РµРІС‹С… round-trip'РѕРІ Рё Р±С‹СЃС‚СЂРµРµ СЃРёРЅС…СЂРѕРЅРёР·Р°С†РёСЏ.
         */
        private const val PAGE_LIMIT = 200
    }

    // ---------- РљРµС€РёСЂРѕРІР°РЅРёРµ ----------
    fun saveLibraryToCache(data: ScLibraryData) {
        val json = gson.toJson(data)
        prefs.edit()
            .putString(LIBRARY_CACHE_KEY, json)
            .putLong(CACHE_TIMESTAMP_KEY, System.currentTimeMillis())
            .apply()
    }

    fun getCachedLibrary(): ScLibraryData? {
        val json = prefs.getString(LIBRARY_CACHE_KEY, null) ?: return null
        return try {
            val type = object : TypeToken<ScLibraryData>() {}.type
            gson.fromJson<ScLibraryData>(json, type)
        } catch (e: Exception) {
            null
        }
    }

    fun isCacheValid(): Boolean {
        val timestamp = prefs.getLong(CACHE_TIMESTAMP_KEY, 0L)
        return timestamp > 0 && (System.currentTimeMillis() - timestamp) < CACHE_TTL_MS
    }

    fun clearCache() {
        prefs.edit().remove(LIBRARY_CACHE_KEY).remove(CACHE_TIMESTAMP_KEY).apply()
    }

    /**
     * Треки плейлиста кешируются на диск после первого открытия: повторный
     * заход не делает сетевой resolve, пока кеш не устарел (7 дней) или пока
     * пользователь не потянет экран вниз для обновления.
     */
    fun getCachedPlaylistTracks(permalinkUrl: String): List<TrackInfo>? {
        val key = playlistCacheKey(permalinkUrl) ?: return null
        return synchronized(this) {
            val entry = readPlaylistTracksCache()[key] ?: return@synchronized null
            if (System.currentTimeMillis() - entry.timestamp > PLAYLIST_TRACKS_TTL_MS) {
                null
            } else {
                entry.tracks
            }
        }
    }

    fun savePlaylistTracksToCache(permalinkUrl: String, tracks: List<TrackInfo>) {
        val key = playlistCacheKey(permalinkUrl) ?: return
        synchronized(this) {
            val cache = readPlaylistTracksCache().toMutableMap()
            cache[key] = CachedPlaylistTracks(tracks, System.currentTimeMillis())
            playlistTracksMemoryCache = cache
            prefs.edit()
                .putString(PLAYLIST_TRACKS_CACHE_KEY, gson.toJson(cache))
                .apply()
        }
    }

    /** Удаляет кеш одного плейлиста или весь кеш треков, если URL не передан. */
    fun clearPlaylistTracksCache(permalinkUrl: String? = null) {
        synchronized(this) {
            if (permalinkUrl.isNullOrBlank()) {
                playlistTracksMemoryCache = null
                prefs.edit().remove(PLAYLIST_TRACKS_CACHE_KEY).apply()
                return
            }
            val key = playlistCacheKey(permalinkUrl) ?: return
            val cache = readPlaylistTracksCache().toMutableMap()
            if (cache.remove(key) != null) {
                playlistTracksMemoryCache = cache
                prefs.edit()
                    .putString(PLAYLIST_TRACKS_CACHE_KEY, gson.toJson(cache))
                    .apply()
            }
        }
    }

    private fun readPlaylistTracksCache(): Map<String, CachedPlaylistTracks> {
        playlistTracksMemoryCache?.let { return it }
        val raw = prefs.getString(PLAYLIST_TRACKS_CACHE_KEY, null) ?: return emptyMap()
        val parsed = try {
            val type = object : TypeToken<Map<String, CachedPlaylistTracks>>() {}.type
            gson.fromJson<Map<String, CachedPlaylistTracks>>(raw, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        playlistTracksMemoryCache = parsed
        return parsed
    }

    private fun playlistCacheKey(permalinkUrl: String): String? =
        permalinkUrl.trim()
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .takeIf { it.isNotBlank() }

    // ---------- Вспомогательные ----------
    /**
     * Для авторизованных запросов всегда используем официальный client_id:
     * OAuth-токен выдан именно этому клиенту. Скрапленный веб-client_id
     * (и закэшированный до логина) с таким токеном даёт 403.
     */
    private suspend fun ensureClientId(): String? =
        if (authManager.isLoggedIn || !authManager.getRefreshToken().isNullOrEmpty()) {
            ScConfig.OFFICIAL_CLIENT_ID
        } else {
            baseRepo.getCachedClientId() ?: baseRepo.fetchClientId()
        }

    private suspend fun authToken(): String? = authManager.getFreshAccessToken()

    private suspend inline fun authedRequest(
        endpoint: String? = null,
        withCookies: Boolean = true,
        builder: Request.Builder.() -> Unit = {}
    ): Request.Builder {
        val token = authToken() ?: throw IllegalStateException("Not authenticated")
        val request = Request.Builder()
        if (endpoint != null) {
            if (endpoint.isBlank() || endpoint == "null") {
                throw IllegalStateException("Invalid SoundCloud URL")
            }
            request.url(endpoint)
        }
        return request
            .header("Authorization", "OAuth $token")
            // UA официального мобильного клиента: api-mobile/api-v2 отвечают 403,
            // если браузерный UA приходит вместе с App-Version/UDID.
            .header("User-Agent", apiUserAgent())
            .header("Accept", "application/json")
            .header("Accept-Language", Locale.getDefault().toLanguageTag())
            // Те же заголовки, что отправляет официальный клиент SoundCloud.
            .header("App-Version", "330120")
            .header("UDID", authManager.getDeviceId())
            .apply {
                // Cookies нужны DataDome; auth-cookies внутри уже отфильтрованы,
                // чтобы не перебивать свежий токен из Authorization.
                if (withCookies) {
                    // Точный хост важен для datadome: cookie, выданный
                    // api-mobile, не подходит для api-v2 и наоборот.
                    authManager.getCookieHeader(endpoint)?.let { header("Cookie", it) }
                }
            }
            .apply(builder)
    }

    // ---------- РЈРЅРёРІРµСЂСЃР°Р»СЊРЅР°СЏ РїР°РіРёРЅР°С†РёСЏ ----------
    private suspend fun <T> fetchPaginated(
        initialUrl: String,
        maxItems: Int = Int.MAX_VALUE,
        // Р”Р»СЏ Р»Р°Р№РєРѕРІ/РїРѕРґРїРёСЃРѕРє/РїР»РµР№Р»РёСЃС‚РѕРІ С‡Р°СЃС‚РёС‡РЅС‹Р№ РѕС‚РІРµС‚ С…СѓР¶Рµ РѕС€РёР±РєРё:
        // РёРЅР°С‡Рµ РЅРµРїРѕР»РЅС‹Р№ СЃРїРёСЃРѕРє Р·Р°С‚СЂС‘С‚ С…РѕСЂРѕС€РёР№ РєРµС€ Рё СЃС‡С‘С‚С‡РёРє В«РїРѕС…СѓРґРµРµС‚В».
        required: Boolean = false,
        getNextHref: (JSONObject) -> String = { json ->
            // JSONObject.optString РґР»СЏ JSON-Р·РЅР°С‡РµРЅРёСЏ null РІРѕР·РІСЂР°С‰Р°РµС‚ СЃС‚СЂРѕРєСѓ
            // "null", Р° РЅРµ РїСѓСЃС‚СѓСЋ СЃС‚СЂРѕРєСѓ. Р Р°РЅСЊС€Рµ РёР·-Р·Р° СЌС‚РѕРіРѕ РїРѕСЃР»РµРґРЅСЏСЏ
            // СЃС‚СЂР°РЅРёС†Р° РїСЂРµРІСЂР°С‰Р°Р»Р°СЃСЊ РІ Р·Р°РїСЂРѕСЃ Рє URL "null".
            val direct = json.optString("next_href", "")
                .takeUnless { it.isBlank() || it == "null" }
            val fromLinks = json.optJSONObject("_links")
                ?.optJSONObject("next")
                ?.optString("href", "")
                ?.takeUnless { it.isBlank() || it == "null" }
            direct ?: fromLinks.orEmpty()
        },
        parseItem: (JSONObject) -> T?
    ): List<T> = withContext(Dispatchers.IO) {
        val results = mutableListOf<T>()
        var pageUrl = initialUrl
        var loaded = 0

        try {
            while (pageUrl.isNotBlank() && loaded < maxItems) {
                var page: JSONObject? = null
                var lastCode = 0

                // РЎС‚СЂР°РЅРёС†С‹ РёРЅРѕРіРґР° РѕС‚РІР°Р»РёРІР°СЋС‚СЃСЏ РїРѕ 429/5xx. Р‘РµР· РїРѕРІС‚РѕСЂРѕРІ
                // СЃРёРЅС…СЂРѕРЅРёР·Р°С†РёСЏ РјРѕР»С‡Р° СЃРѕС…СЂР°РЅСЏР»Р° С‚РѕР»СЊРєРѕ РїРµСЂРІСѓСЋ С‡Р°СЃС‚СЊ Р»Р°Р№РєРѕРІ.
                for (attempt in 0 until 3) {
                    val req = authedRequest(pageUrl).build()
                    try {
                        client.newCall(req).execute().use { resp ->
                            lastCode = resp.code
                            if (resp.isSuccessful) {
                                val body = resp.body?.string()
                                if (!body.isNullOrBlank()) page = JSONObject(body)
                            } else if (resp.code == 401) {
                                authManager.clearAuth()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ZYRO_PLAYER", "fetchPaginated request error", e)
                    }

                    if (page != null) break
                    if (lastCode == 401 || lastCode == 403 || lastCode == 404) break
                    if (attempt < 2) delay(700L * (attempt + 1))
                }

                val json = page
                if (json == null) {
                    if (required) {
                        throw IllegalStateException(
                            "РќРµ СѓРґР°Р»РѕСЃСЊ Р·Р°РіСЂСѓР·РёС‚СЊ СЃС‚СЂР°РЅРёС†Сѓ SoundCloud (HTTP $lastCode)"
                        )
                    }
                    break
                }
                val collection = json.optJSONArray("collection")
                if (collection == null) {
                    if (required) {
                        throw IllegalStateException("РќРµРєРѕСЂСЂРµРєС‚РЅС‹Р№ РѕС‚РІРµС‚ SoundCloud: РЅРµС‚ collection")
                    }
                    break
                }

                for (i in 0 until collection.length()) {
                    if (loaded >= maxItems) break
                    val item = collection.optJSONObject(i) ?: continue
                    parseItem(item)?.let {
                        results.add(it)
                        loaded++
                    }
                }

                val nextUrl = getNextHref(json)
                if (nextUrl.isBlank() || nextUrl == "null" || nextUrl == pageUrl) break
                pageUrl = nextUrl
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "fetchPaginated error", e)
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (required) throw e
        }
        results
    }

    /** ISO-8601 РёР· SoundCloud в†’ РјРёР»Р»РёСЃРµРєСѓРЅРґС‹. 0, РµСЃР»Рё С„РѕСЂРјР°С‚ РЅРµ СЂР°СЃРїРѕР·РЅР°РЅ. */
    private fun parseSoundCloudDate(value: String): Long {
        if (value.isBlank()) return 0L
        return try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(value)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }
    // ---------- Р—Р°РїСЂРѕСЃС‹ ----------
    suspend fun fetchCurrentUser(): UserInfo? = withContext(Dispatchers.IO) {
        try {
            val req = authedRequest {
                url("https://api-v2.soundcloud.com/me?client_id=${ScConfig.OFFICIAL_CLIENT_ID}")
            }.build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 401) authManager.clearAuth()
                    return@withContext null
                }
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                val user = parseUserInfo(json) ?: return@withContext null
                if (user.id != 0L) {
                    authManager.updateUserProfile(user.id, user.username)
                }
                user
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "fetchCurrentUser error", e)
            null
        }
    }

    /** Парсит объект пользователя SoundCloud (ответ /me и профильных мутаций). */
    private fun parseUserInfo(json: JSONObject): UserInfo? {
        val userId = json.optLong("id", 0L)
        val username = json.optString("username", "")
        if (userId == 0L && username.isBlank()) return null
        var banner = json.optString("visual_url", "")
        if (banner.isBlank()) {
            val visuals = json.optJSONObject("visuals")?.optJSONArray("visuals")
            if (visuals != null && visuals.length() > 0) {
                banner = visuals.optJSONObject(0)?.optString("visual_url", "") ?: ""
            }
        }
        val countryCode = json.optString("country_code", "")
        return UserInfo(
            permalinkUrl = json.optString("permalink_url", ""),
            username = username,
            avatarUrl = baseRepo.formatAvatar(json.optString("avatar_url", ""), 300),
            followersCount = json.optInt("followers_count", 0),
            trackCount = json.optInt("track_count", 0),
            description = json.optString("description", ""),
            bannerUrl = banner,
            country = json.optString("country", "").ifBlank { countryCode },
            city = json.optString("city", ""),
            id = userId,
            countryCode = countryCode,
            firstName = json.optString("first_name", ""),
            lastName = json.optString("last_name", "")
        )
    }

    suspend fun fetchLikedTracks(limit: Int = 500): List<TrackInfo> {
        val cid = ensureClientId()
            ?: throw IllegalStateException("РќРµ СѓРґР°Р»РѕСЃСЊ РїРѕР»СѓС‡РёС‚СЊ client_id SoundCloud")
        val userId = authManager.currentUserId
        if (userId == 0L) return emptyList()

        val initialUrl = "https://api-v2.soundcloud.com/users/$userId/track_likes" +
            "?client_id=$cid&limit=$PAGE_LIMIT&offset=0&linked_partitioning=1"
        val result = fetchPaginated(initialUrl, maxItems = limit, required = true) { obj ->
            val item = obj.optJSONObject("track") ?: obj
            val permalink = item.optString("permalink_url", "")
            if (permalink.isEmpty()) return@fetchPaginated null
            val userObj = item.optJSONObject("user")
            return@fetchPaginated TrackInfo(
                originalUrl = permalink,
                streamUrl = "",
                title = item.optString("title", ""),
                artist = baseRepo.parseArtistName(item, userObj),
                artworkUrl = baseRepo.getBestArtworkUrl(item.optString("artwork_url", ""))
                    .ifBlank {
                        baseRepo.getBestArtworkUrl(userObj?.optString("avatar_url", "") ?: "")
                    },
                durationMs = item.optLong("duration", 0L),
                likesCount = item.optLong("likes_count", item.optLong("favoritings_count", 0L)),
                playbackCount = item.optLong("playback_count", 0L),
                commentCount = item.optLong("comment_count", 0L),
                id = item.optLong("id", 0L),
                isLiked = true,
                likedAt = parseSoundCloudDate(obj.optString("created_at", "")),
                artistAvatarUrl = baseRepo.getBestArtworkUrl(
                    userObj?.optString("avatar_url", "") ?: ""
                ),
                isGoPlus = baseRepo.isGoPlusTrack(item)
            )
        }
        // Р•СЃР»Рё СЃРµСЂРІРµСЂ РЅРµ РѕС‚РґР°Р» created_at, СЃРѕС…СЂР°РЅСЏРµРј РїРѕСЂСЏРґРѕРє РІС‹РґР°С‡Рё: СЃРІРµСЂС…Сѓ вЂ”
        // Р±РѕР»РµРµ СЃРІРµР¶РёРµ Р»Р°Р№РєРё. РЁР°Рі РІ СЃРµРєСѓРЅРґСѓ РЅРµ РґР°С‘С‚ РѕРґРёРЅР°РєРѕРІС‹Рј РјРµС‚РєР°Рј СЃР»РёРїРЅСѓС‚СЊСЃСЏ.
        val now = System.currentTimeMillis()
        return result.mapIndexed { index, track ->
            if (track.likedAt > 0L) track else track.copy(likedAt = now - index * 1000L)
        }
    }

    suspend fun fetchPlaylists(limit: Int = 200): List<PlaylistInfo> {
        val cid = ensureClientId()
            ?: throw IllegalStateException("РќРµ СѓРґР°Р»РѕСЃСЊ РїРѕР»СѓС‡РёС‚СЊ client_id SoundCloud")
        val userId = authManager.currentUserId
        if (userId == 0L) return emptyList()

        val initialUrl = "https://api-v2.soundcloud.com/users/$userId/playlists" +
            "?client_id=$cid&limit=$PAGE_LIMIT&linked_partitioning=1"
        return fetchPaginated(initialUrl, maxItems = limit, required = true) { item ->
            val permalink = item.optString("permalink_url", "")
            if (permalink.isEmpty()) return@fetchPaginated null
            val userObj = item.optJSONObject("user")
            return@fetchPaginated PlaylistInfo(
                id = item.optLong("id", 0L),
                permalinkUrl = permalink,
                title = item.optString("title", ""),
                user = userObj?.optString("username", "") ?: "",
                artworkUrl = baseRepo.getBestArtworkUrl(item.optString("artwork_url", ""))
                    .ifBlank {
                        baseRepo.getBestArtworkUrl(item.optString("calculated_artwork_url", ""))
                    }
                    .ifBlank { baseRepo.getBestArtworkUrl(baseRepo.getFirstTrackArtwork(item)) },
                trackCount = item.optInt("track_count", 0),
                description = item.optString("description", ""),
                isLiked = item.optBoolean("user_favorite", false),
                genre = item.optString("genre", ""),
                tagList = item.optString("tag_list", ""),
                isPublic = item.optString("sharing", "private") == "public",
                durationMs = item.optLong("duration", 0L),
                createdAtMs = parsePlaylistDate(item)
            )
        }
    }

    suspend fun fetchFollowings(limit: Int = 500): List<UserInfo> {
        val cid = ensureClientId()
            ?: throw IllegalStateException("РќРµ СѓРґР°Р»РѕСЃСЊ РїРѕР»СѓС‡РёС‚СЊ client_id SoundCloud")
        val userId = authManager.currentUserId
        if (userId == 0L) return emptyList()

        val initialUrl = "https://api-v2.soundcloud.com/users/$userId/followings" +
            "?client_id=$cid&limit=$PAGE_LIMIT&linked_partitioning=1"
        return fetchPaginated(initialUrl, maxItems = limit, required = true) { item ->
            val permalink = item.optString("permalink_url", "")
            if (permalink.isEmpty()) return@fetchPaginated null
            return@fetchPaginated UserInfo(
                permalinkUrl = permalink,
                username = item.optString("username", ""),
                avatarUrl = baseRepo.formatAvatar(item.optString("avatar_url", ""), 300),
                followersCount = item.optInt("followers_count", 0),
                trackCount = item.optInt("track_count", 0),
                description = item.optString("description", ""),
                bannerUrl = item.optString("visual_url", ""),
                country = item.optString("country_code", "").ifBlank { item.optString("country", "") },
                city = item.optString("city", ""),
                id = item.optLong("id", 0L)
            )
        }
    }

    suspend fun fetchPlayHistory(limit: Int = 200): List<TrackInfo> {
        val cid = ensureClientId() ?: return emptyList()
        val initialUrl = "https://api-v2.soundcloud.com/me/play-history" +
            "?client_id=$cid&limit=$PAGE_LIMIT&linked_partitioning=1"
        return fetchPaginated(initialUrl, maxItems = limit) { obj ->
            val item = obj.optJSONObject("track") ?: return@fetchPaginated null
            val permalink = item.optString("permalink_url", "")
            if (permalink.isEmpty()) return@fetchPaginated null
            val userObj = item.optJSONObject("user")
            return@fetchPaginated TrackInfo(
                originalUrl = permalink,
                streamUrl = "",
                title = item.optString("title", ""),
                artist = baseRepo.parseArtistName(item, userObj),
                artworkUrl = baseRepo.getBestArtworkUrl(item.optString("artwork_url", ""))
                    .ifBlank {
                        baseRepo.getBestArtworkUrl(userObj?.optString("avatar_url", "") ?: "")
                    },
                durationMs = item.optLong("duration", 0L),
                likesCount = item.optLong("likes_count", item.optLong("favoritings_count", 0L)),
                playbackCount = item.optLong("playback_count", 0L),
                commentCount = item.optLong("comment_count", 0L),
                artistAvatarUrl = baseRepo.getBestArtworkUrl(
                    userObj?.optString("avatar_url", "") ?: ""
                ),
                isGoPlus = baseRepo.isGoPlusTrack(item)
            )
        }
    }

    // ---------- Лента подписок ----------
    /**
     * Одна страница ленты `/stream`. В отличие от остальной библиотеки,
     * лента листается курсором `next_href`, а не выкачивается целиком.
     */
    suspend fun fetchFeedPage(
        nextHref: String? = null,
        limit: Int = 20
    ): Result<ScFeedPage> = withContext(Dispatchers.IO) {
        try {
            val cid = ensureClientId() ?: ScConfig.OFFICIAL_CLIENT_ID
            val url = if (nextHref.isNullOrBlank()) {
                "https://api-v2.soundcloud.com/stream" +
                    "?limit=${limit.coerceIn(1, 200)}&linked_partitioning=1&client_id=$cid"
            } else {
                normalizeFeedUrl(nextHref, cid)
            }

            suspend fun attempt(withCookies: Boolean): Result<ScFeedPage> = try {
                val req = authedRequest(endpoint = url, withCookies = withCookies).build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    Log.d(
                        "ZYRO_PLAYER",
                        "feed HTTP ${resp.code} cookies=$withCookies body=${body.take(300)}"
                    )
                    if (!resp.isSuccessful) {
                        val message = when (resp.code) {
                            401 -> "HTTP 401: сессия SoundCloud истекла"
                            403 -> "HTTP 403: SoundCloud временно отклонил запрос"
                            429 -> "HTTP 429: слишком много запросов"
                            else -> "HTTP ${resp.code}"
                        }
                        Result.Error(message)
                    } else {
                        val json = runCatching { JSONObject(body) }.getOrNull()
                        if (json == null) {
                            Result.Error("SoundCloud вернул некорректный ответ ленты")
                        } else {
                            val collection = json.optJSONArray("collection")
                            val activities = buildList {
                                if (collection != null) {
                                    for (i in 0 until collection.length()) {
                                        val item = collection.optJSONObject(i) ?: continue
                                        runCatching { parseFeedActivity(item) }
                                            .getOrNull()
                                            ?.let { add(it) }
                                    }
                                }
                            }.distinctBy { it.id }
                            val next = if (json.isNull("next_href")) {
                                null
                            } else {
                                json.optString("next_href", "")
                                    .takeIf { it.isNotBlank() && it != "null" }
                                    ?.let { normalizeFeedUrl(it, cid) }
                            }
                            Result.Success(ScFeedPage(activities, next))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ZYRO_PLAYER", "feed request error", e)
                Result.Error("Не удалось загрузить ленту", e)
            }

            // Снятие cookies и обновление токена при 401/403 делает
            // SoundCloudAuthRecoveryInterceptor до возврата ответа сюда.
            val result = attempt(withCookies = true)
            if (result is Result.Error && result.message.contains("401")) {
                authManager.clearAuth()
            }
            result
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "fetchFeedPage error", e)
            Result.Error("Не удалось загрузить ленту", e)
        }
    }

    /** next_href приходит абсолютным, но иногда без client_id. */
    private fun normalizeFeedUrl(raw: String, clientId: String): String {
        val absolute = when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://api-v2.soundcloud.com$raw"
            else -> "https://api-v2.soundcloud.com/$raw"
        }
        if (absolute.contains("client_id=")) return absolute
        if (clientId.isBlank()) return absolute
        val separator = if (absolute.contains('?')) '&' else '?'
        return "$absolute${separator}client_id=$clientId"
    }

    private fun parseFeedActivity(item: JSONObject): ScFeedActivity? {
        val type = item.optString("type", "")
            .lowercase(Locale.US)
            .replace(':', '_')
        val origin = item.optJSONObject("origin")
        val originKind = origin?.optString("kind", "").orEmpty()
        val isPlaylist = type.startsWith("playlist") ||
            item.optJSONObject("playlist") != null ||
            originKind == "playlist"

        val action = when {
            type.contains("repost") -> ScFeedAction.REPOST
            type.contains("like") -> ScFeedAction.LIKE
            else -> ScFeedAction.UPLOAD
        }

        val content = if (isPlaylist) {
            item.optJSONObject("playlist") ?: origin
        } else {
            item.optJSONObject("track") ?: origin
        } ?: return null

        val track = if (isPlaylist) null else parseFeedTrack(content)
        val playlist = if (isPlaylist) parseFeedPlaylist(content) else null
        if (track == null && playlist == null) return null

        val fallbackUser = content.optJSONObject("user")
        val actor = item.optJSONObject("user")?.let { parseUserInfo(it) }
            ?: if (action == ScFeedAction.UPLOAD) {
                fallbackUser?.let { parseUserInfo(it) }
            } else {
                null
            }

        val createdAt = parseSoundCloudDate(item.optString("created_at", ""))
        val contentId = track?.id ?: playlist?.id ?: 0L
        val contentUrl = track?.originalUrl ?: playlist?.permalinkUrl.orEmpty()
        val id = item.optString("uuid", "")
            .takeUnless { it.isBlank() || it == "null" }
            ?: "${type}_${contentId}_${createdAt}_${actor?.id ?: 0L}_$contentUrl"
        return ScFeedActivity(
            id = id,
            action = action,
            actor = actor,
            track = track,
            playlist = playlist,
            createdAtMs = createdAt
        )
    }

    private fun parseFeedTrack(item: JSONObject): TrackInfo? {
        val permalink = item.optString("permalink_url", "")
        if (permalink.isBlank() || permalink == "null") return null
        val userObj = item.optJSONObject("user")
        val artRaw = item.optString("artwork_url", "")
            .takeUnless { it.isBlank() || it == "null" }
        val avatarRaw = userObj?.optString("avatar_url", "")
            ?.takeUnless { it.isBlank() || it == "null" }
        return TrackInfo(
            originalUrl = permalink,
            title = item.optString("title", ""),
            artist = baseRepo.parseArtistName(item, userObj),
            artworkUrl = baseRepo.getBestArtworkUrl(artRaw ?: avatarRaw.orEmpty()),
            durationMs = item.optLong("duration", 0L),
            likesCount = item.optLong("likes_count", item.optLong("favoritings_count", 0L)),
            playbackCount = item.optLong("playback_count", 0L),
            commentCount = item.optLong("comment_count", 0L),
            genre = item.optString("genre", ""),
            description = item.optString("description", ""),
            id = item.optLong("id", 0L),
            isLiked = item.optBoolean("user_favorite", false),
            artistAvatarUrl = baseRepo.getBestArtworkUrl(avatarRaw.orEmpty()),
            isGoPlus = baseRepo.isGoPlusTrack(item)
        )
    }

    private fun parseFeedPlaylist(item: JSONObject): PlaylistInfo? =
        parsePlaylistJson(item).takeIf {
            it.permalinkUrl.isNotBlank() && it.permalinkUrl != "null"
        }

    // ---------- РџРѕР»РЅР°СЏ Р±РёР±Р»РёРѕС‚РµРєР° ----------
    suspend fun fetchFullLibrary(
        forceRefresh: Boolean = false,
        limitLikes: Int = 500,
        limitPlaylists: Int = 200,
        limitFollowings: Int = 500,
        limitHistory: Int = 200
    ): ScLibraryData {
        if (!forceRefresh && isCacheValid()) {
            getCachedLibrary()?.let { return it }
        }

        val freshData = loadLibraryData(limitLikes, limitPlaylists, limitFollowings, limitHistory)
        saveLibraryToCache(freshData)
        return freshData
    }

    private suspend fun loadLibraryData(
        limitLikes: Int,
        limitPlaylists: Int,
        limitFollowings: Int,
        limitHistory: Int
    ): ScLibraryData = withContext(Dispatchers.IO) {
        // userId РЅСѓР¶РµРЅ Р·Р°РїСЂРѕСЃР°Рј Р»Р°Р№РєРѕРІ/РїР»РµР№Р»РёСЃС‚РѕРІ/РїРѕРґРїРёСЃРѕРє. Р•СЃР»Рё РѕРЅ СѓР¶Рµ РёР·РІРµСЃС‚РµРЅ,
        // Р·Р°РїСѓСЃРєР°РµРј РІСЃС‘ РїР°СЂР°Р»Р»РµР»СЊРЅРѕ; РµСЃР»Рё РЅРµС‚ вЂ” СЃРЅР°С‡Р°Р»Р° РїРѕР»СѓС‡Р°РµРј РїСЂРѕС„РёР»СЊ,
        // РёРЅР°С‡Рµ Р·Р°РїСЂРѕСЃС‹ СѓР№РґСѓС‚ СЃ userId=0 Рё РІРµСЂРЅСѓС‚ РїСѓСЃС‚С‹Рµ СЃРїРёСЃРєРё.
        val knownUserId = authManager.currentUserId
        val userDeferred = async { fetchCurrentUser() }

        val likesDeferred = if (knownUserId != 0L) async { fetchLikedTracks(limitLikes) } else null
        val playlistsDeferred = if (knownUserId != 0L) async { fetchPlaylists(limitPlaylists) } else null
        val likedPlaylistsDeferred = if (knownUserId != 0L) async { fetchLikedPlaylists(limitPlaylists) } else null
        val followingsDeferred = if (knownUserId != 0L) async { fetchFollowings(limitFollowings) } else null
        val historyDeferred = async { fetchPlayHistory(limitHistory) }

        val currentUser = userDeferred.await()
        // Р•СЃР»Рё РїСЂРѕС„РёР»СЊ РЅРµ РїРѕР»СѓС‡РµРЅ Рё ID РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ РЅРµРёР·РІРµСЃС‚РµРЅ, Р·Р°РїСЂРѕСЃС‹ Р»Р°Р№РєРѕРІ
        // СѓС€Р»Рё Р±С‹ СЃ userId=0 Рё РІРµСЂРЅСѓР»Рё РїСѓСЃС‚РѕС‚Сѓ. Р›СѓС‡С€Рµ РЅРµ Р·Р°С‚РёСЂР°С‚СЊ РєРµС€ РїСѓСЃС‚С‹Рј
        // СЃРЅР°РїС€РѕС‚РѕРј, Р° РїРѕРєР°Р·Р°С‚СЊ РѕС€РёР±РєСѓ СЃРёРЅС…СЂРѕРЅРёР·Р°С†РёРё.
        if (currentUser == null && authManager.currentUserId == 0L) {
            throw IllegalStateException("РќРµ СѓРґР°Р»РѕСЃСЊ РїРѕР»СѓС‡РёС‚СЊ РїСЂРѕС„РёР»СЊ SoundCloud")
        }

        ScLibraryData(
            currentUser = currentUser,
            likedTracks = likesDeferred?.await() ?: fetchLikedTracks(limitLikes),
            playlists = playlistsDeferred?.await() ?: fetchPlaylists(limitPlaylists),
            likedPlaylists = likedPlaylistsDeferred?.await() ?: fetchLikedPlaylists(limitPlaylists),
            followings = followingsDeferred?.await() ?: fetchFollowings(limitFollowings),
            playHistory = historyDeferred.await()
        )
    }

    // ==================== РњРЈРўРђР¦РР РќРђ РЎР•Р Р’Р•Р Р• ====================

    /**
     * Р’С‹РїРѕР»РЅСЏРµС‚ Р·Р°РїСЂРѕСЃ Рё РїСЂРµРІСЂР°С‰Р°РµС‚ HTTP-РѕС‚РІРµС‚ РІ [Result]. РљРѕРґС‹ РёР·
     * [idempotentCodes] СЃС‡РёС‚Р°РµРј СѓСЃРїРµС…РѕРј: РїРѕРІС‚РѕСЂРЅС‹Р№ Р»Р°Р№Рє/РїРѕРґРїРёСЃРєР° РёР»Рё
     * СѓРґР°Р»РµРЅРёРµ СѓР¶Рµ РЅРµСЃСѓС‰РµСЃС‚РІСѓСЋС‰РµРіРѕ Р»Р°Р№РєР° РЅРµ РґРѕР»Р¶РЅС‹ РѕС‚РєР°С‚С‹РІР°С‚СЊ UI.
     */
    private fun executeResult(
        req: Request,
        idempotentCodes: Set<Int> = emptySet(),
        clearAuthOn401: Boolean = true
    ): Result<Unit> {
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful || resp.code in idempotentCodes -> Result.Success(Unit)
                    else -> {
                        // Р”Р»СЏ api-mobile 401 РјРѕР¶РµС‚ РѕР·РЅР°С‡Р°С‚СЊ Р»РёС€СЊ РѕС‚СЃСѓС‚СЃС‚РІРёРµ cookie,
                        // РїРѕСЌС‚РѕРјСѓ СЃРµСЃСЃРёСЋ РЅРµ СЃР±СЂР°СЃС‹РІР°РµРј вЂ” СЃРЅР°С‡Р°Р»Р° РїСЂРѕР±СѓРµРј v2-С„РѕР»Р±СЌРє.
                        val errorBody = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                        Log.w(
                            "ZYRO_PLAYER",
                            "${req.method} ${req.url.encodedPath} -> ${resp.code} " +
                                errorBody.take(300)
                        )
                        if (resp.code == 401 && clearAuthOn401) authManager.clearAuth()
                        Result.Error("HTTP ${resp.code}")
                    }
                }
            }
        } catch (e: Exception) {
            Result.Error("Network error", e)
        }
    }

    /**
     * UA официального Android-клиента. Должен быть мобильным приложением
     * SoundCloud, а не браузером: api-mobile/api-v2 с браузерным UA
     * (плюс `App-Version`/`UDID`) DataDome отбрасывает с 403, из-за чего
     * не работали лайки, подписки и запись плейлистов. Браузерный UA остаётся
     * только у ghost WebView и скрапинга HTML — там он обязателен.
     */
    private fun apiUserAgent(): String = ScConfig.API_USER_AGENT

    /**
     * Р›Р°Р№Рє/РґРёР·Р»Р°Р№Рє С‡РµСЂРµР· РјРѕР±РёР»СЊРЅС‹Р№ API SoundCloud. РРјРµРЅРЅРѕ СЌС‚Сѓ СЂСѓС‡РєСѓ РёСЃРїРѕР»СЊР·СѓРµС‚
     * РѕС„РёС†РёР°Р»СЊРЅС‹Р№ РєР»РёРµРЅС‚; СЃС‚Р°СЂС‹Рµ v2-СЌРЅРґРїРѕРёРЅС‚С‹ РЅР° С‡Р°СЃС‚Рё Р°РєРєР°СѓРЅС‚РѕРІ РЅРµ СЂР°Р±РѕС‚Р°СЋС‚.
     */
    private suspend fun mobileLikeRequest(
        endpoint: String,
        urn: String,
        idempotentCodes: Set<Int> = emptySet(),
        withCookies: Boolean = true
    ): Result<Unit> {
        return try {
            val payload = JSONObject()
                .put(
                    "likes",
                    JSONArray().put(JSONObject().put("target_urn", urn))
                )
                .toString()
            val req = authedRequest(withCookies = withCookies) {
                url(endpoint)
            }
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            executeResult(req, idempotentCodes, clearAuthOn401 = false)
        } catch (e: Exception) {
            Result.Error("Mobile like request failed", e)
        }
    }

    /**
     * Лайк/дизлайк через api-mobile. Снятие cookies и обновление токена при
     * `401/403` выполняет [com.zyro.player.data.auth.SoundCloudAuthRecoveryInterceptor],
     * поэтому здесь не нужны повторные попытки и второй запрос.
     */
    private suspend fun mobileLikeWithFallback(
        endpoint: String,
        urn: String,
        idempotentCodes: Set<Int>
    ): Result<Unit> =
        mobileLikeRequest(endpoint, urn, idempotentCodes, withCookies = true)

    private suspend fun mobileFollowRequest(
        userId: Long,
        follow: Boolean,
        withCookies: Boolean = true
    ): Result<Unit> {
        return try {
            val endpoint =
                "https://api-mobile.soundcloud.com/follows/users/soundcloud:users:$userId"
            val req = authedRequest(withCookies = withCookies) {
                url(endpoint)
            }
                .method(
                    if (follow) "POST" else "DELETE",
                    if (follow) "{}".toRequestBody(jsonMediaType) else null
                )
                .build()
            executeResult(
                req,
                if (follow) setOf(409, 422) else setOf(404),
                clearAuthOn401 = false
            )
        } catch (e: Exception) {
            Result.Error("Mobile follow request failed", e)
        }
    }

    /** Подписка/отписка: восстановление после 401/403 делает общий интерцептор. */
    private suspend fun mobileFollowWithFallback(
        userId: Long,
        follow: Boolean
    ): Result<Unit> = mobileFollowRequest(userId, follow, withCookies = true)

    /**
     * ID текущего пользователя. Если профиль ещё не сохранялся (старая сессия),
     * запрашивает /me: без user_id веб-эндпоинты лайков отвечают 404.
     */
    private suspend fun requireCurrentUserId(): Long {
        val known = authManager.currentUserId
        if (known != 0L) return known
        fetchCurrentUser()
        return authManager.currentUserId
    }

    /**
     * Лайк/дизлайк через api-v2 ровно так, как его делает веб-клиент SoundCloud:
     * PUT/DELETE /users/{userId}/track_likes/{id} (для плейлистов — playlist_likes,
     * для станций — system_playlist_likes).
     *
     * Старый путь /me/track_likes/{id} остался только для GET; на PUT/DELETE он
     * отвечает 404 — именно из-за него появлялась ошибка при лайке трека.
     */
    private suspend fun v2UserLikeRequest(
        collection: String,
        target: String,
        create: Boolean,
        idempotentCodes: Set<Int>
    ): Result<Unit> {
        val userId = requireCurrentUserId()
        if (userId == 0L) return Result.Error("Не удалось определить пользователя SoundCloud")

        // Для мутаций нужен именно тот client_id, на который выдан OAuth-токен
        // (официальный клиент). Случайный client_id из веба даёт 403.
        val cid = ScConfig.OFFICIAL_CLIENT_ID
        val url = buildString {
            append("https://api-v2.soundcloud.com/users/")
            append(userId)
            append('/').append(collection).append('/').append(target)
            append("?client_id=").append(cid)
        }
        return try {
            val req = authedRequest(url) {
                if (create) put(ByteArray(0).toRequestBody(null)) else delete()
            }.build()
            executeResult(req, idempotentCodes)
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "v2 $collection mutation error", e)
            Result.Error("Не удалось обновить лайк на SoundCloud", e)
        }
    }

    // ---------- Р›Р°Р№РєРё С‚СЂРµРєРѕРІ ----------

    /**
     * Р›Р°Р№Рє С‚СЂРµРєР° РЅР° SoundCloud. Р’РѕР·РІСЂР°С‰Р°РµС‚ true РїСЂРё СѓСЃРїРµС…Рµ.
     * РСЃРїРѕР»СЊР·СѓРµС‚ РѕС„РёС†РёР°Р»СЊРЅС‹Р№ API: PUT /me/track_likes/{trackId}
     */
    suspend fun likeTrack(trackId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        // РћСЃРЅРѕРІРЅР°СЏ СЂСѓС‡РєР° вЂ” РєР°Рє РІ РѕС„РёС†РёР°Р»СЊРЅРѕРј РјРѕР±РёР»СЊРЅРѕРј РєР»РёРµРЅС‚Рµ. РЎС‚Р°СЂС‹Р№
        // /me/track_likes РѕСЃС‚Р°РІР»РµРЅ С„РѕР»Р±СЌРєРѕРј: РЅР° С‡Р°СЃС‚Рё Р°РєРєР°СѓРЅС‚РѕРІ РѕРЅ СѓР¶Рµ РЅРµ СЂР°Р±РѕС‚Р°РµС‚.
        // Основной путь — мобильная ручка официального клиента; 401/403
        // обрабатывает общий интерцептор, затем пробуем api-v2 как веб-клиент.
        val mobile = mobileLikeWithFallback(
            "https://api-mobile.soundcloud.com/likes/tracks/create",
            "soundcloud:tracks:$trackId",
            idempotentCodes = setOf(409, 422)
        )
        if (mobile is Result.Success) return@withContext mobile
        Log.w("ZYRO_PLAYER", "mobile like failed: ${(mobile as? Result.Error)?.message}; trying v2")

        val v2 = v2UserLikeRequest(
            collection = "track_likes",
            target = trackId.toString(),
            create = true,
            idempotentCodes = setOf(409, 422)
        )
        if (v2 is Result.Success) v2 else mobile
    }

    /**
     * РЈРґР°Р»РµРЅРёРµ Р»Р°Р№РєР° С‚СЂРµРєР° СЃ SoundCloud. Р’РѕР·РІСЂР°С‰Р°РµС‚ true РїСЂРё СѓСЃРїРµС…Рµ.
     * РСЃРїРѕР»СЊР·СѓРµС‚ РѕС„РёС†РёР°Р»СЊРЅС‹Р№ API: DELETE /me/track_likes/{trackId}
     */
    suspend fun unlikeTrack(trackId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val mobile = mobileLikeWithFallback(
            "https://api-mobile.soundcloud.com/likes/tracks/delete",
            "soundcloud:tracks:$trackId",
            idempotentCodes = setOf(404)
        )
        if (mobile is Result.Success) return@withContext mobile
        Log.w("ZYRO_PLAYER", "mobile unlike failed: ${(mobile as? Result.Error)?.message}; trying v2")

        val v2 = v2UserLikeRequest(
            collection = "track_likes",
            target = trackId.toString(),
            create = false,
            idempotentCodes = setOf(404)
        )
        if (v2 is Result.Success) v2 else mobile
    }

    /**
     * РџСЂРѕРІРµСЂРєР°, Р»Р°Р№РєРЅСѓС‚ Р»Рё С‚СЂРµРє С‚РµРєСѓС‰РёРј РїРѕР»СЊР·РѕРІР°С‚РµР»РµРј.
     * Р’РѕР·РІСЂР°С‰Р°РµС‚ true РµСЃР»Рё Р»Р°Р№РєРЅСѓС‚, false РµСЃР»Рё РЅРµС‚.
     */
    suspend fun isTrackLiked(trackId: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api-v2.soundcloud.com/me/track_likes/$trackId"
            val req = authedRequest(url).build()
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> Result.Success(true)
                    404 -> Result.Success(false)
                    401 -> { authManager.clearAuth(); Result.Error("Unauthorized") }
                    else -> Result.Error("HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "isTrackLiked error", e)
            Result.Error("Failed to check like status", e)
        }
    }

    // ---------- Р›Р°Р№РєРё РїР»РµР№Р»РёСЃС‚РѕРІ ----------

    /**
     * Р›Р°Р№Рє РїР»РµР№Р»РёСЃС‚Р° РЅР° SoundCloud. Р’РѕР·РІСЂР°С‰Р°РµС‚ true РїСЂРё СѓСЃРїРµС…Рµ.
     * РСЃРїРѕР»СЊР·СѓРµС‚ РѕС„РёС†РёР°Р»СЊРЅС‹Р№ API: PUT /me/playlist_likes/{playlistId}
     */
    suspend fun likePlaylist(playlistId: Long, urn: String = ""): Result<Unit> =
        withContext(Dispatchers.IO) {
            val isSystem = urn.startsWith("soundcloud:system-playlists:")
            val mobile = mobileLikeWithFallback(
                "https://api-mobile.soundcloud.com/likes/playlists/create",
                if (isSystem) urn else "soundcloud:playlists:$playlistId",
                idempotentCodes = setOf(409, 422)
            )
            if (mobile is Result.Success) return@withContext mobile
            Log.w("ZYRO_PLAYER", "mobile playlist like failed: ${(mobile as? Result.Error)?.message}; trying v2")

            val v2 = v2UserLikeRequest(
                collection = if (isSystem) "system_playlist_likes" else "playlist_likes",
                target = if (isSystem) urn else playlistId.toString(),
                create = true,
                idempotentCodes = setOf(409, 422)
            )
            if (v2 is Result.Success) v2 else mobile
        }

    /**
     * РЈРґР°Р»РµРЅРёРµ Р»Р°Р№РєР° РїР»РµР№Р»РёСЃС‚Р° СЃ SoundCloud. Р’РѕР·РІСЂР°С‰Р°РµС‚ true РїСЂРё СѓСЃРїРµС…Рµ.
     * РСЃРїРѕР»СЊР·СѓРµС‚ РѕС„РёС†РёР°Р»СЊРЅС‹Р№ API: DELETE /me/playlist_likes/{playlistId}
     */
    suspend fun unlikePlaylist(playlistId: Long, urn: String = ""): Result<Unit> =
        withContext(Dispatchers.IO) {
            val isSystem = urn.startsWith("soundcloud:system-playlists:")
            val mobile = mobileLikeWithFallback(
                "https://api-mobile.soundcloud.com/likes/playlists/delete",
                if (isSystem) urn else "soundcloud:playlists:$playlistId",
                idempotentCodes = setOf(404)
            )
            if (mobile is Result.Success) return@withContext mobile
            Log.w("ZYRO_PLAYER", "mobile playlist unlike failed: ${(mobile as? Result.Error)?.message}; trying v2")

            val v2 = v2UserLikeRequest(
                collection = if (isSystem) "system_playlist_likes" else "playlist_likes",
                target = if (isSystem) urn else playlistId.toString(),
                create = false,
                idempotentCodes = setOf(404)
            )
            if (v2 is Result.Success) v2 else mobile
        }

    /**
     * РџРѕР»СѓС‡РµРЅРёРµ СЃРїРёСЃРєР° Р»Р°Р№РєРЅСѓС‚С‹С… РїР»РµР№Р»РёСЃС‚РѕРІ С‚РµРєСѓС‰РµРіРѕ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ.
     */
    suspend fun fetchLikedPlaylists(limit: Int = 200): List<PlaylistInfo> {
        val cid = ensureClientId()
            ?: throw IllegalStateException("РќРµ СѓРґР°Р»РѕСЃСЊ РїРѕР»СѓС‡РёС‚СЊ client_id SoundCloud")
        val userId = authManager.currentUserId
        if (userId == 0L) return emptyList()

        val initialUrl = "https://api-v2.soundcloud.com/users/$userId/playlist_likes" +
            "?client_id=$cid&limit=$PAGE_LIMIT&linked_partitioning=1"
        return fetchPaginated(initialUrl, maxItems = limit, required = true) { item ->
            val permalink = item.optString("permalink_url", "")
            if (permalink.isEmpty()) return@fetchPaginated null
            val userObj = item.optJSONObject("user")
            return@fetchPaginated PlaylistInfo(
                id = item.optLong("id", 0L),
                permalinkUrl = permalink,
                title = item.optString("title", ""),
                user = userObj?.optString("username", "") ?: "",
                artworkUrl = baseRepo.getBestArtworkUrl(item.optString("artwork_url", ""))
                    .ifBlank {
                        baseRepo.getBestArtworkUrl(item.optString("calculated_artwork_url", ""))
                    }
                    .ifBlank { baseRepo.getBestArtworkUrl(baseRepo.getFirstTrackArtwork(item)) },
                trackCount = item.optInt("track_count", 0),
                description = item.optString("description", ""),
                isLiked = true,
                genre = item.optString("genre", ""),
                tagList = item.optString("tag_list", ""),
                isPublic = item.optString("sharing", "private") == "public",
                durationMs = item.optLong("duration", 0L),
                createdAtMs = parsePlaylistDate(item)
            )
        }
    }

    // ---------- РџРѕРґРїРёСЃРєРё ----------

    /**
     * РџРѕРґРїРёСЃРєР° РЅР° РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ РЅР° SoundCloud. Р’РѕР·РІСЂР°С‰Р°РµС‚ true РїСЂРё СѓСЃРїРµС…Рµ.
     * РСЃРїРѕР»СЊР·СѓРµС‚ РѕС„РёС†РёР°Р»СЊРЅС‹Р№ API: PUT /me/followings/{userId}
     */
    suspend fun followUser(userId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val mobile = mobileFollowWithFallback(userId, follow = true)
        if (mobile is Result.Success) return@withContext mobile
        Log.w("ZYRO_PLAYER", "Mobile follow failed: ${(mobile as? Result.Error)?.message}; trying v2")

        try {
            val url = "https://api-v2.soundcloud.com/me/followings/$userId"
            // Веб-клиент SoundCloud подписывается через POST, не PUT.
            val req = authedRequest(url).post(ByteArray(0).toRequestBody(null)).build()
            executeResult(req, idempotentCodes = setOf(409, 422))
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "followUser error", e)
            Result.Error("Failed to follow user", e)
        }
    }

    /**
     * РћС‚РїРёСЃРєР° РѕС‚ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ РЅР° SoundCloud. Р’РѕР·РІСЂР°С‰Р°РµС‚ true РїСЂРё СѓСЃРїРµС…Рµ.
     * РСЃРїРѕР»СЊР·СѓРµС‚ РѕС„РёС†РёР°Р»СЊРЅС‹Р№ API: DELETE /me/followings/{userId}
     */
    suspend fun unfollowUser(userId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val mobile = mobileFollowWithFallback(userId, follow = false)
        if (mobile is Result.Success) return@withContext mobile
        Log.w("ZYRO_PLAYER", "Mobile unfollow failed: ${(mobile as? Result.Error)?.message}; trying v2")

        try {
            val url = "https://api-v2.soundcloud.com/me/followings/$userId"
            val req = authedRequest(url).delete().build()
            executeResult(req, idempotentCodes = setOf(404))
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "unfollowUser error", e)
            Result.Error("Failed to unfollow user", e)
        }
    }

    /**
     * РџСЂРѕРІРµСЂРєР°, РїРѕРґРїРёСЃР°РЅ Р»Рё С‚РµРєСѓС‰РёР№ РїРѕР»СЊР·РѕРІР°С‚РµР»СЊ РЅР° СѓРєР°Р·Р°РЅРЅРѕРіРѕ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ.
     * Р’РѕР·РІСЂР°С‰Р°РµС‚ true РµСЃР»Рё РїРѕРґРїРёСЃР°РЅ, false РµСЃР»Рё РЅРµС‚.
     */
    suspend fun isFollowing(userId: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api-v2.soundcloud.com/me/followings/$userId"
            val req = authedRequest(url).build()
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> Result.Success(true)
                    404 -> Result.Success(false)
                    401 -> { authManager.clearAuth(); Result.Error("Unauthorized") }
                    else -> Result.Error("HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "isFollowing error", e)
            Result.Error("Failed to check follow status", e)
        }
    }

    // ---------- Редактирование профиля ----------

    /**
     * Обновляет текстовые поля профиля через v2-ручку веб-клиента `PUT /me`.
     *
     * Как и остальные мутации, сначала отправляем cookies веб-сессии (их
     * проверяет DataDome). При 403 общий интерцептор повторяет запрос без них —
     * уже как официальный мобильный клиент, и при необходимости обновляет токен.
     */
    suspend fun updateProfile(
        username: String,
        description: String = "",
        city: String = "",
        countryCode: String = "",
        firstName: String = "",
        lastName: String = ""
    ): Result<UserInfo> = withContext(Dispatchers.IO) {
        if (username.isBlank()) {
            return@withContext Result.Error("Имя пользователя не может быть пустым")
        }
        val payload = JSONObject().apply {
            put("username", username.trim())
            put("description", description)
            put("city", city.trim())
            put("first_name", firstName.trim())
            put("last_name", lastName.trim())
            if (countryCode.isBlank()) {
                put("country_code", JSONObject.NULL)
            } else {
                put("country_code", countryCode.trim().uppercase(Locale.US))
            }
        }.toString()
        val url = "https://api-v2.soundcloud.com/me?client_id=${ScConfig.OFFICIAL_CLIENT_ID}"

        suspend fun attempt(withCookies: Boolean): Result<UserInfo> = try {
            val req = authedRequest(url, withCookies = withCookies)
                .put(payload.toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.d(
                    "ZYRO_PLAYER",
                    "updateProfile cookies=$withCookies HTTP ${resp.code} body=${body.take(500)}"
                )
                if (!resp.isSuccessful) {
                    Result.Error(profileHttpError(resp.code, body))
                } else {
                    val parsed = runCatching { JSONObject(body) }.getOrNull()
                        ?.let { parseUserInfo(it) }
                    if (parsed != null) {
                        if (parsed.id != 0L) {
                            authManager.updateUserProfile(parsed.id, parsed.username)
                        }
                        Result.Success(parsed)
                    } else {
                        fetchCurrentUser()?.let { Result.Success(it) }
                            ?: Result.Error("Профиль обновлён, но ответ SoundCloud не удалось прочитать")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "updateProfile error", e)
            Result.Error("Не удалось обновить профиль", e)
        }

        // 401/403 обрабатывает SoundCloudAuthRecoveryInterceptor: снимает
        // cookies и обновляет токен до возврата ответа сюда.
        attempt(withCookies = true)
    }

    /** Загрузка/удаление аватара и баннера профиля. */
    private suspend fun profileImageRequest(
        endpoint: String,
        imageBase64: String?
    ): Result<Unit> {
        val isDelete = imageBase64 == null

        suspend fun attempt(withCookies: Boolean): Pair<Result<Unit>, Int> = try {
            val req = if (imageBase64 != null) {
                val payload = JSONObject().put("image_data", imageBase64).toString()
                authedRequest(endpoint, withCookies = withCookies)
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()
            } else {
                authedRequest(endpoint, withCookies = withCookies).delete().build()
            }
            client.newCall(req).execute().use { resp ->
                val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                Log.d(
                    "ZYRO_PLAYER",
                    "profileImage ${req.method} ${req.url.encodedPath} " +
                        "cookies=$withCookies HTTP ${resp.code} body=${body.take(300)}"
                )
                val result = when {
                    resp.isSuccessful -> Result.Success(Unit)
                    isDelete && resp.code == 404 -> Result.Success(Unit)
                    else -> Result.Error(profileHttpError(resp.code, body))
                }
                result to resp.code
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "profileImageRequest error", e)
            Result.Error("Не удалось обновить изображение профиля", e) to 0
        }

        // 401/403 уже обработан общим интерцептором.
        return attempt(withCookies = true).first
    }

    suspend fun uploadAvatar(imageBase64: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (imageBase64.isBlank()) {
            Result.Error("Пустое изображение")
        } else {
            profileImageRequest(
                "https://api-mobile.soundcloud.com/you/profile/avatar",
                imageBase64
            )
        }
    }

    suspend fun deleteAvatar(): Result<Unit> = withContext(Dispatchers.IO) {
        profileImageRequest(
            "https://api-mobile.soundcloud.com/you/profile/avatar",
            imageBase64 = null
        )
    }

    suspend fun uploadBanner(imageBase64: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (imageBase64.isBlank()) {
            Result.Error("Пустое изображение")
        } else {
            profileImageRequest(
                "https://api-mobile.soundcloud.com/you/profile/header_image",
                imageBase64
            )
        }
    }

    suspend fun deleteBanner(): Result<Unit> = withContext(Dispatchers.IO) {
        profileImageRequest(
            "https://api-mobile.soundcloud.com/you/profile/header_image",
            imageBase64 = null
        )
    }

    private fun profileHttpError(code: Int, body: String): String {
        val serverMessage = runCatching {
            JSONObject(body).optString("error", "")
        }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 300 }
        val suffix = if (serverMessage != null) ": $serverMessage" else ""
        return when (code) {
            401 -> "Сессия SoundCloud истекла — войдите в аккаунт заново"
            403 -> "SoundCloud отклонил изменение профиля (HTTP 403)"
            422 -> "SoundCloud отклонил данные профиля (HTTP 422)$suffix"
            else -> "HTTP $code$suffix"
        }
    }

    // ---------- РЈРїСЂР°РІР»РµРЅРёРµ РїР»РµР№Р»РёСЃС‚Р°РјРё ----------

    private suspend fun mobilePlaylistRequest(
        endpoint: String,
        builder: Request.Builder.() -> Unit = {}
    ): Request.Builder = authedRequest(endpoint) {
        header("User-Agent", apiUserAgent())
        header("App-Version", "330120")
        header("UDID", authManager.getDeviceId())
        // api-mobile проверяет веб-сессию; OAuth-токена одного мало.
        authManager.getCookieHeader(endpoint)?.let { header("Cookie", it) }
        builder()
    }

    private fun mobilePlaylistPayload(
        title: String,
        description: String,
        genre: String,
        tagList: String,
        isPublic: Boolean,
        trackIds: List<Long>
    ): JSONObject = JSONObject().apply {
        put(
            "track_urns",
            JSONArray().apply {
                trackIds.distinct().filter { it > 0L }
                    .forEach { put("soundcloud:tracks:$it") }
            }
        )
        put("title", title)
        put("description", description)
        put("genre", genre)
        put("public", isPublic)
        put("tag_list", tagList)
    }

    private fun playlistEndpoint(playlistId: Long): String =
        "https://api-mobile.soundcloud.com/playlists/soundcloud:playlists:$playlistId"

    private fun playlistIdFromJson(json: JSONObject): Long {
        val direct = json.optLong("id", 0L)
        if (direct > 0L) return direct

        val urn = json.optString("urn", "")
        val fromUrn = urn.substringAfterLast(':').toLongOrNull() ?: 0L
        if (fromUrn > 0L) return fromUrn

        val nested = json.optJSONObject("playlist")
        return if (nested != null && nested !== json) playlistIdFromJson(nested) else 0L
    }

    /**
     * РЎРѕР·РґР°РЅРёРµ РїР»РµР№Р»РёСЃС‚Р° РЅР° SoundCloud.
     *
     * РћС„РёС†РёР°Р»СЊРЅС‹Р№ api.soundcloud.com РѕС‚РґР°С‘С‚ `403 Quota exceeded`, Р° Р·Р°РїРёСЃСЊ
     * С‡РµСЂРµР· api-v2 Р·Р°РєСЂС‹С‚Р° DataDome. РћС„РёС†РёР°Р»СЊРЅС‹Р№ РєР»РёРµРЅС‚ СЃРѕР·РґР°С‘С‚ РїР»РµР№Р»РёСЃС‚С‹
     * С‡РµСЂРµР· РјРѕР±РёР»СЊРЅСѓСЋ СЂСѓС‡РєСѓ, РѕРЅР° СЂР°Р±РѕС‚Р°РµС‚ СЃ РѕР±С‹С‡РЅС‹Рј OAuth-С‚РѕРєРµРЅРѕРј.
     */
    suspend fun createPlaylist(
        title: String,
        description: String = "",
        isPublic: Boolean = false,
        genre: String = "",
        tagList: String = ""
    ): Result<PlaylistInfo> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject()
                .put(
                    "playlist",
                    JSONObject()
                        .put("title", title)
                        .put("public", isPublic)
                )
                .put("track_urns", JSONArray())
                .toString()
            val req = mobilePlaylistRequest("https://api-mobile.soundcloud.com/playlists")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            val created = client.newCall(req).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                Log.d(
                    "ZYRO_PLAYER",
                    "createPlaylist HTTP ${resp.code} body=${responseBody.take(500)}"
                )
                if (!resp.isSuccessful) {
                    if (resp.code == 401) authManager.clearAuth()
                    return@withContext Result.Error(playlistHttpError(resp.code, responseBody))
                }
                val json = runCatching {
                    JSONObject(
                        responseBody.ifBlank {
                            return@withContext Result.Error("Empty response")
                        }
                    )
                }.getOrElse {
                    return@withContext Result.Error("Failed to parse playlist response", it)
                }
                val playlistJson = json.optJSONObject("playlist") ?: json
                val playlistId = playlistIdFromJson(playlistJson)
                if (playlistId <= 0L) {
                    return@withContext Result.Error("Failed to parse playlist id")
                }
                // РњРѕР±РёР»СЊРЅС‹Р№ РѕС‚РІРµС‚ РєРѕСЂРѕС‡Рµ v2-РѕР±СЉРµРєС‚Р°, РїРѕСЌС‚РѕРјСѓ РґРѕР±РёСЂР°РµРј РїРѕР»РЅС‹Р№.
                val full = fetchPlaylistJson(playlistId) ?: playlistJson
                val playlist = parsePlaylistJson(full, fallbackTitle = title)
                if (playlist.permalinkUrl.isBlank() && playlist.id <= 0L) {
                    Result.Error("Failed to parse playlist")
                } else {
                    Result.Success(playlist)
                }
            }
            // РњРѕР±РёР»СЊРЅС‹Р№ create РїСЂРёРЅРёРјР°РµС‚ С‚РѕР»СЊРєРѕ РЅР°Р·РІР°РЅРёРµ Рё РїСЂРёРІР°С‚РЅРѕСЃС‚СЊ,
            // РѕСЃС‚Р°Р»СЊРЅС‹Рµ РїРѕР»СЏ РґРѕРїРёСЃС‹РІР°РµРј РѕС‚РґРµР»СЊРЅС‹Рј РјРѕР±РёР»СЊРЅС‹Рј update.
            if (created is Result.Success &&
                (description.isNotBlank() || genre.isNotBlank() || tagList.isNotBlank())
            ) {
                val updated = updatePlaylist(
                    playlistId = created.data.id,
                    description = description,
                    genre = genre,
                    tagList = tagList
                )
                if (updated is Result.Success) {
                    return@withContext updated
                }
                return@withContext Result.Success(
                    created.data.copy(
                        description = description,
                        genre = genre,
                        tagList = tagList
                    )
                )
            }
            created
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "createPlaylist error", e)
            Result.Error("Failed to create playlist", e)
        }
    }

    /**
     * РћР±РЅРѕРІР»РµРЅРёРµ РїР»РµР№Р»РёСЃС‚Р° РЅР° SoundCloud.
     *
     * РњРѕР±РёР»СЊРЅР°СЏ СЂСѓС‡РєР° РїСЂРёРЅРёРјР°РµС‚ РїРѕР»РЅС‹Р№ РѕР±СЉРµРєС‚: С‚СЂРµРєРё Рё РјРµС‚Р°РґР°РЅРЅС‹Рµ. РџРѕСЌС‚РѕРјСѓ
     * СЃРЅР°С‡Р°Р»Р° С‡РёС‚Р°РµРј С‚РµРєСѓС‰РёР№ РїР»РµР№Р»РёСЃС‚, С‡С‚РѕР±С‹ СЂРµРґР°РєС‚РёСЂРѕРІР°РЅРёРµ РЅР°Р·РІР°РЅРёСЏ РёР»Рё
     * РѕРїРёСЃР°РЅРёСЏ РЅРµ СЃС‚С‘СЂР»Рѕ СЃРїРёСЃРѕРє С‚СЂРµРєРѕРІ.
     */
    suspend fun updatePlaylist(
        playlistId: Long,
        title: String? = null,
        description: String? = null,
        isPublic: Boolean? = null,
        genre: String? = null,
        tagList: String? = null
    ): Result<PlaylistInfo> = withContext(Dispatchers.IO) {
        try {
            if (title == null && description == null && isPublic == null &&
                genre == null && tagList == null
            ) {
                return@withContext Result.Error("Empty playlist update")
            }
            val current = fetchPlaylistJson(playlistId)
                ?: return@withContext Result.Error("Failed to load playlist")
            val payload = mobilePlaylistPayload(
                title = title ?: current.optString("title", ""),
                description = description ?: current.optString("description", ""),
                genre = genre ?: current.optString("genre", ""),
                tagList = tagList ?: current.optString("tag_list", ""),
                isPublic = isPublic ?: (current.optString("sharing", "private") == "public"),
                trackIds = extractTrackIds(current)
            ).toString()
            val req = mobilePlaylistRequest(playlistEndpoint(playlistId))
                .put(payload.toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                Log.d(
                    "ZYRO_PLAYER",
                    "updatePlaylist HTTP ${resp.code} body=${responseBody.take(500)}"
                )
                if (!resp.isSuccessful) {
                    if (resp.code == 401) authManager.clearAuth()
                    return@withContext Result.Error(playlistHttpError(resp.code, responseBody))
                }
                val json = JSONObject(
                    responseBody.ifBlank {
                        return@withContext Result.Error("Empty response")
                    }
                )
                val playlistJson = json.optJSONObject("playlist") ?: json
                // РњРѕР±РёР»СЊРЅС‹Р№ РѕС‚РІРµС‚ РјРѕР¶РµС‚ Р±С‹С‚СЊ РЅРµРїРѕР»РЅС‹Рј вЂ” СЃРѕС…СЂР°РЅСЏРµРј РЅРµРґРѕСЃС‚Р°СЋС‰РёРµ
                // РїРѕР»СЏ РёР· С‚РµРєСѓС‰РµРіРѕ РїР»РµР№Р»РёСЃС‚Р°.
                val merged = JSONObject(current.toString())
                playlistJson.keys().forEach { key ->
                    merged.put(key, playlistJson.get(key))
                }
                val playlist = parsePlaylistJson(
                    merged,
                    fallbackTitle = title ?: current.optString("title", "")
                )
                if (playlist.permalinkUrl.isBlank() && playlist.id <= 0L) {
                    Result.Error("Failed to parse playlist")
                } else {
                    Result.Success(playlist)
                }
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "updatePlaylist error", e)
            Result.Error("Failed to update playlist", e)
        }
    }

    /**
     * Р—Р°РіСЂСѓР·РєР° РѕР±Р»РѕР¶РєРё РїР»РµР№Р»РёСЃС‚Р° РІ SoundCloud.
     *
     * РћС„РёС†РёР°Р»СЊРЅС‹Р№ РєР»РёРµРЅС‚ РѕС‚РїСЂР°РІР»СЏРµС‚ JPEG РІ base64 РЅР° РјРѕР±РёР»СЊРЅСѓСЋ СЂСѓС‡РєСѓ:
     * PUT /playlists/soundcloud:playlists:{id}/artwork
     */
    suspend fun uploadPlaylistArtwork(
        playlistId: Long,
        imageBase64: String
    ): Result<PlaylistInfo> = withContext(Dispatchers.IO) {
        try {
            if (imageBase64.isBlank()) {
                return@withContext Result.Error("Empty artwork")
            }
            val payload = JSONObject().put("image_data", imageBase64).toString()
            val req = mobilePlaylistRequest(
                "https://api-mobile.soundcloud.com/playlists/" +
                    "soundcloud:playlists:$playlistId/artwork"
            )
                .put(payload.toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                Log.d(
                    "ZYRO_PLAYER",
                    "uploadPlaylistArtwork playlist=$playlistId HTTP ${resp.code} " +
                        "body=${responseBody.take(300)}"
                )
                if (!resp.isSuccessful) {
                    if (resp.code == 401) authManager.clearAuth()
                    return@withContext Result.Error(playlistHttpError(resp.code, responseBody))
                }
                val responseJson = runCatching { JSONObject(responseBody) }.getOrNull()
                val playlistJson = responseJson?.optJSONObject("playlist") ?: responseJson
                if (playlistJson != null) {
                    val parsed = parsePlaylistJson(playlistJson)
                    if (parsed.permalinkUrl.isNotBlank() || parsed.id > 0L) {
                        return@withContext Result.Success(parsed)
                    }
                }
                val fresh = fetchPlaylistJson(playlistId)
                    ?: return@withContext Result.Error(
                        "Artwork uploaded, but the playlist could not be refreshed"
                    )
                Result.Success(parsePlaylistJson(fresh))
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "uploadPlaylistArtwork error", e)
            Result.Error("Failed to upload playlist artwork", e)
        }
    }

    /**
     * РЈРґР°Р»РµРЅРёРµ РїР»РµР№Р»РёСЃС‚Р° СЃ SoundCloud.
     * РћС„РёС†РёР°Р»СЊРЅС‹Р№ РєР»РёРµРЅС‚ СѓРґР°Р»СЏРµС‚ С‡РµСЂРµР· v2: DELETE /playlists/{playlistId}.
     */
    suspend fun deletePlaylist(playlistId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api-v2.soundcloud.com/playlists/$playlistId"
            val req = authedRequest(url) {
                authManager.getCookieHeader()?.let { header("Cookie", it) }
            }.delete().build()
            client.newCall(req).execute().use { resp ->
                val responseBody = resp.body?.string().orEmpty()
                Log.d(
                    "ZYRO_PLAYER",
                    "deletePlaylist HTTP ${resp.code} body=${responseBody.take(500)}"
                )
                if (resp.isSuccessful) {
                    Result.Success(Unit)
                } else {
                    if (resp.code == 401) authManager.clearAuth()
                    Result.Error(playlistHttpError(resp.code, responseBody))
                }
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "deletePlaylist error", e)
            Result.Error("Failed to delete playlist", e)
        }
    }

    /**
     * Р”РѕР±Р°РІР»РµРЅРёРµ РѕРґРЅРѕРіРѕ С‚СЂРµРєР° РІ РїР»РµР№Р»РёСЃС‚ РЅР° SoundCloud.
     */
    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long): Result<Unit> =
        addTracksToPlaylist(playlistId, listOf(trackId))

    /**
     * Р”РѕР±Р°РІР»РµРЅРёРµ РЅРµСЃРєРѕР»СЊРєРёС… С‚СЂРµРєРѕРІ РІ РїР»РµР№Р»РёСЃС‚ РЅР° SoundCloud.
     * SoundCloud API РЅРµ РёРјРµРµС‚ РѕС‚РґРµР»СЊРЅРѕРіРѕ РјРµС‚РѕРґР° РґР»СЏ РґРѕР±Р°РІР»РµРЅРёСЏ С‚СЂРµРєР° вЂ”
     * РЅСѓР¶РЅРѕ РѕС‚РїСЂР°РІРёС‚СЊ РїРѕР»РЅС‹Р№ СЃРїРёСЃРѕРє С‚СЂРµРєРѕРІ С‡РµСЂРµР· PUT /playlists/{id}.
     */
    suspend fun addTracksToPlaylist(
        playlistId: Long,
        trackIds: List<Long>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        playlistMutationMutex.withLock {
            try {
                val newIds = trackIds.filter { it > 0L }.distinct()
                if (newIds.isEmpty()) return@withLock Result.Success(Unit)

                val current = fetchPlaylistJson(playlistId)
                    ?: return@withLock Result.Error("Failed to load playlist")
                val currentIds = extractTrackIds(current)
                val merged = (currentIds + newIds.filterNot { it in currentIds }).distinct()
                Log.d(
                    "ZYRO_PLAYER",
                    "addTracksToPlaylist playlist=$playlistId current=${currentIds.size} " +
                        "requested=${newIds.size} merged=${merged.size}"
                )

                if (merged.size == currentIds.size) {
                    return@withLock Result.Success(Unit)
                }
                val result = putPlaylistTracks(playlistId, merged, current)
                if (result is Result.Success) {
                    rememberPlaylistTracks(playlistId, current, merged)
                    clearPlaylistTracksCache(current.optString("permalink_url", ""))
                }
                result
            } catch (e: Exception) {
                Log.e("ZYRO_PLAYER", "addTracksToPlaylist error", e)
                Result.Error("Failed to add tracks to playlist", e)
            }
        }
    }

    /**
     * РЈРґР°Р»РµРЅРёРµ С‚СЂРµРєР° РёР· РїР»РµР№Р»РёСЃС‚Р° РЅР° SoundCloud.
     */
    suspend fun removeTrackFromPlaylist(
        playlistId: Long,
        trackId: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        playlistMutationMutex.withLock {
            try {
                val current = fetchPlaylistJson(playlistId)
                    ?: return@withLock Result.Error("Failed to load playlist")
                val currentIds = extractTrackIds(current)
                val remaining = currentIds.filterNot { it == trackId }
                if (remaining.size == currentIds.size) {
                    return@withLock Result.Success(Unit)
                }
                val result = putPlaylistTracks(playlistId, remaining, current)
                if (result is Result.Success) {
                    rememberPlaylistTracks(playlistId, current, remaining)
                    clearPlaylistTracksCache(current.optString("permalink_url", ""))
                }
                result
            } catch (e: Exception) {
                Log.e("ZYRO_PLAYER", "removeTrackFromPlaylist error", e)
                Result.Error("Failed to remove track from playlist", e)
            }
        }
    }

    /** Свежие метаданные плейлиста для pull-to-refresh на его вкладке. */
    suspend fun getPlaylistInfo(playlistId: Long): PlaylistInfo? = withContext(Dispatchers.IO) {
        try {
            fetchPlaylistJson(playlistId, useCache = false)?.let { parsePlaylistJson(it) }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "getPlaylistInfo error", e)
            null
        }
    }

    private suspend fun fetchPlaylistJson(
        playlistId: Long,
        useCache: Boolean = true
    ): JSONObject? {
        if (useCache) {
            synchronized(playlistJsonCache) {
                val cached = playlistJsonCache[playlistId]
                if (cached != null &&
                    System.currentTimeMillis() - cached.timestamp < PLAYLIST_JSON_TTL_MS
                ) {
                    return cached.json
                }
            }
        }
        val url = "https://api-v2.soundcloud.com/playlists/$playlistId"
        val req = authedRequest(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Log.d(
                "ZYRO_PLAYER",
                "fetchPlaylistJson playlist=$playlistId HTTP ${resp.code} " +
                    "body=${body.take(300)}"
            )
            if (!resp.isSuccessful) {
                if (resp.code == 401) authManager.clearAuth()
                return null
            }
            if (body.isBlank()) return null
            return JSONObject(body).also { json ->
                synchronized(playlistJsonCache) {
                    playlistJsonCache[playlistId] =
                        CachedPlaylistJson(json, System.currentTimeMillis())
                }
            }
        }
    }

    private fun rememberPlaylistTracks(
        playlistId: Long,
        playlistJson: JSONObject,
        trackIds: List<Long>
    ) {
        playlistJson.put("tracks", JSONArray().apply { trackIds.forEach { put(it) } })
        playlistJson.put("track_count", trackIds.size)
        synchronized(playlistJsonCache) {
            playlistJsonCache[playlistId] =
                CachedPlaylistJson(playlistJson, System.currentTimeMillis())
        }
    }

    private fun extractTrackIds(json: JSONObject): List<Long> {
        val tracks = json.optJSONArray("tracks") ?: return emptyList()
        return buildList {
            for (i in 0 until tracks.length()) {
                val id = when (val item = tracks.opt(i)) {
                    is JSONObject -> item.optLong("id", 0L)
                    is Number -> item.toLong()
                    else -> 0L
                }
                if (id > 0L) add(id)
            }
        }
    }

    private suspend fun putPlaylistTracks(
        playlistId: Long,
        trackIds: List<Long>,
        current: JSONObject? = null
    ): Result<Unit> {
        val playlistJson = current ?: fetchPlaylistJson(playlistId)
            ?: return Result.Error("Failed to load playlist")
        val payload = mobilePlaylistPayload(
            title = playlistJson.optString("title", ""),
            description = playlistJson.optString("description", ""),
            genre = playlistJson.optString("genre", ""),
            tagList = playlistJson.optString("tag_list", ""),
            isPublic = playlistJson.optString("sharing", "private") == "public",
            trackIds = trackIds
        ).toString()
        val req = mobilePlaylistRequest(playlistEndpoint(playlistId))
            .put(payload.toRequestBody(jsonMediaType))
            .build()
        client.newCall(req).execute().use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            Log.d(
                "ZYRO_PLAYER",
                "putPlaylistTracks playlist=$playlistId tracks=${trackIds.size} " +
                    "HTTP ${resp.code} body=${responseBody.take(500)}"
            )
            return if (resp.isSuccessful) {
                Result.Success(Unit)
            } else {
                if (resp.code == 401) authManager.clearAuth()
                Result.Error(playlistHttpError(resp.code, responseBody))
            }
        }
    }

    private fun parsePlaylistJson(json: JSONObject, fallbackTitle: String = ""): PlaylistInfo {
        val objectJson = json.optJSONObject("playlist") ?: json
        val embedded = objectJson.optJSONObject("_embedded")
        val userObj = objectJson.optJSONObject("user") ?: embedded?.optJSONObject("user")
        return PlaylistInfo(
            id = playlistIdFromJson(objectJson),
            permalinkUrl = objectJson.optString("permalink_url", ""),
            title = objectJson.optString("title", fallbackTitle),
            user = userObj?.optString("username", "") ?: "",
            artworkUrl = baseRepo.getBestArtworkUrl(objectJson.optString("artwork_url", ""))
                .ifBlank {
                    baseRepo.getBestArtworkUrl(
                        objectJson.optString("calculated_artwork_url", "")
                    )
                }
                .ifBlank { baseRepo.getBestArtworkUrl(baseRepo.getFirstTrackArtwork(objectJson)) },
            trackCount = objectJson.optInt("track_count", 0),
            description = objectJson.optString("description", ""),
            isLiked = objectJson.optBoolean("user_favorite", false),
            genre = objectJson.optString("genre", ""),
            tagList = objectJson.optString("tag_list", ""),
            isPublic = objectJson.optString("sharing", "private") == "public",
            durationMs = objectJson.optLong("duration", 0L),
            createdAtMs = parsePlaylistDate(objectJson)
        )
    }

    private fun parsePlaylistDate(json: JSONObject): Long =
        parseSoundCloudDate(json.optString("created_at", ""))

    private fun playlistHttpError(code: Int, body: String?): String {
        val message = runCatching {
            JSONObject(body.orEmpty()).optString("error", "")
        }.getOrNull()
        return if (message.isNullOrBlank()) "HTTP $code" else "HTTP $code: $message"
    }
}
