package com.zyro.player.ui.components

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zyro.player.data.remote.TrackInfo
import com.player.player.PlayerPlaybackState
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.player.player.RepeatMode


// ==========================================
// PLAYER CONTENT
// ==========================================

@Composable
fun PlayerContent(
    track: TrackInfo,
    playbackState: PlayerPlaybackState,
    currentPosition: Long,
    duration: Long,
    isLiked: Boolean,
    isOffline: Boolean,
    isShuffle: Boolean,
    repeatMode: RepeatMode,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleLike: () -> Unit,
    onDownloadClick: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onArtistClick: (TrackInfo) -> Unit,
    onAlbumClick: (TrackInfo) -> Unit,
    onArtworkClick: (() -> Unit)? = null,
    onOpenQueueClick: () -> Unit,
    onTrackSettingsClick: () -> Unit = {},
    onToggleLyrics: () -> Unit,
    onCommentsClick: () -> Unit,
    prevTrack: TrackInfo? = null,
    nextTrack: TrackInfo? = null,
    onSwipeProgress: (Float) -> Unit = {},
    lyricsVisible: Boolean = false,
    /** Содержимое, накладываемое ПОВЕРХ области обложки (тексты). */
    artworkOverlay: (@Composable () -> Unit)? = null,
    /** Маленькие комментарии поверх обложки, привязанные к таймкоду. */
    commentsOverlay: (@Composable BoxScope.() -> Unit)? = null
) {

    val isSwiping = remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    // Режим текстов и GO+-треки: обложка уменьшается и размывается, остаётся
    // фоном под текстом/пояснением. Для GO+ так же освобождаем место под
    // сообщение о том, что для полного прослушивания нужна подписка.
    val artworkScale by animateFloatAsState(
        targetValue = if (lyricsVisible || track.isGoPlus) 0.92f else 1f,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "artwork_scale"
    )
    val artworkBlur by animateDpAsState(
        targetValue = if (lyricsVisible || track.isGoPlus) 24.dp else 0.dp,
        animationSpec = tween(450),
        label = "artwork_blur"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        AnimatedContent(
            targetState = Triple(track, prevTrack, nextTrack),
            transitionSpec = {
                if (isSwiping.value) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    zyroFadeOnlyTransition()
                }
            },
            label = "track_content"
        ) { (t, p, n) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(32.dp))
                ) {
                    SwipeableArtwork(
                        prevTrack = p,
                        currentTrack = t,
                        nextTrack = n,
                        onClick = { onArtworkClick?.invoke() ?: onAlbumClick(t) },
                        onNext = onNextClick,
                        onPrev = onPrevClick,
                        onSwipeStart = { isSwiping.value = true },
                        onSwipeEnd = { isSwiping.value = false },
                        onSwipeProgress = onSwipeProgress,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = artworkScale
                                scaleY = artworkScale
                            }
                            .blur(
                                artworkBlur,
                                edgeTreatment = BlurredEdgeTreatment.Unbounded
                            )
                    )

                    // GO+-трек: затемняем обложку и объясняем, почему доступен
                    // только фрагмент. Поверх текстов пояснение не показываем.
                    if (t.isGoPlus) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f))
                        )
                        if (!lyricsVisible) {
                            GoPlusCoverNotice(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 28.dp)
                            )
                        }
                    }

                    // Маленькие комментарии под текстами: при включённых текстах оверлей скрыт.
                    commentsOverlay?.invoke(this)

                    // Тексты поверх обложки — в её же рамках (скругление сохраняется).
                    artworkOverlay?.invoke()
                }

                TrackInfoBlock(
                    title = t.title,
                    artist = t.artist,
                    onTitleClick = { onAlbumClick(t) },
                    onArtistClick = { onArtistClick(t) },
                    onLyricsClick = onToggleLyrics,
                    artistAvatarUrl = t.artistAvatarUrl,
                    isGoPlus = t.isGoPlus
                )
            }
        }

        PlayerSlider(
            currentPosition = currentPosition,
            duration = duration,
            onSeekTo = onSeekTo
        )

        PlaybackControlIsland(
            playbackState = playbackState,
            isShuffle = isShuffle,
            repeatMode = repeatMode,
            onPlayPauseClick = onPlayPauseClick,
            onNextClick = onNextClick,
            onPrevClick = onPrevClick,
            onToggleShuffle = onToggleShuffle,
            onToggleRepeat = onToggleRepeat
        )

        ActionControlIsland(
            isLiked = isLiked,
            isOffline = isOffline,
            onToggleLike = onToggleLike,
            onDownloadClick = onDownloadClick,
            onCommentsClick = onCommentsClick,
            onOpenQueueClick = onOpenQueueClick,
            likesCount = track.likesCount,   // ← ИСПРАВЛЕНО: берём из трека
            onTrackSettingsClick = onTrackSettingsClick
        )
    }
}

