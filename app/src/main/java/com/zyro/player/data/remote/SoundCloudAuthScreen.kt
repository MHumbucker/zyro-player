package com.zyro.player.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyro.player.data.auth.AuthFlowManager
import com.zyro.player.data.auth.PkceHelper
import com.zyro.player.data.auth.ScConfig
import com.zyro.player.data.auth.SessionManager
import com.zyro.player.data.auth.TokenManager
import com.zyro.player.ui.components.ZyroAccent
import com.zyro.player.ui.components.ZyroBackground
import com.zyro.player.ui.components.ZyroError
import com.zyro.player.ui.components.ZyroTextPrimary
import com.zyro.player.ui.components.ZyroTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "ZYRO_AUTH"

/**
 * Авторизация SoundCloud через официальный OAuth (Auth Code + PKCE).
 *
 * Экран ничего не парсит: он открывает Custom Tab с secure.soundcloud.com/web-auth,
 * а код авторизации прилетает обратно в MainActivity по redirect URI `sc://auth`.
 */
@Composable
fun SoundCloudAuthScreen(
    onAuthSuccess: () -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val tokenManager = remember { TokenManager(context) }
    val pkceVerifier = rememberSaveable { PkceHelper.generateVerifier() }
    val pkceChallenge = remember(pkceVerifier) { PkceHelper.generateChallenge(pkceVerifier) }
    val deviceId = remember { ScConfig.getOrCreateDeviceId(context) }
    val authUrl = remember(pkceChallenge, deviceId) {
        buildAuthUrl(
            clientId = ScConfig.OFFICIAL_CLIENT_ID,
            deviceId = deviceId,
            codeChallenge = pkceChallenge
        )
    }

    var statusText by remember { mutableStateOf("Открываем SoundCloud…") }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var hasLaunchedBrowser by rememberSaveable { mutableStateOf(false) }
    var lastHandledCode by remember { mutableStateOf<String?>(null) }

    val authCode by AuthFlowManager.authCode.collectAsState()

    LaunchedEffect(authUrl) {
        if (!hasLaunchedBrowser) {
            hasLaunchedBrowser = true
            launchSoundCloudAuth(context, authUrl)
        }
    }

    LaunchedEffect(authCode) {
        val code = authCode ?: return@LaunchedEffect
        if (code == lastHandledCode) return@LaunchedEffect
        lastHandledCode = code

        isLoading = true
        errorText = null
        statusText = "Завершаем вход…"

        val success = withContext(Dispatchers.IO) {
            exchangeCodeForTokens(
                code = code,
                codeVerifier = pkceVerifier,
                clientId = ScConfig.OFFICIAL_CLIENT_ID,
                tokenManager = tokenManager
            )
        }

        AuthFlowManager.clearAuthCode()
        isLoading = false

        if (success) {
            statusText = "Вход выполнен"
            // Поднимаем сессию: ghost WebView + cookies + client_id
            SessionManager.ensureGhost(context)
            SessionManager.requestSessionRefresh(context, force = true)
            onAuthSuccess()
        } else {
            statusText = "Не удалось войти"
            errorText = "SoundCloud не принял код авторизации. Попробуйте ещё раз."
        }
    }

    BackHandler { onBackClick() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZyroBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = ZyroAccent, modifier = Modifier.size(42.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Вход в SoundCloud",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = ZyroTextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = errorText ?: statusText,
            fontSize = 14.sp,
            color = if (errorText != null) ZyroError else ZyroTextSecondary,
            textAlign = TextAlign.Center
        )

        if (!isLoading) {
            Spacer(modifier = Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(25.dp))
                    .background(ZyroAccent)
                    .clickable {
                        errorText = null
                        statusText = "Открываем SoundCloud…"
                        launchSoundCloudAuth(context, authUrl)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (errorText != null) "Попробовать снова" else "Открыть браузер",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Вход выполняется в браузере. Если он не открылся, нажмите кнопку выше.",
            fontSize = 12.sp,
            color = ZyroTextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable { onBackClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Назад",
                color = ZyroTextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Собирает auth URL по образцу официального приложения SoundCloud.
 */
private fun buildAuthUrl(
    clientId: String,
    deviceId: String,
    codeChallenge: String
): String {
    val locale = Locale.getDefault().language

    return buildString {
        append("https://secure.soundcloud.com/").append("web-auth?")
        append("client_id=").append(Uri.encode(clientId))
        append("&app_id=").append(ScConfig.OFFICIAL_APP_ID)
        append("&device_id=").append(Uri.encode(deviceId))
        append("&start_view=").append(Uri.encode("sign_in"))
        append("&redirect_uri=").append(Uri.encode(ScConfig.REDIRECT_URI))
        append("&response_type=code")
        append("&code_challenge=").append(Uri.encode(codeChallenge))
        append("&code_challenge_method=S256")
        append("&ui_evo=true")
        append("&stand_alone=true")
        append("&tracking=local")
        append("&show_confirmation=true")
        append("&theme=dark")
        append("&locale=").append(Uri.encode(locale))
        append("&sc_tracking_anonymous_id=").append(Uri.encode(deviceId))
    }
}

private fun launchSoundCloudAuth(context: Context, authUrl: String) {
    val uri = Uri.parse(authUrl)
    try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
            .launchUrl(context, uri)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Нет Custom Tabs, падаем в ACTION_VIEW", e)
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e2: Exception) {
            Log.e(TAG, "Не удалось открыть браузер", e2)
        }
    }
}

private val authHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
}

/**
 * Меняет authorization_code на access/refresh токены и раскладывает cookies
 * по доменам SoundCloud (нужно, чтобы заработали запросы библиотеки).
 */
private fun exchangeCodeForTokens(
    code: String,
    codeVerifier: String,
    clientId: String,
    tokenManager: TokenManager
): Boolean {
    val tokenUrls = listOf(
        "${ScConfig.AUTH_API_BASE_URL.trimEnd('/')}/oauth/token",
        "${ScConfig.BASE_URL.trimEnd('/')}/oauth/token"
    ).distinct()

    for (tokenUrl in tokenUrls) {
        try {
            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", clientId)
                .add("code", code)
                .add("redirect_uri", ScConfig.REDIRECT_URI)
                .add("code_verifier", codeVerifier)
                .build()

            val requestBuilder = Request.Builder()
                .url(tokenUrl)
                .header("User-Agent", ScConfig.USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "https://soundcloud.com")
                .header("Referer", "https://soundcloud.com/")

            if (clientId == ScConfig.OFFICIAL_CLIENT_ID) {
                requestBuilder.header("Authorization", ScConfig.OFFICIAL_CLIENT_SIGNATURE)
            }

            authHttpClient.newCall(requestBuilder.post(formBody).build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                Log.d(TAG, "Token exchange at $tokenUrl -> ${response.code}")

                if (!response.isSuccessful) {
                    Log.w(TAG, "Обмен кода не удался: ${response.code} $bodyStr")
                    return@use
                }

                val json = JSONObject(bodyStr)
                val accessToken = json.optString("access_token", "").cleanOAuthValue()
                if (accessToken.isNullOrEmpty()) {
                    Log.w(TAG, "Пустой access_token в ответе")
                    return@use
                }
                val refreshToken = json.optString("refresh_token", "").cleanOAuthValue()
                val expiresIn = json.optLong("expires_in", 0L)
                val scope = json.optString("scope", "").cleanOAuthValue()

                tokenManager.saveTokens(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresInSeconds = expiresIn.takeIf { it > 0L },
                    scope = scope
                )

                setCookiesForToken(accessToken, refreshToken)
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обмена кода на $tokenUrl", e)
        }
    }

    return false
}

private fun setCookiesForToken(accessToken: String, refreshToken: String?) {
    val cookieManager = CookieManager.getInstance()
    val domains = listOf(
        "https://soundcloud.com",
        "https://m.soundcloud.com",
        "https://api-v2.soundcloud.com",
        "https://api-mobile.soundcloud.com",
        "https://api-auth.soundcloud.com",
        "https://secure.soundcloud.com"
    )

    for (domain in domains) {
        cookieManager.setCookie(domain, "oauth_token=$accessToken; Path=/; Secure")
        if (!refreshToken.isNullOrEmpty()) {
            cookieManager.setCookie(domain, "refresh_token=$refreshToken; Path=/; Secure")
        }
    }
    cookieManager.flush()
}

private fun String?.cleanOAuthValue(): String? = this
    ?.trim()
    ?.trim('"')
    ?.takeIf { it.isNotBlank() && it != "null" }
