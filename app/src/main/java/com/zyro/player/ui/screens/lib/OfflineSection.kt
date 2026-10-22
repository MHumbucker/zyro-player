package com.zyro.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyro.player.data.downloader.OfflineCollectionKeys
import com.zyro.player.data.local.OfflineCollectionEntity
import com.zyro.player.data.local.OfflineCollectionTrackCrossRef
import com.zyro.player.data.local.TrackEntity
import com.zyro.player.data.remote.TrackInfo
import com.zyro.player.ui.components.TrackListItem
import com.zyro.player.ui.components.ZyroAccent
import com.zyro.player.ui.components.ZyroPullToRefreshBox
import com.zyro.player.ui.components.ZyroTextSecondary

/**
 * Офлайн-библиотека с иерархией:
 * офлайн → лайки / плейлисты / альбомы / другое → коллекция → треки.
 */
@Composable
fun OfflineSection(
    offlineTracks: List<TrackEntity>,
    likedTracks: List<TrackEntity>,
    currentTrackUrl: String?,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onDownloadTrack: (TrackInfo) -> Unit,
    onDeleteOfflineTrack: ((TrackInfo) -> Unit)? = null,
    offlineCollections: List<OfflineCollectionEntity> = emptyList(),
    offlineCollectionTracks: List<OfflineCollectionTrackCrossRef> = emptyList(),
    onPlayNext: ((TrackInfo) -> Unit)? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    topPadding: Dp = 0.dp,
    onScrolledChange: (Boolean) -> Unit = {}
) {
    val listState = rememberLazyListState()
    ObserveScrolledUnder(listState, onScrolledChange)

    val offlineByUrl = remember(offlineTracks) { offlineTracks.associateBy { it.originalUrl } }
    val likedUrls = remember(likedTracks) { likedTracks.map { it.originalUrl }.toSet() }

    val likedOffline = remember(likedTracks, offlineByUrl) {
        likedTracks.mapNotNull { offlineByUrl[it.originalUrl] }
    }

    val refsByCollection = remember(offlineCollectionTracks) {
        offlineCollectionTracks
            .groupBy { it.collectionKey }
            .mapValues { (_, refs) -> refs.sortedBy { it.position } }
    }
    val tracksByCollection = remember(refsByCollection, offlineByUrl) {
        refsByCollection.mapValues { (_, refs) ->
            refs.mapNotNull { offlineByUrl[it.trackOriginalUrl] }
        }.filterValues { it.isNotEmpty() }
    }

    val claimedByCollections = remember(tracksByCollection) {
        tracksByCollection.values.flatten().map { it.originalUrl }.toSet()
    }
    val otherOffline = remember(offlineTracks, likedUrls, claimedByCollections) {
        offlineTracks.filter {
            it.originalUrl !in likedUrls && it.originalUrl !in claimedByCollections
        }
    }

    val playlistCollections = remember(offlineCollections, tracksByCollection) {
        offlineCollections.filter {
            it.type == OfflineCollectionKeys.TYPE_PLAYLIST &&
                tracksByCollection[it.collectionKey] != null
        }
    }
    val albumCollections = remember(offlineCollections, tracksByCollection) {
        offlineCollections.filter {
            it.type == OfflineCollectionKeys.TYPE_ALBUM &&
                tracksByCollection[it.collectionKey] != null
        }
    }

    var query by remember { mutableStateOf("") }
    var likesExpanded by remember { mutableStateOf(false) }
    var playlistsExpanded by remember { mutableStateOf(false) }
    var albumsExpanded by remember { mutableStateOf(false) }
    var otherExpanded by remember { mutableStateOf(false) }
    var expandedCollections by remember { mutableStateOf(setOf<String>()) }

    val searching = query.isNotBlank()
    val likedVisible = remember(likedOffline, query) {
        likedOffline.filter { it.matchesQuery(query) }
    }
    val otherVisible = remember(otherOffline, query) {
        otherOffline.filter { it.matchesQuery(query) }
    }

    val visiblePlaylistCollections = remember(playlistCollections, tracksByCollection, query) {
        playlistCollections.filter { collection ->
            collection.matchesQuery(query) ||
                tracksByCollection[collection.collectionKey].orEmpty().any { it.matchesQuery(query) }
        }
    }
    val visibleAlbumCollections = remember(albumCollections, tracksByCollection, query) {
        albumCollections.filter { collection ->
            collection.matchesQuery(query) ||
                tracksByCollection[collection.collectionKey].orEmpty().any { it.matchesQuery(query) }
        }
    }

    val hasAnyContent = likedOffline.isNotEmpty() ||
        otherOffline.isNotEmpty() ||
        visiblePlaylistCollections.isNotEmpty() ||
        visibleAlbumCollections.isNotEmpty()
    val hasSearchResults = likedVisible.isNotEmpty() ||
        otherVisible.isNotEmpty() ||
        visiblePlaylistCollections.isNotEmpty() ||
        visibleAlbumCollections.isNotEmpty()

    ZyroPullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh ?: {},
        modifier = Modifier.fillMaxSize(),
        indicatorTopPadding = topPadding + 32.dp
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = topPadding + 4.dp,
                bottom = 210.dp
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                LibrarySearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Поиск в офлайн-кеше",
                    horizontalPadding = 0.dp
                )
            }

            if (offlineTracks.isEmpty() && offlineCollections.isEmpty()) {
                item {
                    EmptyLibraryState(
                        message = "Нет сохраненных для офлайн-прослушивания треков.",
                        modifier = Modifier.fillParentMaxHeight(0.65f)
                    )
                }
            } else if (searching && !hasSearchResults) {
                item {
                    EmptyLibraryState(
                        message = "Ничего не найдено",
                        modifier = Modifier.fillParentMaxHeight(0.65f)
                    )
                }
            } else if (!hasAnyContent && !searching) {
                item {
                    EmptyLibraryState(
                        message = "Офлайн-кеш пока пуст. Скачайте треки, плейлист или альбом.",
                        modifier = Modifier.fillParentMaxHeight(0.65f)
                    )
                }
            } else {
                if (likedVisible.isNotEmpty()) {
                    item(key = "offline_likes_header") {
                        OfflineGroupHeader(
                            title = "Лайки",
                            count = likedVisible.size,
                            icon = Icons.Default.Favorite,
                            expanded = likesExpanded || searching,
                            onToggle = { likesExpanded = !likesExpanded }
                        )
                    }
                    if (likesExpanded || searching) {
                        items(likedVisible, key = { "offline_like_${it.originalUrl}" }) { track ->
                            OfflineTrackRow(
                                track = track,
                                tracks = likedVisible,
                                currentTrackUrl = currentTrackUrl,
                                onTrackClick = onTrackClick,
                                onLikeTrack = onLikeTrack,
                                onDownloadTrack = onDownloadTrack,
                                onDeleteOfflineTrack = onDeleteOfflineTrack,
                                onPlayNext = onPlayNext
                            )
                        }
                    }
                }

                if (visiblePlaylistCollections.isNotEmpty()) {
                    item(key = "offline_playlists_header") {
                        OfflineGroupHeader(
                            title = "Плейлисты",
                            count = visiblePlaylistCollections.size,
                            icon = Icons.Default.QueueMusic,
                            expanded = playlistsExpanded || searching,
                            onToggle = { playlistsExpanded = !playlistsExpanded }
                        )
                    }
                    if (playlistsExpanded || searching) {
                        visiblePlaylistCollections.forEach { collection ->
                            val allTracks = tracksByCollection[collection.collectionKey].orEmpty()
                            val expanded = collection.collectionKey in expandedCollections || searching
                            val matchingTracks = if (searching && !collection.matchesQuery(query)) {
                                allTracks.filter { it.matchesQuery(query) }
                            } else {
                                allTracks
                            }
                            item(key = "offline_collection_${collection.collectionKey}") {
                                OfflineCollectionCard(
                                    collection = collection,
                                    trackCount = matchingTracks.size,
                                    expanded = expanded,
                                    fallbackIcon = Icons.Default.QueueMusic,
                                    coverFallbackUrl = allTracks
                                        .firstOrNull { it.artworkUrl.isNotBlank() && it.artworkUrl != "null" }
                                        ?.artworkUrl.orEmpty(),
                                    onToggle = {
                                        expandedCollections = expandedCollections.toggle(
                                            collection.collectionKey
                                        )
                                    }
                                )
                            }
                            if (expanded) {
                                items(
                                    matchingTracks,
                                    key = { "${collection.collectionKey}_track_${it.originalUrl}" }
                                ) { track ->
                                    OfflineTrackRow(
                                        track = track,
                                        tracks = matchingTracks,
                                        currentTrackUrl = currentTrackUrl,
                                        onTrackClick = onTrackClick,
                                        onLikeTrack = onLikeTrack,
                                        onDownloadTrack = onDownloadTrack,
                                        onDeleteOfflineTrack = onDeleteOfflineTrack,
                                        onPlayNext = onPlayNext
                                    )
                                }
                            }
                        }
                    }
                }

                if (visibleAlbumCollections.isNotEmpty()) {
                    item(key = "offline_albums_header") {
                        OfflineGroupHeader(
                            title = "Альбомы",
                            count = visibleAlbumCollections.size,
                            icon = Icons.Default.Album,
                            expanded = albumsExpanded || searching,
                            onToggle = { albumsExpanded = !albumsExpanded }
                        )
                    }
                    if (albumsExpanded || searching) {
                        visibleAlbumCollections.forEach { collection ->
                            val allTracks = tracksByCollection[collection.collectionKey].orEmpty()
                            val expanded = collection.collectionKey in expandedCollections || searching
                            val matchingTracks = if (searching && !collection.matchesQuery(query)) {
                                allTracks.filter { it.matchesQuery(query) }
                            } else {
                                allTracks
                            }
                            item(key = "offline_collection_${collection.collectionKey}") {
                                OfflineCollectionCard(
                                    collection = collection,
                                    trackCount = matchingTracks.size,
                                    expanded = expanded,
                                    fallbackIcon = Icons.Default.Album,
                                    coverFallbackUrl = allTracks
                                        .firstOrNull { it.artworkUrl.isNotBlank() && it.artworkUrl != "null" }
                                        ?.artworkUrl.orEmpty(),
                                    onToggle = {
                                        expandedCollections = expandedCollections.toggle(
                                            collection.collectionKey
                                        )
                                    }
                                )
                            }
                            if (expanded) {
                                items(
                                    matchingTracks,
                                    key = { "${collection.collectionKey}_track_${it.originalUrl}" }
                                ) { track ->
                                    OfflineTrackRow(
                                        track = track,
                                        tracks = matchingTracks,
                                        currentTrackUrl = currentTrackUrl,
                                        onTrackClick = onTrackClick,
                                        onLikeTrack = onLikeTrack,
                                        onDownloadTrack = onDownloadTrack,
                                        onDeleteOfflineTrack = onDeleteOfflineTrack,
                                        onPlayNext = onPlayNext
                                    )
                                }
                            }
                        }
                    }
                }

                if (otherVisible.isNotEmpty()) {
                    item(key = "offline_other_header") {
                        OfflineGroupHeader(
                            title = "Другое",
                            count = otherVisible.size,
                            icon = Icons.Default.LibraryMusic,
                            expanded = otherExpanded || searching,
                            onToggle = { otherExpanded = !otherExpanded }
                        )
                    }
                    if (otherExpanded || searching) {
                        items(otherVisible, key = { "offline_other_${it.originalUrl}" }) { track ->
                            OfflineTrackRow(
                                track = track,
                                tracks = otherVisible,
                                currentTrackUrl = currentTrackUrl,
                                onTrackClick = onTrackClick,
                                onLikeTrack = onLikeTrack,
                                onDownloadTrack = onDownloadTrack,
                                onDeleteOfflineTrack = onDeleteOfflineTrack,
                                onPlayNext = onPlayNext
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineGroupHeader(
    title: String,
    count: Int,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    LibraryCollectionHeader(
        title = title,
        count = count,
        icon = icon,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle),
        action = {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
                tint = ZyroAccent,
                modifier = Modifier.size(22.dp)
            )
        }
    )
}

@Composable
private fun OfflineCollectionCard(
    collection: OfflineCollectionEntity,
    trackCount: Int,
    expanded: Boolean,
    fallbackIcon: ImageVector,
    onToggle: () -> Unit,
    /** Обложка первого трека коллекции, если у самой коллекции картинки нет. */
    coverFallbackUrl: String = ""
) {
    val subtitle = buildString {
        if (collection.subtitle.isNotBlank()) append(collection.subtitle)
        if (isNotEmpty()) append(" · ")
        append("$trackCount офлайн")
    }
    LibraryCollectionRow(
        title = collection.title,
        subtitle = subtitle,
        artworkUrl = collection.artworkUrl
            .takeUnless { it.startsWith("/") || it == "null" }
            .orEmpty()
            .ifBlank { coverFallbackUrl },
        fallbackIcon = fallbackIcon,
        onClick = onToggle,
        trailing = {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
                tint = ZyroAccent,
                modifier = Modifier.size(22.dp)
            )
        }
    )
}

@Composable
private fun OfflineTrackRow(
    track: TrackEntity,
    tracks: List<TrackEntity>,
    currentTrackUrl: String?,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onDownloadTrack: (TrackInfo) -> Unit,
    onDeleteOfflineTrack: ((TrackInfo) -> Unit)? = null,
    onPlayNext: ((TrackInfo) -> Unit)?
) {
    val info = remember(track) { track.toTrackInfo() }
    val queue = remember(tracks) { tracks.map { it.toTrackInfo() } }
    Column(modifier = Modifier.padding(start = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ZyroAccent.copy(alpha = 0.22f))
            )
            Spacer(modifier = Modifier.width(6.dp))
            TrackListItem(
                track = info,
                isPlaying = track.originalUrl == currentTrackUrl,
                isLiked = track.isLiked,
                isOffline = true,
                onTrackClick = { onTrackClick(info, queue) },
                onSwipeRight = onPlayNext?.let { cb -> { cb(info) } },
                onLikeClick = { onLikeTrack(info) },
                onDownloadClick = { onDownloadTrack(info) },
                onDeleteOfflineClick = onDeleteOfflineTrack?.let { cb -> { cb(info) } },
                onPlayNextClick = onPlayNext?.let { cb -> { cb(info) } },
                onArtistClick = null,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun OfflineCollectionEntity.matchesQuery(query: String): Boolean =
    query.isBlank() ||
        title.contains(query, ignoreCase = true) ||
        subtitle.contains(query, ignoreCase = true)

private fun TrackEntity.matchesQuery(query: String): Boolean =
    query.isBlank() ||
        title.contains(query, ignoreCase = true) ||
        artist.contains(query, ignoreCase = true)

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

private fun TrackEntity.toTrackInfo() = TrackInfo(
    originalUrl = originalUrl,
    streamUrl = streamUrl,
    title = title,
    artist = artist,
    artworkUrl = artworkUrl,
    durationMs = durationMs,
    likesCount = likesCount,
    playbackCount = playbackCount,
    id = soundcloudId,
    isLiked = isLiked,
    likedAt = likedTimestamp
)
