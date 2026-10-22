package com.zyro.player.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.zyro.player.ui.components.zyroTabTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zyro.player.data.remote.PlaylistInfo
import com.zyro.player.data.remote.ScLibraryData
import com.zyro.player.data.remote.TrackInfo
import com.zyro.player.data.remote.UserInfo
import com.zyro.player.ui.components.TrackListItem
import com.zyro.player.ui.components.ZyroAccent
import com.zyro.player.ui.components.ZyroBackground
import com.zyro.player.ui.components.ZyroGlassBorder
import com.zyro.player.ui.components.ZyroSurface
import com.zyro.player.ui.components.ZyroTextPrimary
import com.zyro.player.ui.components.ZyroTextSecondary
import com.zyro.player.ui.components.zyroPlasticGlass
import com.zyro.player.ui.components.ZyroShapes

@Composable
fun SoundCloudSection(
    isConnected: Boolean,
    isSyncing: Boolean,
    username: String?,
    currentUser: UserInfo?,
    libraryData: ScLibraryData?,
    syncMessage: String?,
    onSyncClick: () -> Unit,
    onConnectClick: () -> Unit,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onPlaylistClick: (PlaylistInfo) -> Unit,
    onArtistClick: (UserInfo) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit = {},
    onLikePlaylist: (PlaylistInfo) -> Unit = {},
    onFollowArtist: (UserInfo) -> Unit = {}
) {
    if (!isConnected) {
        SoundCloudNotConnected(onConnectClick = onConnectClick)
        return
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Лайки", "Плейлисты", "Артисты", "История")

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // ===== Profile Header =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .border(2.dp, ZyroAccent.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = currentUser?.avatarUrl ?: "",
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = username ?: "SoundCloud",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = ZyroTextPrimary
                        )
                    )
                    if (syncMessage != null) {
                        Text(
                            text = syncMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = ZyroTextSecondary
                        )
                    }
                }
                // Sync Button
                val rotation by animateFloatAsState(
                    targetValue = if (isSyncing) 360f else 0f,
                    animationSpec = if (isSyncing) infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ) else spring(),
                    label = "sync_rotation"
                )
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.9f else 1f,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "sync_scale"
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .scale(scale)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ZyroAccent.copy(alpha = 0.12f))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onSyncClick
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Синхронизировать",
                        tint = ZyroAccent,
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(rotation)
                    )
                }
            }
        }

        // ===== Tabs =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                val count = when (index) {
                    0 -> libraryData?.likedTracks?.size ?: 0
                    1 -> libraryData?.playlists?.size ?: 0
                    2 -> libraryData?.followings?.size ?: 0
                    3 -> libraryData?.playHistory?.size ?: 0
                    else -> 0
                }
                val tabInteraction = remember { MutableInteractionSource() }
                val tabPressed by tabInteraction.collectIsPressedAsState()
                val tabScale by animateFloatAsState(
                    targetValue = if (tabPressed) 0.95f else 1f,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "tab_scale"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .scale(tabScale)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isSelected) ZyroAccent.copy(alpha = 0.15f)
                            else ZyroSurface.copy(alpha = 0.06f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) ZyroAccent.copy(alpha = 0.4f) else ZyroGlassBorder,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable(
                            interactionSource = tabInteraction,
                            indication = null,
                            onClick = { selectedTab = index }
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) ZyroAccent else ZyroTextSecondary
                            )
                        )
                        if (count > 0) {
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) ZyroAccent.copy(alpha = 0.8f) else ZyroTextSecondary.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }

        // ===== Tab Content =====
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                zyroTabTransition(goingForward = targetState > initialState)
            },
            label = "sc_tab_content",
            modifier = Modifier.fillMaxSize()
        ) { tab ->
            when (tab) {
                0 -> ScLikedTracksTab(
                    tracks = libraryData?.likedTracks ?: emptyList(),
                    onTrackClick = onTrackClick,
                    onLikeTrack = onLikeTrack
                )
                1 -> ScPlaylistsTab(
                    playlists = libraryData?.playlists ?: emptyList(),
                    likedPlaylists = libraryData?.likedPlaylists ?: emptyList(),
                    onPlaylistClick = onPlaylistClick,
                    onLikePlaylist = onLikePlaylist
                )
                2 -> ScArtistsTab(
                    artists = libraryData?.followings ?: emptyList(),
                    onArtistClick = onArtistClick,
                    onFollowArtist = onFollowArtist
                )
                3 -> ScHistoryTab(
                    tracks = libraryData?.playHistory ?: emptyList(),
                    onTrackClick = onTrackClick
                )
            }
        }
    }
}

