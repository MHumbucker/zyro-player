package com.zyro.player.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.zyro.player.data.remote.TrackInfo
import com.zyro.player.data.remote.UserInfo
import com.zyro.player.ui.HomeSection
import com.zyro.player.ui.HomeSectionKind
import com.zyro.player.ui.components.HomeRecommendationsShimmer
import com.zyro.player.ui.components.GoPlusBadge
import com.zyro.player.ui.components.ZyroAccent
import com.zyro.player.ui.components.ZyroAccentDeep
import com.zyro.player.ui.components.ZyroAccentSoft
import com.zyro.player.ui.components.ZyroArtworkFallback
import com.zyro.player.ui.components.ZyroGlassBorder
import com.zyro.player.ui.components.ZyroIconButton
import com.zyro.player.ui.components.ZyroPullToRefreshBox
import com.zyro.player.ui.components.ZyroViolet
import com.zyro.player.ui.components.ZyroShapes
import com.zyro.player.ui.components.ZyroSurface
import com.zyro.player.ui.components.ZyroTextPrimary
import com.zyro.player.ui.components.ZyroTextSecondary
import com.zyro.player.ui.components.ZyroTopScrim
import com.zyro.player.ui.components.zyroPlasticGlass
import com.zyro.player.ui.components.zyroPressEffect

/**
 * Главный экран в стиле официального SoundCloud:
 * плашка «Ваши лайки» с шаффлом и лента секций из официального фида
 * SoundCloud («Миксы», «Больше из того, что вам нравится»,
 * «В тренде по жанрам», «Новое от артистов, на которых вы подписаны» и т.д.).
 */
@Composable
fun HomeScreen(
    sections: List<HomeSection>,
    coverOverrides: Map<String, String>,
    isLoading: Boolean,
    isConnected: Boolean,
    currentUser: UserInfo?,
    likesCount: Int,
    currentTrackUrl: String?,
    onRefreshClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onLikesPlateClick: () -> Unit,
    onShuffleLikesClick: () -> Unit,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onMixClick: (PlaylistInfo) -> Unit,
    onArtistClick: (UserInfo) -> Unit,
    onLoginClick: () -> Unit,
    onProfileClick: (UserInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    var headerHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxSize()) {
        if (isLoading && sections.isEmpty()) {
            HomeRecommendationsShimmer(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = headerHeight + 12.dp)
            )
        } else {
            ZyroPullToRefreshBox(
                isRefreshing = isLoading,
                onRefresh = onRefreshClick,
                modifier = Modifier.fillMaxSize(),
                indicatorTopPadding = headerHeight + 32.dp
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = headerHeight + 4.dp,
                        bottom = 220.dp
                    )
                ) {
                    // ==== «Ваши лайки»: широкая плашка + кнопка шаффла ====
                    item(key = "likes_plate") {
                        LikesPlate(
                            likesCount = likesCount,
                            onOpenClick = onLikesPlateClick,
                            onShuffleClick = onShuffleLikesClick
                        )
                    }

                    // ==== Приглашение авторизоваться (для гостей) ====
                    if (!isConnected && !isLoading) {
                        item(key = "login_prompt") {
                            LoginPromptCard(onLoginClick = onLoginClick)
                        }
                    }

                    // ==== Официальные секции фида ====
                    sections.forEach { section ->
                        item(key = "header_${section.id}") {
                            HomeSectionHeader(
                                title = section.title,
                                subtitle = section.subtitle
                            )
                        }
                        item(key = "row_${section.id}") {
                            when (section.kind) {
                                HomeSectionKind.TRACKS -> TracksRow(
                                    section = section,
                                    currentTrackUrl = currentTrackUrl,
                                    isHighlight = section.isHighlight,
                                    onTrackClick = onTrackClick
                                )
                                HomeSectionKind.MIXES -> MixesRow(
                                    section = section,
                                    coverOverrides = coverOverrides,
                                    onMixClick = onMixClick
                                )
                                HomeSectionKind.ARTISTS -> ArtistsRow(
                                    section = section,
                                    onArtistClick = onArtistClick
                                )
                            }
                        }
                    }

                    if (sections.isEmpty() && !isLoading) {
                        item(key = "empty_state") {
                            EmptyHomeCard(
                                onRefreshClick = onRefreshClick,
                                onSearchClick = onSearchClick
                            )
                        }
                    }
                }
            }
        }

        // Контент, ушедший под шапку, плавно затемняется — как над нижним баром.
        ZyroTopScrim(
            modifier = Modifier.align(Alignment.TopCenter),
            height = headerHeight + 24.dp,
            visible = isScrolled
        )

        // Прозрачная шапка плавает поверх контента.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                .statusBarsPadding()
        ) {
            HomeHeader(
                userName = currentUser?.username.orEmpty(),
                avatarUrl = currentUser?.avatarUrl.orEmpty(),
                hasProfile = currentUser != null,
                onProfileClick = { currentUser?.let(onProfileClick) },
                onSettingsClick = onSettingsClick
            )

            HomeSearchPill(onClick = onSearchClick)
        }
    }
}

