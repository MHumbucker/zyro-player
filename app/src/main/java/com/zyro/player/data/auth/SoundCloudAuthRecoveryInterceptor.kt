package com.zyro.player.data.auth

import android.os.Looper
import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Единая точка восстановления после SoundCloud `401`/`403`.
 *
 * Причины таких ответов:
 *  - протухший OAuth-токен, который ещё не помечен просроченным;
 *  - устаревшие/чужие cookies (`datadome`, `oauth_token`), оставшиеся от
 *    браузерной сессии ghost WebView;
 *  - несовпадение клиента (`client_id`) и токена.
 *
 * Интерцептор не пытается угадать причину, а делает два безопасных шага:
 *  1. на `403` с Cookie повторяет запрос без Cookie — как официальный клиент,
 *     который авторизуется только заголовком `Authorization`;
 *  2. если ответ снова `401/403`, принудительно обновляет OAuth-токен и
 *     повторяет запрос без Cookie уже с новым токеном.
 *
 * Повторы ограничены: каждый запрос проходит эти шаги не более одного раза,
 * поэтому рекурсии и бесконечных циклов нет.
 *
 * [onAuthFailure] вызывается только для финального `403` (после всех повторов) —
 * это сигнал, что нужна ручная проверка DataDome.
 */
class SoundCloudAuthRecoveryInterceptor(
    private val refreshTokenBlocking: (staleToken: String?) -> String?,
    private val onAuthFailure: ((responseBody: String?) -> Unit)? = null
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER_ATTEMPT) != null) {
            return chain.proceed(request)
        }

        var response = chain.proceed(request)
        if (!response.isAuthFailure()) return response

        val staleToken = request.header("Authorization")
            ?.removePrefix("OAuth ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val hadCookies = request.header("Cookie") != null

        // Шаг 1. 403 с cookies почти всегда означает, что DataDome увидел
        // cookie от другой сессии/User-Agent. Официальный клиент обходится
        // без Cookie, поэтому сначала пробуем именно его режим.
        if (response.code == 403 && hadCookies) {
            response.close()
            response = chain.proceed(
                request.newBuilder()
                    .removeHeader("Cookie")
                    .header(HEADER_ATTEMPT, "no-cookies")
                    .build()
            )
            if (!response.isAuthFailure()) return response
        }

        // Шаг 2. Токен мог быть отозван или заменён в другой сессии. Обновляем
        // его и повторяем запрос уже без cookies, чтобы старый datadome не
        // перебивал свежий Authorization.
        if (response.isAuthFailure()) {
            val body = runCatching { response.peekBody(2048).string() }.getOrNull()
            Log.d(
                TAG,
                "HTTP ${response.code}, cookies=$hadCookies — refreshing token; " +
                    "body=${body?.take(300).orEmpty()}"
            )
            response.close()

            // На main-потоке blocking-refresh может привести к взаимной
            // блокировке с ghost WebView. В этом случае оставляем ответ как есть.
            val freshToken = if (Looper.myLooper() != Looper.getMainLooper()) {
                runCatching { refreshTokenBlocking(staleToken) }.getOrNull()
            } else {
                null
            }

            val retry = request.newBuilder()
                .removeHeader("Cookie")
                .header(HEADER_ATTEMPT, "refreshed")
            if (!freshToken.isNullOrBlank()) {
                retry.header("Authorization", "OAuth $freshToken")
            }
            response = chain.proceed(retry.build())
        }

        // Финальный 403 после всех автоматических попыток — почти всегда
        // капча DataDome. Сообщаем наверх, чтобы показать плашку проверки.
        if (response.code == 403) {
            val body = runCatching { response.peekBody(4096).string() }.getOrNull()
            runCatching { onAuthFailure?.invoke(body) }
        }

        return response
    }

    private fun Response.isAuthFailure(): Boolean = code == 401 || code == 403

    private companion object {
        const val TAG = "SC_AUTH_RECOVERY"
        const val HEADER_ATTEMPT = "X-Zyro-Auth-Retry"
    }
}
