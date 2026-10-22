package com.zyro.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zyro.player.data.remote.PlaylistInfo
import com.zyro.player.data.remote.ScFeedAction
import com.zyro.player.data.remote.ScFeedActivity
import com.zyro.player.data.remote.TrackInfo
import com.zyro.player.data.remote.UserInfo
import com.zyro.player.ui.components.GoPlusBadge
import com.zyro.player.ui.components.ZyroAccent
import com.zyro.player.ui.components.ZyroAccentDeep
import com.zyro.player.ui.components.ZyroAccentSoft
import com.zyro.player.ui.components.ZyroArtworkFallback
import com.zyro.player.ui.components.ZyroGlassBorder
import com.zyro.player.ui.components.ZyroIconButton
import com.zyro.player.ui.components.ZyroPullToRefreshBox
import com.zyro.player.ui.components.ZyroShapes
import com.zyro.player.ui.components.ZyroSurface
import com.zyro.player.ui.components.ZyroTextPrimary
import com.zyro.player.ui.components.ZyroTextSecondary
import com.zyro.player.ui.components.ZyroTopScrim
import com.zyro.player.ui.components.formatCount
import com.zyro.player.ui.components.formatDurationMs
import com.zyro.player.ui.components.zyroPlasticGlass
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Секции вкладки «Лента». */
enum class FeedSection { DISCOVER, FOLLOWING }

/**
 * Лента SoundCloud: рекомендации («Найти», вертикальный свайпер) и новые
 * треки, плейлисты и репосты артистов, на которых подписан пользователь.
 * Данные подписок приходят из `GET /stream`, рекомендации — из
 * персонального фида и официальных чартов.
 */