// ==========================================
// GO+ NOTICE ON COVER
// ==========================================

/**
 * Пояснение поверх затемнённой обложки GO+-трека. Показывается только в
 * полноэкранном плеере: в списках достаточно бейджа [GoPlusBadge].
 */
@Composable
private fun GoPlusCoverNotice(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(ZyroShapes.XL))
            .background(Color.Black.copy(alpha = 0.62f))
            .border(
                width = 1.dp,
                color = ZyroAccent.copy(alpha = 0.35f),
                shape = RoundedCornerShape(ZyroShapes.XL)
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = ZyroAccent,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        GoPlusBadge()
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Только для GO+",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Для прослушивания нужен аккаунт SoundCloud с подпиской Go+. Но скоро мы чтонибудь придумаем, чтобы обойти ограничение :)",
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ==========================================
// SWIPEABLE ARTWORK — 3 CARDS WITH GAP
// ==========================================

@Composable
private fun SwipeableArtwork(
    prevTrack: TrackInfo?,
    currentTrack: TrackInfo,
    nextTrack: TrackInfo?,
    onClick: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSwipeStart: () -> Unit,
    onSwipeEnd: () -> Unit,
    onSwipeProgress: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val density = LocalDensity.current
    val gapPx = remember(density) { with(density) { 56.dp.toPx() } }

    LaunchedEffect(currentTrack) {
        offsetX.snapTo(0f)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val glowColor by remember { derivedStateOf { ZyroAccent } }
        val animatedGlow by animateColorAsState(
            targetValue = glowColor,
            animationSpec = tween(800),
            label = "glow"
        )
        Box(
            modifier = Modifier
                .fillMaxSize(0.92f)
                .blur(40.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            animatedGlow.copy(alpha = 0.40f),
                            Color.Transparent
                        )
                    )
                )
        )

        if (prevTrack != null) {
            key(prevTrack.originalUrl) {
                ArtworkCard(
                    track = prevTrack,
                    onColorExtracted = {},
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val w = size.width
                            translationX = -w - gapPx + offsetX.value
                            val p = (1f - abs(translationX) / (w + gapPx)).coerceIn(0f, 1f)
                            scaleX = 0.82f + p * 0.18f
                            scaleY = 0.82f + p * 0.18f
                            alpha = 0.30f + p * 0.70f
                        }
                )
            }
        }

        if (nextTrack != null) {
            key(nextTrack.originalUrl) {
                ArtworkCard(
                    track = nextTrack,
                    onColorExtracted = {},
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val w = size.width
                            translationX = w + gapPx + offsetX.value
                            val p = (1f - abs(translationX) / (w + gapPx)).coerceIn(0f, 1f)
                            scaleX = 0.82f + p * 0.18f
                            scaleY = 0.82f + p * 0.18f
                            alpha = 0.30f + p * 0.70f
                        }
                )
            }
        }

        key(currentTrack.originalUrl) {
            ArtworkCard(
                track = currentTrack,
                onClick = onClick,
                onColorExtracted = {},
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val w = size.width
                        translationX = offsetX.value
                        val dragProgress = abs(offsetX.value) / (w + gapPx)
                        scaleX = 1f - dragProgress * 0.06f
                        scaleY = 1f - dragProgress * 0.06f
                        rotationZ = (offsetX.value / (w + gapPx)) * 5f
                        alpha = 1f - dragProgress * 0.25f
                    }
                    .pointerInput(prevTrack, currentTrack, nextTrack) {
                        val boxWidth = size.width.toFloat()
                        val threshold = boxWidth * 0.18f

                        detectHorizontalDragGestures(
                            onDragStart = { onSwipeStart() },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                                onSwipeProgress(offsetX.value / (boxWidth + gapPx))
                            },
                            onDragEnd = {
                                when {
                                    offsetX.value < -threshold && nextTrack != null -> {
                                        scope.launch {
                                            offsetX.animateTo(
                                                -boxWidth - gapPx,
                                                tween(300, easing = FastOutSlowInEasing)
                                            )
                                            onNext()
                                            onSwipeEnd()
                                        }
                                    }
                                    offsetX.value > threshold && prevTrack != null -> {
                                        scope.launch {
                                            offsetX.animateTo(
                                                boxWidth + gapPx,
                                                tween(300, easing = FastOutSlowInEasing)
                                            )
                                            onPrev()
                                            onSwipeEnd()
                                        }
                                    }
                                    else -> {
                                        scope.launch {
                                            offsetX.animateTo(
                                                0f,
                                                spring(dampingRatio = 0.55f, stiffness = 500f)
                                            )
                                            onSwipeEnd()
                                        }
                                        onSwipeProgress(0f)
                                    }
                                }
                            }
                        )
                    }
            )
        }
    }
}

