package com.zyro.player.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyro.player.data.downloader.OfflineCollectionKeys
import com.zyro.player.data.downloader.OfflineDownloadState
import com.zyro.player.data.local.LikedAlbumEntity
import com.zyro.player.data.remote.AlbumInfo
import com.zyro.player.ui.components.ZyroPullToRefreshBox

@Composable
fun AlbumsSection(
    albums: List<LikedAlbumEntity>,
    onAlbumClick: (AlbumInfo) -> Unit,
    onDownloadAlbum: ((AlbumInfo) -> Unit)? = null,
    downloadState: OfflineDownloadState = OfflineDownloadState(),
    offlineCollectionKeys: Set<String> = emptySet(),
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    topPadding: Dp = 0.dp,
    onScrolledChange: (Boolean) -> Unit = {}
) {
    val listState = rememberLazyListState()
    ObserveScrolledUnder(listState, onScrolledChange)

    var query by remember { mutableStateOf("") }
    val filteredAlbums = remember(albums, query) {
        if (query.isBlank()) albums
        else albums.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true)
        }
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                LibrarySearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Поиск среди альбомов",
                    horizontalPadding = 0.dp
                )
            }
            if (filteredAlbums.isEmpty()) {
                item {
                    EmptyLibraryState(
                        message = if (albums.isEmpty()) {
                            "Нет сохраненных альбомов в избранном"
                        } else {
                            "Ничего не найдено"
                        },
                        modifier = Modifier.fillParentMaxHeight(0.65f)
                    )
                }
            } else {
                    item {
                        LibraryCollectionHeader(
                            title = "В избранном",
                            count = filteredAlbums.size,
                            icon = Icons.Default.Album,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(filteredAlbums, key = { it.permalinkUrl }) { album ->
                        // Старые записи в Room могли сохранить строку "null"
                        // (JSON null от API) — обложкой она не считается.
                        val cover = album.artworkUrl.takeUnless { it.isBlank() || it == "null" }.orEmpty()
                        val info = AlbumInfo(
                            id = 0L,
                            permalinkUrl = album.permalinkUrl,
                            title = album.title,
                            artist = album.artist,
                            artworkUrl = cover,
                            trackCount = album.trackCount
                        )
                        val collectionKey = OfflineCollectionKeys.album(album.permalinkUrl)
                        LibraryCollectionRow(
                            title = album.title,
                            subtitle = buildString {
                                if (album.artist.isNotBlank()) append(album.artist)
                                if (album.trackCount > 0) {
                                    if (isNotEmpty()) append(" · ")
                                    append("${album.trackCount} треков")
                                }
                            },
                            artworkUrl = cover,
                            artworkShape = RoundedCornerShape(18.dp),
                            fallbackIcon = Icons.Default.Album,
                            onClick = { onAlbumClick(info) },
                            onDownloadClick = onDownloadAlbum?.let { callback ->
                                { callback(info) }
                            },
                            downloadState = downloadState,
                            downloadCollectionKey = collectionKey,
                            isDownloaded = collectionKey in offlineCollectionKeys
                        )
                    }
                }
            }
        }
    }