@Composable
fun FeedScreen(
    section: FeedSection,
    onSectionChange: (FeedSection) -> Unit,
    activities: List<ScFeedActivity>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    error: String?,
    isConnected: Boolean,
    currentTrackUrl: String?,
    likedUrls: Set<String>,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onPlaylistClick: (PlaylistInfo) -> Unit,
    onArtistClick: (UserInfo) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onLoginClick: () -> Unit,
    discoverTracks: List<TrackInfo>,
    isDiscoverLoading: Boolean,
    isDiscoverLoadingMore: Boolean,
    discoverHasMore: Boolean,
    discoverError: String?,
    isPlaying: Boolean,
    onDiscoverRefresh: () -> Unit,
    onDiscoverLoadMore: () -> Unit,
    onDiscoverPlay: (TrackInfo, List<TrackInfo>) -> Unit,
    onDiscoverArtistClick: (TrackInfo) -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val discoverPagerState = rememberPagerState(pageCount = { discoverTracks.size })
    // Очередь для плеера — все треки ленты в порядке показа, без дублей.
    val trackQueue = remember(activities) {
        activities.mapNotNull { it.track }.distinctBy { it.originalUrl }
    }

    // Догрузка следующей страницы, когда пользователь почти у конца списка.
    LaunchedEffect(section, listState, activities.size, hasMore, isLoading, isLoadingMore, isConnected) {
        if (section != FeedSection.FOLLOWING) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (last, total) ->
            if (isConnected && hasMore && !isLoading && !isLoadingMore &&
                total > 0 && last >= total - 3
            ) {
                onLoadMore()
            }
        }
    }

    var headerHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxSize()) {
        if (section == FeedSection.DISCOVER) {
            DiscoverFeedContent(
                pagerState = discoverPagerState,
                tracks = discoverTracks,
                isLoading = isDiscoverLoading,
                isLoadingMore = isDiscoverLoadingMore,
                hasMore = discoverHasMore,
                error = discoverError,
                currentTrackUrl = currentTrackUrl,
                isPlaying = isPlaying,
                likedUrls = likedUrls,
                onRefresh = onDiscoverRefresh,
                onLoadMore = onDiscoverLoadMore,
                onPlayTrack = { track -> onDiscoverPlay(track, discoverTracks) },
                onTogglePlayPause = onTogglePlayPause,
                onLikeTrack = onLikeTrack,
                onArtistClick = onDiscoverArtistClick
            )
        } else {
            when {
                !isConnected -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = headerHeight),
                    contentAlignment = Alignment.TopCenter
                ) {
                    FeedLoginCard(onLoginClick = onLoginClick)
                }

                isLoading && activities.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = ZyroAccent, strokeWidth = 2.5.dp)
                }

                error != null && activities.isEmpty() -> {
                    val needsLogin = error.contains("HTTP 401")
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = headerHeight),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        FeedMessageCard(
                            title = "Не удалось загрузить ленту",
                            text = error,
                            buttonText = if (needsLogin) "Войти снова" else "Повторить",
                            onButtonClick = if (needsLogin) onLoginClick else onRefresh
                        )
                    }
                }

                activities.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = headerHeight),
                    contentAlignment = Alignment.TopCenter
                ) {
                    FeedMessageCard(
                        title = "Пока тихо",
                        text = "Подпишитесь на артистов в SoundCloud — здесь появятся их новые треки, плейлисты и репосты.",
                        buttonText = "Обновить",
                        onButtonClick = onRefresh
                    )
                }

                else -> ZyroPullToRefreshBox(
                    isRefreshing = isLoading && activities.isNotEmpty(),
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                    indicatorTopPadding = headerHeight + 32.dp
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = headerHeight + 4.dp,
                            bottom = 220.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(activities, key = { it.id }) { activity ->
                            val track = activity.track
                            FeedActivityCard(
                                activity = activity,
                                isPlaying = track != null && track.originalUrl == currentTrackUrl,
                                isLiked = track != null &&
                                    likedUrls.contains(track.originalUrl),
                                onTrackClick = { track?.let { onTrackClick(it, trackQueue) } },
                                onPlaylistClick = { activity.playlist?.let(onPlaylistClick) },
                                onArtistClick = { activity.actor?.let(onArtistClick) },
                                onLikeClick = {
                                    track?.let {
                                        onLikeTrack(
                                            it.copy(isLiked = likedUrls.contains(it.originalUrl))
                                        )
                                    }
                                }
                            )
                        }

                        if (isLoadingMore) {
                            item(key = "feed_loading_more") { FeedLoadingMore() }
                        } else if (!hasMore && activities.isNotEmpty()) {
                            item(key = "feed_end") { FeedEndLabel() }
                        }

                        if (error != null) {
                            item(key = "feed_inline_error") {
                                FeedInlineError(
                                    error = error,
                                    onRetry = if (hasMore) onLoadMore else onRefresh
                                )
                            }
                        }
                    }
                }
            }
        }

        // Контент под прозрачной шапкой затемняется — как над нижним баром.
        ZyroTopScrim(
            modifier = Modifier.align(Alignment.TopCenter),
            height = headerHeight + 24.dp,
            visible = section == FeedSection.DISCOVER || isScrolled
        )

        // Прозрачная шапка плавает поверх ленты.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                .statusBarsPadding()
        ) {
            FeedHeader(
                section = section,
                isLoading = if (section == FeedSection.DISCOVER) {
                    isDiscoverLoading && discoverTracks.isEmpty()
                } else {
                    isLoading && activities.isEmpty()
                },
                isConnected = isConnected,
                onRefresh = if (section == FeedSection.DISCOVER) onDiscoverRefresh else onRefresh
            )
            FeedSectionSwitcher(selected = section, onSelect = onSectionChange)
        }
    }
}

@Composable
private fun FeedHeader(
    section: FeedSection,
    isLoading: Boolean,
    isConnected: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Лента",
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                color = ZyroTextPrimary
            )
            Text(
                text = if (section == FeedSection.DISCOVER) {
                    "Рекомендации SoundCloud — свайпайте вверх"
                } else {
                    "Новое от артистов, на которых вы подписаны"
                },
                fontSize = 12.sp,
                color = ZyroTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when {
            section == FeedSection.FOLLOWING && !isConnected -> Unit
            isLoading -> CircularProgressIndicator(
                modifier = Modifier.size(23.dp),
                color = ZyroAccent,
                strokeWidth = 2.2.dp
            )
            else -> ZyroIconButton(
                icon = Icons.Default.Refresh,
                onClick = onRefresh,
                size = 44.dp,
                iconSize = 21.dp,
                contentDescription = if (section == FeedSection.DISCOVER) {
                    "Обновить рекомендации"
                } else {
                    "Обновить ленту"
                }
            )
        }
    }
}

