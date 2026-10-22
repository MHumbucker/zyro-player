package com.zyro.player.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zyro.player.data.local.PlaylistEntity
import com.zyro.player.data.downloader.OfflineDownloadState
import com.zyro.player.data.remote.TrackInfo
import com.zyro.player.ui.components.OfflineDownloadButton
import com.zyro.player.ui.components.TrackListItem
import com.zyro.player.ui.components.ZyroAccent
import com.zyro.player.ui.components.ZyroArtworkFallback
import com.zyro.player.ui.components.ZyroBackground
import com.zyro.player.ui.components.ZyroGlassBorder
import com.zyro.player.ui.components.ZyroIconButton
import com.zyro.player.ui.components.ZyroPullToRefreshBox
import com.zyro.player.ui.components.ZyroSurface
import com.zyro.player.ui.components.ZyroSurfaceElevated
import com.zyro.player.ui.components.ZyroTextPrimary
import com.zyro.player.ui.components.ZyroTextSecondary
import com.zyro.player.ui.components.zyroPlasticGlass
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

fun Modifier.zyroGlass(
    cornerRadius: Dp = 24.dp,
    backgroundAlpha: Float = 0.07f
): Modifier = this.zyroPlasticGlass(
    shape = RoundedCornerShape(cornerRadius),
    strong = backgroundAlpha > 0.08f
)

/**
 * Сообщает, уехал ли список под плавающую шапку: нужен для затемнения.
 * Список считается прокрученным, как только первая строка ушла вверх.
 */
@Composable
fun ObserveScrolledUnder(
    listState: LazyListState,
    onScrolledChange: (Boolean) -> Unit
) {
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
            .distinctUntilChanged()
            .collect(onScrolledChange)
    }
}

@Composable
fun PressScaleBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "press_scale"
    )
    Box(
        modifier = modifier
            .zyroGlass()
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        content = content
    )
}

@Composable
fun QuickLibraryCard(
    title: String,
    subtitle: String,
    icon: ImageVector? = null,
    painter: Painter? = null,
    tint: Color? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "card_scale"
    )

    Box(
        modifier = modifier
            .zyroGlass(cornerRadius = 24.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ZyroAccent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    icon != null -> {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = ZyroAccent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    painter != null -> {
                        Icon(
                            painter = painter,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = ZyroTextPrimary
                    )
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZyroTextSecondary
                )
            }
        }
    }
}

@Composable
fun LibraryCollectionHeader(
    title: String,
    count: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(ZyroAccent.copy(alpha = 0.12f))
                .border(1.dp, ZyroAccent.copy(alpha = 0.18f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ZyroAccent,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = ZyroTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.weight(1f))
        action?.invoke()
        if (action != null) Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = ZyroAccent,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ZyroAccent.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun LibrarySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Поиск",
    /** Горизонтальный отступ поля. В LazyColumn с собственным contentPadding его убирают. */
    horizontalPadding: Dp = 20.dp
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 6.dp),
        placeholder = {
            Text(
                text = placeholder,
                color = ZyroTextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = ZyroTextSecondary,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Очистить поиск",
                        tint = ZyroTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = ZyroSurface.copy(alpha = 0.55f),
            unfocusedContainerColor = ZyroSurface.copy(alpha = 0.35f),
            focusedBorderColor = ZyroAccent.copy(alpha = 0.48f),
            unfocusedBorderColor = ZyroGlassBorder,
            cursorColor = ZyroAccent,
            focusedTextColor = ZyroTextPrimary,
            unfocusedTextColor = ZyroTextPrimary,
            focusedPlaceholderColor = ZyroTextSecondary,
            unfocusedPlaceholderColor = ZyroTextSecondary
        )
    )
}

