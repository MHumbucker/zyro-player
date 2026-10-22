@file:OptIn(ExperimentalMaterial3Api::class)

package com.zyro.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.zyro.player.data.remote.AlbumInfo
import com.zyro.player.data.remote.ArtistFullData
import com.zyro.player.data.remote.PlaylistInfo
import com.zyro.player.data.remote.TrackInfo


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailModal(
    artistData: ArtistFullData,
    currentTrackUrl: String?,
    likedUrls: Set<String>,
    offlineUrls: Set<String>,
    isLoading: Boolean = false,
    isRefreshing: Boolean = false,
    isLiked: Boolean = false,
    isFollowing: Boolean = false,
    isOwnProfile: Boolean = false,
    onRefresh: () -> Unit = {},
    onLikeArtist: () -> Unit = {},
    onFollowArtist: () -> Unit = {},
    onEditProfile: () -> Unit = {},
    onClose: () -> Unit,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onDownloadTrack: (TrackInfo) -> Unit,
    onDeleteOfflineTrack: ((TrackInfo) -> Unit)? = null,
    onAlbumClick: (AlbumInfo) -> Unit = {},
    onPlaylistClick: (PlaylistInfo) -> Unit = {}
) {
    var activeCategoryView by remember { mutableStateOf<ArtistCategoryView?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (activeCategoryView != null) {
            activeCategoryView = null
        } else {
            onClose()
        }
    }

    val artist = artistData.user
    val location = listOf(artist.city, artist.country).filter { it.isNotBlank() }.joinToString(", ")

    ZyroAuroraBackground {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("artist_detail_screen"),
            color = Color.Transparent
        ) {
        // Плавный переход между профилем и экраном «Смотреть все».
        // Pull-to-refresh работает и в основном профиле, и в списках «Смотреть все».
        ZyroPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
        AnimatedContent(
            targetState = activeCategoryView,
            transitionSpec = { zyroDetailTransition(forward = targetState != null) },
            label = "artist_view_transition",
            modifier = Modifier.fillMaxSize()
        ) { categoryView ->
            if (categoryView != null) {
                // "SEE ALL" SCREEN VIEW
                var headerHeight by remember { mutableStateOf(0.dp) }
                val density = LocalDensity.current
                val listState = rememberLazyListState()
                val isScrolled by remember {
                    derivedStateOf {
                        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .zIndex(2f)
                            .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                        title = {
                            Text(
                                text = "${categoryView.title} — ${artist.username}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = ZyroTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { activeCategoryView = null }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Назад"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = headerHeight, bottom = 180.dp)
                    ) {
                        val trackList = when (categoryView) {
                            ArtistCategoryView.TOP_TRACKS -> artistData.topTracks
                            ArtistCategoryView.TRACKS -> artistData.tracks
                            ArtistCategoryView.REPOSTS -> artistData.reposts
                            else -> emptyList()
                        }

                        if (trackList.isNotEmpty()) {
                            item(key = "cat_actions_${categoryView.name}") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ZyroIconButton(
                                        icon = Icons.Default.Shuffle,
                                        onClick = {
                                            val shuffled = trackList.shuffled()
                                            if (shuffled.isNotEmpty()) onTrackClick(shuffled.first(), shuffled)
                                        },
                                        size = 48.dp,
                                        iconSize = 22.dp,
                                        tint = ZyroAccent,
                                        contentDescription = "Перемешать"
                                    )
                                    Button(
                                        onClick = { onTrackClick(trackList.first(), trackList) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ZyroAccent,
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Слушать")
                                    }
                                }
                            }

                            itemsIndexed(
                                trackList,
                                key = { i, tr -> "${categoryView.name}_$i#${tr.originalUrl}" }
                            ) { _, track ->
                                TrackListItem(
                                    track = track,
                                    isPlaying = track.originalUrl == currentTrackUrl,
                                    isLiked = likedUrls.contains(track.originalUrl),
                                    isOffline = offlineUrls.contains(track.originalUrl),
                                    onTrackClick = { onTrackClick(track, trackList) },
                                    onLikeClick = { onLikeTrack(track) },
                                    onDownloadClick = { onDownloadTrack(track) },
                                    onDeleteOfflineClick = onDeleteOfflineTrack?.let { cb -> { cb(track) } },
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(horizontal = 12.dp, vertical = 2.dp)
                                )
                            }
                        } else if (categoryView == ArtistCategoryView.ALBUMS) {
                            itemsIndexed(
                                artistData.albums,
                                key = { i, a -> "albums_$i#${a.permalinkUrl}#${a.id}" }
                            ) { _, album ->
                                Box(modifier = Modifier.animateItem()) {
                                    AlbumListItem(album = album, onClick = { onAlbumClick(album) })
                                }
                            }
                        } else if (categoryView == ArtistCategoryView.PLAYLISTS) {
                            itemsIndexed(
                                artistData.playlists,
                                key = { i, p -> p.permalinkUrl.ifBlank { "pl_${i}_${p.id}" } }
                            ) { _, playlist ->
                                Box(modifier = Modifier.animateItem()) {
                                    PlaylistListItem(playlist = playlist, onClick = { onPlaylistClick(playlist) })
                                }
                            }
                        }
                    }

                    ZyroTopScrim(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .zIndex(1f),
                        height = headerHeight + 16.dp,
                        visible = isScrolled
                    )
                }
            } else {
                ArtistMainView(
                    artistData = artistData,
                    isLoading = isLoading,
                    currentTrackUrl = currentTrackUrl,
                    likedUrls = likedUrls,
                    offlineUrls = offlineUrls,
                    isLiked = isLiked,
                    isFollowing = isFollowing,
                    isOwnProfile = isOwnProfile,
                    location = location,
                    onLikeArtist = onLikeArtist,
                    onFollowArtist = onFollowArtist,
                    onEditProfile = onEditProfile,
                    onClose = onClose,
                    onShowInfo = { showInfoDialog = true },
                    onTrackClick = onTrackClick,
                    onLikeTrack = onLikeTrack,
                    onDownloadTrack = onDownloadTrack,
                    onDeleteOfflineTrack = onDeleteOfflineTrack,
                    onAlbumClick = onAlbumClick,
                    onPlaylistClick = onPlaylistClick,
                    onOpenCategory = { activeCategoryView = it }
                )
            }
        }
        }

        // Шторка «Об артисте» — на общей стеклянной дизайн-системе.
        ArtistInfoSheet(
            visible = showInfoDialog,
            artist = artist,
            location = location,
            description = artistData.description,
            onDismiss = { showInfoDialog = false }
        )
        }
    }
}

