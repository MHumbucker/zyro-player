package com.zyro.player.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Обложки SoundCloud (`*.sndcdn.com/...`).
 *
 * CDN отдаёт одну и ту же картинку в нескольких размерах:
 * `-mini` / `-tiny` / `-small` / `-badge` / `-crop` / `-large` (100px),
 * `-t300x300`, `-t500x500` и `-original` (максимум, заданный при загрузке).
 *
 * Раньше доступность `-original` проверялась синхронным HEAD-запросом на
 * каждую обложку: это медленно (N лишних запросов на список) и ненадёжно —
 * если HEAD почему-то не проходит, обложка тихо деградировала до `t500x500`
 * и выглядела «мыльной» на большом экране плеера.
 *
 * Теперь URL сразу указывает на `-original`, а если картинки в оригинале
 * действительно нет (404) — тот же запрос повторяется с гарантированным
 * `t500x500` (см. [SoundCloudArtworkInterceptor]).
 */
object SoundCloudArtwork {

    const val ORIGINAL_SIZE = "original"
    const val FALLBACK_SIZE = "t500x500"

    /** Заголовок-маркер, чтобы повторный запрос не переписывался снова. */
    const val RETRY_HEADER = "X-Zyro-Artwork-Retry"

    private val SIZE_TOKEN_REGEX = Regex(
        "(tiny|small|badge|crop|large|t\\d+x\\d+)\\.(jpg|jpeg|png|webp)",
        RegexOption.IGNORE_CASE
    )

    fun isSoundCloudArtwork(url: String): Boolean =
        url.contains("sndcdn.com", ignoreCase = true)

    /** Поднимает любой размер обложки SoundCloud до максимального (`-original`). */
    fun toOriginal(url: String): String = replaceSize(url, ORIGINAL_SIZE)

    /** Гарантированно существующий размер — используется как fallback при 404. */
    fun toFallback(url: String): String = replaceSize(url, FALLBACK_SIZE)

    private fun replaceSize(url: String, size: String): String {
        if (url.isBlank() || url == "null") return ""
        if (!isSoundCloudArtwork(url)) return url

        val clean = url.substringBefore('?')
        return SIZE_TOKEN_REGEX.replace(clean) { match ->
            "$size.${match.groupValues[2]}"
        }
    }
}

/**
 * OkHttp-интерцептор для загрузчика картинок (Coil):
 * 1) переписывает размер в `-original`;
 * 2) если CDN ответил ошибкой — повторяет запрос с `t500x500`.
 */
class SoundCloudArtworkInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (!SoundCloudArtwork.isSoundCloudArtwork(url) ||
            request.header(SoundCloudArtwork.RETRY_HEADER) != null
        ) {
            return chain.proceed(request)
        }

        val upgraded = SoundCloudArtwork.toOriginal(url)
        if (upgraded == url) return chain.proceed(request)

        val response = chain.proceed(request.newBuilder().url(upgraded).build())
        if (response.isSuccessful) return response

        response.close()

        val fallback = SoundCloudArtwork.toFallback(url)
        if (fallback == upgraded) {
            return chain.proceed(request.newBuilder().url(upgraded).build())
        }

        return chain.proceed(
            request.newBuilder()
                .url(fallback)
                .header(SoundCloudArtwork.RETRY_HEADER, "1")
                .build()
        )
    }
}