@Composable
private fun ArtworkCard(
    track: TrackInfo,
    onClick: (() -> Unit)? = null,
    onColorExtracted: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var dominantColor by remember { mutableStateOf(ZyroAccent) }

    LaunchedEffect(dominantColor) {
        onColorExtracted(dominantColor)
    }

    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    } else Modifier

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ZyroShapes.XXL))
            .background(Color.White.copy(alpha = 0.04f))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.28f),
                        Color.White.copy(alpha = 0.06f)
                    )
                ),
                RoundedCornerShape(ZyroShapes.XXL)
            )
            .then(clickModifier)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(track.artworkUrl.ifBlank {
                    ZyroArtworkFallback
                })
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { resultState ->
                val drawable = resultState.result.drawable
                if (drawable is BitmapDrawable) {
                    val bitmap = drawable.bitmap
                    Palette.from(bitmap).generate { palette ->
                        val colorInt = palette?.getVibrantColor(
                            palette.getDominantColor(ZyroAccent.toArgb())
                        )
                        if (colorInt != null) {
                            dominantColor = Color(colorInt)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ==========================================
// TRACK INFO
// ==========================================

@Composable
private fun TrackInfoBlock(
    title: String,
    artist: String,
    onTitleClick: () -> Unit,
    onArtistClick: () -> Unit,
    onLyricsClick: () -> Unit,
    modifier: Modifier = Modifier,
    artistAvatarUrl: String = "",
    isGoPlus: Boolean = false
) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (isGoPlus) {
                GoPlusBadge()
                Spacer(modifier = Modifier.height(6.dp))
            }
            ZyroMarqueeText(
                text = title,
                style = TextStyle(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZyroTextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onTitleClick
                    )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onArtistClick
                )
            ) {
                // Круглая мини-аватарка исполнителя
                if (artistAvatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(artistAvatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.22f),
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                }
                Text(
                    text = artist,
                    fontSize = 16.sp,
                    color = ZyroTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        ZyroIconButton(
            icon = Icons.Default.Article,
            size = 44.dp,
            iconSize = 24.dp,
            onClick = onLyricsClick
        )
    }
}

// ==========================================
// SLIDER (ПЛАВНЫЙ И С ВИБРАЦИЕЙ)
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSlider(
    currentPosition: Long,
    duration: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    var lastHapticValue by remember { mutableFloatStateOf(0f) }

    var ignoreUpdatesUntil by remember { mutableLongStateOf(0L) }

    val progressAnim = remember { Animatable(0f) }

    LaunchedEffect(currentPosition, isDragging) {
        if (!isDragging) {
            val target = if (duration > 0) currentPosition.toFloat() else 0f
            val now = System.currentTimeMillis()

            if (now < ignoreUpdatesUntil) return@LaunchedEffect

            if (abs(progressAnim.value - target) > 1500f) {
                progressAnim.snapTo(target)
            } else {
                progressAnim.animateTo(
                    targetValue = target,
                    animationSpec = tween(1000, easing = LinearEasing)
                )
            }
        }
    }

    val displayValue = if (isDragging) dragPosition else progressAnim.value

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = displayValue.coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
            onValueChange = { newValue ->
                if (!isDragging) {
                    isDragging = true
                    lastHapticValue = newValue
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                dragPosition = newValue

                scope.launch { progressAnim.snapTo(newValue) }

                val threshold = duration.toFloat() * 0.015f
                if (abs(newValue - lastHapticValue) > threshold) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    lastHapticValue = newValue
                }
            },
            onValueChangeFinished = {
                onSeekTo(dragPosition.toLong())
                ignoreUpdatesUntil = System.currentTimeMillis() + 1000
                isDragging = false
            },
            valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
            thumb = {
                Box(
                    modifier = Modifier.size(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .offset(y = 2.dp)
                            .background(Color.White, CircleShape)
                    )
                }
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                    ),
                    sliderState = sliderState,
                    modifier = Modifier.height(2.dp)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatMs(if (isDragging) dragPosition.toLong() else currentPosition),
                fontSize = 12.sp,
                color = ZyroTextSecondary
            )
            Text(text = formatMs(duration), fontSize = 12.sp, color = ZyroTextSecondary)
        }
    }
}

// ==========================================
// PLAYBACK CONTROLS
// ==========================================

