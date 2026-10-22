package com.zyro.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.zyro.player.data.downloader.OfflineDownloadState
import com.zyro.player.data.remote.AlbumInfo
import com.zyro.player.data.remote.ArtistFullData
import com.zyro.player.data.remote.PlaylistInfo
import com.zyro.player.data.remote.TrackInfo
import com.zyro.player.domain.PlaylistDraft
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailModal(
    playlist: PlaylistInfo,
    tracks: List<TrackInfo>,
    currentTrackUrl: String?,
    likedUrls: Set<String>,
    offlineUrls: Set<String>,
    isLiked: Boolean = false,
    isFollowing: Boolean = false,
    /** Для локальных плейлистов лайк на SoundCloud не имеет смысла. */
    showLikeAction: Boolean = true,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    /** Изменять и удалять можно только собственные плейлисты. */
    canManage: Boolean = true,
    /** Источники для «Добавить из»: лайки и доступные плейлисты. */
    addSources: List<PlaylistAddSource> = emptyList(),
    onLoadSourceTracks: suspend (PlaylistAddSource) -> List<TrackInfo> = { emptyList() },
    onAddTracks: (List<TrackInfo>) -> Unit = {},
    onLikePlaylist: () -> Unit = {},
    onFollowArtist: () -> Unit = {},
    onAddTrackToPlaylist: (Long) -> Unit = {},
    onRemoveTrackFromPlaylist: (Long) -> Unit = {},
    /** Локальное удаление: в Room трек адресуется originalUrl, а не SoundCloud id. */
    onRemoveTrackFromPlaylistUrl: ((String) -> Unit)? = null,
    onDeletePlaylist: () -> Unit = {},
    onEditPlaylist: (PlaylistDraft) -> Unit = {},
    onClose: () -> Unit,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onDownloadTrack: (TrackInfo) -> Unit,
    onDeleteOfflineTrack: ((TrackInfo) -> Unit)? = null,
    onDownloadAll: (() -> Unit)? = null,
    /** Удаление скачанного плейлиста целиком (офлайн-коллекция). */
    onRemoveOfflineCollection: (() -> Unit)? = null,
    downloadState: OfflineDownloadState = OfflineDownloadState(),
    downloadCollectionKey: String? = null,
    isDownloaded: Boolean = false
) {
    BackHandler {
        onClose()
    }

    var showEdit by remember { mutableStateOf(false) }
    var showAddSources by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pickerSource by remember { mutableStateOf<PlaylistAddSource?>(null) }
    val addSourcesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val visibleAddSources = remember(addSources, playlist) {
        addSources.filterNot { source ->
            (source.playlist != null &&
                source.playlist.id == playlist.id &&
                playlist.permalinkUrl.isNotBlank()) ||
                (source.localPlaylistId != null &&
                    source.localPlaylistId == playlist.id &&
                    playlist.permalinkUrl.isBlank())
        }
    }

    val isServerPlaylist = playlist.permalinkUrl.isNotBlank()
    val trackCount = if (tracks.isNotEmpty()) tracks.size else playlist.trackCount
    val totalDurationMs = remember(tracks, playlist.durationMs) {
        tracks.sumOf { it.durationMs }.takeIf { it > 0L } ?: playlist.durationMs
    }
    val tags = remember(playlist.tagList) {
        playlist.tagList.split(',')
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotBlank() }
    }

    ZyroAuroraBackground {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("playlist_detail_screen"),
            color = Color.Transparent
        ) {
        var headerHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        val listState = rememberLazyListState()
        val isScrolled by remember {
            derivedStateOf {
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .zIndex(2f)
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                title = {
                    Text(
                        text = "Плейлист",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = ZyroTextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (onDownloadAll != null) {
                        OfflineDownloadButton(
                            state = downloadState,
                            collectionKey = downloadCollectionKey,
                            isDownloaded = isDownloaded,
                            onClick = onDownloadAll,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    if (showLikeAction) {
                        IconButton(onClick = onLikePlaylist) {
                            Icon(
                                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "В избранное",
                                tint = if (isLiked) ZyroAccent else ZyroTextPrimary
                            )
                        }
                    }
                    if (canManage || (isDownloaded && onRemoveOfflineCollection != null)) {
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Ещё",
                                    tint = ZyroTextPrimary
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                shape = RoundedCornerShape(20.dp),
                                containerColor = ZyroDialogBackground,
                                tonalElevation = 0.dp,
                                shadowElevation = 16.dp,
                                border = BorderStroke(1.dp, ZyroGlassBorder)
                            ) {
                                if (isDownloaded && onRemoveOfflineCollection != null) {
                                    DropdownMenuItem(
                                        text = {
                                            Text("Удалить из офлайна", color = ZyroError)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = null,
                                                tint = ZyroError
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            onRemoveOfflineCollection.invoke()
                                        }
                                    )
                                }
                                if (canManage) {
                                    DropdownMenuItem(
                                        text = { Text("Добавить", color = ZyroTextPrimary) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = null,
                                                tint = ZyroAccent
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            showAddSources = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Изменить", color = ZyroTextPrimary) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = null,
                                                tint = ZyroTextPrimary
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            showEdit = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Удалить плейлист", color = ZyroTextPrimary) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = null,
                                                tint = ZyroTextPrimary
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            showDeleteConfirm = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            ZyroPullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
                indicatorTopPadding = headerHeight + 28.dp
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = headerHeight, bottom = 180.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zyroPlasticGlass(
                                    shape = RoundedCornerShape(24.dp),
                                    strong = true
                                )
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(112.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(ZyroSurfaceElevated)
                                    .border(1.dp, ZyroGlassBorder, RoundedCornerShape(20.dp))
                            ) {
                                AsyncImage(
                                    model = playlistCoverModel(playlist.artworkUrl),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color.Transparent,
                                                    Color.Black.copy(alpha = 0.22f)
                                                )
                                            )
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.title,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = ZyroTextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = playlist.user.ifBlank { "Плейлист" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ZyroTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = buildString {
                                        append("$trackCount треков")
                                        if (totalDurationMs > 0L) {
                                            append(" · ")
                                            append(formatPlaylistDuration(totalDurationMs))
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = ZyroAccent
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PlaylistMetaChip(
                                text = if (isServerPlaylist) {
                                    if (playlist.isPublic) "Публичный" else "Приватный"
                                } else {
                                    "Локальный"
                                },
                                accent = true
                            )
                            if (playlist.genre.isNotBlank()) {
                                PlaylistMetaChip(text = "Жанр: ${playlist.genre}")
                            }
                            tags.forEach { tag ->
                                PlaylistMetaChip(text = "#$tag")
                            }
                        }

                        if (playlist.description.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .zyroPlasticGlass(
                                        shape = RoundedCornerShape(18.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = "Описание",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = ZyroTextSecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = playlist.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ZyroTextPrimary
                                )
                            }
                        }

                        if (isServerPlaylist && playlist.createdAtMs > 0L) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Создан ${formatPlaylistDate(playlist.createdAtMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ZyroTextSecondary
                            )
                        }

                        if (tracks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ZyroIconButton(
                                    icon = Icons.Default.Shuffle,
                                    onClick = {
                                        val shuffled = tracks.shuffled()
                                        if (shuffled.isNotEmpty()) onTrackClick(shuffled.first(), shuffled)
                                    },
                                    size = 48.dp,
                                    iconSize = 22.dp,
                                    tint = ZyroAccent,
                                    contentDescription = "Перемешать"
                                )
                                Button(
                                    onClick = { onTrackClick(tracks.first(), tracks) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ZyroAccent,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Слушать")
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
                        onTrackClick = { onTrackClick(track, tracks) },
                        onLikeClick = { onLikeTrack(track) },
                        onDownloadClick = { onDownloadTrack(track) },
                        onDeleteOfflineClick = onDeleteOfflineTrack?.let { cb -> { cb(track) } },
                        onRemoveClick = if (canManage) {
                            {
                                val removeByUrl = onRemoveTrackFromPlaylistUrl
                                if (removeByUrl != null) removeByUrl(track.originalUrl)
                                else onRemoveTrackFromPlaylist(track.id)
                            }
                        } else null,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }
            }

            ZyroTopScrim(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(1f),
                height = headerHeight + 16.dp,
                visible = isScrolled
            )
        }
        }
        pickerSource?.let { source ->
            PlaylistAddTracksPicker(
                source = source,
                existingUrls = tracks.map { it.originalUrl }.toSet(),
                onDismiss = { pickerSource = null },
                loadTracks = onLoadSourceTracks,
                onAddTracks = { selected ->
                    onAddTracks(selected)
                    pickerSource = null
                }
            )
        }
    }

    if (showAddSources) {
        ModalBottomSheet(
            onDismissRequest = { showAddSources = false },
            sheetState = addSourcesSheetState,
            containerColor = ZyroDialogBackground,
            scrimColor = Color.Black.copy(alpha = 0.62f),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 10.dp)
                        .size(width = 42.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ZyroGlassBorder)
                )
            }
        ) {
            AddSourcesSheetContent(
                sources = visibleAddSources,
                onSourceClick = { source ->
                    showAddSources = false
                    pickerSource = source
                }
            )
        }
    }

    if (showEdit) {
        val isServerPlaylist = playlist.permalinkUrl.isNotBlank()
        PlaylistFormDialog(
            dialogTitle = "Изменить плейлист",
            initial = PlaylistDraft(
                title = playlist.title,
                description = playlist.description,
                isPublic = playlist.isPublic,
                genre = playlist.genre,
                tagList = playlist.tagList
            ),
            existingCoverUrl = playlist.artworkUrl,
            showServerFields = isServerPlaylist,
            onDismiss = { showEdit = false },
            onSave = { draft ->
                onEditPlaylist(draft)
                showEdit = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = ZyroDialogBackground,
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
                        onDeletePlaylist()
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
private fun AddSourcesSheetContent(
    sources: List<PlaylistAddSource>,
    onSourceClick: (PlaylistAddSource) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
    ) {
        Text(
            text = "Добавить",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = ZyroTextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Выберите, откуда взять треки",
            style = MaterialTheme.typography.bodyMedium,
            color = ZyroTextSecondary
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (sources.isEmpty()) {
            Text(
                text = "Нет доступных источников",
                style = MaterialTheme.typography.bodyMedium,
                color = ZyroTextSecondary,
                modifier = Modifier.padding(vertical = 18.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sources, key = { it.key }) { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zyroPlasticGlass(shape = RoundedCornerShape(18.dp))
                            .clickable { onSourceClick(source) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(ZyroAccent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (source.isLikes) {
                                    Icons.Default.FavoriteBorder
                                } else {
                                    Icons.Default.QueueMusic
                                },
                                contentDescription = null,
                                tint = ZyroAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = source.title,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = ZyroTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = source.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = ZyroTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = ZyroTextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistMetaChip(
    text: String,
    accent: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (accent) {
            ZyroAccent.copy(alpha = 0.14f)
        } else {
            ZyroSurfaceElevated.copy(alpha = 0.72f)
        },
        border = BorderStroke(1.dp, ZyroGlassBorder)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (accent) ZyroAccent else ZyroTextSecondary,
            maxLines = 1
        )
    }
}

private fun formatPlaylistDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val totalMinutes = ms / 60_000L
    if (totalMinutes <= 0L) return "<1 мин"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "$hours ч $minutes мин"
        hours > 0L -> "$hours ч"
        else -> "$minutes мин"
    }
}

private fun formatPlaylistDate(ms: Long): String =
    SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(ms))

/** Локальные обложки хранятся абсолютным путём, а не URL. */
private fun playlistCoverModel(url: String): Any =
    if (url.startsWith("/")) File(url) else url.ifBlank { ZyroArtworkFallback }

enum class ArtistCategoryView(val title: String) {
    TOP_TRACKS("Лучшие треки"),
    ALBUMS("Альбомы"),
    TRACKS("Все треки"),
    PLAYLISTS("Плейлисты"),
    REPOSTS("Репосты")
}
