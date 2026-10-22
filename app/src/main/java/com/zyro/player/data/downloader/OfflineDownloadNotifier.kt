package com.zyro.player.data.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zyro.player.MainActivity

/**
 * Progress-уведомление пакетного офлайн-кеширования.
 *
 * Показывает «12/300» и название текущего трека. Отдельный foreground
 * service не нужен: загрузка живёт в scope MainViewModel.
 */
class OfflineDownloadNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Офлайн-кеш",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Прогресс сохранения треков для прослушивания без сети"
            setShowBadge(false)
        }
        appContext.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun update(state: OfflineDownloadState) {
        if (!state.isActive) return

        val current = state.currentTrackTitle.ifBlank { "Подготовка…" }
        val builder = baseBuilder()
            .setContentTitle("Офлайн-кеш · ${state.title}")
            .setContentText("${state.currentNumber}/${state.total} · $current")
            .setSubText("Сохраняем для прослушивания без сети")
            .setProgress(state.total.coerceAtLeast(1), state.completed, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun finish(state: OfflineDownloadState) {
        val text = buildString {
            append("Готово: ${state.completed} из ${state.total}")
            if (state.failed > 0) append(" · ошибок: ${state.failed}")
        }
        val builder = baseBuilder()
            .setContentTitle("Офлайн-кеш · ${state.title}")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(7_000)

        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun cancel() {
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private fun baseBuilder(): NotificationCompat.Builder {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    companion object {
        private const val CHANNEL_ID = "zyro_offline_downloads"
        private const val NOTIFICATION_ID = 4107
    }
}
