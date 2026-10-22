package com.zyro.player.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Фасад над [TokenManager] / [SessionManager] для остального приложения.
 *
 * Токены (access + refresh + срок жизни) живут в [TokenManager],
 * профиль (userId / username) и состояние авторизации — здесь.
 */
class SoundCloudAuthManager(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val tokens = TokenManager(appContext)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        migrateLegacyToken()
        refreshAuthState()
    }

    /** Достаточно валидного access_token. */
    val isLoggedIn: Boolean
        get() = tokens.hasAccessToken()

    val currentUserId: Long
        get() = prefs.getLong(KEY_USER_ID, 0L)

    val currentUsername: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""

    fun getAccessToken(): String? = tokens.getAccessToken()

    fun getRefreshToken(): String? = tokens.getRefreshToken()

    /** Стабильный UDID, который ожидает мобильный API SoundCloud. */
    fun getDeviceId(): String = ScConfig.getOrCreateDeviceId(appContext)

    /**
     * Cookies веб-сессии SoundCloud. Они нужны запросам библиотеки (DataDome
     * проверяет сессию), но с двумя оговорками:
     *
     *  - `oauth_token`/`refresh_token` из cookies не отправляем: после обновления
     *    токена cookies остаются старыми, и сервер видит два разных токена
     *    (свежий в `Authorization` и просроченный в `Cookie`) — это даёт 403;
     *  - cookies с одинаковым именем из разных доменов схлопываем в одну
     *    (datadome должен быть ровно один, иначе DataDome блокирует запрос).
     */
    fun getCookieHeader(url: String? = null): String? {
        return try {
            val manager = CookieManager.getInstance()
            // Для DataDome важен cookie именно того хоста, к которому идёт
            // запрос: браузерный datadome с soundcloud.com не подходит для
            // api-mobile/api-v2 и наоборот. Поэтому точный хост идёт первым,
            // а остальные домены — только как fallback.
            val exactHost = url?.let { rawUrl ->
                runCatching {
                    val uri = Uri.parse(rawUrl)
                    val host = uri.host
                    if (host.isNullOrBlank()) null else "${uri.scheme ?: "https"}://$host"
                }.getOrNull()
            }
            val urls = listOfNotNull(
                exactHost,
                "https://soundcloud.com",
                "https://m.soundcloud.com",
                "https://api-v2.soundcloud.com",
                "https://api-mobile.soundcloud.com"
            ).distinct()

            val byName = LinkedHashMap<String, String>()
            for (cookieUrl in urls) {
                val header = manager.getCookie(cookieUrl) ?: continue
                for (raw in header.split(";")) {
                    val part = raw.trim()
                    if (part.isEmpty()) continue
                    val name = part.substringBefore('=').trim()
                    if (name.isEmpty()) continue
                    if (name == "oauth_token" || name == "refresh_token") continue
                    if (name.equals("datadome", ignoreCase = true)) {
                        // Ровно один datadome, приоритет — у точного хоста.
                        if (!byName.containsKey("datadome")) byName["datadome"] = part
                    } else {
                        byName[name] = part
                    }
                }
            }
            byName.values.joinToString("; ").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Актуальный access_token: при необходимости обновляется через cookies/refresh_token.
     * Основной способ получить токен для API-запросов.
     *
     * [staleToken] позволяет принудительному обновлению понять, что сервер
     * вернул тот же токен, и переключиться на ghost WebView.
     */
    suspend fun getFreshAccessToken(
        force: Boolean = false,
        staleToken: String? = null
    ): String? =
        SessionManager.awaitFreshAccessToken(
            appContext,
            staleToken = staleToken,
            force = force
        ) ?: tokens.getAccessToken()

    /**
     * Блокирующее обновление для OkHttp-интерцептора: интерцепторы не умеют
     * вызывать suspend-функции, а обработка 401/403 должна произойти до того,
     * как ответ уйдёт вызывающему коду.
     */
    fun refreshAccessTokenBlocking(staleToken: String? = null): String? =
        SessionManager.refreshSessionBlocking(appContext, staleToken = staleToken)

    /**
     * Сохраняет полный набор токенов (используется после обмена кода авторизации).
     */
    fun saveTokens(
        accessToken: String,
        refreshToken: String? = null,
        expiresInSeconds: Long? = null,
        scope: String? = null
    ) {
        tokens.saveTokens(accessToken, refreshToken, expiresInSeconds, scope)
        refreshAuthState()
    }

    /**
     * Совместимость со старым API: сохраняем только access_token (+ профиль).
     */
    fun saveAuth(token: String, userId: Long = 0L, username: String = "") {
        tokens.saveTokens(token)
        prefs.edit()
            .putLong(KEY_USER_ID, userId)
            .putString(KEY_USERNAME, username)
            .commit()
        refreshAuthState()
    }

    fun updateUserProfile(userId: Long, username: String) {
        prefs.edit()
            .putLong(KEY_USER_ID, userId)
            .putString(KEY_USERNAME, username)
            .commit()
        refreshAuthState()
    }

    /** Полный выход: чистим токены, cookies и профиль. */
    fun clearAuth() {
        tokens.logout()
        prefs.edit().clear().commit()
        SessionManager.dismissCaptchaPrompt()
        _authState.value = AuthState.Idle
    }

    fun refreshAuthState() {
        _authState.value = if (!tokens.hasAccessToken()) {
            AuthState.Idle
        } else {
            AuthState.Authenticated(
                username = currentUsername.ifBlank { "SoundCloud" },
                userId = currentUserId
            )
        }
    }

    /**
     * Миграция старого формата: раньше access_token лежал прямо в этих prefs.
     */
    private fun migrateLegacyToken() {
        if (tokens.hasAccessToken()) return
        val legacy = prefs.getString(KEY_LEGACY_ACCESS_TOKEN, null)
        if (!legacy.isNullOrBlank()) {
            tokens.saveTokens(legacy)
            prefs.edit().remove(KEY_LEGACY_ACCESS_TOKEN).commit()
        }
    }

    companion object {
        private const val PREFS_NAME = "sc_auth_prefs"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_LEGACY_ACCESS_TOKEN = "access_token"
    }

    sealed class AuthState {
        data object Idle : AuthState()
        data object Loading : AuthState()
        data class Authenticated(val username: String, val userId: Long) : AuthState()
        data class Error(val message: String) : AuthState()
    }
}
