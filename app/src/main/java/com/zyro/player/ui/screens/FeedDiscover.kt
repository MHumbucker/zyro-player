package com.zyro.player.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zyro.player.data.remote.TrackInfo
import com.zyro.player.ui.components.GoPlusBadge
import com.zyro.player.ui.components.ZyroAccent
import com.zyro.player.ui.components.ZyroAccentDeep
import com.zyro.player.ui.components.ZyroAccentSoft
import com.zyro.player.ui.components.ZyroArtworkFallback
import com.zyro.player.ui.components.ZyroGlassBorder
import com.zyro.player.ui.components.ZyroSurface
import com.zyro.player.ui.components.ZyroTextSecondary
import com.zyro.player.ui.components.formatCount
import com.zyro.player.ui.components.formatDurationMs

/** Переключатель секций внутри вкладки «Лента». */
@Composable
internal fun FeedSectionSwitcher(
    selected: FeedSection,
    onSelect: (FeedSection) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .height(42.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, ZyroGlassBorder, CircleShape)
            .padding(4.dp)
    ) {
        FeedSectionButton(
            text = "Найти",
            icon = Icons.Default.Explore,
            isSelected = selected == FeedSection.DISCOVER,
            modifier = Modifier.weight(1f)
        ) { onSelect(FeedSection.DISCOVER) }
        FeedSectionButton(
            text = "Подписки",
            icon = Icons.Default.DynamicFeed,
            isSelected = selected == FeedSection.FOLLOWING,
            modifier = Modifier.weight(1f)
        ) { onSelect(FeedSection.FOLLOWING) }
    }
}

@Composable
private fun FeedSectionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "feed_section_press"
    )
    val background by animateColorAsState(
        targetValue = if (isSelected) ZyroAccent.copy(alpha = 0.22f) else Color.Transparent,
        label = "feed_section_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) ZyroAccent.copy(alpha = 0.40f) else Color.Transparent,
        label = "feed_section_border"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) ZyroAccent else ZyroTextSecondary,
        label = "feed_section_content"
    )

    Row(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(background)
            .border(1.dp, borderColor, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1
        )
    }
}

/**
 * Вертикальная рекомендательная лента: одна карточка на экран, свайп вверх
 * открывает следующий трек. Первый трек запускается кнопкой, дальнейшие —
 * автоматически после свайпа (как в TikTok-режиме).
 */