@Composable
private fun FeedActivityCard(
    activity: ScFeedActivity,
    isPlaying: Boolean,
    isLiked: Boolean,
    onTrackClick: () -> Unit,
    onPlaylistClick: () -> Unit,
    onArtistClick: () -> Unit,
    onLikeClick: () -> Unit
) {
    val shape = RoundedCornerShape(ZyroShapes.L)
    Column(modifier = Modifier.fillMaxWidth().zyroPlasticGlass(shape)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(ZyroShapes.S))
                    .then(
                        if (activity.actor != null) {
                            Modifier.clickable(onClick = onArtistClick)
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FeedAvatar(activity.actor)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activity.actor?.username?.takeIf { it.isNotBlank() } ?: "Лента",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ZyroTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = feedActionLine(activity),
                        fontSize = 11.sp,
                        color = ZyroTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (activity.action == ScFeedAction.REPOST) {
                Icon(
                    imageVector = Icons.Default.Repeat,
                    contentDescription = "Репост",
                    tint = ZyroAccent,
                    modifier = Modifier.padding(start = 8.dp).size(17.dp)
                )
            }
        }

        val track = activity.track
        val playlist = activity.playlist
        when {
            track != null -> FeedTrackRow(
                track = track,
                isPlaying = isPlaying,
                isLiked = isLiked,
                onClick = onTrackClick,
                onLikeClick = onLikeClick
            )
            playlist != null -> FeedPlaylistRow(
                playlist = playlist,
                onClick = onPlaylistClick
            )
        }
    }
}

