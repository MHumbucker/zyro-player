package com.zyro.player.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.zyro.player.data.remote.TrackInfo
import com.zyro.player.ui.components.ZyroAccent
import com.zyro.player.ui.components.ZyroAnim
import com.zyro.player.ui.components.ZyroCyan
import com.zyro.player.ui.components.ZyroError
import com.zyro.player.ui.components.ZyroMint
import com.zyro.player.ui.components.ZyroScrim
import com.zyro.player.ui.components.ZyroScrimEnter
import com.zyro.player.ui.components.ZyroScrimExit
import com.zyro.player.ui.components.ZyroShapes
import com.zyro.player.ui.components.ZyroTextPrimary
import com.zyro.player.ui.components.ZyroTextSecondary
import com.zyro.player.ui.components.ZyroViolet
import com.zyro.player.ui.components.ZyroIconButton
import com.zyro.player.ui.components.zyroPlasticGlass

/**
 * Шторка «Ещё» из полноэкранного плеера.
 *
 * Полностью на общей дизайн-системе Zyro: та же «пластиковая» стеклянная
 * поверхность и мягкий выезд снизу, без лишних эффектов на пунктах.
 */
@Composable
fun TrackMoreBottomSheet(
    track: TrackInfo,
    visible: Boolean,
    onDismiss: () -> Unit,
    onArtistClick: (TrackInfo) -> Unit,
    onAlbumClick: ((TrackInfo) -> Unit)? = null,
    onTrackInfoClick: () -> Unit = {},
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onToggleLike: (() -> Unit)? = null,
    isLiked: Boolean = false,
    onDownload: (() -> Unit)? = null,
    onDownloadToDownloads: (() -> Unit)? = null,
    onDeleteOffline: (() -> Unit)? = null,
    isOffline: Boolean = false,
    onAddToPlaylist: (() -> Unit)? = null,
    onScreenCommentsEnabled: Boolean = false,
    onToggleOnScreenComments: (() -> Unit)? = null
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val context = LocalContext.current

    AnimatedVisibility(
        visible = visible,
        enter = ZyroScrimEnter,
        exit = ZyroScrimExit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ZyroScrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(
                    animationSpec = tween(ZyroAnim.Medium, easing = FastOutSlowInEasing)
                ) { it } + fadeIn(tween(ZyroAnim.Fast)),
                exit = slideOutVertically(
                    animationSpec = tween(220, easing = FastOutLinearInEasing)
                ) { it } + fadeOut(tween(160))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = screenHeight * 0.86f)
                        .zyroPlasticGlass(
                            shape = RoundedCornerShape(
                                topStart = 32.dp,
                                topEnd = 32.dp
                            ),
                            strong = true
                        )
                        // Не даём клику по самой плашке закрывать шторку.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent()
                            }
                        }
                        .navigationBarsPadding()
                ) {
                    // Ручка-индикатор
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(ZyroTextSecondary.copy(alpha = 0.28f))
                        )
                    }

                    // Заголовок
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ещё",
                                color = ZyroTextPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                lineHeight = 27.sp
                            )
                            Text(
                                text = track.title,
                                color = ZyroTextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        ZyroIconButton(
                            icon = Icons.Default.Close,
                            onClick = onDismiss,
                            size = 40.dp,
                            iconSize = 20.dp
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Пункты меню
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        val actions = buildList {
                            if (onToggleLike != null) {
                                add(
                                    MoreAction(
                                        icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        label = if (isLiked) "Убрать из любимых" else "Нравится",
                                        tint = ZyroAccent,
                                        onClick = {
                                            onDismiss()
                                            onToggleLike.invoke()
                                        }
                                    )
                                )
                            }
                            if (onToggleOnScreenComments != null) {
                                add(
                                    MoreAction(
                                        icon = if (onScreenCommentsEnabled) {
                                            Icons.Default.ChatBubble
                                        } else {
                                            Icons.Default.ChatBubbleOutline
                                        },
                                        label = if (onScreenCommentsEnabled) {
                                            "Скрыть комментарии на экране"
                                        } else {
                                            "Комментарии на экране"
                                        },
                                        tint = ZyroCyan,
                                        onClick = onToggleOnScreenComments
                                    )
                                )
                            }
                            if (onPlayNext != null) {
                                add(
                                    MoreAction(
                                        icon = Icons.Default.PlaylistPlay,
                                        label = "Играть следующим",
                                        tint = ZyroCyan,
                                        onClick = {
                                            onDismiss()
                                            onPlayNext.invoke()
                                        }
                                    )
                                )
                            }
                            if (onAddToQueue != null) {
                                add(
                                    MoreAction(
                                        icon = Icons.Default.QueueMusic,
                                        label = "Добавить в очередь",
                                        tint = ZyroCyan,
                                        onClick = {
                                            onDismiss()
                                            onAddToQueue.invoke()
                                        }
                                    )
                                )
                            }
                            if (onAddToPlaylist != null) {
                                add(
                                    MoreAction(
                                        icon = Icons.Default.PlaylistAdd,
                                        label = "Добавить в плейлист",
                                        tint = ZyroViolet,
                                        onClick = {
                                            onDismiss()
                                            onAddToPlaylist.invoke()
                                        }
                                    )
                                )
                            }
                            if (onDownload != null) {
                                add(
                                    MoreAction(
                                        icon = if (isOffline) Icons.Default.DownloadDone else Icons.Default.Download,
                                        label = if (isOffline) "Скачано (офлайн)" else "Скачать офлайн",
                                        tint = ZyroMint,
                                        onClick = {
                                            if (!isOffline) {
                                                onDismiss()
                                                onDownload.invoke()
                                            }
                                        }
                                    )
                                )
                            }
                            if (isOffline && onDeleteOffline != null) {
                                add(
                                    MoreAction(
                                        icon = Icons.Default.DeleteOutline,
                                        label = "Удалить из офлайн",
                                        tint = ZyroError,
                                        onClick = {
                                            onDismiss()
                                            onDeleteOffline.invoke()
                                        }
                                    )
                                )
                            }
                            if (onDownloadToDownloads != null) {
                                add(
                                    MoreAction(
                                        icon = Icons.Default.FileDownload,
                                        label = "Скачать в Загрузки",
                                        tint = ZyroMint,
                                        onClick = {
                                            onDismiss()
                                            onDownloadToDownloads.invoke()
                                        }
                                    )
                                )
                            }
                            add(
                                MoreAction(
                                    icon = Icons.Default.ContentCopy,
                                    label = "Скопировать ссылку",
                                    tint = ZyroTextSecondary,
                                    onClick = {
                                        copyToClipboard(context, track.originalUrl)
                                        onDismiss()
                                    }
                                )
                            )
                            add(
                                MoreAction(
                                    icon = Icons.Default.Person,
                                    label = "Перейти к исполнителю",
                                    tint = ZyroCyan,
                                    onClick = {
                                        onDismiss()
                                        onArtistClick(track)
                                    }
                                )
                            )
                            if (onAlbumClick != null &&
                                (track.albumTitle.isNotBlank() || track.albumPermalinkUrl.isNotBlank())
                            ) {
                                add(
                                    MoreAction(
                                        icon = Icons.Default.Album,
                                        label = if (track.albumTitle.isNotBlank()) {
                                            "Альбом: ${track.albumTitle}"
                                        } else {
                                            "Перейти к альбому"
                                        },
                                        tint = ZyroViolet,
                                        onClick = {
                                            onDismiss()
                                            onAlbumClick.invoke(track)
                                        }
                                    )
                                )
                            }
                            add(
                                MoreAction(
                                    icon = Icons.Default.Info,
                                    label = "Информация о треке",
                                    tint = ZyroTextSecondary,
                                    onClick = {
                                        onDismiss()
                                        onTrackInfoClick()
                                    }
                                )
                            )
                        }

                        actions.forEach { action ->
                            MoreMenuItem(action = action)
                        }
                    }
                }
            }
        }
    }
}

private data class MoreAction(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
    val onClick: () -> Unit
)

@Composable
private fun MoreMenuItem(
    action: MoreAction
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zyroPlasticGlass(
                shape = RoundedCornerShape(ZyroShapes.M),
                tint = action.tint
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = action.onClick
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(action.tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = action.tint,
                modifier = Modifier.size(19.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = action.label,
            color = ZyroTextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("SoundCloud URL", text))
}