@Composable
internal fun DiscoverFeedContent(
    pagerState: PagerState,
    tracks: List<TrackInfo>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    error: String?,
    currentTrackUrl: String?,
    isPlaying: Boolean,
    likedUrls: Set<String>,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPlayTrack: (TrackInfo) -> Unit,
    onTogglePlayPause: () -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onArtistClick: (TrackInfo) -> Unit
) {
    when {
        isLoading && tracks.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = ZyroAccent, strokeWidth = 2.5.dp)
        }

        error != null && tracks.isEmpty() -> FeedMessageCard(
            title = "Не удалось загрузить рекомендации",
            text = error,
            buttonText = "Повторить",
            onButtonClick = onRefresh
        )

        tracks.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = ZyroAccent, strokeWidth = 2.5.dp)
        }

        else -> Box(modifier = Modifier.fillMaxSize()) {
            // Догрузка, когда пользователь подошёл к концу очереди.
            LaunchedEffect(pagerState, tracks.size, hasMore, isLoadingMore) {
                snapshotFlow { pagerState.settledPage to tracks.size }
                    .collect { (page, size) ->
                        if (hasMore && !isLoadingMore && size > 0 && page >= size - 3) {
                            onLoadMore()
                        }
                    }
            }

            // Автоплей только после осознанного свайпа: первый экран ждёт
            // нажатия «Слушать», а переход на другую страницу сразу играет её.
            var lastSettledPage by remember { mutableIntStateOf(pagerState.currentPage) }
            // true, если текущая прокрутка начата пальцем (а не animateScrollToPage).
            var userScrollLaunched by remember { mutableStateOf(false) }
            // Подсказка «свайп вверх» нужна только до первого осознанного свайпа.
            var hasSwiped by remember { mutableStateOf(false) }
            val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
            LaunchedEffect(isDragged) {
                if (isDragged) userScrollLaunched = true
            }
            // Ключ — сам список, а не его размер: после обновления ленты
            // эффект должен видеть актуальные треки, иначе свайп запустит
            // трек из прежней подборки.
            LaunchedEffect(pagerState, tracks, currentTrackUrl) {
                snapshotFlow { pagerState.isScrollInProgress to pagerState.settledPage }
                    .collect { (isScrolling, page) ->
                        if (isScrolling) return@collect
                        val wasUserScroll = userScrollLaunched
                        userScrollLaunched = false
                        if (page == lastSettledPage) {
                            lastSettledPage = page
                            return@collect
                        }
                        lastSettledPage = page
                        if (!wasUserScroll) return@collect
                        hasSwiped = true
                        val track = tracks.getOrNull(page) ?: return@collect
                        if (track.originalUrl == currentTrackUrl) {
                            onTogglePlayPause()
                        } else {
                            onPlayTrack(track)
                        }
                    }
            }

            // Если очередь плеера ушла на следующий/предыдущий трек из «Найти»,
            // подтягиваем свайпер к звучащему треку. Программный скролл не
            // включает автоплей (см. userScrollLaunched выше).
            LaunchedEffect(currentTrackUrl, tracks) {
                val url = currentTrackUrl ?: return@LaunchedEffect
                val index = tracks.indexOfFirst { it.originalUrl == url }
                if (index >= 0 && !pagerState.isScrollInProgress && pagerState.settledPage != index) {
                    pagerState.animateScrollToPage(index)
                }
            }

            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                pageSpacing = 10.dp,
                key = { index ->
                    tracks.getOrNull(index)?.let { track ->
                        if (track.id != 0L) "id:${track.id}" else track.originalUrl
                    } ?: "discover_$index"
                }
            ) { page ->
                val track = tracks.getOrNull(page) ?: return@VerticalPager
                val isCurrent = track.originalUrl == currentTrackUrl
                DiscoverTrackPage(
                    track = track,
                    pageIndex = page,
                    total = tracks.size,
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && isPlaying,
                    isLiked = likedUrls.contains(track.originalUrl),
                    isScaledUp = pagerState.settledPage == page,
                    notice = when {
                        error != null -> DiscoverNoticeKind.ERROR
                        isLoadingMore -> DiscoverNoticeKind.LOADING
                        page == 0 && !hasSwiped && tracks.size > 1 ->
                            DiscoverNoticeKind.SWIPE_HINT
                        else -> DiscoverNoticeKind.NONE
                    },
                    onNoticeClick = {
                        if (error != null) {
                            if (hasMore) onLoadMore() else onRefresh()
                        }
                    },
                    onPlayClick = {
                        if (isCurrent) onTogglePlayPause() else onPlayTrack(track)
                    },
                    onLikeClick = { onLikeTrack(track) },
                    onArtistClick = { onArtistClick(track) }
                )
            }
        }
    }
}

private enum class DiscoverNoticeKind { NONE, SWIPE_HINT, LOADING, ERROR }

/**
 * Нижний отступ контента карточки: мини-плеер и нижняя навигация лежат
 * поверх вкладок (Scaffold не режет контент), поэтому текст и действия
 * держим выше них.
 */
private val DiscoverBottomSafeArea = 220.dp

