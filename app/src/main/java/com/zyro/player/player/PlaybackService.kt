package com.zyro.player.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.zyro.player.MainActivity
import com.zyro.player.R
import com.zyro.player.ZyroApplication

/**
 * Foreground-сервис воспроизведения.
 *
 * Сам плеер живёт в [com.player.player.AudioPlayerManager], а сервис держит
 * процесс в foreground, пока загружен трек. Без этого Android в фоне
 * замораживает сеть и процесс: текущий трек перестаёт буферизоваться,
 * а предзагрузка следующего не успевает завершиться.
 *
 * Уведомление строит [MediaSessionManager] — сервис показывает его же через
 * startForeground, поэтому в шторке не появляется двух уведомлений.
 */
class PlaybackService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        private const val TAG = "ZYRO_PLAYBACK_SERVICE"

        @Volatile
        private var isRunning = false

        @Volatile
        var currentNotification: Notification? = null
            private set

        fun setNotification(notification: Notification?) {
            currentNotification = notification
        }

        /**
         * Запускает foreground-сервис. Если он уже работает, повторный
         * startForegroundService просто доставит onStartCommand и освежит
         * foreground-состояние — это лучше, чем полагаться на статический
         * isRunning, который может устареть после kill процесса.
         */
        fun ensureStarted(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PlaybackService::class.java)
                )
            } catch (e: Exception) {
                // На Android 12+ запуск FGS из фона может быть запрещён.
                // Это не критично: плеер продолжит работать, пока процесс жив.
                Log.e(TAG, "startForegroundService failed", e)
            }
        }

        fun stop(context: Context) {
            currentNotification = null
            try {
                context.stopService(Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "stopService failed", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val systemRestart = intent == null
        val notification = currentNotification ?: buildFallbackNotification()

        try {
            promoteToForeground(notification)
            isRunning = true
            acquireWakeLock()
            Log.i(TAG, "Foreground playback started (systemRestart=$systemRestart)")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        // Система перезапустила сервис после смерти процесса. Если до этого
        // музыка играла, поднимаем очередь из PlaybackStateStore и продолжаем
        // с сохранённой позиции. При обычном старте (intent != null) ничего
        // не запускаем: пользователь сам нажал Play.
        if (systemRestart) {
            val keepAlive = try {
                (application as? ZyroApplication)?.playerManager?.resumeIfWasPlaying() ?: false
            } catch (e: Exception) {
                Log.e(TAG, "resumeIfWasPlaying failed", e)
                false
            }

            if (!keepAlive) {
                // Сервис перезапущен системой, но музыка не играла (или
                // очередь пуста). Не держим пустое foreground-уведомление.
                Log.i(TAG, "Nothing to resume; stopping restarted service")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MediaSessionManager.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(MediaSessionManager.NOTIFICATION_ID, notification)
        }
    }

    /**
     * Минимальное уведомление на случай, если MediaSessionManager ещё не
     * успел собрать MediaStyle-уведомление. Без него startForeground
     * невозможен, а значит процесс может быть убит при свайпе задачи.
     */
    private fun buildFallbackNotification(): Notification {
        ensureNotificationChannel()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, MediaSessionManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zyro)
            .setContentTitle("Zyro")
            .setContentText("Воспроизведение")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(MediaSessionManager.CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            MediaSessionManager.CHANNEL_ID,
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

    /**
     * Не даём процессу заснуть, пока сервис в foreground. Иначе при
     * выключенном экране Android может приостановить CPU, и предзагрузка
     * следующих треков не успеет завершиться.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:playback"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "acquireWakeLock failed", e)
        }
    }

    /**
     * Задача может быть смахнута, но плеер принадлежит процессу. Сервис
     * остаётся foreground, чтобы музыка и загрузка следующего трека не
     * прерывались. Останавливаем его только при очистке текущего трека.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed; keeping playback service alive")

        // Фиксируем точную позицию: после свайпа система может убить процесс
        // без предупреждения, а при следующем запуске очередь должна
        // восстановиться с того же места.
        try {
            (application as? ZyroApplication)?.playerManager?.persistNow()
        } catch (e: Exception) {
            Log.e(TAG, "persistNow failed", e)
        }

        // Лишний раз подтверждаем foreground-статус: некоторые OEM-прошивки
        // агрессивнее убивают сервисы, если не видят свежую нотификацию.
        try {
            promoteToForeground(currentNotification ?: buildFallbackNotification())
        } catch (e: Exception) {
            Log.e(TAG, "promoteToForeground on task removal failed", e)
        }

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        try {
            (application as? ZyroApplication)?.playerManager?.persistNow()
        } catch (e: Exception) {
            Log.e(TAG, "persistNow on destroy failed", e)
        }
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "releaseWakeLock failed", e)
        } finally {
            wakeLock = null
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "stopForeground failed", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
