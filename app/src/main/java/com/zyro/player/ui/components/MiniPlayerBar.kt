package com.zyro.player.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.player.player.PlayerPlaybackState
import com.zyro.player.data.remote.TrackInfo
import kotlinx.coroutines.launch
import kotlin.math.abs

private val ZyroMiniPlayerShape = RoundedCornerShape(28.dp)

/**
 * Мини-плеер: при свайпе двигается **весь** бар.
 * При достаточном смещении — prev/next, иначе spring-возврат.
 */
@Composable
fun MiniPlayerBar(
    track: TrackInfo,
    playbackState: PlayerPlaybackState,
    currentPosition: Long,
    duration: Long,
    isLiked: Boolean = false,
    isOffline: Boolean = false,
    onExpandClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onToggleLike: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    val progress = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val threshold = screenWidthPx * 0.28f
    val expandThreshold = with(density) { 56.dp.toPx() }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = ZyroAnim.SpringPress,
        label = "mini_press"
    )

    // Сброс смещения при смене трека
    LaunchedEffect(track.originalUrl) {
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 4.dp)
    ) {
        // Подсказки prev/next под баром
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "‹ prev",
                color = ZyroAccent.copy(alpha = (offsetX.value / threshold).coerceIn(0f, 1f) * 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "next ›",
                color = ZyroAccent.copy(alpha = (-offsetX.value / threshold).coerceIn(0f, 1f) * 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = offsetX.value
                    translationY = offsetY.value
                    // Лёгкий наклон и fade по краям
                    alpha = 1f - (abs(offsetX.value) / screenWidthPx).coerceIn(0f, 0.35f)
                    rotationZ = (offsetX.value / screenWidthPx) * 4f
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .zyroPlasticGlass(
                    shape = ZyroMiniPlayerShape,
                    strong = true
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        if (abs(offsetX.value) < 8f && abs(offsetY.value) < 8f) onExpandClick()
                    }
                )
                .pointerInput(track.originalUrl) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetY.value < -expandThreshold) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    // уезжаем вверх к full
                                    offsetY.animateTo(-800f, animationSpec = tween(ZyroAnim.Fast))
                                    onExpandClick()
                                    offsetY.snapTo(0f)
                                } else {
                                    offsetY.animateTo(
                                        0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                            }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            // только вверх (отрицательный Y)
                            if (dragAmount < 0 || offsetY.value < 0) {
                                change.consume()
                                scope.launch {
                                    offsetY.snapTo((offsetY.value + dragAmount).coerceIn(-400f, 0f))
                                }
                            }
                        }
                    )
                }
                .pointerInput(track.originalUrl + "_h") {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onDragEnd = {
                            scope.launch {
                                when {
                                    offsetX.value <= -threshold -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        offsetX.animateTo(
                                            -screenWidthPx,
                                            animationSpec = tween(ZyroAnim.Fast)
                                        )
                                        onNextClick()
                                        offsetX.snapTo(0f)
                                    }
                                    offsetX.value >= threshold -> {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        offsetX.animateTo(
                                            screenWidthPx,
                                            animationSpec = tween(ZyroAnim.Fast)
                                        )
                                        onPrevClick()
                                        offsetX.snapTo(0f)
                                    }
                                    else -> {
                                        offsetX.animateTo(
                                            0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetX.animateTo(
                                    0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                val next = (offsetX.value + dragAmount)
                                    .coerceIn(-screenWidthPx * 0.85f, screenWidthPx * 0.85f)
                                offsetX.snapTo(next)
                            }
                        }
                    )
                }
                .testTag("mini_player_bar")
        ) {
            Column {
                ZyroMiniProgressBar(progress = progress)

                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MiniPlayerTrackItem(
                        track = track,
                        isPreparing = playbackState == PlayerPlaybackState.PREPARING,
                        isOffline = isOffline,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    MiniPlayerControls(
                        playbackState = playbackState,
                        isLiked = isLiked,
                        onToggleLike = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleLike()
                        },
                        onPlayPauseClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPlayPauseClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ZyroMiniProgressBar(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(ZyroTextPrimary.copy(alpha = 0.06f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = progress)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(ZyroAccentDeep, ZyroAccent, ZyroAccentSoft)
                    )
                )
        )
    }
}

@Composable
private fun MiniPlayerTrackItem(
    track: TrackInfo,
    isPreparing: Boolean,
    isOffline: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .border(1.dp, ZyroTextPrimary.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
        ) {
            AsyncImage(
                model = track.artworkUrl.ifBlank { ZyroArtworkFallback },
                contentDescription = "Обложка ${track.title}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (isOffline) {
                OfflineBadge(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = track.title,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZyroTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (track.isGoPlus) {
                    Spacer(modifier = Modifier.width(6.dp))
                    GoPlusBadge()
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (isPreparing) "Загрузка..." else track.artist,
                fontSize = 13.sp,
                color = ZyroTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MiniPlayerControls(
    playbackState: PlayerPlaybackState,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onPlayPauseClick: () -> Unit
) {
    val playInteraction = remember { MutableInteractionSource() }
    val isPlayPressed by playInteraction.collectIsPressedAsState()
    val playScale by animateFloatAsState(
        targetValue = if (isPlayPressed) 0.90f else 1f,
        animationSpec = ZyroAnim.SpringPress,
        label = "mini_play_scale"
    )
    val likeScale by animateFloatAsState(
        targetValue = if (isLiked) 1.08f else 1f,
        animationSpec = ZyroAnim.SpringSoft,
        label = "mini_like_scale"
    )
    val isPlaying = playbackState == PlayerPlaybackState.PLAYING

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(
            onClick = onToggleLike,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = "Like",
                tint = if (isLiked) ZyroAccent else ZyroTextPrimary,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = likeScale
                        scaleY = likeScale
                    }
            )
        }

        Box(
            modifier = Modifier
                .size(42.dp)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                }
                .clip(CircleShape)
                .background(
                    if (isPlaying) {
                        Brush.verticalGradient(
                            listOf(
                                ZyroAccent.copy(alpha = 0.45f),
                                ZyroAccent.copy(alpha = 0.16f)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.14f),
                                Color.White.copy(alpha = 0.045f)
                            )
                        )
                    }
                )
                .border(
                    width = 1.dp,
                    color = if (isPlaying) {
                        ZyroAccent.copy(alpha = 0.55f)
                    } else {
                        Color.White.copy(alpha = 0.12f)
                    },
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = playInteraction,
                    indication = null,
                    onClick = onPlayPauseClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (playbackState == PlayerPlaybackState.PREPARING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = ZyroAccent
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) {
                        Icons.Default.Pause
                    } else {
                        Icons.Default.PlayArrow
                    },
                    contentDescription = "Play/Pause",
                    tint = if (isPlaying) ZyroAccent else ZyroTextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
