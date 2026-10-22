package com.zyro.player.data.auth

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Поддержка сессии SoundCloud, упрощённая версия SessionManager из kittytune.
 *
 * Что делает:
 *  - держит «призрачный» (невидимый) WebView, чтобы SoundCloud сам обновлял cookies;
 *  - собирает oauth_token / refresh_token из cookies по всем доменам SoundCloud;
 *  - обновляет access_token через refresh_token (api-auth.soundcloud.com/oauth/token);
 *  - раз в 20 минут делает keep-alive;
 *  - перехватывает актуальный client_id из сетевых запросов;
 *  - показывает плашку и WebView для прохождения капчи DataDome, если
 *    SoundCloud всё равно отвечает `403`.
 */
@SuppressLint("StaticFieldLeak")
object SessionManager {

    private const val TAG = "SC_SESSION"
    private const val REFRESH_INTERVAL = 20 * 60 * 1000L
    private const val SESSION_REFRESH_TIMEOUT_MS = 12_000L

    private const val CONFIG_PREFS = "zyro_sc_config"
    private const val KEY_DYNAMIC_CLIENT_ID = "dynamic_client_id"

    private val SESSION_COOKIE_URLS = listOf(
        "https://soundcloud.com",
        "https://m.soundcloud.com",
        "https://api-v2.soundcloud.com",
        "https://api-mobile.soundcloud.com",
        "https://api-auth.soundcloud.com",
        "https://secure.soundcloud.com"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var appContext: Context? = null
    private var ghostWebView: WebView? = null
    private var keepAliveJob: Job? = null

    private val refreshLock = Any()
    private var pendingRefresh: CompletableDeferred<String?>? = null

    private val apiRefreshLock = Any()
    private var pendingApiRefresh: CompletableDeferred<String?>? = null

    /** client_id, подсмотренный в реальных запросах SoundCloud. */
    @Volatile private var capturedClientId: String? = null

    // ------------------------------------------------------------- капча DataDome

    private val _captchaRequired = MutableStateFlow(false)

    /** `true`, когда SoundCloud ответил `403` и нужна ручная проверка. */
    val captchaRequired: StateFlow<Boolean> = _captchaRequired.asStateFlow()

    private val _captchaSolved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Событие после прохождения проверки: можно повторить неудавшееся действие. */
    val captchaSolved: SharedFlow<Unit> = _captchaSolved.asSharedFlow()

    /** Ссылка на challenge из тела `403` (если сервер её прислал). */
    @Volatile private var pendingCaptchaUrl: String? = null

    /** Пока открыт видимый WebView, cookies не должны подменять OAuth-токен. */
    @Volatile private var captchaWebViewActive = false

    /** Последняя страница challenge — с неё берём самый свежий `datadome`. */
    @Volatile private var lastCaptchaPageUrl: String? = null

    private val authHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // ------------------------------------------------------------------ client_id

    fun getCapturedClientId(): String? = capturedClientId

    private fun loadCapturedClientId(context: Context) {
        if (capturedClientId != null) return
        capturedClientId = context.applicationContext
            .getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DYNAMIC_CLIENT_ID, null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun saveCapturedClientId(context: Context, clientId: String) {
        if (clientId.isBlank() || clientId == capturedClientId) return
        capturedClientId = clientId
        context.applicationContext
            .getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DYNAMIC_CLIENT_ID, clientId)
            .apply()
        Log.d(TAG, "Обновлён client_id: $clientId")
    }

    // ------------------------------------------------------------------ ghost WebView

    /**
     * Создаёт (при необходимости) призрачный WebView и включает keep-alive.
     * Безопасно вызывать с любого потока.
     */
    fun ensureGhost(context: Context) {
        val safeContext = context.applicationContext
        appContext = safeContext
        loadCapturedClientId(safeContext)

        scope.launch {
            if (ghostWebView == null) {
                try {
                    ghostWebView = WebView(safeContext).also { setupWebView(it, safeContext) }
                } catch (e: Exception) {
                    Log.e(TAG, "Не удалось создать ghost WebView", e)
                    return@launch
                }
            }
            startKeepAliveCycle(safeContext)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(
        webView: WebView,
        context: Context,
        onPageFinished: (() -> Unit)? = null
    ) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = ScConfig.USER_AGENT
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                if (url.contains("client_id=")) {
                    runCatching { Uri.parse(url).getQueryParameter("client_id") }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { saveCapturedClientId(context, it) }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Во время прохождения капчи не принимаем tokens из cookies:
                // официальный refresh_token остаётся источником истины, а нам
                // нужен только свежий datadome.
                harvestStoredSession(context, completePending = !captchaWebViewActive)
                if (captchaWebViewActive) {
                    if (!url.isNullOrBlank()) lastCaptchaPageUrl = url
                    harvestDataDomeCookie()?.let(::spreadDataDomeCookie)
                }
                onPageFinished?.invoke()
            }
        }
    }

    private fun startKeepAliveCycle(context: Context) {
        if (keepAliveJob?.isActive == true) return

        keepAliveJob = scope.launch {
            while (true) {
                delay(REFRESH_INTERVAL)
                requestSessionRefresh(context, force = false)
            }
        }
    }

    // ------------------------------------------------------------------ капча DataDome

    /**
     * Запоминает ссылку на challenge из тела `403`, но не показывает плашку:
     * ответ мог быть промежуточным (например, mobile-ручка отказала, а
     * v2-фолбэк сработал). Плашку включает вызывающий код по итоговой ошибке.
     */
    fun noteCaptchaChallenge(body: String?) {
        extractDataDomeCaptchaUrl(body)?.let { pendingCaptchaUrl = it }
    }

    /**
     * Запрашивает показ плашки о проверке. Вызывается, когда итоговое действие
     * всё-таки завершилось `403` после всех повторов.
     */
    fun requestCaptchaCheck() {
        lastCaptchaPageUrl = null
        if (!_captchaRequired.value) {
            Log.d(TAG, "SoundCloud отклонил запрос (403) — нужна проверка DataDome")
        }
        _captchaRequired.value = true
    }

    /** Скрывает плашку, не открывая проверку. */
    fun dismissCaptchaPrompt() {
        pendingCaptchaUrl = null
        lastCaptchaPageUrl = null
        _captchaRequired.value = false
    }

    /**
     * Настраивает видимый WebView и открывает в нём challenge.
     * `CookieManager` общий, поэтому cookies сразу видны сетевым запросам.
     */
    fun attachCaptchaWebView(
        webView: WebView,
        context: Context,
        onPageFinished: (() -> Unit)? = null
    ) {
        val safeContext = context.applicationContext
        appContext = safeContext
        captchaWebViewActive = true
        setupWebView(webView, safeContext, onPageFinished)

        val challengeUrl = pendingCaptchaUrl
        if (!challengeUrl.isNullOrBlank()) {
            webView.loadUrl(challengeUrl, mapOf("Referer" to "https://soundcloud.com/"))
        } else {
            // DataDome не прислал ссылку — открываем ленту, проверка появится сама.
            webView.loadUrl(buildSessionUrl(forceRefresh = true))
        }
    }

    /**
     * Пользователь нажал «Я прошёл проверку»: забираем свежий `datadome`,
     * раскладываем его по всем API-хостам и перезапускаем ghost-сессию.
     */
    fun completeCaptchaChallenge(context: Context) {
        val safeContext = context.applicationContext
        appContext = safeContext
        captchaWebViewActive = false

        harvestDataDomeCookie()?.let(::spreadDataDomeCookie)
        harvestStoredSession(safeContext)
        CookieManager.getInstance().flush()

        pendingCaptchaUrl = null
        lastCaptchaPageUrl = null
        _captchaRequired.value = false
        reloadSession(forceRefresh = true)
        _captchaSolved.tryEmit(Unit)
        Log.d(TAG, "Проверка DataDome завершена, cookies обновлены")
    }

    /**
     * Достаёт ссылку на капчу из тела `403`. DataDome кладёт её в поле `url`
     * (иногда с экранированием `\/`), например `geo.captcha-delivery.com`.
     */
    fun extractDataDomeCaptchaUrl(body: String?): String? {
        if (body.isNullOrBlank()) return null

        val fromJson = runCatching { JSONObject(body).optString("url") }.getOrNull()
        return fromJson
            ?.takeIf { it.contains("captcha-delivery.com") }
            ?: Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.takeIf { it.contains("captcha-delivery.com") }
    }

    /** Один `datadome`, найденный в cookies любого домена SoundCloud. */
    private fun harvestDataDomeCookie(preferredUrl: String? = lastCaptchaPageUrl): String? {
        val manager = CookieManager.getInstance()
        val urls = buildList {
            if (!preferredUrl.isNullOrBlank()) add(preferredUrl)
            addAll(SESSION_COOKIE_URLS)
        }.distinct()
        for (url in urls) {
            val cookies = manager.getCookie(url) ?: continue
            val value = extractValue(cookies, "datadome") ?: continue
            return "datadome=$value"
        }
        return null
    }

    /**
     * DataDome проверяет cookie хоста запроса, поэтому один и тот же `datadome`
     * кладём на все домены SoundCloud — иначе api-v2 останется со старым.
     */
    private fun spreadDataDomeCookie(cookie: String) {
        val manager = CookieManager.getInstance()
        SESSION_COOKIE_URLS.forEach { manager.setCookie(it, cookie) }
        manager.flush()
    }

    // ------------------------------------------------------------------ cookies

    /**
     * Читает cookies со всех доменов SoundCloud и обновляет токены в [TokenManager].
     * Возвращает актуальный access_token, если он найден.
     */
    fun harvestStoredSession(context: Context, completePending: Boolean = false): String? {
        val safeContext = context.applicationContext
        appContext = safeContext
        loadCapturedClientId(safeContext)

        var token: String? = null
        SESSION_COOKIE_URLS.forEach { url ->
            if (token == null) {
                token = harvestCookie(url, safeContext, completePending)
            }
        }
        return token
    }

    private fun harvestCookie(url: String?, context: Context, completePending: Boolean): String? {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url) ?: return null
        if (!cookies.contains("oauth_token")) return null

        val newToken = extractValue(cookies, "oauth_token") ?: return null
        val refreshToken = extractValue(cookies, "refresh_token")
        val tokenManager = TokenManager(context)
        val previousToken = tokenManager.getAccessToken()
        val previousRefreshToken = tokenManager.getRefreshToken()

        // Если есть официальный refresh_token, источник истины — OAuth-refresh
        // через /oauth/token. Cookie `oauth_token` с m.soundcloud.com может быть
        // выдан веб-клиенту и не подходить к официальному client_id; приняв его,
        // приложение получало 403 на всех запросах до повторного логина.
        // Cookie-токен принимаем только когда refresh_token нет (legacy) или
        // когда ghost WebView явно завершил аварийное обновление сессии.
        val hasOfficialRefreshToken = !previousRefreshToken.isNullOrEmpty()
        val shouldAccept = previousToken.isNullOrEmpty() ||
            completePending ||
            (!hasOfficialRefreshToken && tokenManager.shouldRefreshAccessToken(bufferMs = 0L))

        if (newToken != previousToken && shouldAccept) {
            tokenManager.saveTokens(newToken, refreshToken ?: previousRefreshToken)
        } else if (!refreshToken.isNullOrEmpty() &&
            refreshToken != previousRefreshToken &&
            !previousToken.isNullOrEmpty() &&
            (!hasOfficialRefreshToken || completePending)
        ) {
            // Не затираем официальный refresh_token веб-токеном из cookies.
            tokenManager.saveTokens(previousToken, refreshToken)
        } else if (completePending) {
            tokenManager.markAccessTokenFresh()
        }

        if (completePending || (newToken != previousToken && shouldAccept)) {
            completePendingRefresh(if (shouldAccept) newToken else previousToken)
        }

        cookieManager.flush()
        return if (shouldAccept) newToken else previousToken
    }