// ============================================================
// HEADER / SEARCH
// ============================================================

@Composable
private fun HomeHeader(
    userName: String,
    avatarUrl: String,
    hasProfile: Boolean,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(ZyroAccentSoft, ZyroAccent, ZyroAccentDeep)
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .then(if (hasProfile) Modifier.clickable(onClick = onProfileClick) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = "Профиль",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(23.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SoundCloud",
                fontSize = 12.sp,
                color = ZyroTextSecondary
            )
            Text(
                text = userName.ifBlank { "Zyro Player" },
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = ZyroTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        ZyroIconButton(
            icon = Icons.Default.Settings,
            size = 44.dp,
            iconSize = 21.dp,
            contentDescription = "Настройки",
            onClick = onSettingsClick
        )
    }
}

@Composable
private fun HomeSearchPill(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .zyroPressEffect(interactionSource)
            .zyroPlasticGlass(shape = RoundedCornerShape(ZyroShapes.L))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = ZyroAccent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Треки, артисты, плейлисты",
            fontSize = 14.sp,
            color = ZyroTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = null,
            tint = ZyroTextSecondary.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
    }
}

// ============================================================
// ПЛАШКА «ВАШИ ЛАЙКИ»
// ============================================================

@Composable
private fun LikesPlate(
    likesCount: Int,
    onOpenClick: () -> Unit,
    onShuffleClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .height(76.dp)
            .clip(RoundedCornerShape(ZyroShapes.L))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        ZyroSurface.copy(alpha = 0.85f),
                        ZyroAccent.copy(alpha = 0.16f),
                        ZyroViolet.copy(alpha = 0.14f)
                    )
                )
            )
            .border(
                1.dp,
                ZyroAccent.copy(alpha = 0.30f),
                RoundedCornerShape(ZyroShapes.L)
            )
            .clickable(onClick = onOpenClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(ZyroAccent.copy(alpha = 0.18f))
                .border(1.dp, ZyroAccent.copy(alpha = 0.30f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = ZyroAccent,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Ваши лайки",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = ZyroTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (likesCount > 0) {
                    "$likesCount " + when {
                        likesCount % 10 == 1 && likesCount % 100 != 11 -> "трек"
                        likesCount % 10 in 2..4 && likesCount % 100 !in 12..14 -> "трека"
                        else -> "треков"
                    }
                } else {
                    "Поставьте первый лайк"
                },
                fontSize = 12.sp,
                color = ZyroTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Кнопка шаффла — единственная на плашке.
        if (likesCount > 0) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ZyroAccent)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable(onClick = onShuffleClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Перемешать и играть лайки",
                    tint = Color.White,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

// ============================================================
// SECTION HEADER
// ============================================================

@Composable
private fun HomeSectionHeader(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp)
    ) {
        Text(
            text = title,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = ZyroTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = ZyroTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ============================================================
// СЕКЦИИ
// ============================================================

/** Обычный ряд треков из официального фида. */
@Composable
private fun TracksRow(
    section: HomeSection,
    currentTrackUrl: String?,
    isHighlight: Boolean,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        if (isHighlight) {
            itemsIndexed(section.tracks, key = { _, t -> t.originalUrl }) { _, track ->
                HighlightTrackCard(
                    track = track,
                    isPlaying = track.originalUrl == currentTrackUrl,
                    onClick = { onTrackClick(track, section.tracks) }
                )
            }
        } else {
            itemsIndexed(section.tracks, key = { _, t -> t.originalUrl }) { _, track ->
                TrackCard(
                    track = track,
                    isPlaying = track.originalUrl == currentTrackUrl,
                    onClick = { onTrackClick(track, section.tracks) }
                )
            }
        }
    }
}

/** Ряд миксов SoundCloud: обложка + бейдж «МИКС N». */
@Composable
private fun MixesRow(
    section: HomeSection,
    coverOverrides: Map<String, String>,
    onMixClick: (PlaylistInfo) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        itemsIndexed(section.mixes, key = { idx, p -> "${p.urn.ifBlank { p.permalinkUrl }}_$idx" }) { idx, mix ->
            MixCard(
                playlist = mix,
                coverUrl = mixCoverUrl(mix, coverOverrides),
                mixNumber = idx + 1,
                onClick = { onMixClick(mix) }
            )
        }
    }
}

/** Ряд артистов из официального фида. */
@Composable
private fun ArtistsRow(
    section: HomeSection,
    onArtistClick: (UserInfo) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(section.artists, key = { _, a -> a.permalinkUrl }) { _, artist ->
            ArtistCircleCard(
                artist = artist,
                onClick = { onArtistClick(artist) }
            )
        }
    }
}

