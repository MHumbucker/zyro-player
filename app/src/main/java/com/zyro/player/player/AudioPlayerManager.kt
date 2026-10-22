package com.player.player

import android.content.Context
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.zyro.player.data.downloader.TrackDownloader
import com.zyro.player.data.local.HistoryDao
import com.zyro.player.data.local.ListeningHistoryEntity
import com.zyro.player.data.local.TrackDao
import com.zyro.player.data.remote.Result
import com.zyro.player.data.remote.SoundCloudRepository
import com.zyro.player.data.remote.TrackInfo
import com.zyro.player.player.MediaSessionManager
import com.zyro.player.player.PlaybackSnapshot
import com.zyro.player.player.PlaybackStateStore
import com.zyro.player.player.TrackAudioCacheManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class PlayerPlaybackState {
    IDLE, PREPARING, PLAYING, PAUSED, ERROR
}

enum class RepeatMode {
    NONE, ONE, ALL
}

/** Пресет системного эквалайзера устройства. */
data class EqualizerPreset(
    val index: Int,
    val name: String
)

/** Состояние эквалайзера для UI. */
data class EqualizerState(
    val available: Boolean = false,
    /** true, если система уже ответила: поддерживается или нет. */
    val checked: Boolean = false,
    val enabled: Boolean = false,
    val presets: List<EqualizerPreset> = emptyList(),
    val selectedPreset: Int = -1
)

/**
 * Воспроизведение через Media3 ExoPlayer.
 *
 * Раньше использовался android.media.MediaPlayer, который не умеет играть
 * HLS-потоки SoundCloud (трек «длится 0 сек» и звука нет). ExoPlayer корректно
 * работает и с progressive MP3, и с HLS.
 */