// ============================================
// NOT CONNECTED STATE
// ============================================
@Composable
private fun SoundCloudNotConnected(onConnectClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .zyroPlasticGlass(
                    shape = RoundedCornerShape(ZyroShapes.XL),
                    tint = ZyroAccent,
                    strong = true
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = ZyroAccent.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "SoundCloud",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                color = ZyroTextPrimary
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Подключите свой аккаунт SoundCloud, чтобы синхронизировать лайки, плейлисты и подписки",
            style = MaterialTheme.typography.bodyLarge,
            color = ZyroTextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.95f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "connect_scale"
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(RoundedCornerShape(20.dp))
                .background(ZyroAccent)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onConnectClick
                )
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Подключить SoundCloud",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = ZyroBackground
                )
            )
        }
    }
}

// ============================================
// LIKED TRACKS TAB
// ============================================
@Composable
private fun ScLikedTracksTab(
    tracks: List<TrackInfo>,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit = {}
) {
    if (tracks.isEmpty()) {
        EmptyLibraryState(message = "Нет синхронизированных лайков. Нажмите ⟳ для обновления.")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 16.dp,
                bottom = 210.dp
            )
        ) {
            items(tracks, key = { it.originalUrl }) { track ->
                TrackListItem(
                    track = track,
                    isPlaying = false,
                    isLiked = true,
                    isOffline = false,
                    onTrackClick = { onTrackClick(track, tracks) },
                    onLikeClick = { onLikeTrack(track) },
                    onDownloadClick = null,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

// ============================================
// HISTORY TAB
// ============================================
@Composable
private fun ScHistoryTab(
    tracks: List<TrackInfo>,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit
) {
    if (tracks.isEmpty()) {
        EmptyLibraryState(message = "История прослушиваний пуста.")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 16.dp,
                bottom = 210.dp
            )
        ) {
            items(tracks, key = { it.originalUrl }) { track ->
                TrackListItem(
                    track = track,
                    isPlaying = false,
                    isLiked = false,
                    isOffline = false,
                    onTrackClick = { onTrackClick(track, tracks) },
                    onLikeClick = null,
                    onDownloadClick = null,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

// ============================================
// PLAYLISTS TAB
// ============================================
@Composable
private fun ScPlaylistsTab(
    playlists: List<PlaylistInfo>,
    likedPlaylists: List<PlaylistInfo>,
    onPlaylistClick: (PlaylistInfo) -> Unit,
    onLikePlaylist: (PlaylistInfo) -> Unit = {}
) {
    val allPlaylists = (playlists + likedPlaylists).distinctBy { it.permalinkUrl.ifBlank { it.id.toString() } }
    if (allPlaylists.isEmpty()) {
        EmptyLibraryState(message = "Нет синхронизированных плейлистов.")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 16.dp,
                bottom = 210.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(allPlaylists, key = { it.permalinkUrl.ifBlank { it.id.toString() } }) { playlist ->
                ScPlaylistRowItem(
                    playlist = playlist,
                    onClick = { onPlaylistClick(playlist) },
                    onLike = { onLikePlaylist(playlist) }
                )
            }
        }
    }
}

@Composable
private fun ScPlaylistRowItem(
    playlist: PlaylistInfo,
    onClick: () -> Unit,
    onLike: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "sc_pl_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .zyroPlasticGlass(shape = RoundedCornerShape(ZyroShapes.L))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = playlist.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = ZyroTextPrimary
                    )
                )
                Text(
                    text = "Плейлист • ${playlist.user} • ${playlist.trackCount} треков",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZyroTextSecondary
                )
            }
            IconButton(
                onClick = onLike,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (playlist.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (playlist.isLiked) "Убрать из избранного" else "В избранное",
                    tint = if (playlist.isLiked) ZyroAccent else ZyroTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ============================================
// ARTISTS TAB
// ============================================
@Composable
private fun ScArtistsTab(
    artists: List<UserInfo>,
    onArtistClick: (UserInfo) -> Unit,
    onFollowArtist: (UserInfo) -> Unit = {}
) {
    if (artists.isEmpty()) {
        EmptyLibraryState(message = "Нет синхронизированных подписок.")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 16.dp,
                bottom = 210.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(artists, key = { it.permalinkUrl.ifBlank { it.username } }) { artist ->
                ScArtistRowItem(
                    artist = artist,
                    onClick = { onArtistClick(artist) },
                    onFollow = { onFollowArtist(artist) }
                )
            }
        }
    }
}

@Composable
private fun ScArtistRowItem(
    artist: UserInfo,
    onClick: () -> Unit,
    onFollow: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "sc_artist_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .zyroPlasticGlass(shape = RoundedCornerShape(ZyroShapes.L))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = artist.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.username,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = ZyroTextPrimary
                    )
                )
                Text(
                    text = "${artist.followersCount} подписчиков • ${artist.trackCount} треков",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZyroTextSecondary
                )
            }
            IconButton(
                onClick = onFollow,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PersonRemove,
                    contentDescription = "Отписаться",
                    tint = ZyroAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = ZyroTextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}