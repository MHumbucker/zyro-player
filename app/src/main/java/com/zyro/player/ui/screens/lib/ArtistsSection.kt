package com.zyro.player.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyro.player.data.local.LikedArtistEntity
import com.zyro.player.data.remote.UserInfo
import com.zyro.player.ui.components.ZyroPullToRefreshBox

@Composable
fun ArtistsSection(
    artists: List<LikedArtistEntity>,
    onArtistClick: (UserInfo) -> Unit,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    topPadding: Dp = 0.dp,
    onScrolledChange: (Boolean) -> Unit = {}
) {
    val listState = rememberLazyListState()
    ObserveScrolledUnder(listState, onScrolledChange)

    var query by remember { mutableStateOf("") }
    val filteredArtists = remember(artists, query) {
        if (query.isBlank()) artists
        else artists.filter { it.username.contains(query, ignoreCase = true) }
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
                    placeholder = "Поиск среди артистов",
                    horizontalPadding = 0.dp
                )
            }
            if (filteredArtists.isEmpty()) {
                item {
                    EmptyLibraryState(
                        message = if (artists.isEmpty()) {
                            "Нет сохраненных артистов в избранном"
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
                            count = filteredArtists.size,
                            icon = Icons.Default.Person,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(filteredArtists, key = { it.permalinkUrl.ifBlank { it.username } }) { artist ->
                        LibraryCollectionRow(
                            title = artist.username,
                            subtitle = buildString {
                                append("${artist.followersCount} подписчиков")
                                if (artist.trackCount > 0) {
                                    append(" · ${artist.trackCount} треков")
                                }
                            },
                            artworkUrl = artist.avatarUrl,
                            artworkShape = CircleShape,
                            fallbackIcon = Icons.Default.Person,
                            onClick = {
                                onArtistClick(
                                    UserInfo(
                                        permalinkUrl = artist.permalinkUrl,
                                        username = artist.username,
                                        avatarUrl = artist.avatarUrl,
                                        followersCount = artist.followersCount,
                                        trackCount = artist.trackCount,
                                        id = artist.soundcloudId
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
