package com.zyro.player.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Инкрементальная отправка обезличенной статистики (только Wi‑Fi)
 * и заглушка загрузки лёгких весов модели.
 *
 * Сейчас без реального бэкенда: локальный no-op / log.
 * Подключи URL API когда появится сервер.
 */
class RecSyncManager(
    private val context: Context,
    private val interactionLogger: InteractionLogger
) {
    companion object {
        private const val TAG = "RecSync"
        // const val AGGREGATE_URL = "https://api.example.com/v1/rec/aggregate"
        // const val WEIGHTS_URL = "https://api.example.com/v1/rec/weights"
    }

    private fun onWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Отправить агрегат genre/time weights — без track URL и имён пользователей.
     */
    suspend fun syncAnonymousAggregateIfWifi(): Boolean = withContext(Dispatchers.IO) {
        if (!onWifi()) {
            Log.d(TAG, "Skip aggregate sync: not on Wi‑Fi")
            return@withContext false
        }
        val aggregate = interactionLogger.buildAnonymousAggregate()
        if (aggregate.isEmpty()) return@withContext false
        Log.d(TAG, "Would upload ${aggregate.size} preference keys (stub)")
        // TODO: HTTP POST JSON aggregate to backend
        true
    }

    /**
     * Периодическая подгрузка квантованных весов (stub).
     */
    suspend fun refreshModelWeightsIfWifi(): Boolean = withContext(Dispatchers.IO) {
        if (!onWifi()) return@withContext false
        Log.d(TAG, "Model weights refresh stub — keep local profile")
        // TODO: download small weight file and merge into PreferenceDao
        true
    }
}
