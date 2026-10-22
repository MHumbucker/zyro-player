package com.zyro.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.zyro.player.data.remote.TrackInfo

/**
 * Полноэкранная информация о треке — на общей дизайн-системе Zyro:
 * стеклянные панели, единые скругления, иконки и типографика.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackInfoDetailModal(
    track: TrackInfo,
    isLiked: Boolean,
    isOffline: Boolean,
    onClose: () -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onDownloadTrack: (TrackInfo) -> Unit,
    onArtistClick: (TrackInfo) -> Unit
) {
    BackHandler { onClose() }

    val scrollState = rememberScrollState()
    val isScrolled by remember {
        derivedStateOf { scrollState.value > 0 }
    }

    ZyroAuroraBackground {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("track_info_screen"),
            color = Color.Transparent
        ) {
            var headerHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            Box(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .zIndex(2f)
                        .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                    title = {
                        Text(
                            text = "Информация о треке",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            color = ZyroTextPrimary
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = ZyroTextPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = ZyroTextPrimary,
                        navigationIconContentColor = ZyroTextPrimary
                    )
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = headerHeight + 8.dp,
                            bottom = 220.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    TrackInfoHeader(
                        track = track,
                        onArtistClick = { onArtistClick(track) }
                    )

                    val meta = remember(track) {
                        buildList {
                            formatDurationMs(track.durationMs)
                                .takeIf { it.isNotBlank() }
                                ?.let {
                                    add(
                                        TrackMeta(
                                            icon = Icons.Default.AccessTime,
                                            label = "Длительность",
                                            value = it,
                                            tint = ZyroCyan
                                        )
                                    )
                                }
                            formatCount(track.playbackCount)
                                .takeIf { it.isNotBlank() }
                                ?.let {
                                    add(
                                        TrackMeta(
                                            icon = Icons.Default.PlayArrow,
                                            label = "Прослушиваний",
                                            value = it,
                                            tint = ZyroAccent
                                        )
                                    )
                                }
                            if (track.likesCount > 0) {
                                add(
                                    TrackMeta(
                                        icon = Icons.Default.Favorite,
                                        label = "Лайков",
                                        value = formatCount(track.likesCount),
                                        tint = ZyroAccent
                                    )
                                )
                            }
                            track.genre.takeIf { it.isNotBlank() }?.let {
                                add(
                                    TrackMeta(
                                        icon = Icons.Outlined.Info,
                                        label = "Жанр",
                                        value = it,
                                        tint = ZyroViolet
                                    )
                                )
                            }
                            track.albumTitle.takeIf { it.isNotBlank() }?.let {
                                add(
                                    TrackMeta(
                                        icon = Icons.Default.Album,
                                        label = "Альбом",
                                        value = it,
                                        tint = ZyroViolet
                                    )
                                )
                            }
                        }
                    }

                    if (meta.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zyroPlasticGlass(
                                    shape = RoundedCornerShape(ZyroShapes.L),
                                    strong = true
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            meta.forEachIndexed { index, item ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = ZyroGlassBorder,
                                        modifier = Modifier.padding(start = 46.dp)
                                    )
                                }
                                TrackMetaRow(item)
                            }
                        }
                    }

                    if (track.description.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zyroPlasticGlass(
                                    shape = RoundedCornerShape(ZyroShapes.L)
                                )
                                .padding(horizontal = 18.dp, vertical = 16.dp)
                        ) {
                            Text(
                                text = "Описание",
                                color = ZyroAccent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = track.description,
                                color = ZyroTextSecondary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TrackInfoActionButton(
                            icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            label = if (isLiked) "В избранном" else "В избранное",
                            tint = ZyroAccent,
                            selected = isLiked,
                            onClick = { onLikeTrack(track) },
                            modifier = Modifier.weight(1f)
                        )

                        TrackInfoActionButton(
                            icon = if (isOffline) Icons.Default.DownloadDone else Icons.Default.Download,
                            label = if (isOffline) "Скачано" else "Скачать",
                            tint = ZyroMint,
                            selected = isOffline,
                            onClick = {
                                if (!isOffline) onDownloadTrack(track)
                            },
                            modifier = Modifier.weight(1f)
                        )
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
        }
    }
}

private data class TrackMeta(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val tint: Color
)

@Composable
private fun TrackInfoHeader(
    track: TrackInfo,
    onArtistClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zyroPlasticGlass(
                shape = RoundedCornerShape(ZyroShapes.XL),
                strong = true
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ZyroSurfaceElevated)
                .border(1.dp, ZyroGlassBorder, RoundedCornerShape(20.dp))
        ) {
            AsyncImage(
                model = track.artworkUrl.ifBlank { ZyroArtworkFallback },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = track.title,
                color = ZyroTextPrimary,
                fontSize = 19.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (track.isGoPlus) {
                GoPlusBadge()
            }

            val interactionSource = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onArtistClick
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (track.artistAvatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = track.artistAvatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .border(1.dp, ZyroGlassBorder, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = track.artist,
                    color = ZyroAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TrackMetaRow(meta: TrackMeta) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(meta.tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = meta.icon,
                contentDescription = null,
                tint = meta.tint,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = meta.label,
            color = ZyroTextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = meta.value,
            color = ZyroTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.25f)
        )
    }
}

@Composable
private fun TrackInfoActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = ZyroAnim.SpringPress,
        label = "track_info_action_press"
    )

    Row(
        modifier = modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .zyroPlasticGlass(
                shape = RoundedCornerShape(ZyroShapes.M),
                tint = if (selected) tint else Color.Transparent,
                strong = selected
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) tint else ZyroTextPrimary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = if (selected) tint else ZyroTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
