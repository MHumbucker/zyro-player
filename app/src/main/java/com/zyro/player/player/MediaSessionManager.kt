package com.zyro.player.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.zyro.player.MainActivity
import com.zyro.player.R
import com.zyro.player.data.remote.SoundCloudArtwork
import com.zyro.player.data.remote.TrackInfo
import kotlinx.coroutines.*
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Медиа-сессия и уведомление плеера.
 *
 * Аудиофокус здесь больше не запрашивается вручную: ExoPlayer делает это сам
 * (`handleAudioFocus = true` в [com.player.player.AudioPlayerManager]). Ручной
 * AudioFocusRequest конфликтовал с ExoPlayer и на потере фокуса вызывал toggle,
 * из-за чего музыка могла включиться, когда начинало играть другое приложение.
 *
 * Колбэки сессии теперь строго разделены: onPlay -> play, onPause -> pause,
 * без переключения состояния.
 */
class MediaSessionManager(
    private val context: Context,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onSkipNext: () -> Unit,
    private val onSkipPrev: () -> Unit,
    private val onSeek: (Long) -> Unit
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Защита от гонки: обложка старого трека не должна перетереть новое уведомление. */
    private val updateToken = AtomicInteger(0)

    /**
     * Иконка приложения для уведомления и media-сессии, когда у трека нет обложки.
     * Adaptive-icon нельзя декодировать через BitmapFactory, поэтому рисуем drawable в bitmap.
     */
    private val appIconBitmap: Bitmap? by lazy { loadAppIconBitmap() }

    private fun loadAppIconBitmap(): Bitmap? = runCatching {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
            ?: return@runCatching null
        val size = 256
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(canvas)
        }
    }.getOrNull()

    /** Не перестраиваем очередь на каждом обновлении позиции. */
    private var lastQueueSignature: String? = null

    // Кеш для обложек (простой LRU с ограничением 10)
    private val bitmapCache = object : LinkedHashMap<String, Bitmap>(10, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > 10
        }
    }

    companion object {
        const val CHANNEL_ID = "zyro_player_media_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY = "com.zyro.player.ACTION_PLAY"
        const val ACTION_PAUSE = "com.zyro.player.ACTION_PAUSE"
        const val ACTION_NEXT = "com.zyro.player.ACTION_NEXT"
        const val ACTION_PREV = "com.zyro.player.ACTION_PREV"
        private const val TAG = "ZYRO_MEDIA"
    }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAY -> onPlay()
                ACTION_PAUSE -> onPause()
                ACTION_NEXT -> onSkipNext()
                ACTION_PREV -> onSkipPrev()
            }
        }
    }

    init {
        createNotificationChannel()
        initMediaSession()
        registerMediaReceiver()
    }

    private fun registerMediaReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_PLAY)
                addAction(ACTION_PAUSE)
                addAction(ACTION_NEXT)
                addAction(ACTION_PREV)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ContextCompat.registerReceiver(
                    context,
                    mediaReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerMediaReceiver error", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zyro Player Media Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media controls for active playback"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSession(context, "ZyroPlayerMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    this@MediaSessionManager.onPlay()
                }

                override fun onPause() {
                    this@MediaSessionManager.onPause()
                }

                override fun onSkipToNext() {
                    this@MediaSessionManager.onSkipNext()
                }

                override fun onSkipToPrevious() {
                    this@MediaSessionManager.onSkipPrev()
                }

                override fun onSeekTo(pos: Long) {
                    this@MediaSessionManager.onSeek(pos)
                }

                override fun onStop() {
                    // «Стоп» в системе = пауза. Фокус ExoPlayer отпустит сам.
                    this@MediaSessionManager.onPause()
                }
            })

            // Устанавливаем флаги для приёма медиа-кнопок
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)

            // Тап по обложке на локскрине / в шторке открывает приложение
            setSessionActivity(createContentIntent())

            // Сессия активируется только когда есть трек (см. updateMediaSession)
            isActive = false
        }
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updateMediaSession(
        track: TrackInfo?,
        isPlaying: Boolean,
        currentPos: Long,
        duration: Long,
        queue: List<TrackInfo> = emptyList(),
        currentIndex: Int = 0
    ) {
        val session = mediaSession ?: return

        if (track == null) {
            updateToken.incrementAndGet()
            lastQueueSignature = null
            session.isActive = false
            session.setMetadata(null)
            session.setQueue(null)
            session.setQueueTitle(null)
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_NONE, 0L, 1.0f)
                    .build()
            )
            notificationManager.cancel(NOTIFICATION_ID)
            PlaybackService.stop(context)
            return
        }

        session.isActive = true
        updateQueue(session, queue, currentIndex)

        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(buildPlaybackState(state, currentPos, isPlaying))

        // Метаданные и уведомление публикуем сразу, не дожидаясь загрузки
        // обложки. Volumee и похожие приложения определяют плеер по медиа-
        // уведомлению, поэтому задержка на скачивание картинки недопустима.
        val cachedArtwork = getCachedBitmap(track.artworkUrl)
        session.setMetadata(buildMetadata(track, duration, cachedArtwork))
        showNotification(track, isPlaying, cachedArtwork)

        val token = updateToken.incrementAndGet()
        if (cachedArtwork == null && track.artworkUrl.isNotBlank()) {
            scope.launch {
                val artworkBitmap = getOrLoadBitmap(track.artworkUrl)
                withContext(Dispatchers.Main) {
                    if (token != updateToken.get()) return@withContext
                    if (artworkBitmap != null) {
                        session.setMetadata(buildMetadata(track, duration, artworkBitmap))
                        showNotification(track, isPlaying, artworkBitmap)
                    }
                }
            }
        }
    }

    /**
     * Обновляет только позицию/состояние сессии. Уведомление и обложку не
     * трогаем, поэтому это можно вызывать раз в секунду без мерцания.
     */
    fun updatePlaybackState(isPlaying: Boolean, currentPos: Long) {
        val session = mediaSession ?: return
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(buildPlaybackState(state, currentPos, isPlaying))
    }

    private fun buildPlaybackState(
        state: Int,
        currentPos: Long,
        isPlaying: Boolean
    ): PlaybackState {
        val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP

        return PlaybackState.Builder()
            .setActions(actions)
            .setState(
                state,
                currentPos.coerceAtLeast(0L),
                if (isPlaying) 1.0f else 0.0f
            )
            .build()
    }

    /**
     * Очередь в MediaSession нужна системным контроллерам и приложениям вроде
     * Volumee: по ней они понимают, что это полноценный плеер, и могут
     * показывать следующий/предыдущий трек. Строим её только при изменении.
     */
    private fun updateQueue(
        session: MediaSession,
        queue: List<TrackInfo>,
        currentIndex: Int
    ) {
        val signature = buildString {
            append(currentIndex)
            append('#')
            queue.forEach {
                append(it.originalUrl)
                append('|')
            }
        }
        if (signature == lastQueueSignature) return
        lastQueueSignature = signature

        if (queue.isEmpty()) {
            session.setQueue(null)
            session.setQueueTitle(null)
            return
        }

        val items = queue.mapIndexed { index, item ->
            val description = MediaDescription.Builder()
                .setMediaId(item.originalUrl)
                .setTitle(item.title)
                .setSubtitle(item.artist)
                .build()
            MediaSession.QueueItem(description, index.toLong())
        }
        session.setQueue(items)
        session.setQueueTitle("Zyro")
    }

    private fun buildMetadata(track: TrackInfo, duration: Long, artwork: Bitmap?): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "SoundCloud")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, track.artist)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, track.artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
        if (track.artworkUrl.isNotBlank()) {
            builder.putString(MediaMetadata.METADATA_KEY_ART_URI, track.artworkUrl)
            builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, track.artworkUrl)
        }
        if (artwork != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
        } else {
            // Пока обложка не загрузилась (или её нет), media-сессия показывает иконку приложения.
            appIconBitmap?.let {
                builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                builder.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
                builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }
        return builder.build()
    }

    private fun getCachedBitmap(urlStr: String): Bitmap? {
        if (urlStr.isBlank()) return null
        synchronized(bitmapCache) {
            return bitmapCache[urlStr]
        }
    }

    private suspend fun getOrLoadBitmap(urlStr: String): Bitmap? {
        if (urlStr.isBlank()) return null

        // Проверяем кеш
        synchronized(bitmapCache) {
            bitmapCache[urlStr]?.let { return it }
        }

        // Если нет в кеше – загружаем
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(SoundCloudArtwork.toOriginal(urlStr)).openConnection()
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val inputStream = connection.getInputStream()
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                // Сохраняем в кеш
                if (bitmap != null) {
                    synchronized(bitmapCache) {
                        bitmapCache[urlStr] = bitmap
                    }
                }
                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load artwork from $urlStr", e)
                // Оригинал может отсутствовать на CDN — пробуем t500x500,
                // иначе в шторке/на локскрине будет пустой артворк.
                val fallback = SoundCloudArtwork.toFallback(urlStr)
                if (fallback == urlStr) return@withContext null
                try {
                    val connection = URL(fallback).openConnection()
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    val inputStream = connection.getInputStream()
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    if (bitmap != null) {
                        synchronized(bitmapCache) { bitmapCache[urlStr] = bitmap }
                    }
                    bitmap
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to load artwork fallback from $fallback", e2)
                    null
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showNotification(track: TrackInfo, isPlaying: Boolean, artwork: Bitmap?) {
        val contentIntent = createContentIntent()

        val playPauseIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getBroadcast(
            context,
            2,
            Intent(ACTION_NEXT).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = PendingIntent.getBroadcast(
            context,
            3,
            Intent(ACTION_PREV).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_zyro)
                .setContentTitle(track.title)
                .setContentText(track.artist)
                .setLargeIcon(artwork ?: appIconBitmap)
                .setContentIntent(contentIntent)
                // Держим уведомление постоянным и на паузе: Volumee и другие
                // приложения считают плеер активным, пока видимо media-
                // уведомление. Скрываем его только при полной очистке трека.
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(mediaSession?.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .addAction(
                    android.R.drawable.ic_media_previous,
                    "Previous",
                    prevIntent
                )
                .addAction(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (isPlaying) "Pause" else "Play",
                    playPauseIntent
                )
                .addAction(
                    android.R.drawable.ic_media_next,
                    "Next",
                    nextIntent
                )

            // На Android 12+ показываем уведомление сразу при старте сервиса,
            // а не через стандартную задержку в 10 секунд.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }

            val notification = builder.build()
            // Сначала поднимаем foreground-сервис: он должен успеть вызвать
            // startForeground в течение нескольких секунд. Если notify() ниже
            // бросит исключение (например, из-за настроек уведомлений), музыка
            // и предзагрузка всё равно останутся в foreground.
            PlaybackService.setNotification(notification)
            PlaybackService.ensureStarted(context)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "showNotification error", e)
        }
    }

    fun release() {
        try {
            context.unregisterReceiver(mediaReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "unregisterReceiver error", e)
        }
        updateToken.incrementAndGet()
        lastQueueSignature = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        notificationManager.cancel(NOTIFICATION_ID)
        PlaybackService.stop(context)
        scope.cancel()
    }
}
