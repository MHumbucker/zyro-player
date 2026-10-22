package com.zyro.player.data.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import java.security.MessageDigest

/**
 * Константы SoundCloud OAuth, повторяющие официальное Android-приложение.
 * Значения совпадают с рабочей реализацией из kittytune (Config.kt).
 */
object ScConfig {

    /** app_id официального приложения SoundCloud. */
    const val OFFICIAL_APP_ID = 3152

    /** client_id официального приложения SoundCloud. */
    const val OFFICIAL_CLIENT_ID = "QOFuKCOeAXIph267vzqj3B1wb65cZVAQ"

    private const val OFFICIAL_CLIENT_SECRET = "EhBDsGIj9EbuBbRf0QkhH9Fq9BX3yN4B"

    /**
     * Подпись клиента для /oauth/token (заголовок Authorization).
     * Считается так же, как в kittytune: SHA-256(client_id:secret) -> Base64URL без паддинга.
     */
    val OFFICIAL_CLIENT_SIGNATURE: String by lazy {
        calculateSignature(OFFICIAL_CLIENT_ID, OFFICIAL_CLIENT_SECRET)
    }

    const val BASE_URL = "https://api-v2.soundcloud.com/"
    const val AUTH_API_BASE_URL = "https://api-auth.soundcloud.com/"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * User-Agent официального Android-клиента SoundCloud.
     *
     * Для запросов к api-v2/api-mobile нужен именно он: ручки мобильного клиента
     * ждут мобильное приложение, и браузерный [USER_AGENT] вместе с заголовками
     * `App-Version`/`UDID` выглядит для DataDome подозрительно — мутации
     * (лайк, подписка, плейлисты) отвечают `403 Forbidden`.
     *
     * Браузерный [USER_AGENT] остаётся для скрапинга HTML soundcloud.com,
     * OAuth-обмена и ghost WebView, чтобы cookies выдавались той же сессии.
     */
    val API_USER_AGENT: String by lazy {
        val release = Build.VERSION.RELEASE ?: "10"
        val model = Build.MODEL ?: "Android"
        "SoundCloud/2025.12.10-release (Android $release; $model)"
    }

    /** Официальный redirect URI приложения SoundCloud. */
    const val REDIRECT_URI = "sc://auth"

    /** SharedPreferences, где лежит стабильный device_id для OAuth-флоу. */
    const val AUTH_FLOW_PREFS = "zyro_sc_auth_flow"
    private const val KEY_DEVICE_ID = "soundcloud_device_id"

    private fun calculateSignature(clientId: String, clientSecret: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$clientId:$clientSecret".toByteArray(Charsets.UTF_8))
        Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    } catch (e: Exception) {
        "ztQ_RKaMCPavrjcXvMT6t0STPQ0vU2cY4YKGVeLU6Iw"
    }

    /**
     * Стабильный device_id: MD5 от ANDROID_ID (как в официальном приложении),
     * либо случайный hex, если ANDROID_ID недоступен.
     */
    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(AUTH_FLOW_PREFS, Context.MODE_PRIVATE)

        prefs.getString(KEY_DEVICE_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val rawId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }

        val deviceId = if (!rawId.isNullOrBlank() && rawId != "9774d56d682e549c") {
            try {
                MessageDigest.getInstance("MD5")
                    .digest(rawId.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                java.util.UUID.randomUUID().toString().replace("-", "")
            }
        } else {
            java.util.UUID.randomUUID().toString().replace("-", "")
        }

        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }
}
