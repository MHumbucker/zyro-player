package com.zyro.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyro.player.data.downloader.OfflineDownloadState

/**
 * Кнопка «Скачать в офлайн-кеш».
 *
 * - обычное состояние: иконка загрузки;
 * - идёт скачивание этой коллекции: круговой прогресс с числом готовых треков;
 * - коллекция уже сохранена: галочка.
 */
@Composable
fun OfflineDownloadButton(
    state: OfflineDownloadState,
    collectionKey: String?,
    isDownloaded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp
) {
    val isActive = state.isActive && state.collectionKey == collectionKey
    val enabled = !state.isActive
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    isActive -> ZyroAccent.copy(alpha = 0.18f)
                    isDownloaded -> ZyroAccent.copy(alpha = 0.12f)
                    else -> ZyroAccent.copy(alpha = 0.08f)
                }
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            isActive -> {
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.size(size - 8.dp),
                    color = ZyroAccent,
                    trackColor = ZyroAccent.copy(alpha = 0.18f),
                    strokeWidth = 2.5.dp
                )
                Text(
                    text = "${state.completed}",
                    color = ZyroAccent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            isDownloaded -> {
                Icon(
                    imageVector = Icons.Default.DownloadDone,
                    contentDescription = "Сохранено офлайн",
                    tint = ZyroAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Скачать офлайн",
                    tint = if (enabled) ZyroAccent else ZyroAccent.copy(alpha = 0.35f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
