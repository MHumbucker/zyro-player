package com.zyro.player.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyro.player.data.local.LikedAlbumEntity
import com.zyro.player.data.local.LikedArtistEntity
import com.zyro.player.data.local.LikedPlaylistEntity
import com.zyro.player.data.local.ListeningHistoryEntity
import com.zyro.player.data.local.PlaylistEntity
import com.zyro.player.data.local.TrackEntity
import com.zyro.player.data.remote.PlaylistInfo
import com.zyro.player.data.remote.TrackInfo
import com.zyro.player.ui.components.TrackListItem
import com.zyro.player.ui.components.ZyroPullToRefreshBox
import com.zyro.player.ui.components.ZyroTextPrimary
import com.zyro.player.R
import com.zyro.player.ui.components.ZyroAccent


@Composable
fun LibraryAllSection(
    likedTracks: List<TrackEntity>,
    likedArtists: List<LikedArtistEntity>,
    likedAlbums: List<LikedAlbumEntity>,
    offlineTracks: List<TrackEntity>,
    playlists: List<PlaylistEntity>,
    likedPlaylists: List<LikedPlaylistEntity>,
    scLikedPlaylists: List<PlaylistInfo> = emptyList(),
    serverPlaylists: List<PlaylistInfo> = emptyList(),
    history: List<ListeningHistoryEntity>,
    currentTrackUrl: String?,
    onSectionClick: (String) -> Unit,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onDownloadTrack: (TrackInfo) -> Unit,
    onDeleteOfflineTrack: ((TrackInfo) -> Unit)? = null,
    onPlayNext: ((TrackInfo) -> Unit)? = null,
    offlineTrackUrls: Set<String>,
    scUsername: String?,
    scIsConnected: Boolean,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    topPadding: Dp = 0.dp,
    onScrolledChange: (Boolean) -> Unit = {}
) {
    val listState = rememberLazyListState()
    ObserveScrolledUnder(listState, onScrolledChange)

    // Серверные плейлисты больше не дублируются в Room, поэтому считаем их
    // из снапшота SoundCloud. Старые импортированные копии (coverUrl != "")
    // не учитываем.
    val localPlaylistCount = playlists.count { it.coverUrl.isBlank() }
    // Лайкнутые плейлисты приходят из двух источников: Room (лайки из приложения)
    // и снапшот SoundCloud (всё, что сохранено в аккаунте). Считаем уникальные.
    val likedPlaylistUrls = (scLikedPlaylists.map { it.permalinkUrl } +
        likedPlaylists.map { it.permalinkUrl })
        .filter { it.isNotBlank() }
        .toSet()
    val likedPlaylistCount = likedPlaylistUrls.size
    val serverPlaylistCount = serverPlaylists.count { server ->
        server.permalinkUrl !in likedPlaylistUrls
    }

    // «Недавно прослушанные» — локальная история. Одинаковые треки не
    // дублируем: при повторном прослушивании запись просто поднимается наверх.
    val recentTrackInfos = remember(history) {
        history
            .distinctBy { it.trackOriginalUrl }
            .map {
                TrackInfo(
                    originalUrl = it.trackOriginalUrl,
                    streamUrl = it.streamUrl,
                    title = it.title,
                    artist = it.artist,
                    artworkUrl = it.artworkUrl,
                    durationMs = it.durationMs
                )
            }
    }
    val likedUrls = remember(likedTracks) {
        likedTracks.map { it.originalUrl }.toSet()
    }

    ZyroPullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        indicatorTopPadding = topPadding + 32.dp
    ) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = topPadding + 8.dp,
            bottom = 210.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Row 1 (Любимые треки и Артисты с фиксированной высотой для одинакового размера)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickLibraryCard(
                        title = "Любимые треки",
                        subtitle = "${likedTracks.size} треков",
                        icon = Icons.Default.Favorite,
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp),
                        onClick = { onSectionClick("LIKED_TRACKS") }
                    )
                    QuickLibraryCard(
                        title = "Артисты",
                        subtitle = "${likedArtists.size} артистов",
                        icon = Icons.Default.Person,
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp),
                        onClick = { onSectionClick("LIKED_ARTISTS") }
                    )
                }

                // Row 2 (Альбомы и Офлайн с фиксированной высотой)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickLibraryCard(
                        title = "Альбомы",
                        subtitle = "${likedAlbums.size} альбомов",
                        icon = Icons.Default.Album,
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp),
                        onClick = { onSectionClick("LIKED_ALBUMS") }
                    )
                    QuickLibraryCard(
                        title = "Офлайн",
                        subtitle = "${offlineTracks.size} файлов",
                        icon = Icons.Default.DownloadDone,
                        modifier = Modifier
                            .weight(1f)
                            .height(100.dp),
                        onClick = { onSectionClick("OFFLINE") }
                    )
                }

                // Плейлисты (на всю ширину)
                QuickLibraryCard(
                    title = "Плейлисты",
                    subtitle = "${localPlaylistCount + likedPlaylistCount + serverPlaylistCount} плейлистов",
                    icon = Icons.Default.QueueMusic,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSectionClick("PLAYLISTS") }
                )
            }
        }

        if (recentTrackInfos.isNotEmpty()) {
            item {
                Text(
                    text = "Недавно прослушанные",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = ZyroTextPrimary
                    ),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(recentTrackInfos.take(5), key = { it.originalUrl }) { track ->
                TrackListItem(
                    track = track,
                    isPlaying = track.originalUrl == currentTrackUrl,
                    isLiked = likedUrls.contains(track.originalUrl),
                    isOffline = offlineTrackUrls.contains(track.originalUrl),
                    onTrackClick = { onTrackClick(track, recentTrackInfos) },
                    onSwipeRight = onPlayNext?.let { cb -> { cb(track) } },
                    onLikeClick = { onLikeTrack(track) },
                    onDownloadClick = { onDownloadTrack(track) },
                    onDeleteOfflineClick = onDeleteOfflineTrack?.let { cb -> { cb(track) } },
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
    }
}