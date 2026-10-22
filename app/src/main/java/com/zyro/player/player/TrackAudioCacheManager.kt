package com.zyro.player.player

import android.content.Context
import android.util.Log
import com.zyro.player.data.remote.Result
import com.zyro.player.data.remote.SoundCloudRepository
import com.zyro.player.data.remote.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class TrackAudioCacheManager(
    private val context: Context,
    private val repository: SoundCloudRepository
) {
    private val client = OkHttpClient()
    private val cacheDir = File(context.cacheDir, "audio_cache").apply { mkdirs() }
    private val downloadingMap = ConcurrentHashMap<String, Boolean>()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // В старых версиях в кеш могли попасть HLS-плейлисты (.m3u8), сохранённые
        // как .mp3. Такие файлы «играются» с длительностью 0 и без звука.
        // При смене версии формата кеша полностью очищаем каталог.
        val storedVersion = prefs.getInt(KEY_CACHE_VERSION, 0)
        if (storedVersion != CACHE_VERSION) {
            runCatching { cacheDir.listFiles()?.forEach { it.delete() } }
            prefs.edit().putInt(KEY_CACHE_VERSION, CACHE_VERSION).apply()
        }
    }

    private fun getCacheFile(url: String): File {
        val hash = md5(url)
        return File(cacheDir, "$hash.mp3")
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Проверяет, что файл — реальное аудио, а не HLS-плейлист/мусор.
     * HLS-плейлист начинается с тега "#EXTM3U".
     */
    private fun isValidAudioFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4096) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(16)
                val read = input.read(header)
                if (read <= 0) return false
                val text = String(header, 0, read, Charsets.US_ASCII)
                !text.startsWith("#EXTM3U")
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isCached(url: String): Boolean {
        if (url.isBlank()) return false
        return isValidAudioFile(getCacheFile(url))
    }

    fun getCachedFilePath(url: String): String? {
        val file = getCacheFile(url)
        if (isValidAudioFile(file)) return file.absolutePath
        if (file.exists()) {
            // Битый/устаревший файл — удаляем, чтобы не мешал воспроизведению.
            runCatching { file.delete() }
        }
        return null
    }

    fun deleteCachedFile(url: String) {
        if (url.isBlank()) return
        runCatching { getCacheFile(url).delete() }
    }

    suspend fun getOrCacheAudioFile(track: TrackInfo): File? = withContext(Dispatchers.IO) {
        if (track.originalUrl.isBlank() && track.streamUrl.isBlank()) return@withContext null

        val cacheKey = if (track.originalUrl.isNotBlank()) track.originalUrl else track.streamUrl
        val cachedFile = getCacheFile(cacheKey)
        if (isValidAudioFile(cachedFile)) {
            return@withContext cachedFile
        }

        // Get stream URL if missing
        var streamUrl = track.streamUrl
        if (streamUrl.isBlank() && track.originalUrl.isNotBlank()) {
            val result = repository.getTrackInfo(track.originalUrl)
            when (result) {
                is Result.Success -> {
                    if (result.data.streamUrl.isNotBlank()) {
                        streamUrl = result.data.streamUrl
                    }
                }
                is Result.Error -> {
                    Log.e("ZYRO_CACHE", "Failed to get track info: ${result.message}")
                }
                else -> {}
            }
        }

        if (streamUrl.isBlank()) return@withContext null

        val downloadKey = cacheKey
        if (downloadingMap[downloadKey] == true) {
            // Wait until downloaded or timeout
            var attempts = 0
            while (downloadingMap[downloadKey] == true && attempts < 50) {
                delay(200)
                attempts++
            }
            if (isValidAudioFile(cachedFile)) {
                return@withContext cachedFile
            }
        }

        // HLS-поток (m3u8) — это плейлист, а не аудио. Кешировать его как .mp3 нельзя,
        // иначе плеер получит «битый» файл и трек не будет играть.
        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            return@withContext null
        }

        downloadingMap[downloadKey] = true
        val tempFile = File(cacheDir, "${cachedFile.name}.tmp")
        try {
            val request = Request.Builder().url(streamUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.contains("mpegurl", ignoreCase = true)) {
                    // HLS — играем потоком, не кешируем
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(tempFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()
            }

            val renamed = tempFile.renameTo(cachedFile)
            if (renamed && isValidAudioFile(cachedFile)) {
                Log.d("ZYRO_CACHE", "Successfully cached audio for track: ${track.title}")
                return@withContext cachedFile
            }
            if (tempFile.exists()) tempFile.delete()
            if (cachedFile.exists()) cachedFile.delete()
        } catch (e: Exception) {
            Log.e("ZYRO_CACHE", "Error caching audio for ${track.title}", e)
            if (tempFile.exists()) tempFile.delete()
        } finally {
            downloadingMap.remove(downloadKey)
        }
        return@withContext null
    }

    suspend fun prefetchTrack(track: TrackInfo) = withContext(Dispatchers.IO) {
        try {
            getOrCacheAudioFile(track)
        } catch (e: Exception) {
            Log.e("ZYRO_CACHE", "Prefetch failed for ${track.title}", e)
        }
    }

    companion object {
        private const val PREFS_NAME = "zyro_audio_cache"
        private const val KEY_CACHE_VERSION = "cache_version"

        /** Версию увеличивать при изменении формата/валидации кеша. */
        private const val CACHE_VERSION = 2
    }
}