// ============================================================
// КАРТОЧКИ
// ============================================================

/** Стандартная карточка трека SoundCloud: обложка + название/артист. */
@Composable
private fun TrackCard(
    track: TrackInfo,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(ZyroShapes.L)

    Column(
        modifier = Modifier
            .width(148.dp)
            .zyroPressEffect(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(shape)
                .background(ZyroSurface)
                .border(
                    width = 1.dp,
                    color = if (isPlaying) ZyroAccent.copy(alpha = 0.55f) else ZyroGlassBorder,
                    shape = shape
                )
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
                        .padding(8.dp)
                )
            }
            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ZyroAccent.copy(alpha = 0.16f))
                )
                NowPlayingBars(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = track.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isPlaying) ZyroAccent else ZyroTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = track.artist,
            fontSize = 11.sp,
            color = ZyroTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Большая карточка «Новое от артистов, на которых вы подписаны». */
@Composable
private fun HighlightTrackCard(
    track: TrackInfo,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(ZyroShapes.L)

    Box(
        modifier = Modifier
            .width(240.dp)
            .zyroPressEffect(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(shape)
                .background(ZyroSurface)
                .border(
                    width = 1.dp,
                    color = if (isPlaying) ZyroAccent.copy(alpha = 0.55f) else ZyroGlassBorder,
                    shape = shape
                )
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
                        .padding(8.dp)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.72f)
                            )
                        )
                    )
            )
            if (isPlaying) NowPlayingBars(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = track.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = track.artist,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Карточка микса SoundCloud: обложка с бейджем «МИКС N». */
@Composable
private fun MixCard(
    playlist: PlaylistInfo,
    coverUrl: String,
    mixNumber: Int,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(ZyroShapes.L)

    Column(
        modifier = Modifier
            .width(152.dp)
            .zyroPressEffect(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(152.dp)
                .clip(shape)
                .background(ZyroSurface)
                .border(1.dp, ZyroGlassBorder, shape)
        ) {
            AsyncImage(
                model = coverUrl.ifBlank { ZyroArtworkFallback },
                contentDescription = playlist.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.35f)
                            )
                        )
                    )
            )
            // Плашка «МИКС N», как в официальном SoundCloud.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(9.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.dp, ZyroAccent.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = null,
                    tint = ZyroAccent,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "МИКС $mixNumber",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = Color.White
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = ZyroTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val subtitle = playlist.description.ifBlank { playlist.user }
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = ZyroTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Круглая карточка артиста из официального фида. */
@Composable
private fun ArtistCircleCard(
    artist: UserInfo,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .width(100.dp)
            .zyroPressEffect(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(ZyroSurface)
                .border(1.dp, ZyroGlassBorder, CircleShape)
        ) {
            if (artist.avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = artist.avatarUrl,
                    contentDescription = artist.username,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = ZyroTextSecondary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(
            text = artist.username,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = ZyroTextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (artist.followersCount > 0) {
            Text(
                text = formatFollowersCount(artist.followersCount) + " подписчиков",
                fontSize = 10.sp,
                color = ZyroTextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ============================================================
// ИНДИКАТОР ВОСПРОИЗВЕДЕНИЯ
// ============================================================

@Composable
private fun NowPlayingBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "now_playing")

    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(ZyroAccent.copy(alpha = 0.90f))
            .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { index ->
                val height by transition.animateFloat(
                    initialValue = 4f,
                    targetValue = if (index == 1) 11f else 8f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = 420 + index * 90,
                            easing = FastOutSlowInEasing
                        ),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "bar_$index"
                )
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(height.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color.White)
                )
            }
        }
    }
}

