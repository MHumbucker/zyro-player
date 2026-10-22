package com.zyro.player.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zyro.player.data.remote.TrackInfo

fun formatDurationMs(ms: Long): String {
    if (ms <= 0) return ""
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, seconds)
}

fun formatCount(count: Long): String {
    if (count <= 0) return ""
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
        count >= 1_000 -> String.format("%.1fK", count / 1_000f)
        else -> count.toString()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListItem(
    track: TrackInfo,
    isPlaying: Boolean = false,
    isLiked: Boolean = false,
    isOffline: Boolean = false,
    isDownloading: Boolean = false,
    onTrackClick: () -> Unit,
    onSwipeRight: (() -> Unit)? = null,
    onLikeClick: (() -> Unit)? = null,
    onPlayNextClick: (() -> Unit)? = null,
    onAddToQueueClick: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    onDeleteOfflineClick: (() -> Unit)? = null,
    onAddToPlaylistClick: (() -> Unit)? = null,
    onRemoveClick: (() -> Unit)? = null,
    onArtistClick: (() -> Unit)? = null,
    onAlbumClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "track_item_scale"
    )

    ZyroSwipeRow(
        onSwipeRight = onSwipeRight,
        rightIcon = Icons.Default.PlaylistPlay,
        rightLabel = "Следующим",
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .zyroPlasticGlass(
                    shape = RoundedCornerShape(ZyroShapes.L),
                    tint = if (isPlaying) ZyroAccent else Color.Transparent
                )
                .then(
                    if (isPlaying) {
                        Modifier.border(
                            width = 1.dp,
                            color = ZyroAccent.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(ZyroShapes.L)
                        )
                    } else Modifier
                )
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onTrackClick,
                    onLongClick = { showMenu = true }
                )
                .testTag("track_item_${track.originalUrl.hashCode()}")
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Artwork
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(ZyroShapes.M))
                    .background(Color.White.copy(alpha = 0.04f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = track.artworkUrl.ifBlank { ZyroArtworkFallback },
                    contentDescription = "Track Artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ZyroAccent.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Playing",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Title & Artist & Sub-Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = if (isPlaying) ZyroAccent else ZyroTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = ZyroAccent,
                            trackColor = ZyroAccent.copy(alpha = 0.18f),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    } else if (isOffline) {
                        Icon(
                            imageVector = Icons.Default.DownloadDone,
                            contentDescription = "Downloaded",
                            tint = ZyroAccent,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                    if (track.isGoPlus) {
                        GoPlusBadge()
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = track.artist.ifBlank { "Unknown Artist" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = ZyroTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Extra info row: Duration, Playback count, Liked heart icon
                val durationStr = formatDurationMs(track.durationMs)
                val countStr = formatCount(track.playbackCount)
                val hasInfo = durationStr.isNotBlank() || countStr.isNotBlank() || isLiked
                if (hasInfo) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (durationStr.isNotBlank()) {
                            Text(
                                text = durationStr,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = ZyroTextSecondary.copy(alpha = 0.7f)
                            )
                        }
                        if (countStr.isNotBlank()) {
                            Text(
                                text = "• $countStr прослушиваний",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = ZyroTextSecondary.copy(alpha = 0.7f)
                            )
                        }
                        if (isLiked) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Liked",
                                tint = ZyroAccent,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }

            // 3-dots Menu
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Menu",
                        tint = ZyroTextSecondary
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    ZyroSurfaceElevated.copy(alpha = 0.95f),
                                    ZyroSurface.copy(alpha = 0.95f)
                                ),
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                            )
                        )
                        .border(1.dp, ZyroGlassBorder, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    if (onLikeClick != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isLiked) "Убрать из любимых" else "Нравится",
                                    color = ZyroTextPrimary
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (isLiked) ZyroAccent else ZyroTextPrimary
                                )
                            },
                            onClick = {
                                onLikeClick()
                                showMenu = false
                            }
                        )
                    }
                    if (onPlayNextClick != null) {
                        DropdownMenuItem(
                            text = { Text("Играть следующим", color = ZyroTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.PlaylistPlay,
                                    contentDescription = null,
                                    tint = ZyroAccent
                                )
                            },
                            onClick = {
                                onPlayNextClick()
                                showMenu = false
                            }
                        )
                    }
                    if (onAddToQueueClick != null) {
                        DropdownMenuItem(
                            text = { Text("Добавить в очередь", color = ZyroTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.QueueMusic,
                                    contentDescription = null,
                                    tint = ZyroAccent
                                )
                            },
                            onClick = {
                                onAddToQueueClick()
                                showMenu = false
                            }
                        )
                    }
                    if (onAddToPlaylistClick != null) {
                        DropdownMenuItem(
                            text = { Text("Добавить в плейлист", color = ZyroTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.PlaylistAdd,
                                    contentDescription = null,
                                    tint = ZyroTextPrimary
                                )
                            },
                            onClick = {
                                onAddToPlaylistClick()
                                showMenu = false
                            }
                        )
                    }
                    if (onRemoveClick != null) {
                        DropdownMenuItem(
                            text = { Text("Удалить из плейлиста", color = ZyroTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = ZyroTextPrimary
                                )
                            },
                            onClick = {
                                onRemoveClick()
                                showMenu = false
                            }
                        )
                    }
                    if (onArtistClick != null) {
                        DropdownMenuItem(
                            text = { Text("Перейти в профиль исполнителя", color = ZyroTextPrimary) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = ZyroTextPrimary
                                )
                            },
                            onClick = {
                                onArtistClick()
                                showMenu = false
                            }
                        )
                    }
                    if (onAlbumClick != null && track.albumPermalinkUrl.isNotBlank()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (track.albumTitle.isNotBlank()) "Альбом: ${track.albumTitle}"
                                    else "Перейти к альбому",
                                    color = ZyroTextPrimary
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Album,
                                    contentDescription = null,
                                    tint = ZyroTextPrimary
                                )
                            },
                            onClick = {
                                onAlbumClick()
                                showMenu = false
                            }
                        )
                    }
                    if (onDownloadClick != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when {
                                        isDownloading -> "Скачивается…"
                                        isOffline -> "Скачано (Офлайн)"
                                        else -> "Скачать офлайн"
                                    },
                                    color = ZyroTextPrimary
                                )
                            },
                            enabled = !isDownloading && !isOffline,
                            leadingIcon = {
                                Icon(
                                    imageVector = when {
                                        isDownloading -> Icons.Default.Downloading
                                        isOffline -> Icons.Default.DownloadDone
                                        else -> Icons.Default.Download
                                    },
                                    contentDescription = null,
                                    tint = if (isOffline || isDownloading) ZyroAccent else ZyroTextPrimary
                                )
                            },
                            onClick = {
                                onDownloadClick()
                                showMenu = false
                            }
                        )
                    }
                    if (isOffline && onDeleteOfflineClick != null) {
                        DropdownMenuItem(
                            text = { Text("Удалить из офлайн", color = ZyroError) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = ZyroError
                                )
                            },
                            onClick = {
                                onDeleteOfflineClick()
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }
        }
    }
}