package com.zyro.player.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyro.player.data.local.ListeningHistoryEntity
import com.zyro.player.data.local.TrackEntity
import com.zyro.player.data.remote.TrackInfo

@Composable
fun HistorySection(
    history: List<ListeningHistoryEntity>,
    likedTracks: List<TrackEntity>,
    offlineTracks: List<TrackEntity>,
    currentTrackUrl: String?,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onDownloadTrack: (TrackInfo) -> Unit,
    onDeleteOfflineTrack: ((TrackInfo) -> Unit)? = null,
    onPlayNext: ((TrackInfo) -> Unit)? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    topPadding: Dp = 0.dp,
    onScrolledChange: (Boolean) -> Unit = {}
) {
    val historyTrackInfos = remember(history) {
        history.map {
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

    TrackListTabContent(
        tracks = historyTrackInfos,
        currentTrackUrl = currentTrackUrl,
        emptyText = "История прослушивания пока пуста.",
        onTrackClick = onTrackClick,
        onLikeTrack = onLikeTrack,
        onDownloadTrack = onDownloadTrack,
        onDeleteOfflineTrack = onDeleteOfflineTrack,
        onPlayNext = onPlayNext,
        likedUrls = likedTracks.map { it.originalUrl }.toSet(),
        offlineUrls = offlineTracks.map { it.originalUrl }.toSet(),
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        contentTopPadding = topPadding,
        onScrolledChange = onScrolledChange
    )
}