@Composable
private fun FeedAvatar(actor: UserInfo?) {
    val shape = CircleShape
    val initial = actor?.username?.trim()?.firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(ZyroAccentSoft, ZyroAccent, ZyroAccentDeep)))
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape),
        contentAlignment = Alignment.Center
    ) {
        val avatar = actor?.avatarUrl.orEmpty()
        if (avatar.isNotBlank()) {
            AsyncImage(
                model = avatar,
                contentDescription = actor?.username,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (initial.isNotBlank()) {
            Text(
                text = initial,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        } else {
            Icon(
                imageVector = Icons.Default.DynamicFeed,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun FeedTrackRow(
    track: TrackInfo,
    isPlaying: Boolean,
    isLiked: Boolean,
    onClick: () -> Unit,
    onLikeClick: () -> Unit
) {
    val shape = RoundedCornerShape(ZyroShapes.M)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 12.dp, bottom = 12.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = if (isPlaying) 0.07f else 0.035f))
            .border(
                width = 1.dp,
                color = if (isPlaying) ZyroAccent.copy(alpha = 0.45f) else ZyroGlassBorder,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(RoundedCornerShape(ZyroShapes.S))
                .background(ZyroSurface)
        ) {
            AsyncImage(
                model = track.artworkUrl.ifBlank { ZyroArtworkFallback },
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (track.isGoPlus) {
                GoPlusBadge(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                )
            }
            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ZyroAccent.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Играет",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Играть",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title.ifBlank { "Без названия" },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isPlaying) ZyroAccent else ZyroTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track.artist.ifBlank { "Неизвестный артист" },
                fontSize = 11.sp,
                color = ZyroTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val plays = formatCount(track.playbackCount)
                if (plays.isNotBlank()) {
                    FeedStat(Icons.Default.PlayArrow, plays)
                    Spacer(modifier = Modifier.width(10.dp))
                }
                val likes = formatCount(track.likesCount)
                if (likes.isNotBlank()) {
                    FeedStat(Icons.Default.FavoriteBorder, likes)
                    Spacer(modifier = Modifier.width(10.dp))
                }
                val duration = formatDurationMs(track.durationMs)
                if (duration.isNotBlank()) {
                    Text(text = duration, fontSize = 10.sp, color = ZyroTextSecondary)
                }
            }
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onLikeClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (isLiked) "Убрать лайк" else "Лайкнуть",
                tint = if (isLiked) ZyroAccent else ZyroTextSecondary,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun FeedStat(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ZyroTextSecondary,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(text = text, fontSize = 10.sp, color = ZyroTextSecondary)
    }
}

@Composable
private fun FeedPlaylistRow(playlist: PlaylistInfo, onClick: () -> Unit) {
    val shape = RoundedCornerShape(ZyroShapes.M)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 12.dp, bottom = 12.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, ZyroGlassBorder, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(RoundedCornerShape(ZyroShapes.S))
                .background(ZyroSurface)
        ) {
            AsyncImage(
                model = playlist.artworkUrl.ifBlank { ZyroArtworkFallback },
                contentDescription = playlist.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "ПЛЕЙЛИСТ",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 0.5.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.title.ifBlank { "Без названия" },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = ZyroTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = playlist.user.ifBlank { "SoundCloud" },
                fontSize = 11.sp,
                color = ZyroTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = tracksCountLabel(playlist.trackCount),
                fontSize = 10.sp,
                color = ZyroTextSecondary
            )
        }

        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(ZyroAccent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Открыть плейлист",
                tint = Color.White,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun FeedLoginCard(onLoginClick: () -> Unit) {
    FeedMessageCard(
        title = "Лента подписок",
        text = "Войдите в SoundCloud, чтобы видеть новые треки, плейлисты и репосты артистов, на которых вы подписаны.",
        buttonText = "Войти в SoundCloud",
        onButtonClick = onLoginClick
    )
}

@Composable
internal fun FeedMessageCard(
    title: String,
    text: String,
    buttonText: String,
    onButtonClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .zyroPlasticGlass(RoundedCornerShape(ZyroShapes.L))
            .padding(20.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(ZyroAccent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DynamicFeed,
                contentDescription = null,
                tint = ZyroAccent,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = ZyroTextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            color = ZyroTextSecondary,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onButtonClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(ZyroShapes.S),
            colors = ButtonDefaults.buttonColors(
                containerColor = ZyroAccent,
                contentColor = Color.White
            )
        ) {
            Text(text = buttonText, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FeedLoadingMore() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = ZyroAccent,
            strokeWidth = 2.2.dp
        )
    }
}

@Composable
private fun FeedEndLabel() {
    Text(
        text = "Вы дошли до конца ленты",
        fontSize = 12.sp,
        color = ZyroTextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp)
    )
}

@Composable
private fun FeedInlineError(error: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = error,
            fontSize = 11.sp,
            color = ZyroTextSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "Повторить",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = ZyroAccent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRetry)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

private fun feedActionLine(activity: ScFeedActivity): String {
    val action = when (activity.action) {
        ScFeedAction.UPLOAD -> if (activity.playlist != null) {
            "опубликовал плейлист"
        } else {
            "загрузил новый трек"
        }
        ScFeedAction.REPOST -> if (activity.playlist != null) {
            "поделился плейлистом"
        } else {
            "поделился треком"
        }
        ScFeedAction.LIKE -> if (activity.playlist != null) {
            "лайкнул плейлист"
        } else {
            "лайкнул трек"
        }
    }
    val time = feedTimeAgo(activity.createdAtMs)
    return if (time.isBlank()) action else "$action • $time"
}

private fun feedTimeAgo(createdAtMs: Long): String {
    if (createdAtMs <= 0L) return ""
    val diffMinutes = ((System.currentTimeMillis() - createdAtMs).coerceAtLeast(0L)) / 60_000L
    return when {
        diffMinutes < 1L -> "только что"
        diffMinutes < 60L -> "$diffMinutes мин назад"
        diffMinutes < 24L * 60L -> "${diffMinutes / 60L} ч назад"
        diffMinutes < 7L * 24L * 60L -> "${diffMinutes / (24L * 60L)} дн назад"
        else -> SimpleDateFormat("d MMM", Locale.forLanguageTag("ru"))
            .format(Date(createdAtMs))
    }
}

private fun tracksCountLabel(count: Int): String {
    if (count <= 0) return "Плейлист"
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> "треков"
        mod10 == 1 -> "трек"
        mod10 in 2..4 -> "трека"
        else -> "треков"
    }
    return "$count $word"
}