    private fun extractValue(cookies: String, key: String): String? =
        cookies.split(";")
            .map { it.trim() }
            .find { it.startsWith("$key=") }
            ?.substringAfter("$key=")
            ?.replace("\"", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ refresh

    fun requestSessionRefresh(context: Context, force: Boolean = false) {
        val safeContext = context.applicationContext
        appContext = safeContext
        val tokenManager = TokenManager(safeContext)
        harvestStoredSession(safeContext)

        val shouldRefreshToken = tokenManager.shouldRefreshAccessToken()
        if (force || shouldRefreshToken) {
            scope.launch {
                val refreshedByApi = if (shouldRefreshToken) {
                    withTimeoutOrNull(SESSION_REFRESH_TIMEOUT_MS) {
                        refreshAccessTokenFromApi(safeContext)
                    }
                } else {
                    null
                }

                if (refreshedByApi == null) {
                    reloadSession(forceRefresh = force || shouldRefreshToken)
                }
            }
        }
    }

    fun reloadSession(forceRefresh: Boolean = false) {
        val webView = ghostWebView
        if (webView == null) {
            appContext?.let { harvestStoredSession(it) }
            return
        }
        scope.launch { webView.loadUrl(buildSessionUrl(forceRefresh)) }
    }

    /**
     * Возвращает валидный access_token: сначала cookies, потом API-refresh,
     * потом перезагрузка ghost WebView.
     */
    suspend fun awaitFreshAccessToken(
        context: Context,
        staleToken: String? = null,
        force: Boolean = false,
        timeoutMs: Long = SESSION_REFRESH_TIMEOUT_MS
    ): String? {
        val safeContext = context.applicationContext
        appContext = safeContext

        val tokenManager = TokenManager(safeContext)
        if (tokenManager.isGuestMode()) return null

        val cookieToken = harvestStoredSession(safeContext)
        val currentToken = cookieToken ?: tokenManager.getAccessToken()

        // Гость/полностью пустая сессия: обновлять нечего, не дёргаем WebView.
        if (currentToken.isNullOrEmpty() && tokenManager.getRefreshToken().isNullOrEmpty()) {
            return null
        }

        if (!force && !currentToken.isNullOrEmpty() && !tokenManager.shouldRefreshAccessToken()) {
            return currentToken
        }

        if (force && !staleToken.isNullOrEmpty() &&
            !cookieToken.isNullOrEmpty() && cookieToken != staleToken
        ) {
            return cookieToken
        }

        val refreshedByApi = withTimeoutOrNull(timeoutMs) {
            refreshAccessTokenFromApi(safeContext)
        }
        // При force-обновлении не отдаём тот же самый токен: если API вернул
        // его без изменений, пробуем поднять новую сессию через ghost WebView.
        if (!refreshedByApi.isNullOrEmpty() &&
            (!force || staleToken.isNullOrEmpty() || refreshedByApi != staleToken)
        ) {
            return refreshedByApi
        }

        val webView = ghostWebView ?: return refreshedByApi ?: currentToken

        val refreshDeferred = getOrCreatePendingRefresh()
        withContext(Dispatchers.Main.immediate) {
            webView.loadUrl(buildSessionUrl(forceRefresh = true))
        }

        val refreshedToken = withTimeoutOrNull(timeoutMs) { refreshDeferred.await() }
        if (refreshedToken == null) clearPendingRefresh(refreshDeferred)

        val harvested = harvestStoredSession(safeContext)
        return harvested?.takeIf {
            !force || staleToken.isNullOrEmpty() || it != staleToken
        } ?: refreshedToken ?: refreshedByApi ?: tokenManager.getAccessToken()
    }

    /**
     * Синхронный вариант [awaitFreshAccessToken] для сетевых интерцепторов,
     * которые не могут вызывать suspend-функции. Всегда запускает принудительное
     * обновление и возвращает новый токен либо null, если сессию обновить не удалось.
     */
    fun refreshSessionBlocking(
        context: Context,
        staleToken: String? = null,
        timeoutMs: Long = SESSION_REFRESH_TIMEOUT_MS
    ): String? = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            awaitFreshAccessToken(
                context = context,
                staleToken = staleToken,
                force = true,
                timeoutMs = timeoutMs
            )
        }
    }

    private suspend fun refreshAccessTokenFromApi(context: Context): String? {
        val safeContext = context.applicationContext
        val tokenManager = TokenManager(safeContext)
        val refreshToken = tokenManager.getRefreshToken() ?: return null

        var shouldStartRefresh = false
        val refreshDeferred = synchronized(apiRefreshLock) {
            pendingApiRefresh?.takeIf { !it.isCompleted } ?: CompletableDeferred<String?>().also {
                pendingApiRefresh = it
                shouldStartRefresh = true
            }
        }

        if (shouldStartRefresh) {
            scope.launch(Dispatchers.IO) {
                val refreshed = executeRefreshTokenRequest(safeContext, refreshToken)
                refreshDeferred.complete(refreshed)
                synchronized(apiRefreshLock) {
                    if (pendingApiRefresh === refreshDeferred) pendingApiRefresh = null
                }
            }
        }

        return refreshDeferred.await()
    }

    private fun executeRefreshTokenRequest(context: Context, refreshToken: String): String? =
        oauthTokenUrls().firstNotNullOfOrNull { tokenUrl ->
            val request = Request.Builder()
                .url(tokenUrl)
                .header("User-Agent", ScConfig.USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "https://soundcloud.com")
                .header("Referer", "https://soundcloud.com/")
                .header("Authorization", ScConfig.OFFICIAL_CLIENT_SIGNATURE)
                .post(
                    FormBody.Builder()
                        .add("grant_type", "refresh_token")
                        .add("client_id", ScConfig.OFFICIAL_CLIENT_ID)
                        .add("refresh_token", refreshToken)
                        .build()
                )
                .build()

            runCatching {
                authHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null

                    val body = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(body) }.getOrNull() ?: return@use null
                    val accessToken = json.optString("access_token", "").cleanOAuthValue()
                        ?: return@use null
                    val nextRefreshToken = json.optString("refresh_token", "").cleanOAuthValue()
                        ?: refreshToken
                    val expiresIn = json.optLong("expires_in", 0L)
                    val scope = json.optString("scope", "").cleanOAuthValue()

                    TokenManager(context).saveTokens(
                        accessToken = accessToken,
                        refreshToken = nextRefreshToken,
                        expiresInSeconds = expiresIn.takeIf { it > 0L },
                        scope = scope
                    )

                    completePendingRefresh(accessToken)
                    accessToken
                }
            }.getOrNull()
        }

    private fun oauthTokenUrls(): List<String> = listOf(
        "${ScConfig.AUTH_API_BASE_URL.trimEnd('/')}/oauth/token",
        "${ScConfig.BASE_URL.trimEnd('/')}/oauth/token"
    ).distinct()

    private fun buildSessionUrl(forceRefresh: Boolean): String {
        val baseUrl = "https://m.soundcloud.com/discover"
        return if (forceRefresh) {
            "$baseUrl?zyro_refresh=${System.currentTimeMillis()}"
        } else {
            baseUrl
        }
    }

    // ------------------------------------------------------------------ pending helpers

    private fun getOrCreatePendingRefresh(): CompletableDeferred<String?> =
        synchronized(refreshLock) {
            pendingRefresh?.takeIf { !it.isCompleted }
                ?: CompletableDeferred<String?>().also { pendingRefresh = it }
        }

    private fun completePendingRefresh(token: String?) {
        val deferred = synchronized(refreshLock) {
            pendingRefresh.also { pendingRefresh = null }
        }
        deferred?.complete(token)
    }

    private fun clearPendingRefresh(deferred: CompletableDeferred<String?>) {
        synchronized(refreshLock) {
            if (pendingRefresh === deferred) pendingRefresh = null
        }
    }

    private fun String?.cleanOAuthValue(): String? = this
        ?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotBlank() && it != "null" }

    /** Полный выход: останавливаем keep-alive и чистим cookies. */
    fun shutdown() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
