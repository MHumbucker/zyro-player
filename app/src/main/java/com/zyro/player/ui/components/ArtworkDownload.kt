package com.zyro.player.ui.components

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.zyro.player.data.remote.SoundCloudArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Скачивает обложку SoundCloud в Pictures/Zyro.
 *
 * Если оригинала на CDN нет — пробует гарантированный размер, чтобы
 * пользователь не получил «пустой» файл.
 */
suspend fun downloadArtworkToPictures(
    context: Context,
    artworkUrl: String,
    trackTitle: String
): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver

    try {
        val client = OkHttpClient()

        fun request(url: String): okhttp3.Response = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
        ).execute()

        var response = request(SoundCloudArtwork.toOriginal(artworkUrl))
        if (!response.isSuccessful) {
            response.close()
            response = request(SoundCloudArtwork.toFallback(artworkUrl))
        }

        response.use { result ->
            if (!result.isSuccessful) return@withContext false

            val body = result.body ?: return@withContext false
            val inputStream = body.byteStream()

            val fileName = buildArtworkFileName(trackTitle)

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/Zyro"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri: Uri? = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )

            if (uri == null) {
                inputStream.close()
                return@withContext false
            }

            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                } ?: throw IllegalStateException("Не удалось открыть файл")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null,
                        null
                    )
                }

                true
            } catch (e: Exception) {
                // Если запись оборвалась, удаляем недописанный файл.
                resolver.delete(uri, null, null)
                Log.e("ZYRO_PLAYER", "Failed to save artwork", e)
                false
            } finally {
                inputStream.close()
            }
        }
    } catch (e: Exception) {
        Log.e("ZYRO_PLAYER", "Artwork download failed", e)
        false
    }
}

/** Безопасное имя файла обложки: "zyro_<title>_<timestamp>.jpg". */
private fun buildArtworkFileName(trackTitle: String): String {
    val cleanTitle = trackTitle
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .take(100)

    val baseName = if (cleanTitle.isBlank()) "zyro_cover" else "zyro_$cleanTitle"

    return "${baseName}_${System.currentTimeMillis()}.jpg"
}