@RequiresApi(Build.VERSION_CODES.O)
class AudioPlayerManager(
    private val context: Context,
    private val repository: SoundCloudRepository,
    private val trackDao: TrackDao,
    private val historyDao: HistoryDao
) {
    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val cacheManager = TrackAudioCacheManager(context, repository)
    private var mediaSessionManager: MediaSessionManager? = null

    private val _playbackState = MutableStateFlow(PlayerPlaybackState.IDLE)
    val playbackState: StateFlow<PlayerPlaybackState> = _playbackState.asStateFlow()

    private val _currentTrack = MutableStateFlow<TrackInfo?>(null)
    val currentTrack: StateFlow<TrackInfo?> = _currentTrack.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _queue = MutableStateFlow<List<TrackInfo>>(emptyList())
    val queue: StateFlow<List<TrackInfo>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.NONE)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    // === Настройки звука ===
    // Скорость и эквалайзер живут на уровне менеджера плеера, поэтому
    // действуют на все треки и переживают переключения/перезапуск.
    private val audioSettingsPrefs =
        context.getSharedPreferences("zyro_audio_settings", Context.MODE_PRIVATE)

    private val _playbackSpeed = MutableStateFlow(
        audioSettingsPrefs.getFloat("playback_speed", 1f).coerceIn(0.5f, 2f)
    )
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _equalizerState = MutableStateFlow(EqualizerState())
    val equalizerState: StateFlow<EqualizerState> = _equalizerState.asStateFlow()

    private var equalizer: Equalizer? = null
    private var equalizerSessionId = 0

    /**
     * Последние прослушанные треки. Нужны для «честного» shuffle:
     * при новом перемешивании не ставим недавние треки в самое начало.
     */
    private val recentlyPlayedUrls = ArrayDeque<String>()
    private val recentlyPlayedLimit = 24

    private var progressJob: Job? = null
    private var preloadJob: Job? = null

    /**
     * Уже полученные stream-URL следующих треков. Для HLS кеш файла невозможен,
     * но метаданные/подписанный URL можно подготовить заранее, чтобы при
     * переходе в фоне не ждать лишний сетевой round-trip.
     */
    private val resolvedStreams = ConcurrentHashMap<String, String>()

    /** Очередь/позиция, переживающие перезапуск приложения. */
    private val playbackStateStore = PlaybackStateStore(context)
    private var restoredWasPlaying = false
    private var lastPersistedAt = 0L
    private var persistenceReady = false

    /** Откуда сейчас играет трек — нужно для ретрая при ошибке. */
    private var activeSource: PlaySource = PlaySource.NONE
    private var retriedFromCache = false
    private var networkRetryCount = 0

    private enum class PlaySource { NONE, LOCAL, CACHE, NETWORK }

    init {
        initPlayer()
        initMediaSession()
        restorePlaybackState()
        seedRecentlyPlayedFromHistory()
        persistenceReady = true
    }

    /**
     * Подтягивает недавнюю историю, чтобы антиповторы работали и после
     * перезапуска приложения, а не только в рамках текущей сессии.
     */
    private fun seedRecentlyPlayedFromHistory() {
        scope.launch {
            val recent = withContext(Dispatchers.IO) {
                runCatching { historyDao.getRecentHistory() }
                    .getOrDefault(emptyList())
            }
            // История приходит от новых к старым; добавляем от старых к новым,
            // чтобы в конце остались именно последние прослушанные треки.
            recent.asReversed().forEach { rememberRecentlyPlayed(it.trackOriginalUrl) }
        }
    }

    private fun initMediaSession() {
        try {
            mediaSessionManager = MediaSessionManager(
                context = context,
                onPlay = { play() },
                onPause = { pause() },
                onSkipNext = { playNext() },
                onSkipPrev = { playPrevious() },
                onSeek = { seekTo(it) }
            )
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "Failed to init MediaSessionManager", e)
        }
    }

    private fun updateMediaSession() {
        mediaSessionManager?.updateMediaSession(
            track = _currentTrack.value,
            isPlaying = _playbackState.value == PlayerPlaybackState.PLAYING,
            currentPos = _currentPosition.value,
            duration = _duration.value,
            queue = _queue.value,
            currentIndex = _currentIndex.value
        )
    }

    private fun initPlayer() {
        player?.release()

        val attributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Дольше держим буфер впереди: при переходе в фон и на мобильной сети
        // ExoPlayer не должен останавливаться на каждой яме. 2.5 с — минимум
        // для старта, 5 с — после ребуферизации, до 2 минут впереди.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Zyro/1.0")

        // Важно: DefaultDataSource сам выбирает источник по схеме URI
        // (file:// -> FileDataSource, content:// -> ContentDataSource,
        // http(s):// -> DefaultHttpDataSource). Если передать в фабрику
        // напрямую DefaultHttpDataSource, офлайн-файлы и аудио-кеш падают
        // с `io unspecified` (FileURLConnection нельзя скастить в HttpURLConnection).
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        player = ExoPlayer.Builder(context)
            // Аудиофокус полностью на ExoPlayer: он запрашивает его при старте,
            // ставит на паузу при потере, приглушает при duck и восстанавливает
            // звук после временной потери. Ручной AudioFocusRequest не нужен —
            // два обработчика конфликтуют, а toggle на потере фокуса включал
            // музыку, когда начинало играть другое приложение.
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                if (_playbackState.value != PlayerPlaybackState.PAUSED) {
                                    _playbackState.value = PlayerPlaybackState.PREPARING
                                }
                            }

                            Player.STATE_READY -> {
                                val d = player?.duration ?: 0L
                                if (d > 0) _duration.value = d
                                persistPlaybackState()
                                updateMediaSession()
                            }

                            Player.STATE_ENDED -> handlePlaybackCompletion()

                            Player.STATE_IDLE -> {}
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            _playbackState.value = PlayerPlaybackState.PLAYING
                            networkRetryCount = 0
                            startProgressTracker()
                        } else {
                            if (_playbackState.value == PlayerPlaybackState.PLAYING) {
                                _playbackState.value = PlayerPlaybackState.PAUSED
                            }
                            stopProgressTracker()
                        }
                        persistPlaybackState(force = true)
                        updateMediaSession()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        handlePlayerError(error)
                    }

                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        if (audioSessionId != 0) initEqualizer(audioSessionId)
                    }
                })
            }

        player?.let { p ->
            runCatching { p.setPlaybackSpeed(_playbackSpeed.value) }
            initEqualizer(p.audioSessionId)
        }
    }

    /**
     * Подключает системный эквалайзер к аудиосессии ExoPlayer.
     *
     * На части устройств/эмуляторов эффект недоступен — в этом случае
     * UI получает `available = false` и не показывает нерабочие настройки.
     */
    private fun initEqualizer(sessionId: Int) {
        if (sessionId <= 0) return
        if (equalizer != null && equalizerSessionId == sessionId) return

        releaseEqualizer()
        try {
            val eq = Equalizer(0, sessionId)
            val presets = (0 until eq.numberOfPresets.toInt()).map { index ->
                EqualizerPreset(
                    index = index,
                    name = eq.getPresetName(index.toShort())
                )
            }

            val savedEnabled = audioSettingsPrefs.getBoolean("equalizer_enabled", false)
            val savedPreset = audioSettingsPrefs.getInt("equalizer_preset", -1)
            if (savedPreset in presets.indices) {
                runCatching { eq.usePreset(savedPreset.toShort()) }
            }
            runCatching { eq.enabled = savedEnabled }

            equalizer = eq
            equalizerSessionId = sessionId
            _equalizerState.value = EqualizerState(
                available = true,
                checked = true,
                enabled = eq.enabled,
                presets = presets,
                selectedPreset = savedPreset.takeIf { it in presets.indices }
                    ?: eq.currentPreset.toInt()
            )
            Log.i(
                "ZYRO_PLAYER",
                "Equalizer attached: session=$sessionId, presets=${presets.size}, enabled=${eq.enabled}"
            )
        } catch (e: Exception) {
            Log.w("ZYRO_PLAYER", "Equalizer unavailable", e)
            _equalizerState.value = EqualizerState(available = false, checked = true)
        }
    }

    private fun releaseEqualizer() {
        runCatching { equalizer?.release() }
        equalizer = null
        equalizerSessionId = 0
    }

    /** Меняет скорость воспроизведения (0.5x…2x) и запоминает выбор. */
    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2f)
        _playbackSpeed.value = clamped
        runCatching { player?.setPlaybackSpeed(clamped) }
            .onFailure { Log.w("ZYRO_PLAYER", "Failed to set playback speed", it) }
        audioSettingsPrefs.edit().putFloat("playback_speed", clamped).apply()
    }

    /** Включает/выключает эквалайзер. */
    fun setEqualizerEnabled(enabled: Boolean) {
        val eq = equalizer ?: run {
            _equalizerState.value = _equalizerState.value.copy(enabled = false)
            return
        }
        runCatching { eq.enabled = enabled }
            .onSuccess {
                _equalizerState.value = _equalizerState.value.copy(
                    enabled = eq.enabled,
                    selectedPreset = eq.currentPreset.toInt()
                )
                audioSettingsPrefs.edit()
                    .putBoolean("equalizer_enabled", eq.enabled)
                    .apply()
            }
            .onFailure { Log.w("ZYRO_PLAYER", "Failed to toggle equalizer", it) }
    }

    /** Выбирает пресет эквалайзера. */
    fun selectEqualizerPreset(index: Int) {
        val eq = equalizer ?: return
        if (index !in 0 until eq.numberOfPresets.toInt()) return

        runCatching { eq.usePreset(index.toShort()) }
            .onSuccess {
                _equalizerState.value = _equalizerState.value.copy(
                    enabled = eq.enabled,
                    selectedPreset = index
                )
                audioSettingsPrefs.edit()
                    .putInt("equalizer_preset", index)
                    .apply()
            }
            .onFailure { Log.w("ZYRO_PLAYER", "Failed to select equalizer preset", it) }
    }

    private fun handlePlayerError(error: PlaybackException) {
        Log.e(
            "ZYRO_PLAYER",
            "ExoPlayer error: ${error.errorCodeName} — ${error.message}",
            error
        )

        val track = _currentTrack.value
        // Возможно, битый кеш-файл: удаляем и пробуем обычный сетевой поток.
        if (activeSource == PlaySource.CACHE && track != null && !retriedFromCache) {
            retriedFromCache = true
            Log.w("ZYRO_PLAYER", "Cached audio failed, retrying from network")
            cacheManager.deleteCachedFile(track.originalUrl)
            playTrack(
                track = track,
                playlistQueue = _queue.value,
                startPositionMs = _currentPosition.value
            )
            return
        }

        // В фоне сеть может моргнуть, а подписанный stream-URL — истечь.
        // Повторяем с повторным resolve, сохраняя позицию. Это важно для
        // перехода на следующий трек без участия пользователя.
        if (activeSource == PlaySource.NETWORK &&
            track != null &&
            networkRetryCount < 2 &&
            isRetryableNetworkError(error)
        ) {
            networkRetryCount++
            val position = _currentPosition.value
            Log.w(
                "ZYRO_PLAYER",
                "Network error ${error.errorCodeName}; retry $networkRetryCount for ${track.title}"
            )
            resolvedStreams.remove(track.originalUrl)
            playTrack(
                track = track.copy(streamUrl = ""),
                playlistQueue = _queue.value,
                startPositionMs = position
            )
            return
        }

        _playbackState.value = PlayerPlaybackState.ERROR
        _errorMessage.value = "Ошибка воспроизведения: ${error.errorCodeName}"
        stopProgressTracker()
        persistPlaybackState(force = true)
        updateMediaSession()
    }

    private fun isRetryableNetworkError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED

    fun playTrack(
        track: TrackInfo,
        playlistQueue: List<TrackInfo> = emptyList(),
        startPositionMs: Long = 0L,
        resetShuffle: Boolean = false
    ) {
        // Сразу поднимаем обложку и аватар до максимального качества, чтобы UI
        // не показывал сначала маленькую картинку из локальной базы, а потом
        // не перезагружал её после getTrackInfo().
        val normalizedTrack = track.copy(
            artworkUrl = repository.getBestArtworkUrl(track.artworkUrl),
            artistAvatarUrl = repository.getBestArtworkUrl(track.artistAvatarUrl)
        )

        // Выбор нового трека вне очереди начинает новый контекст прослушивания,
        // поэтому shuffle сбрасывается. Переходы внутри очереди, next/previous
        // и автопереходы вызывают playTrack без этого флага.
        if (resetShuffle &&
            _isShuffle.value &&
            _currentTrack.value?.originalUrl != normalizedTrack.originalUrl
        ) {
            _isShuffle.value = false
        }
        val normalizedQueue = playlistQueue.map {
            it.copy(
                artworkUrl = repository.getBestArtworkUrl(it.artworkUrl),
                artistAvatarUrl = repository.getBestArtworkUrl(it.artistAvatarUrl)
            )
        }

        rememberRecentlyPlayed(normalizedTrack.originalUrl)

        // Счётчик сетевых повторов относится к текущему треку. При переходе
        // на другой трек начинаем с чистого листа.
        if (_currentTrack.value?.originalUrl != normalizedTrack.originalUrl) {
            networkRetryCount = 0
        }

        scope.launch {
            try {
                _playbackState.value = PlayerPlaybackState.PREPARING
                _errorMessage.value = null
                _currentTrack.value = normalizedTrack
                _currentPosition.value = startPositionMs.coerceAtLeast(0L)
                _duration.value = normalizedTrack.durationMs.coerceAtLeast(0L)
                retriedFromCache = false

                if (normalizedQueue.isNotEmpty()) {
                    val idx = normalizedQueue.indexOfFirst {
                        it.originalUrl == normalizedTrack.originalUrl
                    }
                    if (idx >= 0) {
                        _queue.value = normalizedQueue
                        _currentIndex.value = idx
                    } else {
                        // Очередь пришла без текущего трека (например, из
                        // отфильтрованного списка) — добавляем его первым,
                        // иначе next/previous будут ходить мимо.
                        _queue.value = listOf(normalizedTrack) + normalizedQueue
                        _currentIndex.value = 0
                    }
                } else if (_queue.value.none { it.originalUrl == normalizedTrack.originalUrl }) {
                    _queue.value = listOf(normalizedTrack)
                    _currentIndex.value = 0
                }

                persistPlaybackState(force = true)
                updateMediaSession()

                // Логируем историю прослушивания
                launch(Dispatchers.IO) {
                    try {
                        historyDao.insertHistoryRecord(
                            ListeningHistoryEntity(
                                trackOriginalUrl = normalizedTrack.originalUrl,
                                title = normalizedTrack.title,
                                artist = normalizedTrack.artist,
                                artworkUrl = normalizedTrack.artworkUrl,
                                streamUrl = normalizedTrack.streamUrl,
                                durationMs = normalizedTrack.durationMs
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("ZYRO_PLAYER", "Error logging history", e)
                    }
                }

                preloadUpcomingTracks()

                // Определяем URL для воспроизведения.
                // Кеш/локальный файл — быстрый старт; иначе играем сетевой поток сразу
                // (ExoPlayer сам буферизует progressive MP3 и HLS).
                var playUrl = ""
                var source = PlaySource.NETWORK

                // 1. Локальный офлайн-файл.
                // Проверяем, что файл реально существует и не испорчен: после
                // прерванной загрузки в Room мог остаться путь на удалённый
                // файл, и ExoPlayer падал с `io unspecified`.
                val localDbTrack = trackDao.getTrackByUrl(track.originalUrl)
                if (localDbTrack != null && localDbTrack.isOffline && !localDbTrack.localFilePath.isNullOrBlank()) {
                    if (TrackDownloader.isValidAudioFile(File(localDbTrack.localFilePath))) {
                        playUrl = localDbTrack.localFilePath
                        source = PlaySource.LOCAL
                    } else {
                        Log.w(
                            "ZYRO_PLAYER",
                            "Offline file missing or invalid: ${localDbTrack.localFilePath}"
                        )
                        runCatching {
                            trackDao.setOfflineStatus(
                                track.originalUrl,
                                isOffline = false,
                                filePath = null
                            )
                        }.onFailure {
                            Log.e("ZYRO_PLAYER", "Failed to reset offline status", it)
                        }
                    }
                }

                // 2. Уже готовый кеш
                if (playUrl.isBlank()) {
                    val cachedFile = cacheManager.getCachedFilePath(track.originalUrl)
                    if (!cachedFile.isNullOrBlank()) {
                        playUrl = cachedFile
                        source = PlaySource.CACHE
                    }
                }

                // 3. Сетевой поток
                if (playUrl.isBlank()) {
                    // URL мог быть подготовлен предзагрузкой следующего трека.
                    var resolvedUrl = track.streamUrl
                        .ifBlank { resolvedStreams[track.originalUrl].orEmpty() }
                    if (resolvedUrl.isBlank()) {
                        val result = repository.getTrackInfo(track.originalUrl)
                        when (result) {
                            is Result.Success -> {
                                resolvedUrl = result.data.streamUrl
                                if (resolvedUrl.isNotBlank()) {
                                    resolvedStreams[track.originalUrl] = resolvedUrl
                                    // Обогащаем текущий трек (лайки, жанр, альбом и т.д.).
                                    // Обложку и аватар не затираем пустыми значениями —
                                    // иначе Coil перезагрузит картинку «с нуля».
                                    _currentTrack.value = result.data.copy(
                                        artworkUrl = result.data.artworkUrl
                                            .ifBlank { normalizedTrack.artworkUrl },
                                        artistAvatarUrl = result.data.artistAvatarUrl
                                            .ifBlank { normalizedTrack.artistAvatarUrl }
                                    )
                                    updateMediaSession()
                                }
                            }
                            is Result.Error -> {
                                _errorMessage.value = "Не удалось получить ссылку: ${result.message}"
                            }
                            else -> {}
                        }
                    } else {
                        resolvedStreams[track.originalUrl] = resolvedUrl
                    }
                    playUrl = resolvedUrl
                    source = PlaySource.NETWORK
                }

                if (playUrl.isBlank()) {
                    _playbackState.value = PlayerPlaybackState.ERROR
                    if (_errorMessage.value.isNullOrBlank()) {
                        _errorMessage.value = "Не удалось получить ссылку на поток аудио"
                    }
                    persistPlaybackState(force = true)
                    updateMediaSession()
                    return@launch
                }

                activeSource = source
                val mediaUri = if (source == PlaySource.LOCAL || source == PlaySource.CACHE) {
                    Uri.fromFile(File(playUrl))
                } else {
                    Uri.parse(playUrl)
                }

                player?.let { p ->
                    val metadataTrack = _currentTrack.value ?: normalizedTrack
                    val metadata = MediaMetadata.Builder()
                        .setTitle(metadataTrack.title)
                        .setArtist(metadataTrack.artist)
                        .setAlbumTitle(metadataTrack.albumTitle.takeIf { it.isNotBlank() })
                        .setArtworkUri(
                            metadataTrack.artworkUrl
                                .takeIf { it.isNotBlank() }
                                ?.let(Uri::parse)
                        )
                        .build()
                    p.setMediaItem(
                        MediaItem.Builder()
                            .setUri(mediaUri)
                            .setMediaMetadata(metadata)
                            .build()
                    )
                    p.prepare()
                    if (startPositionMs > 0L) {
                        p.seekTo(startPositionMs)
                    }
                    p.playWhenReady = true
                    persistPlaybackState(force = true)
                } ?: run {
                    _playbackState.value = PlayerPlaybackState.ERROR
                    _errorMessage.value = "Плеер не инициализирован"
                    persistPlaybackState(force = true)
                }
            } catch (e: Exception) {
                Log.e("ZYRO_PLAYER", "Error playing track", e)
                _playbackState.value = PlayerPlaybackState.ERROR
                _errorMessage.value = "Ошибка подготовки потока: ${e.localizedMessage}"
                persistPlaybackState(force = true)
                updateMediaSession()
            }
        }
    }

    private fun preloadUpcomingTracks() {
        preloadJob?.cancel()
        preloadJob = scope.launch(Dispatchers.IO) {
            // Небольшая задержка: даём текущему потоку забуферизоваться,
            // чтобы предзагрузка не «съедала» канал в самом начале.
            delay(2_000L)
            val q = _queue.value
            val start = _currentIndex.value + 1
            val end = minOf(start + 2, q.size)
            for (i in start until end) {
                ensureActive()
                var candidate = q[i]

                // Для HLS-треков файл кешировать нельзя, но подписанный URL
                // можно получить заранее. Тогда в фоне не будет задержки на
                // resolve + запрос transcoding перед самым переключением.
                var stream = candidate.streamUrl
                    .ifBlank { resolvedStreams[candidate.originalUrl].orEmpty() }
                if (stream.isBlank() && candidate.originalUrl.isNotBlank()) {
                    when (val result = repository.getTrackInfo(candidate.originalUrl)) {
                        is Result.Success -> {
                            val data = result.data
                            if (data.streamUrl.isNotBlank()) {
                                stream = data.streamUrl
                                resolvedStreams[candidate.originalUrl] = stream
                                candidate = candidate.copy(
                                    streamUrl = stream,
                                    id = data.id.takeIf { it != 0L } ?: candidate.id,
                                    likesCount = data.likesCount,
                                    playbackCount = data.playbackCount,
                                    commentCount = data.commentCount,
                                    genre = data.genre,
                                    description = data.description,
                                    albumTitle = data.albumTitle,
                                    albumPermalinkUrl = data.albumPermalinkUrl,
                                    isLiked = data.isLiked,
                                    likedAt = data.likedAt,
                                    artistAvatarUrl = data.artistAvatarUrl
                                        .ifBlank { candidate.artistAvatarUrl },
                                    artworkUrl = data.artworkUrl
                                        .ifBlank { candidate.artworkUrl }
                                )
                            }
                        }
                        is Result.Error -> {
                            Log.w(
                                "ZYRO_PLAYER",
                                "Prefetch resolve failed for ${candidate.title}: ${result.message}"
                            )
                        }
                        else -> {}
                    }
                }

                // Обновляем очередь, чтобы следующая карточка уже имела
                // актуальные метаданные (артист, альбом, лайки).
                if (candidate != q[i]) {
                    withContext(Dispatchers.Main) {
                        val current = _queue.value.toMutableList()
                        val index = current.indexOfFirst {
                            it.originalUrl == candidate.originalUrl
                        }
                        if (index >= 0) {
                            current[index] = candidate
                            _queue.value = current
                            persistPlaybackState(force = true)
                        }
                    }
                }

                cacheManager.prefetchTrack(candidate)
                Log.i(
                    "ZYRO_PLAYER",
                    "Prefetched next: ${candidate.title}, " +
                        "streamReady=${stream.isNotBlank()}, " +
                        "cached=${cacheManager.isCached(candidate.originalUrl)}"
                )
            }
        }
    }

    // ==========================================
    // QUEUE OPERATIONS
    // ==========================================

    fun setQueue(newQueue: List<TrackInfo>) {
        _queue.value = newQueue
        if (_currentIndex.value >= newQueue.size) {
            _currentIndex.value = (newQueue.size - 1).coerceAtLeast(0)
        }
        persistPlaybackState(force = true)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val currentQueue = _queue.value.toMutableList()
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices || fromIndex == toIndex) return

        val item = currentQueue.removeAt(fromIndex)
        currentQueue.add(toIndex, item)
        _queue.value = currentQueue

        val currentIdx = _currentIndex.value
        _currentIndex.value = when {
            fromIndex == currentIdx -> toIndex
            fromIndex < currentIdx && toIndex >= currentIdx -> currentIdx - 1
            fromIndex > currentIdx && toIndex <= currentIdx -> currentIdx + 1
            else -> currentIdx
        }
        // Не сериализуем всю очередь на каждом пересечении при drag.
        // Порядок фиксируется в onDragStopped через persistQueueOrder().
    }

    /** Сохраняет порядок очереди после завершения перетаскивания. */
    fun persistQueueOrder() {
        persistPlaybackState(force = true)
    }

    fun toggleQueueItem(track: TrackInfo) {
        val current = _queue.value.toMutableList()
        val index = current.indexOfFirst { it.originalUrl == track.originalUrl }
        if (index >= 0) {
            current.removeAt(index)
            if (index < _currentIndex.value) {
                _currentIndex.value = (_currentIndex.value - 1).coerceAtLeast(0)
            } else if (_currentIndex.value >= current.size) {
                _currentIndex.value = (current.size - 1).coerceAtLeast(0)
            }
        } else {
            current.add(track)
        }
        _queue.value = current
        persistPlaybackState(force = true)
    }

    fun playNextInQueue(track: TrackInfo) {
        val current = _queue.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.originalUrl == track.originalUrl }
        var currentIdx = _currentIndex.value

        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
            if (existingIndex < currentIdx) currentIdx--
        }

        val insertIndex = (currentIdx + 1).coerceAtMost(current.size)
        current.add(insertIndex, track)
        _queue.value = current
        _currentIndex.value = currentIdx
        persistPlaybackState(force = true)

        scope.launch(Dispatchers.IO) {
            cacheManager.prefetchTrack(track)
        }
    }

    fun removeFromQueue(index: Int) {
        val current = _queue.value.toMutableList()
        if (index !in current.indices) return

        val isCurrent = index == _currentIndex.value
        current.removeAt(index)

        _currentIndex.value = when {
            index < _currentIndex.value -> _currentIndex.value - 1
            index == _currentIndex.value -> {
                _currentIndex.value.coerceAtMost((current.size - 1).coerceAtLeast(0))
            }
            else -> _currentIndex.value
        }

        _queue.value = current
        persistPlaybackState(force = true)

        if (isCurrent) {
            if (current.isNotEmpty()) {
                val newIdx = _currentIndex.value.coerceIn(0, current.lastIndex)
                playTrack(current[newIdx], current)
            } else {
                _currentTrack.value = null
                _playbackState.value = PlayerPlaybackState.IDLE
                stopProgressTracker()
                player?.stop()
                player?.clearMediaItems()
                persistPlaybackState(force = true)
                updateMediaSession()
            }
        }
    }

    fun playQueueIndex(index: Int) {
        val q = _queue.value
        if (index in q.indices) {
            _currentIndex.value = index
            playTrack(q[index], q)
        }
    }

    fun clearQueue() {
        val active = _currentTrack.value
        if (active != null) {
            _queue.value = listOf(active)
            _currentIndex.value = 0
        } else {
            _queue.value = emptyList()
            _currentIndex.value = 0
        }
        persistPlaybackState(force = true)
    }

    // ==========================================
    // PLAYBACK CONTROL
    // ==========================================

    fun togglePlayPause() {
        when (_playbackState.value) {
            PlayerPlaybackState.PLAYING -> pause()
            PlayerPlaybackState.PAUSED -> play()
            PlayerPlaybackState.IDLE, PlayerPlaybackState.ERROR -> {
                _currentTrack.value?.let {
                    playTrack(it, _queue.value, startPositionMs = _currentPosition.value)
                }
            }
            else -> {}
        }
    }

    /** Продолжить воспроизведение (без toggle). */
    fun play() {
        val p = player ?: return
        val track = _currentTrack.value ?: return
        when (p.playbackState) {
            // После ошибки/остановки media item мог быть сброшен — готовим заново.
            // Позиция важна для восстановленной очереди: не начинаем с нуля.
            Player.STATE_IDLE -> playTrack(
                track = track,
                playlistQueue = _queue.value,
                startPositionMs = _currentPosition.value
            )
            // После конца трека playWhenReady уже true — нужно перемотать в начало
            Player.STATE_ENDED -> {
                p.seekTo(0L)
                p.playWhenReady = true
            }
            else -> p.playWhenReady = true
        }
    }

    /** Поставить на паузу (без toggle). */
    fun pause() {
        player?.let { p ->
            // Фиксируем точную позицию до остановки: progress tracker тикает
            // раз в 500 мс, и внешний контроллер иначе увидит отставание.
            // Для восстановленной, но ещё не подготовленной очереди media item
            // отсутствует — сохранённую позицию в этом случае не затираем.
            if (p.mediaItemCount > 0) {
                _currentPosition.value = p.currentPosition
            }
            p.playWhenReady = false
            mediaSessionManager?.updatePlaybackState(
                isPlaying = false,
                currentPos = _currentPosition.value
            )
            persistPlaybackState(force = true)
        }
    }

    fun seekTo(positionMs: Long) {
        player?.let { p ->
            if (_playbackState.value == PlayerPlaybackState.PLAYING || _playbackState.value == PlayerPlaybackState.PAUSED) {
                if (p.mediaItemCount > 0) {
                    p.seekTo(positionMs)
                    updateMediaSession()
                }
                _currentPosition.value = positionMs
                persistPlaybackState(force = true)
            }
        }
    }

    fun playNext() {
        val q = _queue.value
        if (q.isEmpty()) return

        if (_repeatMode.value == RepeatMode.ONE && _currentTrack.value != null) {
            playTrack(_currentTrack.value!!, q)
            return
        }

        val nextIdx = _currentIndex.value + 1
        if (nextIdx >= q.size) {
            if (_repeatMode.value == RepeatMode.ALL) {
                // При shuffle новый круг начинается с нового случайного порядка,
                // а не с того же трека, которым открывался предыдущий круг.
                val nextQueue = if (_isShuffle.value) {
                    val lastPlayed = q.getOrNull(_currentIndex.value) ?: _currentTrack.value
                    fairShuffle(
                        tracks = q,
                        avoidAtHead = setOfNotNull(lastPlayed?.originalUrl)
                    )
                } else {
                    q
                }
                _queue.value = nextQueue
                _currentIndex.value = 0
                playTrack(nextQueue[0], nextQueue)
            } else {
                _playbackState.value = PlayerPlaybackState.IDLE
                stopProgressTracker()
                persistPlaybackState(force = true)
                updateMediaSession()
            }
        } else {
            _currentIndex.value = nextIdx
            playTrack(q[nextIdx], q)
        }
    }

    fun playPrevious() {
        val q = _queue.value
        if (q.isEmpty()) return
        if (_currentPosition.value > 3000L) {
            seekTo(0L)
            return
        }
        val prevIdx = _currentIndex.value - 1
        if (prevIdx < 0) {
            if (_repeatMode.value == RepeatMode.ALL) {
                _currentIndex.value = q.size - 1
                playTrack(q.last(), q)
            } else {
                seekTo(0L)
            }
        } else {
            _currentIndex.value = prevIdx
            playTrack(q[prevIdx], q)
        }
    }

    fun toggleShuffle() {
        val newShuffle = !_isShuffle.value
        _isShuffle.value = newShuffle

        if (newShuffle) {
            val currentQ = _queue.value
            val currentTrack = _currentTrack.value ?: currentQ.getOrNull(_currentIndex.value)
            val shouldRebuild = currentQ.size > 1 ||
                (currentTrack != null && currentQ.none {
                    it.originalUrl == currentTrack.originalUrl
                })

            if (shouldRebuild) {
                // Текущий трек всегда остаётся первым, а ВСЕ остальные треки
                // уходят в случайный хвост. Раньше часть треков оставалась
                // перед текущим индексом и выпадала из круга — из-за этого
                // shuffle мог крутить почти один и тот же набор.
                val others = currentQ.filterNot {
                    it.originalUrl == currentTrack?.originalUrl
                }
                _queue.value = listOfNotNull(currentTrack) + fairShuffle(others)
                _currentIndex.value = 0
                preloadUpcomingTracks()
            }
        }
        persistPlaybackState(force = true)
    }

    /** Запоминает трек для антиповторов при shuffle. */
    private fun rememberRecentlyPlayed(url: String) {
        if (url.isBlank()) return
        recentlyPlayedUrls.remove(url)
        recentlyPlayedUrls.addLast(url)
        while (recentlyPlayedUrls.size > recentlyPlayedLimit) {
            recentlyPlayedUrls.removeFirst()
        }
    }

    /**
     * Перемешивает треки так, чтобы недавно прослушанные не оказывались
     * в первых позициях. Порядок при этом остаётся случайным.
     */
    private fun fairShuffle(
        tracks: List<TrackInfo>,
        avoidAtHead: Set<String> = emptySet()
    ): List<TrackInfo> {
        if (tracks.size <= 1) return tracks

        val shuffled = tracks.toMutableList()
        shuffled.shuffle()

        val recent = recentlyPlayedUrls.toSet()
        val headWindow = minOf(3, shuffled.size - 1)

        for (index in 0 until headWindow) {
            val url = shuffled[index].originalUrl
            if (url !in recent && url !in avoidAtHead) continue

            val freshCandidates = (index + 1 until shuffled.size).filter {
                val candidate = shuffled[it].originalUrl
                candidate !in recent && candidate !in avoidAtHead
            }
            val allowedCandidates = (index + 1 until shuffled.size).filter {
                shuffled[it].originalUrl !in avoidAtHead
            }
            val swapIndex = freshCandidates.randomOrNull()
                ?: allowedCandidates.randomOrNull()
                ?: (index + 1 until shuffled.size).randomOrNull()

            if (swapIndex != null) {
                val tmp = shuffled[index]
                shuffled[index] = shuffled[swapIndex]
                shuffled[swapIndex] = tmp
            }
        }

        return shuffled
    }

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
        persistPlaybackState(force = true)
    }

    private fun handlePlaybackCompletion() {
        stopProgressTracker()
        _playbackState.value = PlayerPlaybackState.IDLE
        persistPlaybackState(force = true)
        updateMediaSession()
        when (_repeatMode.value) {
            RepeatMode.ONE -> _currentTrack.value?.let { playTrack(it, _queue.value) }
            RepeatMode.ALL, RepeatMode.NONE -> playNext()
        }
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (isActive) {
                player?.let { p ->
                    if (p.isPlaying) {
                        _currentPosition.value = p.currentPosition
                        val d = p.duration
                        if (d > 0) _duration.value = d
                        // Внешние контроллеры (Volumee, локскрин, часы) должны
                        // видеть актуальную позицию, а не только состояние на
                        // момент последнего переключения трека.
                        mediaSessionManager?.updatePlaybackState(
                            isPlaying = true,
                            currentPos = _currentPosition.value
                        )
                        // Пишем позицию не чаще раза в несколько секунд,
                        // чтобы не дёргать SharedPreferences каждые 500 мс.
                        persistPlaybackState()
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    /**
     * Поднимает очередь, трек и позицию, сохранённые при прошлом запуске.
     *
     * Специально не запускаем воспроизведение и не создаём foreground-
     * уведомление: приложение может открыться просто чтобы полистать
     * библиотеку. Плеер подготовится только после нажатия Play.
     */
    private fun restorePlaybackState() {
        val snapshot = playbackStateStore.load() ?: return
        if (snapshot.queue.isEmpty()) return

        val index = snapshot.currentIndex.coerceIn(0, snapshot.queue.lastIndex)
        val track = snapshot.queue[index]

        _queue.value = snapshot.queue
        _currentIndex.value = index
        _currentTrack.value = track
        _currentPosition.value = snapshot.positionMs
        _duration.value = snapshot.durationMs.takeIf { it > 0L } ?: track.durationMs
        _isShuffle.value = snapshot.shuffle
        _repeatMode.value = snapshot.repeatMode
        _playbackState.value = PlayerPlaybackState.PAUSED
        restoredWasPlaying = snapshot.wasPlaying
        lastPersistedAt = System.currentTimeMillis()

        Log.i(
            "ZYRO_PLAYER",
            "Restored playback state: ${snapshot.queue.size} tracks, " +
                "index=$index, position=${snapshot.positionMs}, " +
                "wasPlaying=${snapshot.wasPlaying}"
        )
    }

    /**
     * Вызывается при открытии UI. Если очередь была восстановлена после
     * смерти процесса, но сервис ещё не успел её продолжить, снимаем флаг
     * автозапуска: пользователь открыл приложение и сам решит, играть или нет.
     */
    fun onUiStarted() {
        if (!restoredWasPlaying) return
        if (_playbackState.value == PlayerPlaybackState.PLAYING ||
            _playbackState.value == PlayerPlaybackState.PREPARING
        ) {
            return
        }

        restoredWasPlaying = false
        persistPlaybackState(force = true)
    }

    /**
     * Вызывается сервисом, если система перезапустила его после смерти
     * процесса, а до этого музыка играла. Возвращает воспроизведение с
     * сохранённой позиции.
     */
    fun resumeIfWasPlaying(): Boolean {
        val track = _currentTrack.value
        if (track == null || _queue.value.isEmpty()) return false

        // Плеер уже играет/готовится — сервис должен остаться foreground.
        if (_playbackState.value == PlayerPlaybackState.PLAYING ||
            _playbackState.value == PlayerPlaybackState.PREPARING
        ) {
            return true
        }

        if (!restoredWasPlaying) return false

        restoredWasPlaying = false
        Log.i("ZYRO_PLAYER", "Resuming playback after service restart")
        playTrack(
            track = track,
            playlistQueue = _queue.value,
            startPositionMs = _currentPosition.value
        )
        return true
    }

    /** Принудительно сохранить состояние (например, перед свайпом задачи). */
    fun persistNow() {
        persistPlaybackState(force = true, synchronous = true)
    }

    private fun persistPlaybackState(
        force: Boolean = false,
        synchronous: Boolean = false
    ) {
        if (!persistenceReady) return

        val track = _currentTrack.value
        val rawQueue = _queue.value
        if (track == null) {
            playbackStateStore.clear(synchronous)
            lastPersistedAt = System.currentTimeMillis()
            return
        }

        // Пользователь может убрать текущий трек из очереди (или очередь
        // окажется пустой), но он всё ещё играет. Для восстановления всегда
        // сохраняем очередь, в которой текущий трек присутствует.
        val queue = if (rawQueue.any { it.originalUrl == track.originalUrl }) {
            rawQueue
        } else {
            listOf(track) + rawQueue
        }
        val persistedIndex = queue
            .indexOfFirst { it.originalUrl == track.originalUrl }
            .coerceAtLeast(0)

        val now = System.currentTimeMillis()
        if (!force && now - lastPersistedAt < 4_000L) return
        lastPersistedAt = now

        val wasPlaying = _playbackState.value == PlayerPlaybackState.PLAYING
        restoredWasPlaying = wasPlaying

        playbackStateStore.save(
            snapshot = PlaybackSnapshot(
                queue = queue,
                currentIndex = persistedIndex,
                positionMs = _currentPosition.value,
                durationMs = _duration.value,
                shuffle = _isShuffle.value,
                repeatMode = _repeatMode.value,
                wasPlaying = wasPlaying
            ),
            // Очередь пересобираем только при реальных изменениях. Тик
            // позиции раз в несколько секунд пишет лишь скалярные ключи.
            includeQueue = force,
            synchronous = synchronous
        )
    }

    private fun releasePlayer() {
        persistPlaybackState(force = true, synchronous = true)
        stopProgressTracker()
        releaseEqualizer()
        player?.release()
        player = null
        mediaSessionManager?.release()
        mediaSessionManager = null
    }

    fun onCleared() {
        releasePlayer()
        scope.cancel()
    }
}