@Composable
private fun DiscoverTrackPage(
    track: TrackInfo,
    pageIndex: Int,
    total: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isLiked: Boolean,
    isScaledUp: Boolean,
    notice: DiscoverNoticeKind,
    onNoticeClick: () -> Unit,
    onPlayClick: () -> Unit,
    onLikeClick: () -> Unit,
    onArtistClick: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    val scale by animateFloatAsState(
        targetValue = if (isScaledUp) 1f else 0.96f,
        label = "discover_page_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(ZyroSurface)
            .border(1.dp, Color.White.copy(alpha = if (isScaledUp) 0.14f else 0.06f), shape)
    ) {
        AsyncImage(
            model = track.artworkUrl.ifBlank { ZyroArtworkFallback },
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Затемнение снизу — под ним читаются название, артист и действия.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.20f),
                            0.42f to Color.Black.copy(alpha = 0.06f),
                            0.66f to Color.Black.copy(alpha = 0.60f),
                            1f to Color.Black.copy(alpha = 0.94f)
                        )
                    )
                )
        )

        // Тап по обложке — play/pause, как в TikTok. Действия справа и
        // подпись артиста лежат выше и перехватывают нажатия сами.
        val artInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = artInteractionSource,
                    indication = null,
                    onClick = onPlayClick
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.38f))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = null,
                    tint = ZyroAccentSoft,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "НАЙТИ",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${pageIndex + 1} / $total",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.75f)
            )
            if (track.isGoPlus) {
                Spacer(modifier = Modifier.width(8.dp))
                GoPlusBadge()
            }
        }

        if (notice != DiscoverNoticeKind.NONE) {
            DiscoverNotice(
                kind = notice,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 62.dp),
                onClick = onNoticeClick
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 18.dp, end = 96.dp, bottom = DiscoverBottomSafeArea)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onArtistClick)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DiscoverAvatar(
                    avatarUrl = track.artistAvatarUrl,
                    contentDescription = track.artist
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = track.artist.ifBlank { "Неизвестный артист" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = track.title.ifBlank { "Без названия" },
                fontSize = 23.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val plays = formatCount(track.playbackCount)
                if (plays.isNotBlank()) {
                    DiscoverStat(Icons.Default.PlayArrow, plays)
                    Spacer(modifier = Modifier.width(12.dp))
                }
                val likes = formatCount(track.likesCount)
                if (likes.isNotBlank()) {
                    DiscoverStat(Icons.Default.FavoriteBorder, likes)
                    Spacer(modifier = Modifier.width(12.dp))
                }
                val duration = formatDurationMs(track.durationMs)
                if (duration.isNotBlank()) {
                    Text(
                        text = duration,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }

            if (isCurrent) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(ZyroAccent.copy(alpha = 0.22f))
                        .border(1.dp, ZyroAccent.copy(alpha = 0.45f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = ZyroAccentSoft,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isPlaying) "Играет сейчас" else "На паузе",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = DiscoverBottomSafeArea),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            DiscoverRailButton(
                icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                label = formatCount(track.likesCount).ifBlank { "Лайк" },
                tint = if (isLiked) ZyroAccent else Color.White,
                contentDescription = if (isLiked) "Убрать лайк" else "Лайкнуть",
                onClick = onLikeClick
            )
            DiscoverPlayButton(
                isPlaying = isPlaying,
                onClick = onPlayClick
            )
            DiscoverRailButton(
                icon = Icons.Default.Person,
                label = "Артист",
                tint = Color.White,
                contentDescription = "Открыть артиста",
                onClick = onArtistClick
            )
        }
    }
}

@Composable
private fun DiscoverNotice(
    kind: DiscoverNoticeKind,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val text = when (kind) {
        DiscoverNoticeKind.SWIPE_HINT -> "Свайп вверх — следующий трек"
        DiscoverNoticeKind.LOADING -> "Подгружаем ещё…"
        DiscoverNoticeKind.ERROR -> "Не удалось подгрузить • Повторить"
        DiscoverNoticeKind.NONE -> return
    }
    val isClickable = kind == DiscoverNoticeKind.ERROR
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .then(
                if (isClickable) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (kind == DiscoverNoticeKind.LOADING) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = ZyroAccentSoft,
                strokeWidth = 1.6.dp
            )
            Spacer(modifier = Modifier.width(7.dp))
        }
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.88f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DiscoverAvatar(avatarUrl: String, contentDescription: String?) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl.isNotBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun DiscoverStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.70f),
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = text,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.72f)
        )
    }
}

@Composable
private fun DiscoverRailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    contentDescription: String?,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        label = "discover_rail_press"
    )
    Column(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.38f))
                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.82f),
            maxLines = 1
        )
    }
}

@Composable
private fun DiscoverPlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        label = "discover_play_press"
    )
    Box(
        modifier = Modifier
            .size(58.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(ZyroAccentSoft, ZyroAccentDeep)))
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Пауза" else "Слушать",
            tint = Color.White,
            modifier = Modifier.size(30.dp)
        )
    }
}
