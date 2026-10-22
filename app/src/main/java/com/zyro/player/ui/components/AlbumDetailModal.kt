package com.zyro.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailModal(
    album: AlbumInfo,
    tracks: List<TrackInfo>,
    currentTrackUrl: String?,
    likedUrls: Set<String>,
    offlineUrls: Set<String>,
    isLiked: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onLikeAlbum: () -> Unit = {},
    onClose: () -> Unit,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onDownloadTrack: (TrackInfo) -> Unit,
    onDeleteOfflineTrack: ((TrackInfo) -> Unit)? = null,
    onDownloadAll: (() -> Unit)? = null,
    downloadState: OfflineDownloadState = OfflineDownloadState(),
    downloadCollectionKey: String? = null,
    isDownloaded: Boolean = false
) {
    BackHandler {
        onClose()
    }

    ZyroAuroraBackground {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("album_detail_screen"),
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
            // Header Top Bar
            TopAppBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .zIndex(2f)
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                title = {
                    Text(
                        text = "Альбом",
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
                            onClick = onDownloadAll
                        )
                    }
                    IconButton(onClick = onLikeAlbum) {
                        Icon(
                            imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "В избранное",
                            tint = if (isLiked) ZyroAccent else ZyroTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = ZyroTextPrimary,
                    navigationIconContentColor = ZyroTextPrimary,
                    actionIconContentColor = ZyroTextPrimary
                )
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
                // Large Header Cover & Information
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AsyncImage(
                            model = album.artworkUrl.ifBlank { ZyroArtworkFallback },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(20.dp))
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = ZyroTextPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${album.artist} • ${tracks.size} треков",
                            style = MaterialTheme.typography.bodyLarge,
                            color = ZyroTextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        if (tracks.isNotEmpty()) {
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
    }
}