@Composable
fun LibraryCollectionRow(
    title: String,
    subtitle: String,
    artworkUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkShape: Shape = RoundedCornerShape(18.dp),
    fallbackIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    downloadState: OfflineDownloadState = OfflineDownloadState(),
    downloadCollectionKey: String? = null,
    isDownloaded: Boolean = false
) {
    PressScaleBox(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(artworkShape)
                    .background(ZyroSurfaceElevated)
                    .border(1.dp, ZyroGlassBorder, artworkShape),
                contentAlignment = Alignment.Center
            ) {
                if (artworkUrl.isBlank() && fallbackIcon != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ZyroAccent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = fallbackIcon,
                            contentDescription = null,
                            tint = ZyroAccent,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                } else {
                    AsyncImage(
                        model = artworkUrl.ifBlank { ZyroArtworkFallback },
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f))
                                )
                            )
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = ZyroTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZyroTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            if (onDownloadClick != null) {
                OfflineDownloadButton(
                    state = downloadState,
                    collectionKey = downloadCollectionKey,
                    isDownloaded = isDownloaded,
                    onClick = onDownloadClick
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            if (trailing != null) {
                trailing()
            } else {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(ZyroAccent.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = ZyroAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TrackListTabContent(
    tracks: List<TrackInfo>,
    currentTrackUrl: String?,
    emptyText: String,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onDownloadTrack: (TrackInfo) -> Unit,
    onDeleteOfflineTrack: ((TrackInfo) -> Unit)? = null,
    likedUrls: Set<String>,
    offlineUrls: Set<String>,
    onPlayNext: ((TrackInfo) -> Unit)? = null,
    onAddToQueue: ((TrackInfo) -> Unit)? = null,
    onArtistClick: ((TrackInfo) -> Unit)? = null,
    onDownloadAll: (() -> Unit)? = null,
    downloadState: OfflineDownloadState = OfflineDownloadState(),
    downloadCollectionKey: String? = null,
    isDownloaded: Boolean = false,
    downloadingUrl: String? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    /** Отступ сверху под плавающую шапку. */
    contentTopPadding: Dp = 0.dp,
    /** Необязательный элемент над списком (например, поиск). Скроллится вместе с контентом. */
    header: (@Composable () -> Unit)? = null,
    /** Уведомляет, что список уехал под шапку — для градиентного затемнения. */
    onScrolledChange: ((Boolean) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    if (onScrolledChange != null) {
        ObserveScrolledUnder(listState, onScrolledChange)
    }

    val content: @Composable () -> Unit = {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = contentTopPadding + 8.dp,
                bottom = 210.dp
            )
        ) {
            if (header != null) {
                item { header() }
            }
            if (tracks.isEmpty()) {
                item {
                    EmptyLibraryState(
                        message = emptyText,
                        modifier = Modifier.fillParentMaxHeight(0.65f)
                    )
                }
            } else {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Всего: ${tracks.size} треков",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZyroTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val scale by animateFloatAsState(
                        targetValue = if (isPressed) 0.95f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "play_all_scale"
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onDownloadAll != null) {
                            OfflineDownloadButton(
                                state = downloadState,
                                collectionKey = downloadCollectionKey,
                                isDownloaded = isDownloaded,
                                onClick = onDownloadAll,
                                size = 40.dp
                            )
                        }
                        ZyroIconButton(
                            icon = Icons.Default.Shuffle,
                            onClick = {
                                val shuffled = tracks.shuffled()
                                if (shuffled.isNotEmpty()) onTrackClick(shuffled.first(), shuffled)
                            },
                            size = 40.dp,
                            iconSize = 18.dp,
                            tint = ZyroAccent,
                            contentDescription = "Перемешать"
                        )
                        Box(
                            modifier = Modifier
                                .scale(scale)
                                .clip(RoundedCornerShape(16.dp))
                                .background(ZyroAccent)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = { onTrackClick(tracks.first(), tracks) }
                                )
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = ZyroBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Слушать все",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = ZyroBackground
                                    )
                                )
                            }
                        }
                    }
                }
            }
            items(tracks, key = { it.originalUrl }) { track ->
                TrackListItem(
                    track = track,
                    isPlaying = track.originalUrl == currentTrackUrl,
                    isLiked = likedUrls.contains(track.originalUrl),
                    isOffline = offlineUrls.contains(track.originalUrl),
                    isDownloading = downloadingUrl == track.originalUrl,
                    onTrackClick = { onTrackClick(track, tracks) },
                    onSwipeRight = onPlayNext?.let { cb -> { cb(track) } },
                    onLikeClick = { onLikeTrack(track) },
                    onDownloadClick = { onDownloadTrack(track) },
                    onDeleteOfflineClick = onDeleteOfflineTrack?.let { cb -> { cb(track) } },
                    onPlayNextClick = onPlayNext?.let { cb -> { cb(track) } },
                    onAddToQueueClick = onAddToQueue?.let { cb -> { cb(track) } },
                    onArtistClick = onArtistClick?.let { cb -> { cb(track) } },
                    onAlbumClick = if (track.albumPermalinkUrl.isNotBlank()) {
                        { /* album open via parent if needed */ }
                    } else null,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        }
    }

    if (onRefresh != null) {
        ZyroPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            indicatorTopPadding = contentTopPadding + 32.dp
        ) {
            content()
        }
    } else {
        content()
    }
}

@Composable
fun PlaylistRowItem(
    playlist: PlaylistEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onDownloadClick: (() -> Unit)? = null,
    downloadState: OfflineDownloadState = OfflineDownloadState(),
    downloadCollectionKey: String? = null,
    isDownloaded: Boolean = false,
    /** Обложка первого трека, если у самого плейлиста картинки нет. */
    coverFallbackUrl: String = ""
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    PressScaleBox(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val cover = playlist.localCoverPath
                .ifBlank { playlist.coverUrl }
                .ifBlank { coverFallbackUrl }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(ZyroAccent.copy(alpha = 0.12f))
                    .border(
                        width = 1.dp,
                        color = ZyroAccent.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (cover.isNotBlank()) {
                    AsyncImage(
                        model = if (cover.startsWith("/")) File(cover) else cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = ZyroAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = ZyroTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = playlist.description.ifBlank { "Локальный плейлист" },
                    style = MaterialTheme.typography.bodySmall,
                    color = ZyroTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (onDownloadClick != null) {
                OfflineDownloadButton(
                    state = downloadState,
                    collectionKey = downloadCollectionKey,
                    isDownloaded = isDownloaded,
                    onClick = onDownloadClick
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Удалить плейлист",
                    tint = ZyroTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(ZyroAccent.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ZyroAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = ZyroSurfaceElevated,
            titleContentColor = ZyroTextPrimary,
            textContentColor = ZyroTextSecondary,
            title = {
                Text(
                    text = "Удалить плейлист?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text("Плейлист «${playlist.title}» будет удалён без возможности восстановления.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text("Удалить", color = ZyroAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Отмена", color = ZyroTextSecondary)
                }
            }
        )
    }
}

@Composable
fun EmptyLibraryState(
    message: String,
    onAction: (() -> Unit)? = null,
    actionText: String = "",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(ZyroSurface.copy(alpha = 0.5f))
                .border(1.dp, ZyroGlassBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.LibraryMusic,
                contentDescription = "Пустой раздел библиотеки",
                modifier = Modifier.size(36.dp),
                tint = ZyroTextSecondary.copy(alpha = 0.4f)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = ZyroTextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        if (onAction != null) {
            Spacer(modifier = Modifier.height(24.dp))
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (isPressed) 0.95f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "empty_action_scale"
            )
            Box(
                modifier = Modifier
                    .scale(scale)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ZyroAccent)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onAction
                    )
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = ZyroBackground
                    )
                )
            }
        }
    }
}