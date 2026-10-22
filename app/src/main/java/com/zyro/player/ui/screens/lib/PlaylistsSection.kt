package com.zyro.player.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyro.player.data.downloader.OfflineCollectionKeys
import com.zyro.player.data.downloader.OfflineDownloadState
import com.zyro.player.data.local.LikedPlaylistEntity
import com.zyro.player.data.local.PlaylistEntity
import com.zyro.player.data.remote.PlaylistInfo
import com.zyro.player.ui.components.ZyroAccent
import com.zyro.player.ui.components.ZyroPullToRefreshBox

@Composable
fun PlaylistsSection(
    likedPlaylists: List<LikedPlaylistEntity>,
    /** Лайкнутые плейлисты из снапшота SoundCloud (могут отсутствовать в Room). */
    scLikedPlaylists: List<PlaylistInfo> = emptyList(),
    serverPlaylists: List<PlaylistInfo> = emptyList(),
    playlists: List<PlaylistEntity>,
    onPlaylistClick: (PlaylistInfo) -> Unit,
    onSelectPlaylist: (PlaylistEntity) -> Unit,
    onDeletePlaylist: (PlaylistEntity) -> Unit,
    onCreatePlaylist: () -> Unit,
    onDownloadPlaylist: ((PlaylistInfo) -> Unit)? = null,
    onDownloadLocalPlaylist: ((PlaylistEntity) -> Unit)? = null,
    /** Обложки локальных плейлистов из обложек их первых треков. */
    localPlaylistCovers: Map<Long, String> = emptyMap(),
    downloadState: OfflineDownloadState = OfflineDownloadState(),
    offlineCollectionKeys: Set<String> = emptySet(),
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    topPadding: Dp = 0.dp,
    onScrolledChange: (Boolean) -> Unit = {}
) {
    val listState = rememberLazyListState()
    ObserveScrolledUnder(listState, onScrolledChange)

    // Раньше серверные плейлисты импортировались в local Room при каждой
    // синхронизации и превращались в десятки одинаковых строк. Теперь они
    // показываются напрямую из снапшота SoundCloud. Фильтр ниже — страховка
    // для уже сохранённых в БД старых копий (у них всегда была обложка).
    val localPlaylists = remember(playlists) { playlists.filter { it.coverUrl.isBlank() } }

    // Room хранит только лайки, поставленные в этом приложении. Полный список
    // сохранённых плейлистов приходит из снапшота SoundCloud, поэтому
    // объединяем оба источника: снапшот важнее (у него есть настоящий id).
    val allLikedPlaylists = remember(likedPlaylists, scLikedPlaylists) {
        val fromRoom = likedPlaylists.map { liked ->
            PlaylistInfo(
                id = 0L,
                permalinkUrl = liked.permalinkUrl,
                title = liked.title,
                user = liked.user,
                artworkUrl = liked.artworkUrl.takeUnless { it == "null" }.orEmpty(),
                trackCount = liked.trackCount,
                isLiked = true
            )
        }
        (scLikedPlaylists + fromRoom)
            .filter { it.permalinkUrl.isNotBlank() }
            .distinctBy { it.permalinkUrl }
    }
    val visibleServerPlaylists = remember(serverPlaylists, allLikedPlaylists) {
        val likedUrls = allLikedPlaylists.map { it.permalinkUrl }.toSet()
        serverPlaylists
            .filter { it.permalinkUrl !in likedUrls }
            .distinctBy { it.permalinkUrl.ifBlank { it.id.toString() } }
    }

    var query by remember { mutableStateOf("") }
    val filteredLiked = remember(allLikedPlaylists, query) {
        if (query.isBlank()) allLikedPlaylists
        else allLikedPlaylists.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.user.contains(query, ignoreCase = true)
        }
    }
    val filteredServer = remember(visibleServerPlaylists, query) {
        if (query.isBlank()) visibleServerPlaylists
        else visibleServerPlaylists.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.user.contains(query, ignoreCase = true)
        }
    }
    val filteredLocal = remember(localPlaylists, query) {
        if (query.isBlank()) localPlaylists
        else localPlaylists.filter { it.title.contains(query, ignoreCase = true) }
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
                top = topPadding,
                bottom = 210.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                LibrarySearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Поиск среди плейлистов",
                    horizontalPadding = 0.dp
                )
            }
                if (filteredLiked.isNotEmpty()) {
                    item {
                        LibraryCollectionHeader(
                            title = "Лайкнутые плейлисты",
                            count = filteredLiked.size,
                            icon = Icons.Default.Favorite
                        )
                    }
                    items(filteredLiked, key = { "liked_${it.permalinkUrl}" }) { playlist ->
                        val collectionKey = OfflineCollectionKeys.playlist(playlist.permalinkUrl)
                        LibraryCollectionRow(
                            title = playlist.title,
                            subtitle = playlistSubtitle(
                                user = playlist.user,
                                trackCount = playlist.trackCount
                            ),
                            artworkUrl = playlist.artworkUrl,
                            fallbackIcon = Icons.Default.QueueMusic,
                            onClick = { onPlaylistClick(playlist) },
                            onDownloadClick = onDownloadPlaylist?.let { callback ->
                                { callback(playlist) }
                            },
                            downloadState = downloadState,
                            downloadCollectionKey = collectionKey,
                            isDownloaded = collectionKey in offlineCollectionKeys
                        )
                    }
                }

                val hasOwnPlaylists = filteredServer.isNotEmpty() || filteredLocal.isNotEmpty()
                if (hasOwnPlaylists) {
                    item {
                        LibraryCollectionHeader(
                            title = "Мои плейлисты",
                            count = filteredServer.size + filteredLocal.size,
                            icon = Icons.Default.QueueMusic,
                            modifier = Modifier.padding(top = if (filteredLiked.isNotEmpty()) 4.dp else 0.dp),
                            action = {
                                IconButton(
                                    onClick = onCreatePlaylist,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("create_playlist_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Создать плейлист",
                                        tint = ZyroAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )
                    }
                    items(
                        filteredServer,
                        key = { "server_${it.permalinkUrl.ifBlank { it.id.toString() }}" }
                    ) { playlist ->
                        val collectionKey = OfflineCollectionKeys.playlist(playlist.permalinkUrl)
                        LibraryCollectionRow(
                            title = playlist.title,
                            subtitle = playlistSubtitle(
                                user = playlist.user,
                                trackCount = playlist.trackCount
                            ),
                            artworkUrl = playlist.artworkUrl,
                            fallbackIcon = Icons.Default.QueueMusic,
                            onClick = { onPlaylistClick(playlist) },
                            onDownloadClick = onDownloadPlaylist?.let { callback ->
                                { callback(playlist) }
                            },
                            downloadState = downloadState,
                            downloadCollectionKey = collectionKey,
                            isDownloaded = collectionKey in offlineCollectionKeys
                        )
                    }
                    items(filteredLocal, key = { it.playlistId }) { playlist ->
                        val collectionKey = OfflineCollectionKeys.local(playlist.playlistId)
                        PlaylistRowItem(
                            playlist = playlist,
                            onClick = { onSelectPlaylist(playlist) },
                            onDelete = { onDeletePlaylist(playlist) },
                            onDownloadClick = onDownloadLocalPlaylist?.let { callback ->
                                { callback(playlist) }
                            },
                            downloadState = downloadState,
                            downloadCollectionKey = collectionKey,
                            isDownloaded = collectionKey in offlineCollectionKeys,
                            coverFallbackUrl = localPlaylistCovers[playlist.playlistId].orEmpty()
                        )
                    }
                } else if (query.isBlank()) {
                    item {
                        LibraryCollectionHeader(
                            title = "Мои плейлисты",
                            count = 0,
                            icon = Icons.Default.QueueMusic,
                            modifier = Modifier.padding(top = if (filteredLiked.isNotEmpty()) 4.dp else 0.dp),
                            action = {
                                IconButton(
                                    onClick = onCreatePlaylist,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("create_playlist_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Создать плейлист",
                                        tint = ZyroAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )
                    }
                    item {
                        EmptyLibraryState(
                            message = "Плейлистов пока нет. Создайте свой первый плейлист.",
                            onAction = onCreatePlaylist,
                            actionText = "Создать плейлист",
                            modifier = Modifier.fillParentMaxHeight(0.6f)
                        )
                    }
                } else if (filteredLiked.isEmpty()) {
                    item {
                        EmptyLibraryState(
                            message = "Ничего не найдено",
                            modifier = Modifier.fillParentMaxHeight(0.6f)
                        )
                    }
                }
            }
        }
    }

private fun playlistSubtitle(user: String, trackCount: Int): String = buildString {
    if (user.isNotBlank()) append(user)
    if (trackCount > 0) {
        if (isNotEmpty()) append(" · ")
        append("$trackCount треков")
    }
    if (isEmpty()) append("Плейлист")
}