@Composable
private fun ArtistMainView(
    artistData: ArtistFullData,
    isLoading: Boolean,
    currentTrackUrl: String?,
    likedUrls: Set<String>,
    offlineUrls: Set<String>,
    isLiked: Boolean,
    isFollowing: Boolean,
    isOwnProfile: Boolean,
    location: String,
    onLikeArtist: () -> Unit,
    onFollowArtist: () -> Unit,
    onEditProfile: () -> Unit,
    onClose: () -> Unit,
    onShowInfo: () -> Unit,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onDownloadTrack: (TrackInfo) -> Unit,
    onDeleteOfflineTrack: ((TrackInfo) -> Unit)? = null,
    onAlbumClick: (AlbumInfo) -> Unit,
    onPlaylistClick: (PlaylistInfo) -> Unit,
    onOpenCategory: (ArtistCategoryView) -> Unit
) {
    val artist = artistData.user
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 180.dp)
        ) {
            // ITEM 0: BANNER + AVATAR OVERLAPPING 50%
            item(key = "artist_header") {
                val bannerHeight = 180.dp
                val avatarSize = 120.dp
                val avatarHalf = avatarSize / 2

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bannerHeight + avatarHalf)
                ) {
                    if (artist.bannerUrl.isNotBlank()) {
                        AsyncImage(
                            model = artist.bannerUrl,
                            contentDescription = "Artist Banner",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(bannerHeight)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(bannerHeight)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            ZyroAccent.copy(alpha = 0.6f),
                                            ZyroSurface
                                        )
                                    )
                                )
                        )
                    }

                    // Dark gradient overlay at top of banner for floating action buttons visibility
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)
                                )
                            )
                    )

                    // Avatar overlapping banner by 50%
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 20.dp)
                            .size(avatarSize)
                            .clip(CircleShape)
                            .background(ZyroBackground)
                            .padding(3.dp)
                            .clip(CircleShape)
                    ) {
                        ArtistAvatarImage(
                            url = artist.avatarUrl,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // ITEM 1: LEFT-ALIGNED ARTIST INFO & ACTIONS
            item(key = "artist_info") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Text(
                            text = artist.username.ifBlank { "Исполнитель" },
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = ZyroTextPrimary,
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                        )
                        if (artistData.description.isNotBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onShowInfo,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = "Информация об артисте",
                                    tint = ZyroAccent
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val statsKnown = artist.trackCount > 0 || artist.followersCount > 0
                    if (isLoading && !statsKnown) {
                        // Заглушка вместо «0 подписчиков • 0 треков»
                        ShimmerBox(
                            modifier = Modifier
                                .width(190.dp)
                                .height(14.dp),
                            cornerRadius = ZyroShapes.XS
                        )
                    } else {
                        Text(
                            text = "${formatCount(artist.followersCount)} подписчиков • ${formatCount(artist.trackCount)} треков",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ZyroTextSecondary,
                            textAlign = TextAlign.Start
                        )
                    }

                    if (location.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Place,
                                contentDescription = null,
                                tint = ZyroAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = location,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ZyroTextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    when {
                        artistData.topTracks.isNotEmpty() -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ZyroIconButton(
                                    icon = Icons.Default.Shuffle,
                                    onClick = {
                                        val shuffled = artistData.topTracks.shuffled()
                                        if (shuffled.isNotEmpty()) onTrackClick(shuffled.first(), shuffled)
                                    },
                                    size = 48.dp,
                                    iconSize = 22.dp,
                                    tint = ZyroAccent,
                                    contentDescription = "Перемешать"
                                )
                                Button(
                                    onClick = { onTrackClick(artistData.topTracks.first(), artistData.topTracks) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ZyroAccent,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Слушать")
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Для своего профиля — редактирование, для чужого — подписка
                            if (isOwnProfile) {
                                OutlinedButton(
                                    onClick = onEditProfile,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = ZyroAccent
                                    ),
                                    border = BorderStroke(1.dp, ZyroAccent)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Редактировать профиль")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = onFollowArtist,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = if (isFollowing) ZyroTextSecondary else ZyroAccent
                                    ),
                                    border = BorderStroke(1.dp, if (isFollowing) ZyroTextSecondary else ZyroAccent)
                                ) {
                                    Icon(
                                        imageVector = if (isFollowing) Icons.Default.PersonRemove else Icons.Default.PersonAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(if (isFollowing) "Вы подписаны" else "Подписаться")
                                }
                            }
                        }

                        isLoading -> {
                            // Заглушки кнопок, пока грузятся треки
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ShimmerBox(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    cornerRadius = 14.dp
                                )
                                ShimmerBox(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    cornerRadius = 14.dp
                                )
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                // Скелетон секций — приложение выглядит живым, пока грузятся данные
                item(key = "artist_skeleton") {
                    ArtistContentSkeleton()
                }
            } else {
                // SECTION 1: BEST TRACKS (Лучшие треки)
                if (artistData.topTracks.isNotEmpty()) {
                    item(key = "sec_top_header") {
                        ArtistSectionHeader(
                            title = "Лучшие треки",
                            count = artistData.topTracks.size,
                            onSeeAll = { onOpenCategory(ArtistCategoryView.TOP_TRACKS) }
                        )
                    }

                    itemsIndexed(
                        artistData.topTracks.take(3),
                        key = { i, tr -> "top_$i#${tr.originalUrl}" }
                    ) { _, track ->
                        TrackListItem(
                            track = track,
                            isPlaying = track.originalUrl == currentTrackUrl,
                            isLiked = likedUrls.contains(track.originalUrl),
                            isOffline = offlineUrls.contains(track.originalUrl),
                            onTrackClick = { onTrackClick(track, artistData.topTracks) },
                            onLikeClick = { onLikeTrack(track) },
                            onDownloadClick = { onDownloadTrack(track) },
                            onDeleteOfflineClick = onDeleteOfflineTrack?.let { cb -> { cb(track) } },
                            modifier = Modifier
                                .animateItem()
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }

                // SECTION 2: ALBUMS (Альбомы)
                if (artistData.albums.isNotEmpty()) {
                    item(key = "sec_albums_header") {
                        ArtistSectionHeader(
                            title = "Альбомы",
                            count = artistData.albums.size,
                            onSeeAll = { onOpenCategory(ArtistCategoryView.ALBUMS) }
                        )
                    }

                    itemsIndexed(
                        artistData.albums.take(3),
                        key = { i, a -> "albums_$i#${a.permalinkUrl}#${a.id}" }
                    ) { _, album ->
                        Box(modifier = Modifier.animateItem()) {
                            AlbumListItem(album = album, onClick = { onAlbumClick(album) })
                        }
                    }
                }

                // SECTION 3: ALL TRACKS (Треки)
                if (artistData.tracks.isNotEmpty()) {
                    item(key = "sec_tracks_header") {
                        ArtistSectionHeader(
                            title = "Все треки",
                            // track_count из профиля — авторитетный счётчик,
                            // даже если API отдал не всю коллекцию.
                            count = if (artistData.user.trackCount > 0) {
                                artistData.user.trackCount
                            } else {
                                artistData.tracks.size
                            },
                            onSeeAll = { onOpenCategory(ArtistCategoryView.TRACKS) }
                        )
                    }

                    itemsIndexed(
                        artistData.tracks.take(3),
                        key = { i, tr -> "all_$i#${tr.originalUrl}" }
                    ) { _, track ->
                        TrackListItem(
                            track = track,
                            isPlaying = track.originalUrl == currentTrackUrl,
                            isLiked = likedUrls.contains(track.originalUrl),
                            isOffline = offlineUrls.contains(track.originalUrl),
                            onTrackClick = { onTrackClick(track, artistData.tracks) },
                            onLikeClick = { onLikeTrack(track) },
                            onDownloadClick = { onDownloadTrack(track) },
                            onDeleteOfflineClick = onDeleteOfflineTrack?.let { cb -> { cb(track) } },
                            modifier = Modifier
                                .animateItem()
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }

                // SECTION 4: PLAYLISTS (Плейлисты)
                if (artistData.playlists.isNotEmpty()) {
                    item(key = "sec_playlists_header") {
                        ArtistSectionHeader(
                            title = "Плейлисты",
                            count = artistData.playlists.size,
                            onSeeAll = { onOpenCategory(ArtistCategoryView.PLAYLISTS) }
                        )
                    }

                    itemsIndexed(
                        artistData.playlists.take(3),
                        key = { i, p -> "playlists_$i#${p.permalinkUrl}#${p.id}" }
                    ) { _, playlist ->
                        Box(modifier = Modifier.animateItem()) {
                            PlaylistListItem(playlist = playlist, onClick = { onPlaylistClick(playlist) })
                        }
                    }
                }

                // SECTION 5: REPOSTS (Репосты)
                if (artistData.reposts.isNotEmpty()) {
                    item(key = "sec_reposts_header") {
                        ArtistSectionHeader(
                            title = "Репосты",
                            count = artistData.reposts.size,
                            onSeeAll = { onOpenCategory(ArtistCategoryView.REPOSTS) }
                        )
                    }

                    itemsIndexed(
                        artistData.reposts.take(3),
                        key = { i, tr -> "reposts_$i#${tr.originalUrl}" }
                    ) { _, track ->
                        TrackListItem(
                            track = track,
                            isPlaying = track.originalUrl == currentTrackUrl,
                            isLiked = likedUrls.contains(track.originalUrl),
                            isOffline = offlineUrls.contains(track.originalUrl),
                            onTrackClick = { onTrackClick(track, artistData.reposts) },
                            onLikeClick = { onLikeTrack(track) },
                            onDownloadClick = { onDownloadTrack(track) },
                            onDeleteOfflineClick = onDeleteOfflineTrack?.let { cb -> { cb(track) } },
                            modifier = Modifier
                                .animateItem()
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }

                // Пусто — ничего не нашлось (загрузка завершилась без данных)
                if (artistData.topTracks.isEmpty() &&
                    artistData.albums.isEmpty() &&
                    artistData.tracks.isEmpty() &&
                    artistData.playlists.isEmpty() &&
                    artistData.reposts.isEmpty()
                ) {
                    item(key = "artist_empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Не удалось загрузить материалы исполнителя",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ZyroTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // Мягкое затемнение под плавающей прозрачной шапкой.
        ZyroTopScrim(
            modifier = Modifier.align(Alignment.TopCenter),
            height = 104.dp
        )

        // Floating transparent TopAppBar over scrolling view
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.35f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = Color.White
                            )
                        }
                    }
                }
            },
            actions = {
                IconButton(onClick = onLikeArtist) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.35f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val iconScale by animateFloatAsState(
                                targetValue = if (isLiked) 1.15f else 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "artist_like_scale"
                            )
                            Icon(
                                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "В избранное",
                                tint = if (isLiked) ZyroAccent else Color.White,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                }
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun ArtistSectionHeader(
    title: String,
    count: Int,
    onSeeAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = ZyroTextPrimary
        )
        TextButton(onClick = onSeeAll) {
            Text("Смотреть все ($count)")
        }
    }
}

/** Скелетон контента профиля, пока догружаются данные SoundCloud. */
@Composable
private fun ArtistContentSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(2) { group ->
            Column(modifier = Modifier.padding(top = 12.dp)) {
                ShimmerBox(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .width(if (group == 0) 170.dp else 140.dp)
                        .height(18.dp),
                    cornerRadius = ZyroShapes.XS
                )
                Spacer(modifier = Modifier.height(10.dp))
                repeat(3) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ShimmerBox(
                            modifier = Modifier.size(56.dp),
                            cornerRadius = ZyroShapes.S
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ShimmerBox(
                                modifier = Modifier
                                    .fillMaxWidth(0.62f)
                                    .height(14.dp),
                                cornerRadius = ZyroShapes.XS
                            )
                            ShimmerBox(
                                modifier = Modifier
                                    .fillMaxWidth(0.36f)
                                    .height(12.dp),
                                cornerRadius = ZyroShapes.XS
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Компактный формат чисел: 12345 → "12,3K". */
private fun formatCount(value: Int): String {
    return when {
        value >= 1_000_000 -> String.format("%.1fM", value / 1_000_000f)
        value >= 1_000 -> String.format("%.1fK", value / 1_000f)
        else -> value.toString()
    }
}

/**
 * Круглая аватарка артиста.
 *
 * Пока настоящий аватар не загрузился (или его нет), показываем нейтральную
 * заглушку с иконкой — без случайных стоковых фото.
 */
@Composable
private fun ArtistAvatarImage(
    url: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = ZyroTextSecondary.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxSize(0.48f)
        )
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
