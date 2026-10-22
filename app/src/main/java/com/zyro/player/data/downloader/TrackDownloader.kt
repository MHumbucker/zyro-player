package com.zyro.player.data.downloader

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.zyro.player.data.local.TrackDao
import com.zyro.player.data.local.TrackEntity
import com.zyro.player.data.remote.Result
import com.zyro.player.data.remote.SoundCloudRepository
import com.zyro.player.data.remote.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URI

/** Результат сохранения трека в системную папку «Загрузки». */
enum class PublicDownloadResult {
    SUCCESS,
    /** SoundCloud отдаёт трек только как HLS-поток — сохранить файлом нельзя. */
    UNSUPPORTED_STREAM,
    FAILED
}

/** Результат офлайн-кеширования одного трека. */
enum class OfflineDownloadResult {
    SUCCESS,
    /** SoundCloud ответил 429 — пакетную загрузку нужно поставить на паузу. */
    RATE_LIMITED,
    FAILED
}

class TrackDownloader(
    private val context: Context,
    private val repository: SoundCloudRepository,
    private val trackDao: TrackDao
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Сохраняет трек в приватный офлайн-кеш.
     *
     * SoundCloud для большинства треков отдаёт не прогрессивный MP3, а HLS:
     * m3u8-плейлист + fMP4-сегменты. Раньше в кеш попадал сам текст m3u8,
     * из-за чего файл «скачан», но плеер падал с `io unspecified`.
     * Теперь HLS собирается в один fMP4-файл (init + все сегменты), а
     * прогрессивный MP3 скачивается как раньше.
     */
    suspend fun downloadTrack(
        track: TrackEntity,
        onProgress: (Float) -> Unit = {}
    ): OfflineDownloadResult = withContext(Dispatchers.IO) {
        try {
            val downloadsDir = File(context.filesDir, "downloads").apply {
                if (!exists()) mkdirs()
            }
            val existing = trackDao.getTrackByUrl(track.originalUrl)
            val base = existing ?: track

            if (isValidOfflineFile(base)) {
                if (!base.isOffline) {
                    trackDao.insertOrUpdateTrack(base.copy(isOffline = true))
                }
                return@withContext OfflineDownloadResult.SUCCESS
            }

            // Старый битый кеш (например, m3u8-текст под именем .mp3) удаляем,
            // чтобы он не занимал место и не путал пользователя. Флаг офлайна
            // снимаем сразу: иначе при прерванной загрузке в Room останется
            // «скачанный» трек с путём на несуществующий файл, и плеер упадёт
            // с `io unspecified`.
            base.localFilePath?.let { path ->
                val old = File(path)
                if (!isValidAudioFile(old)) {
                    if (old.exists()) old.delete()
                    trackDao.setOfflineStatus(base.originalUrl, isOffline = false, filePath = null)
                }
            }

            var rateLimited = false

            // 1. Пробуем ранее сохранённую ссылку — это экономит запросы к API.
            if (base.streamUrl.isNotBlank()) {
                when (val attempt = downloadStream(
                    url = base.streamUrl,
                    track = base,
                    downloadsDir = downloadsDir,
                    onProgress = onProgress
                )) {
                    is DownloadAttempt.Success ->
                        return@withContext saveOffline(base, attempt.file, base.streamUrl)
                    DownloadAttempt.RateLimited -> rateLimited = true
                    DownloadAttempt.RetryWithFreshUrl,
                    DownloadAttempt.Failed -> Unit
                }
            }

            // 2. Ссылка устарела/отсутствует — получаем свежую у API.
            val infoResult = repository.getTrackInfo(base.originalUrl)
            if (infoResult !is Result.Success) {
                val message = (infoResult as? Result.Error)?.message.orEmpty()
                Log.e("ZYRO_PLAYER", "Failed to fetch track info: $message")
                return@withContext if (rateLimited || message.contains("429")) {
                    OfflineDownloadResult.RATE_LIMITED
                } else {
                    OfflineDownloadResult.FAILED
                }
            }

            val freshUrl = infoResult.data.streamUrl
            if (freshUrl.isBlank()) {
                Log.e("ZYRO_PLAYER", "Cannot download track: streamUrl empty")
                return@withContext OfflineDownloadResult.FAILED
            }

            when (val attempt = downloadStream(
                url = freshUrl,
                track = base,
                downloadsDir = downloadsDir,
                onProgress = onProgress
            )) {
                is DownloadAttempt.Success -> saveOffline(base, attempt.file, freshUrl)
                DownloadAttempt.RateLimited -> OfflineDownloadResult.RATE_LIMITED
                DownloadAttempt.RetryWithFreshUrl,
                DownloadAttempt.Failed -> OfflineDownloadResult.FAILED
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "Error downloading track", e)
            OfflineDownloadResult.FAILED
        }
    }

    /**
     * Проверяет, что в кеше лежит реальный аудиофайл, а не m3u8-текст
     * и не оборванная загрузка. Нужна, чтобы старые «скачанные» треки
     * можно было перекачать, а плеер не падал с `io unspecified`.
     */
    fun isValidOfflineFile(track: TrackEntity): Boolean {
        val path = track.localFilePath ?: return false
        return isValidAudioFile(File(path))
    }

    /**
     * Чистит офлайн-хранилище:
     * 1) незавершённые загрузки (`*.part`) — докачки нет, файл всё равно
     *    перезаписывается с нуля;
     * 2) записи, у которых файл исчез или испорчен, — с них снимается флаг
     *    офлайна (иначе плеер получает путь на несуществующий файл);
     * 3) файлы, на которые не ссылается ни один трек.
     *
     * Возвращает количество удалённых файлов/записей.
     */
    suspend fun cleanupOfflineStorage(): Int = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "downloads")
        if (!dir.isDirectory) return@withContext 0

        var cleaned = 0

        // 1. Обрывы загрузки.
        dir.listFiles().orEmpty().forEach { file ->
            if (file.name.endsWith(".part") && file.delete()) cleaned++
        }

        // 2. Записи с отсутствующим или битым файлом.
        trackDao.getOfflineTracksSnapshot().forEach { track ->
            val path = track.localFilePath
            if (path.isNullOrBlank() || !isValidAudioFile(File(path))) {
                val file = path?.let(::File)
                if (file != null && file.exists() && file.delete()) cleaned++
                trackDao.setOfflineStatus(track.originalUrl, isOffline = false, filePath = null)
                cleaned++
            }
        }

        // 3. Осиротевшие файлы (в том числе старые m3u8-«mp3»).
        val referenced = trackDao.getAllLocalFilePaths().toHashSet()
        dir.listFiles().orEmpty().forEach { file ->
            if (file.absolutePath !in referenced && file.delete()) cleaned++
        }

        if (cleaned > 0) Log.i("ZYRO_PLAYER", "Offline cleanup: removed $cleaned item(s)")
        cleaned
    }

    /** Быстрая проверка: сервер отдал m3u8-плейлист вместо аудио. */
    private fun isM3u8File(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(7)
            input.read(header) >= 7 &&
                String(header, 0, 7, Charsets.US_ASCII) == "#EXTM3U"
        }
    }.getOrDefault(false)

    companion object {
        private const val MIN_AUDIO_BYTES = 8_192L

        /**
         * Валидация файла офлайн-кеша по заголовку:
         * - m3u8-текст (`#EXTM3U`) — всегда битый кеш;
         * - fMP4 должен начинаться с бокса `ftyp` (иначе это обрывок без init);
         * - MPEG-TS — с sync-байта 0x47;
         * - MP3 — с `ID3` или frame sync 0xFF.
         */
        fun isValidAudioFile(file: File): Boolean {
            if (!file.isFile || file.length() < MIN_AUDIO_BYTES) return false

            val header = ByteArray(12)
            val read = runCatching {
                file.inputStream().use { it.read(header) }
            }.getOrElse { return false }

            if (read >= 7 && String(header, 0, 7, Charsets.US_ASCII) == "#EXTM3U") return false

            return when (file.extension.lowercase()) {
                "mp4", "m4a" -> read >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
                "ts" -> read >= 1 && header[0] == 0x47.toByte()
                "mp3" -> read >= 3 &&
                    (String(header, 0, 3, Charsets.US_ASCII) == "ID3" ||
                        (header[0].toInt() and 0xFF) == 0xFF)
                else -> true
            }
        }
    }

    private suspend fun saveOffline(
        base: TrackEntity,
        file: File,
        streamUrl: String
    ): OfflineDownloadResult {
        val oldPath = base.localFilePath
        if (!oldPath.isNullOrBlank() && oldPath != file.absolutePath) {
            File(oldPath).takeIf { it.exists() }?.delete()
        }
        trackDao.insertOrUpdateTrack(
            base.copy(
                isOffline = true,
                localFilePath = file.absolutePath,
                streamUrl = streamUrl
            )
        )
        return OfflineDownloadResult.SUCCESS
    }

    private sealed class DownloadAttempt {
        data class Success(val file: File) : DownloadAttempt()
        object Failed : DownloadAttempt()
        object RetryWithFreshUrl : DownloadAttempt()
        object RateLimited : DownloadAttempt()
    }

    private enum class TransferResult {
        OK,
        FAILED,
        RETRY_FRESH,
        RATE_LIMITED
    }

    private suspend fun downloadStream(
        url: String,
        track: TrackEntity,
        downloadsDir: File,
        onProgress: (Float) -> Unit
    ): DownloadAttempt {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 429 -> return DownloadAttempt.RateLimited
                response.code == 401 || response.code == 403 ||
                    response.code == 404 || response.code == 410 ->
                    return DownloadAttempt.RetryWithFreshUrl
                !response.isSuccessful -> return DownloadAttempt.Failed
            }

            val body = response.body ?: return DownloadAttempt.Failed
            val contentType = body.contentType()?.toString().orEmpty()
            val finalUrl = response.request.url.toString()
            val looksLikeHls = url.contains(".m3u8", ignoreCase = true) ||
                contentType.contains("mpegurl", ignoreCase = true)

            if (looksLikeHls) {
                val playlist = body.string()
                return downloadHlsPlaylist(
                    playlist = playlist,
                    baseUrl = finalUrl,
                    track = track,
                    downloadsDir = downloadsDir,
                    onProgress = onProgress
                )
            }

            val temp = File(downloadsDir, "track_${Math.abs(track.originalUrl.hashCode())}.part")
            temp.delete()
            try {
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L
                        val contentLength = body.contentLength()

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0) {
                                onProgress(
                                    (totalRead.toFloat() / contentLength.toFloat())
                                        .coerceIn(0f, 1f)
                                )
                            }
                        }
                        output.flush()
                    }
                }

                // Иногда сервер отдаёт m3u8 без расширения и без корректного
                // Content-Type. Проверяем первые байты скачанного файла.
                if (isM3u8File(temp)) {
                    val playlist = temp.readText(Charsets.UTF_8)
                    temp.delete()
                    return downloadHlsPlaylist(
                        playlist = playlist,
                        baseUrl = finalUrl,
                        track = track,
                        downloadsDir = downloadsDir,
                        onProgress = onProgress
                    )
                }

                val target = File(
                    downloadsDir,
                    "track_${Math.abs(track.originalUrl.hashCode())}.mp3"
                )
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                return DownloadAttempt.Success(target)
            } catch (e: Exception) {
                temp.delete()
                throw e
            }
        }
    }

    // ======================== HLS ========================

    private data class HlsMediaPlaylist(
        val initUrl: String?,
        val segments: List<HlsSegment>,
        val totalDuration: Double,
        val isFmp4: Boolean
    )

    private data class HlsSegment(val url: String, val duration: Double)

    private suspend fun downloadHlsPlaylist(
        playlist: String,
        baseUrl: String,
        track: TrackEntity,
        downloadsDir: File,
        onProgress: (Float) -> Unit,
        depth: Int = 0
    ): DownloadAttempt {
        if (depth > 2) return DownloadAttempt.Failed

        val media = parseMediaPlaylist(playlist, baseUrl)
        if (media == null) {
            // Master playlist: выбираем вариант с максимальным битрейтом.
            val variantUrl = chooseBestVariant(playlist, baseUrl)
                ?: return DownloadAttempt.Failed
            val variantResult = fetchText(variantUrl)
            return when (variantResult) {
                is TextFetchResult.Success -> downloadHlsPlaylist(
                    playlist = variantResult.text,
                    baseUrl = variantUrl,
                    track = track,
                    downloadsDir = downloadsDir,
                    onProgress = onProgress,
                    depth = depth + 1
                )
                TextFetchResult.RateLimited -> DownloadAttempt.RateLimited
                TextFetchResult.RetryFresh -> DownloadAttempt.RetryWithFreshUrl
                TextFetchResult.Failed -> DownloadAttempt.Failed
            }
        }

        if (media.segments.isEmpty()) return DownloadAttempt.Failed

        val temp = File(
            downloadsDir,
            "track_${Math.abs(track.originalUrl.hashCode())}.hls.part"
        )
        temp.delete()

        try {
            var failure: DownloadAttempt? = null
            FileOutputStream(temp).use { output ->
                media.initUrl?.let { initUrl ->
                    when (val result = copyUrlToStream(initUrl, output)) {
                        TransferResult.OK -> Unit
                        TransferResult.RATE_LIMITED -> {
                            failure = DownloadAttempt.RateLimited
                            return@use
                        }
                        TransferResult.RETRY_FRESH -> {
                            failure = DownloadAttempt.RetryWithFreshUrl
                            return@use
                        }
                        TransferResult.FAILED -> {
                            failure = DownloadAttempt.Failed
                            return@use
                        }
                    }
                }

                var downloadedDuration = 0.0
                for ((index, segment) in media.segments.withIndex()) {
                    when (val result = copyUrlToStream(segment.url, output)) {
                        TransferResult.OK -> Unit
                        TransferResult.RATE_LIMITED -> {
                            failure = DownloadAttempt.RateLimited
                            break
                        }
                        TransferResult.RETRY_FRESH -> {
                            failure = DownloadAttempt.RetryWithFreshUrl
                            break
                        }
                        TransferResult.FAILED -> {
                            failure = DownloadAttempt.Failed
                            break
                        }
                    }

                    downloadedDuration += segment.duration
                    val progress = if (media.totalDuration > 0) {
                        (downloadedDuration / media.totalDuration).toFloat()
                    } else {
                        (index + 1).toFloat() / media.segments.size.toFloat()
                    }
                    onProgress(progress.coerceIn(0f, 1f))
                }
                if (failure == null) output.flush()
            }

            failure?.let {
                temp.delete()
                return it
            }

            val extension = if (media.isFmp4) "mp4" else "ts"
            val target = File(
                downloadsDir,
                "track_${Math.abs(track.originalUrl.hashCode())}.$extension"
            )
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return DownloadAttempt.Success(target)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun parseMediaPlaylist(text: String, baseUrl: String): HlsMediaPlaylist? {
        if (!text.contains("#EXTM3U")) return null
        if (text.contains("#EXT-X-STREAM-INF")) return null
        if (text.contains("#EXT-X-KEY") && !text.contains("METHOD=NONE")) return null

        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        var initUrl: String? = null
        var pendingDuration = 0.0
        var totalDuration = 0.0
        val segments = mutableListOf<HlsSegment>()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri = Regex("URI=\"([^\"]+)\"").find(line)
                        ?.groupValues?.getOrNull(1)
                    if (!uri.isNullOrBlank()) {
                        initUrl = resolveUrl(baseUrl, uri)
                    }
                }
                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.removePrefix("#EXTINF:")
                        .substringBefore(',')
                        .trim()
                        .toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#") -> Unit
                else -> {
                    val segmentUrl = resolveUrl(baseUrl, line)
                    segments += HlsSegment(segmentUrl, pendingDuration)
                    totalDuration += pendingDuration
                    pendingDuration = 0.0
                }
            }
        }

        val isFmp4 = initUrl != null || segments.any { segment ->
            segment.url.substringBefore('?').endsWith(".m4s", ignoreCase = true) ||
                segment.url.substringBefore('?').endsWith(".mp4", ignoreCase = true)
        }

        return HlsMediaPlaylist(
            initUrl = initUrl,
            segments = segments,
            totalDuration = totalDuration,
            isFmp4 = isFmp4
        )
    }

    private fun chooseBestVariant(text: String, baseUrl: String): String? {
        val lines = text.lineSequence().map { it.trim() }.toList()
        var bestBandwidth = -1L
        var bestUrl: String? = null
        var pendingBandwidth = -1L

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    pendingBandwidth = Regex("BANDWIDTH=(\\d+)").find(line)
                        ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
                }
                line.startsWith("#") || line.isBlank() -> Unit
                else -> {
                    if (pendingBandwidth > bestBandwidth) {
                        bestBandwidth = pendingBandwidth
                        bestUrl = resolveUrl(baseUrl, line)
                    }
                    pendingBandwidth = -1L
                }
            }
        }
        return bestUrl
    }

    private sealed class TextFetchResult {
        data class Success(val text: String) : TextFetchResult()
        object Failed : TextFetchResult()
        object RetryFresh : TextFetchResult()
        object RateLimited : TextFetchResult()
    }

    private fun fetchText(url: String): TextFetchResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        return client.newCall(request).execute().use { response ->
            when {
                response.code == 429 -> TextFetchResult.RateLimited
                response.code == 401 || response.code == 403 ||
                    response.code == 404 || response.code == 410 -> TextFetchResult.RetryFresh
                !response.isSuccessful -> TextFetchResult.Failed
                else -> {
                    val text = response.body?.string()
                    if (text.isNullOrBlank()) TextFetchResult.Failed
                    else TextFetchResult.Success(text)
                }
            }
        }
    }

    private fun copyUrlToStream(url: String, output: OutputStream): TransferResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        return client.newCall(request).execute().use { response ->
            when {
                response.code == 429 -> TransferResult.RATE_LIMITED
                response.code == 401 || response.code == 403 ||
                    response.code == 404 || response.code == 410 -> TransferResult.RETRY_FRESH
                !response.isSuccessful -> TransferResult.FAILED
                else -> {
                    val body = response.body ?: return TransferResult.FAILED
                    body.byteStream().use { input ->
                        input.copyTo(output, bufferSize = 32 * 1024)
                    }
                    TransferResult.OK
                }
            }
        }
    }

    private fun resolveUrl(baseUrl: String, relative: String): String = runCatching {
        URI(baseUrl).resolve(relative).toString()
    }.getOrDefault(relative)

    suspend fun deleteOfflineTrack(trackUrl: String) = withContext(Dispatchers.IO) {
        val track = trackDao.getTrackByUrl(trackUrl) ?: return@withContext
        if (track.localFilePath != null) {
            val file = File(track.localFilePath)
            if (file.exists()) file.delete()
        }
        trackDao.setOfflineStatus(trackUrl, false, null)
    }

    /**
     * Полностью очищает офлайн-кеш: удаляет скачанные файлы, снимает флаги
     * офлайна и подчищает осиротевшие файлы в каталоге `downloads`.
     * Коллекции (лайки/плейлисты/альбомы) чистит вызывающая сторона.
     */
    suspend fun deleteAllOfflineTracks(): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        trackDao.getOfflineTracksSnapshot().forEach { track ->
            track.localFilePath?.let(::File)?.takeIf { it.exists() }?.let {
                if (it.delete()) deleted++
            }
            trackDao.setOfflineStatus(track.originalUrl, isOffline = false, filePath = null)
        }

        // Подчищаем всё, что осталось в каталоге (в т.ч. *.part и битые файлы).
        File(context.filesDir, "downloads").listFiles().orEmpty().forEach { file ->
            if (file.delete()) deleted++
        }

        Log.i("ZYRO_PLAYER", "Offline cache cleared: $deleted file(s)")
        deleted
    }

    // ======================== Скачивание в «Загрузки» ========================

    /**
     * Скачивает трек как обычный аудиофайл в системную папку «Загрузки»
     * (`Download/Zyro` на Android 10+, публичная «Загрузки»/Zyro на 9 и ниже).
     *
     * Это не офлайн-кэш: файл остаётся у пользователя, его видно в
     * файловом менеджере и плеере/галерее.
     */
    suspend fun downloadToPublicDownloads(
        track: TrackInfo,
        onProgress: (Float) -> Unit = {}
    ): PublicDownloadResult = withContext(Dispatchers.IO) {
        try {
            val info = (repository.getTrackInfo(track.originalUrl) as? Result.Success)?.data
            val streamUrl = info?.streamUrl.orEmpty()

            if (streamUrl.isBlank()) {
                Log.e("ZYRO_PLAYER", "Public download: empty streamUrl")
                return@withContext PublicDownloadResult.FAILED
            }

            // HLS-плейлист нельзя сохранить как обычный файл.
            if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                Log.e("ZYRO_PLAYER", "Public download: HLS stream is not supported")
                return@withContext PublicDownloadResult.UNSUPPORTED_STREAM
            }

            val fileName = buildAudioFileName(
                artist = info?.artist ?: track.artist,
                title = info?.title ?: track.title
            )

            val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                downloadViaMediaStore(streamUrl, fileName, onProgress)
            } else {
                downloadViaLegacyStorage(streamUrl, fileName, onProgress)
            }

            if (saved) PublicDownloadResult.SUCCESS else PublicDownloadResult.FAILED
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "Public download failed", e)
            PublicDownloadResult.FAILED
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun downloadViaMediaStore(
        streamUrl: String,
        fileName: String,
        onProgress: (Float) -> Unit
    ): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Zyro"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                if (!streamTo(streamUrl, output, onProgress)) {
                    throw IllegalStateException("Stream failed")
                }
            } ?: throw IllegalStateException("No output stream")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Log.e("ZYRO_PLAYER", "MediaStore download failed", e)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun downloadViaLegacyStorage(
        streamUrl: String,
        fileName: String,
        onProgress: (Float) -> Unit
    ): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("ZYRO_PLAYER", "Public download: no WRITE_EXTERNAL_STORAGE permission")
            return false
        }

        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Zyro"
        )
        if (!dir.exists() && !dir.mkdirs()) return false

        return try {
            FileOutputStream(File(dir, fileName)).use { output ->
                streamTo(streamUrl, output, onProgress)
            }
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "Legacy download failed", e)
            false
        }
    }

    private fun streamTo(
        url: String,
        output: OutputStream,
        onProgress: (Float) -> Unit
    ): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            val total = body.contentLength()

            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                var bytesRead: Int
                var totalRead = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (total > 0) {
                        onProgress((totalRead.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                    }
                }
                output.flush()
            }
        }
        return true
    }

    private fun buildAudioFileName(artist: String, title: String): String {
        val raw = listOf(artist, title)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { "zyro_track" }

        val clean = raw
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)

        return "$clean.mp3"
    }
}