// ============================================================
// ПУСТОЕ СОСТОЯНИЕ / ПРИГЛАШЕНИЕ
// ============================================================

@Composable
private fun LoginPromptCard(onLoginClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(ZyroShapes.L)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .zyroPlasticGlass(shape = shape)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ZyroAccent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = ZyroAccent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Покажите, что вам нравится",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZyroTextPrimary
                )
                Text(
                    text = "Войдите в SoundCloud — и здесь появятся «Миксы» и личный фид",
                    fontSize = 12.sp,
                    color = ZyroTextSecondary
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ZyroAccent)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onLoginClick
                )
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Войти в SoundCloud",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun EmptyHomeCard(
    onRefreshClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    val shape = RoundedCornerShape(ZyroShapes.L)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .zyroPlasticGlass(shape = shape)
            .padding(18.dp)
    ) {
        Text(
            text = "Пока пусто",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = ZyroTextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Фид SoundCloud не загрузился. Проверьте сеть или обновите главную.",
            fontSize = 12.sp,
            color = ZyroTextSecondary
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ZyroAccent)
                    .clickable(onClick = onRefreshClick)
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Обновить",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ZyroSurface.copy(alpha = 0.55f))
                    .border(1.dp, ZyroGlassBorder, RoundedCornerShape(14.dp))
                    .clickable(onClick = onSearchClick)
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "В поиск",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZyroTextPrimary
                )
            }
        }
    }
}

// ============================================================
// HELPERS
// ============================================================

/** Ключ обложки плашки: urn системного плейлиста либо permalink обычного. */
private fun mixCoverKey(playlist: PlaylistInfo): String =
    playlist.urn.ifBlank { playlist.permalinkUrl }

/**
 * Обложка микса: собственная картинка → сохранённая обложка первого трека.
 * Строка "null" приходит из API как JSON null и обложкой не считается.
 */
private fun mixCoverUrl(playlist: PlaylistInfo, overrides: Map<String, String>): String {
    val own = playlist.artworkUrl.takeUnless { it.isBlank() || it == "null" }
    return own ?: overrides[mixCoverKey(playlist)].takeUnless { it == null || it == "null" }.orEmpty()
}

private fun formatFollowersCount(value: Int): String = when {
    value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000f)
    value >= 1_000 -> String.format("%.1fK", value / 1_000f)
    else -> value.toString()
}
