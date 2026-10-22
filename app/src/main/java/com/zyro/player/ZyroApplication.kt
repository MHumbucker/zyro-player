package com.zyro.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.player.player.AudioPlayerManager
import com.zyro.player.data.auth.ScConfig
import com.zyro.player.data.auth.SoundCloudAuthManager
import com.zyro.player.data.local.AppDatabase
import com.zyro.player.data.remote.SoundCloudArtworkInterceptor
import com.zyro.player.data.remote.SoundCloudRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Кастомный загрузчик картинок Coil.
 *
 * Главная задача — гарантировать максимальное качество обложек SoundCloud:
 * запрос поднимается до `-original`, а при 404 прозрачно откатывается
 * на `t500x500` (см. [SoundCloudArtworkInterceptor]).
 */
class ZyroApplication : Application(), ImageLoaderFactory {

    /**
     * Общие зависимости уровня процесса.
     *
     * Плеер раньше создавался внутри MainViewModel и освобождался в onCleared().
     * Из-за этого при закрытии Activity (в том числе свайпом задачи) музыка
     * останавливалась, а предзагрузка следующих треков отменялась. Теперь плеер
     * живёт столько же, сколько процесс, и его может удерживать foreground-сервис.
     */
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val authManager: SoundCloudAuthManager by lazy { SoundCloudAuthManager(this) }

    val soundCloudRepository: SoundCloudRepository by lazy {
        SoundCloudRepository(
            cacheDir = cacheDir,
            clientIdFallbacks = listOf(ScConfig.OFFICIAL_CLIENT_ID),
            authTokenProvider = { authManager.getAccessToken() },
            deviceIdProvider = { authManager.getDeviceId() },
            authTokenRefresher = { staleToken ->
                authManager.refreshAccessTokenBlocking(staleToken)
            }
        )
    }

    val playerManager: AudioPlayerManager by lazy {
        AudioPlayerManager(
            context = this,
            repository = soundCloudRepository,
            trackDao = database.trackDao(),
            historyDao = database.historyDao()
        )
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .addInterceptor(SoundCloudArtworkInterceptor())
                    .build()
            }
            .build()
}
