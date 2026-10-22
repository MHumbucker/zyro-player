package com.zyro.player.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyro.player.data.downloader.OfflineCollectionKeys
import com.zyro.player.data.downloader.OfflineDownloadState
import com.zyro.player.data.local.TrackEntity
import com.zyro.player.data.remote.TrackInfo

@Composable
fun LikedTracksSection(
    likedTracks: List<TrackEntity>,
    currentTrackUrl: String?,
    offlineTracks: List<TrackEntity>,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onDownloadTrack: (TrackInfo) -> Unit,
    onDeleteOfflineTrack: ((TrackInfo) -> Unit)? = null,
    onPlayNext: ((TrackInfo) -> Unit)? = null,
    onAddToQueue: ((TrackInfo) -> Unit)? = null,
    onArtistClick: ((TrackInfo) -> Unit)? = null,
    onDownloadAll: (() -> Unit)? = null,
    downloadState: OfflineDownloadState = OfflineDownloadState(),
    isDownloaded: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    topPadding: Dp = 0.dp,
    onScrolledChange: (Boolean) -> Unit = {}
) {
    // Порядок приходит из Room (likedTimestamp DESC, fallback addedTimestamp) — отдельная сортировка не нужна.
    val likedTrackInfos = remember(likedTracks) {
        likedTracks.map {
            TrackInfo(
                originalUrl = it.originalUrl,
                streamUrl = it.streamUrl,
                title = it.title,
                artist = it.artist,
                artworkUrl = it.artworkUrl,
                durationMs = it.durationMs,
                likesCount = it.likesCount,
                playbackCount = it.playbackCount,
                id = it.soundcloudId,
                isLiked = true,
                likedAt = it.likedTimestamp
            )
        }
    }

    var query by remember { mutableStateOf("") }
    val filtered = remember(likedTrackInfos, query) {
        if (query.isBlank()) likedTrackInfos
        else likedTrackInfos.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
        }
    }

    TrackListTabContent(
        tracks = filtered,
        currentTrackUrl = currentTrackUrl,
        emptyText = if (query.isBlank()) {
            "Вы еще не добавили ни одного трека в избранное."
        } else {
            "Ничего не найдено."
        },
        onTrackClick = onTrackClick,
        onLikeTrack = onLikeTrack,
        onDownloadTrack = onDownloadTrack,
        onDeleteOfflineTrack = onDeleteOfflineTrack,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onArtistClick = onArtistClick,
        likedUrls = likedTrackInfos.map { it.originalUrl }.toSet(),
        offlineUrls = offlineTracks.map { it.originalUrl }.toSet(),
        onDownloadAll = onDownloadAll,
        downloadState = downloadState,
        downloadCollectionKey = OfflineCollectionKeys.LIKES,
        isDownloaded = isDownloaded,
        downloadingUrl = downloadState.currentTrackUrl.takeIf { downloadState.isActive },
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        contentTopPadding = topPadding,
        header = {
            LibrarySearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Поиск в любимых треках",
                horizontalPadding = 0.dp
            )
        },
        onScrolledChange = onScrolledChange
    )
}