@Composable
private fun PlaybackControlIsland(
    playbackState: PlayerPlaybackState,
    isShuffle: Boolean,
    repeatMode: RepeatMode,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val playInteraction = remember { MutableInteractionSource() }
    val isPlayPressed by playInteraction.collectIsPressedAsState()
    val playScale by animateFloatAsState(
        targetValue = if (isPlayPressed) 0.92f else 1f,
        animationSpec = ZyroAnim.SpringPress,
        label = "play_press"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .zyroPlasticGlass(
                shape = RoundedCornerShape(ZyroShapes.XXL),
                strong = true
            )
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (isShuffle) ZyroAccent else ZyroTextPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            IconButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onPrevClick()
            }) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = ZyroTextPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(66.dp)
                    .graphicsLayer {
                        scaleX = playScale
                        scaleY = playScale
                    }
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color.White, Color(0xFFE7E9F0))
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                    .clickable(
                        interactionSource = playInteraction,
                        indication = null,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPlayPauseClick()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (playbackState == PlayerPlaybackState.PREPARING) {
                    CircularProgressIndicator(
                        color = Color.Black,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(
                        imageVector = if (playbackState == PlayerPlaybackState.PLAYING)
                            Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.Black,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            IconButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onNextClick()
            }) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = ZyroTextPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            IconButton(onClick = onToggleRepeat) {
                Icon(
                    imageVector = when (repeatMode) {
                        RepeatMode.NONE -> Icons.Default.Repeat
                        RepeatMode.ALL -> Icons.Default.Repeat
                        RepeatMode.ONE -> Icons.Default.RepeatOne
                    },
                    contentDescription = "Repeat",
                    tint = if (repeatMode != RepeatMode.NONE) ZyroAccent else ZyroTextPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

// ==========================================
// ACTION ISLAND
// ==========================================

@Composable
private fun ActionControlIsland(
    isLiked: Boolean,
    isOffline: Boolean,
    onToggleLike: () -> Unit,
    onDownloadClick: () -> Unit,
    onCommentsClick: () -> Unit,
    onOpenQueueClick: () -> Unit,
    likesCount: Long,
    onTrackSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .zyroPlasticGlass(shape = RoundedCornerShape(ZyroShapes.L))
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Лайк + счётчик
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onToggleLike)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isLiked)
                        Icons.Rounded.Favorite
                    else
                        Icons.Rounded.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isLiked) ZyroAccent else ZyroTextPrimary,
                    modifier = Modifier.size(28.dp)
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = formatCount(likesCount),
                    color = ZyroTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Группа действий
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Загрузка
                IconButton(onClick = onDownloadClick) {
                    Icon(
                        imageVector = if (isOffline)
                            Icons.Default.DownloadDone
                        else
                            Icons.Default.Download,
                        contentDescription = null,
                        tint = ZyroTextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Комментарии
                IconButton(onClick = onCommentsClick) {
                    Icon(
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = "Комментарии",
                        tint = ZyroTextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Настройки
                IconButton(onClick = onTrackSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Настройки звука",
                        tint = ZyroTextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Очередь
                IconButton(onClick = onOpenQueueClick) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = ZyroTextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0D0D)
@Composable
fun PlayerContentPreview() {
    val mockTrack = TrackInfo(
        title = "Never Gonna Give You Up",
        artist = "Rick Astley",
        artworkUrl = "",
        originalUrl = "dummy_url_1",
        likesCount = 42_000L   // ← для превью
    )
    val mockPrevTrack = TrackInfo(
        title = "Previous Song",
        artist = "Unknown Artist",
        artworkUrl = "",
        originalUrl = "dummy_url_prev"
    )
    val mockNextTrack = TrackInfo(
        title = "Next Song",
        artist = "Unknown Artist",
        artworkUrl = "",
        originalUrl = "dummy_url_next"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {
        PlayerContent(
            track = mockTrack,
            playbackState = PlayerPlaybackState.PLAYING,
            currentPosition = 65000L,
            duration = 212000L,
            isLiked = true,
            isOffline = false,
            isShuffle = true,
            repeatMode = RepeatMode.NONE,
            onPlayPauseClick = {},
            onNextClick = {},
            onPrevClick = {},
            onSeekTo = {},
            onToggleLike = {},
            onDownloadClick = {},
            onToggleShuffle = {},
            onToggleRepeat = {},
            onArtistClick = {},
            onAlbumClick = {},
            onOpenQueueClick = {},
            onToggleLyrics = {},
            onCommentsClick = {},
            prevTrack = mockPrevTrack,
            nextTrack = mockNextTrack,
            onSwipeProgress = {}
        )
    }
}