package com.zyro.player.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.player.player.PlayerPlaybackState
import com.player.player.EqualizerState
import com.zyro.player.data.local.LyricsEntity
import com.zyro.player.data.remote.CommentInfo
import com.zyro.player.data.remote.Result
import com.zyro.player.data.remote.SoundCloudRepository          // ← ДОБАВЛЕНО
import com.zyro.player.data.remote.TrackInfo
import com.player.player.RepeatMode
import androidx.compose.ui.input.pointer.pointerInput
import com.zyro.player.ui.screens.TrackCommentsBottomSheet    // ← ДОБАВЛЕНО

// Ритм показа комментариев поверх обложки.
private const val COMMENT_LEAD_MS = 300L
private const val COMMENT_MAX_AGE_MS = 2_500L
private const val COMMENT_VISIBLE_MS = 8_000L
private const val COMMENT_GAP_MS = 1_200L


@Composable
fun FullPlayerSheet(
    track: TrackInfo,
    playbackState: PlayerPlaybackState,
    currentPosition: Long,
    duration: Long,
    isLiked: Boolean,
    isOffline: Boolean,
    isShuffle: Boolean,
    repeatMode: RepeatMode,
    lyrics: LyricsEntity?,
    isLyricsLoading: Boolean,
    playbackContext: String? = null,
    onScreenCommentsEnabled: Boolean = false,
    onToggleOnScreenComments: () -> Unit = {},
    onCommentUserClick: (permalinkUrl: String, username: String, avatarUrl: String) -> Unit = { _, _, _ -> },
    onCloseClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPrevClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleLike: () -> Unit,
    onDownloadClick: () -> Unit,
    onDeleteOfflineClick: (() -> Unit)? = null,
    onDownloadToDownloads: () -> Unit = {},
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSetLyricsSource: (String) -> Unit,
    onSaveCustomLyrics: (String) -> Unit,
    onSearchLyricsManual: (String, String) -> Unit = { _, _ -> },
    onOpenQueueClick: () -> Unit = {},
    onTrackSettingsClick: (() -> Unit)? = null,
    playbackSpeed: Float = 1f,
    equalizerState: EqualizerState = EqualizerState(),
    onSetPlaybackSpeed: (Float) -> Unit = {},
    onToggleEqualizer: (Boolean) -> Unit = {},
    onSelectEqualizerPreset: (Int) -> Unit = {},
    onArtistClick: (TrackInfo) -> Unit = {},
    onAlbumClick: (TrackInfo) -> Unit = {},
    onPlayNextFromMore: (TrackInfo) -> Unit = {},
    onAddToQueueFromMore: (TrackInfo) -> Unit = {},
    onAddToPlaylistFromMore: (TrackInfo) -> Unit = {},
    onTrackInfoFromMore: (TrackInfo) -> Unit = {},
    prevTrack: TrackInfo? = null,
    nextTrack: TrackInfo? = null,
    queue: List<TrackInfo> = emptyList(),
    currentIndex: Int = -1,
    repository: SoundCloudRepository,                          // ← ДОБАВЛЕНО
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val actualPrev = prevTrack ?: queue.getOrNull(currentIndex - 1)
    val actualNext = nextTrack ?: queue.getOrNull(currentIndex + 1)

    var swipeProgress by remember { mutableFloatStateOf(0f) }
    var showComments by remember { mutableStateOf(false) }     // ← ДОБАВЛЕНО

    // Сброс прогресса при смене трека
    LaunchedEffect(track) {
        swipeProgress = 0f
    }

    var showLyricsTab by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showArtworkViewer by remember { mutableStateOf(false) }

    // Просмотр обложки — отдельный режим поверх плеера. При смене трека
    // или включении текста закрываем его, чтобы не показывать чужую обложку.
    LaunchedEffect(track.originalUrl) { showArtworkViewer = false }
    LaunchedEffect(showLyricsTab) {
        if (showLyricsTab) showArtworkViewer = false
    }

    // Комментарии для маленьких подсказок поверх обложки.
    var screenComments by remember(track.originalUrl) {
        mutableStateOf<List<CommentInfo>>(emptyList())
    }
    var visibleScreenComment by remember(track.originalUrl) { mutableStateOf<CommentInfo?>(null) }
    var screenCommentVisible by remember(track.originalUrl) { mutableStateOf(false) }

    // Позиция из параметра не обновляется внутри долгоживущей корутины,
    // поэтому читаем её через актуальное состояние.
    val latestPosition = rememberUpdatedState(currentPosition)

    LaunchedEffect(onScreenCommentsEnabled, track.originalUrl, track.id) {
        if (!onScreenCommentsEnabled) {
            screenComments = emptyList()
            visibleScreenComment = null
            screenCommentVisible = false
            return@LaunchedEffect
        }

        screenComments = emptyList()
        val trackId = track.id.takeIf { it > 0L } ?: run {
            val resolved = repository.resolveTrackId(track.originalUrl)
            (resolved as? Result.Success)?.data ?: return@LaunchedEffect
        }
        val result = repository.fetchTrackComments(
            trackId = trackId,
            offset = 0,
            limit = 100
        )
        if (result is Result.Success) {
            screenComments = result.data.comments
                .filter { it.timestampMs > 0 }
                .sortedBy { it.timestampMs }
        }
    }

    // Показываем комментарии по одному, с фиксированной длительностью.
    // Раньше новый комментарий перекрывал предыдущий сразу, из-за чего
    // при плотном потоке подсказки менялись почти каждую секунду.
    LaunchedEffect(
        onScreenCommentsEnabled,
        track.originalUrl,
        showLyricsTab,
        screenComments
    ) {
        screenCommentVisible = false
        visibleScreenComment = null

        if (!onScreenCommentsEnabled || showLyricsTab) return@LaunchedEffect

        val comments = screenComments
        if (comments.isEmpty()) return@LaunchedEffect

        suspend fun awaitPosition(predicate: (Long) -> Boolean): Long =
            snapshotFlow { latestPosition.value }.first(predicate)

        fun cursorFor(position: Long): Int =
            comments.indexOfFirst { it.timestampMs >= position - COMMENT_LEAD_MS }
                .let { if (it < 0) comments.size else it }

        var cursor = cursorFor(latestPosition.value)
        var lastPosition = latestPosition.value

        while (true) {
            val position = latestPosition.value

            // Перемотка назад или повтор трека — начинаем подбор заново.
            if (position < lastPosition - 1500L) {
                cursor = cursorFor(position)
            }
            lastPosition = position

            if (cursor >= comments.size) {
                // Ждём, пока трек начнётся заново или пользователь перемотает назад.
                awaitPosition { it < position - 1500L }
                cursor = cursorFor(latestPosition.value)
                continue
            }

            val comment = comments[cursor]
            val showAt = comment.timestampMs - COMMENT_LEAD_MS

            if (position < showAt) {
                awaitPosition { it >= showAt || it < position - 1500L }
                continue
            }

            // После перемотки вперёд этот комментарий уже неактуален.
            if (position > comment.timestampMs + COMMENT_MAX_AGE_MS) {
                cursor++
                continue
            }

            visibleScreenComment = comment
            screenCommentVisible = true

            delay(COMMENT_VISIBLE_MS)
            screenCommentVisible = false

            cursor++
            delay(COMMENT_GAP_MS)
        }
    }

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val dismissOffset = remember { Animatable(0f) }
    val screenHeightPx = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val dismissThreshold = screenHeightPx * 0.22f

    // Единый offset: 0 = fully open, screenHeight = collapsed toward mini
    // Без внутреннего sheetVisible — иначе после dismiss LaunchedEffect(Unit) не перезапускается
    // и full «открывается» пустым, а mini уже скрыт (isPlayerExpanded=true).
    fun finishDismiss() {
        scope.launch {
            dismissOffset.animateTo(
                screenHeightPx,
                animationSpec = tween(ZyroAnim.Normal)
            )
            onCloseClick()
        }
    }

    BackHandler {
        when {
            showArtworkViewer -> showArtworkViewer = false
            showLyricsTab -> showLyricsTab = false
            else -> finishDismiss()
        }
    }

    // Вход: с позиции у мини-плеера (~85% высоты) вверх — края «соприкасаются»
    LaunchedEffect(Unit) {
        dismissOffset.snapTo(screenHeightPx * 0.88f)
        dismissOffset.animateTo(
            0f,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = dismissOffset.value.coerceAtLeast(0f)
                val p = (dismissOffset.value / screenHeightPx).coerceIn(0f, 1f)
                alpha = 1f - p * 0.45f
                scaleX = 1f - p * 0.04f
                scaleY = 1f - p * 0.04f
            }
            .background(ZyroBackground)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onDragEnd = {
                        scope.launch {
                            if (dismissOffset.value > dismissThreshold) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                dismissOffset.animateTo(
                                    screenHeightPx,
                                    animationSpec = tween(ZyroAnim.Normal)
                                )
                                onCloseClick()
                            } else {
                                dismissOffset.animateTo(
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
                            dismissOffset.animateTo(
                                0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            val next = (dismissOffset.value + dragAmount).coerceAtLeast(0f)
                            dismissOffset.snapTo(next)
                        }
                    }
                )
            }
            .testTag("full_player_sheet")
    )
    {
        // === ФОН: размазанная обложка current ===
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(track.artworkUrl.ifBlank { ZyroArtworkFallback })
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(140.dp)
                .alpha(0.55f)
        )

        // === ФОН: размазанная prev (появляется при свайпе вправо) ===
        actualPrev?.let { prev ->
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(prev.artworkUrl.ifBlank { ZyroArtworkFallback })
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(140.dp)
                    .alpha(swipeProgress.coerceIn(0f, 1f) * 0.55f)
            )
        }

        // === ФОН: размазанная next (появляется при свайпе влево) ===
        actualNext?.let { next ->
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(next.artworkUrl.ifBlank { ZyroArtworkFallback })
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(140.dp)
                    .alpha((-swipeProgress).coerceIn(0f, 1f) * 0.55f)
            )
        }

        // Лёгкий градиент-сетка для читаемости контролов и «пластиковой» глубины
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            ZyroBackground.copy(alpha = 0.55f),
                            ZyroBackground.copy(alpha = 0.28f),
                            ZyroBackground.copy(alpha = 0.78f)
                        )
                    )
                )
        )

        // Контент
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                // В режиме просмотра обложки фон и управление уходят в blur,
                // а сама обложка показывается отдельным резким слоем ниже.
                .blur(if (showArtworkViewer) 18.dp else 0.dp)
        ) {
            PlayerTopBar(
                // Кнопка сворачивания всегда закрывает полноэкранный плеер,
                // даже если открыт текст песни (текст переключается отдельной кнопкой).
                onCloseClick = { finishDismiss() },
                onMoreClick = { showMoreSheet = true },
                lyricsVisible = showLyricsTab,
                playbackContext = playbackContext,
                activeLyricsSource = lyrics?.activeSource,
                syncedProvider = lyrics?.syncedProvider,
                plainProvider = lyrics?.plainProvider,
                onSetLyricsSource = onSetLyricsSource,
                onSearchLyricsManual = { showSearchDialog = true }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Плеер всегда на месте; тексты накладываются ПОВЕРХ области обложки
                // (обложка слегка уменьшается/размывается, но остаётся фоном).
                PlayerContent(
                    track = track,
                    prevTrack = actualPrev,
                    nextTrack = actualNext,
                    playbackState = playbackState,
                    currentPosition = currentPosition,
                    duration = duration,
                    isLiked = isLiked,
                    isOffline = isOffline,
                    isShuffle = isShuffle,
                    repeatMode = repeatMode,
                    onPlayPauseClick = onPlayPauseClick,
                    onNextClick = onNextClick,
                    onPrevClick = onPrevClick,
                    onSeekTo = onSeekTo,
                    onToggleLike = onToggleLike,
                    onDownloadClick = onDownloadClick,
                    onToggleShuffle = onToggleShuffle,
                    onToggleRepeat = onToggleRepeat,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                    onArtworkClick = {
                        if (!showLyricsTab) showArtworkViewer = true
                    },
                    onOpenQueueClick = onOpenQueueClick,
                    onTrackSettingsClick = onTrackSettingsClick
                        ?: { showSettingsSheet = true },
                    onToggleLyrics = { showLyricsTab = !showLyricsTab },
                    onCommentsClick = { showComments = true },   // ← ДОБАВЛЕНО
                    onSwipeProgress = { swipeProgress = it },
                    lyricsVisible = showLyricsTab,
                    artworkOverlay = {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showLyricsTab,
                            enter = fadeIn(
                                animationSpec = tween(
                                    durationMillis = ZyroAnim.Slow,
                                    easing = FastOutSlowInEasing
                                )
                            ),
                            exit = fadeOut(
                                animationSpec = tween(
                                    durationMillis = ZyroAnim.Normal,
                                    easing = FastOutLinearInEasing
                                )
                            ),
                            label = "lyrics_overlay",
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LyricsContent(
                                lyrics = lyrics,
                                currentPosition = currentPosition,
                                isLyricsLoading = isLyricsLoading,
                                artworkUrl = track.artworkUrl,
                                showHeader = false,
                                drawBackground = false,
                                leftAligned = true,
                                onSetLyricsSource = onSetLyricsSource,
                                onSearchManual = { showSearchDialog = true },
                                onSeekTo = onSeekTo,
                                onBackToPlayer = { showLyricsTab = false }
                            )
                        }
                    },
                    commentsOverlay = {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = screenCommentVisible,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 10.dp, vertical = 16.dp)
                                .fillMaxWidth(0.92f),
                            enter = fadeIn(
                                animationSpec = tween(
                                    durationMillis = 420,
                                    easing = FastOutSlowInEasing
                                )
                            ) + slideInVertically(
                                animationSpec = tween(
                                    durationMillis = 480,
                                    easing = FastOutSlowInEasing
                                )
                            ) { it },
                            exit = fadeOut(
                                animationSpec = tween(
                                    durationMillis = 340,
                                    easing = FastOutLinearInEasing
                                )
                            ) + slideOutVertically(
                                animationSpec = tween(
                                    durationMillis = 420,
                                    easing = FastOutSlowInEasing
                                )
                            ) { -it },
                            label = "screen_comment"
                        ) {
                            visibleScreenComment?.let { comment ->
                                OnScreenCommentBubble(
                                    comment = comment,
                                    onClick = { showComments = true }
                                )
                            }
                        }
                    }
                )
            }
        }

        // ← ДОБАВЛЕНО: шторка комментариев поверх всего
        TrackCommentsBottomSheet(
            trackUrl = track.originalUrl,
            repository = repository,
            visible = showComments,
            preferredTrackId = track.id,
            onUserClick = { permalinkUrl, username, avatarUrl ->
                showComments = false
                onCommentUserClick(permalinkUrl, username, avatarUrl)
            },
            onSeekTo = onSeekTo,
            onDismiss = { showComments = false }
        )

        com.zyro.player.ui.screens.TrackMoreBottomSheet(
            track = track,
            visible = showMoreSheet,
            onDismiss = { showMoreSheet = false },
            onArtistClick = {
                showMoreSheet = false
                onArtistClick(it)
            },
            onAlbumClick = if (track.albumPermalinkUrl.isNotBlank() || track.albumTitle.isNotBlank()) {
                { t ->
                    showMoreSheet = false
                    onAlbumClick(t)
                }
            } else null,
            onTrackInfoClick = {
                showMoreSheet = false
                onTrackInfoFromMore(track)
            },
            onPlayNext = null,
            onAddToQueue = null,
            onAddToPlaylist = { onAddToPlaylistFromMore(track) },
            onToggleLike = {
                showMoreSheet = false
                onToggleLike()
            },
            isLiked = isLiked,
            onDownload = {
                showMoreSheet = false
                onDownloadClick()
            },
            onDownloadToDownloads = {
                showMoreSheet = false
                onDownloadToDownloads()
            },
            onDeleteOffline = onDeleteOfflineClick?.let {
                {
                    showMoreSheet = false
                    it()
                }
            },
            isOffline = isOffline,
            onScreenCommentsEnabled = onScreenCommentsEnabled,
            onToggleOnScreenComments = onToggleOnScreenComments
        )

        PlayerSettingsSheet(
            visible = showSettingsSheet,
            trackTitle = track.title,
            playbackSpeed = playbackSpeed,
            equalizerState = equalizerState,
            onDismiss = { showSettingsSheet = false },
            onSpeedChange = onSetPlaybackSpeed,
            onEqualizerToggle = onToggleEqualizer,
            onPresetSelect = onSelectEqualizerPreset
        )

        androidx.compose.animation.AnimatedVisibility(
            visible = showArtworkViewer,
            enter = fadeIn(tween(ZyroAnim.Fast)),
            exit = fadeOut(tween(ZyroAnim.Fast)),
            label = "artwork_viewer"
        ) {
            ArtworkViewerOverlay(
                track = track,
                onClose = { showArtworkViewer = false }
            )
        }
    }

    if (showSearchDialog) {
        ZyroSearchLyricsDialog(
            initialTitle = track.title,
            initialArtist = track.artist,
            onDismiss = { showSearchDialog = false },
            onSearch = { title, artist ->
                showSearchDialog = false
                onSearchLyricsManual(title, artist)
            }
        )
    }
}

/**
 * Полноэкранный просмотр обложки: фон и элементы плеера размываются,
 * обложка показывается крупно и чуть увеличивается, под ней — скачивание.
 */
@Composable
private fun ArtworkViewerOverlay(
    track: TrackInfo,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appeared by remember { mutableStateOf(false) }
    val coverScale by animateFloatAsState(
        targetValue = if (appeared) 1.04f else 1f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "cover_viewer_scale"
    )

    LaunchedEffect(Unit) { appeared = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Закрыть",
                tint = ZyroTextPrimary
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(track.artworkUrl.ifBlank { ZyroArtworkFallback })
                    .crossfade(false)
                    .build(),
                contentDescription = "Обложка трека",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        scaleX = coverScale
                        scaleY = coverScale
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .border(1.dp, ZyroTextPrimary.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
            )

            Spacer(modifier = Modifier.height(22.dp))

            Button(
                onClick = {
                    if (track.artworkUrl.isNotBlank()) {
                        scope.launch {
                            val success = downloadArtworkToPictures(
                                context = context,
                                artworkUrl = track.artworkUrl,
                                trackTitle = track.title
                            )
                            Toast.makeText(
                                context,
                                if (success) "Обложка сохранена в Pictures/Zyro"
                                else "Не удалось скачать обложку",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                enabled = track.artworkUrl.isNotBlank(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ZyroAccent,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Скачать обложку",
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = track.title,
                color = ZyroTextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Маленький комментарий поверх обложки — появляется на несколько секунд,
 * когда воспроизведение проходит его таймкод. Не перекрывает управление.
 */
@Composable
private fun OnScreenCommentBubble(
    comment: CommentInfo,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ZyroBackground.copy(alpha = 0.72f))
            .border(1.dp, ZyroGlassBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        AsyncImage(
            model = comment.userAvatar.ifBlank { ZyroArtworkFallback },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(1.dp, ZyroGlassBorder, CircleShape)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = comment.username,
                    color = ZyroTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (comment.timestampMs > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatCommentTimestamp(comment.timestampMs),
                        color = ZyroAccent.copy(alpha = 0.95f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = comment.body,
                color = ZyroTextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlayerTopBar(
    onCloseClick: () -> Unit,
    onMoreClick: () -> Unit,
    lyricsVisible: Boolean = false,
    playbackContext: String? = null,
    activeLyricsSource: String? = null,
    syncedProvider: String? = null,
    plainProvider: String? = null,
    onSetLyricsSource: (String) -> Unit = {},
    onSearchLyricsManual: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ZyroIconButton(
            icon = Icons.Default.KeyboardArrowDown,
            size = 44.dp,
            iconSize = 28.dp,
            onClick = onCloseClick
        )

        // Обе подписи лежат в одном контейнере фиксированной высоты:
        // переключение — только плавный fade, без скачков ширины и размера.
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(42.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !lyricsVisible,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = ZyroAnim.Fast,
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = ZyroAnim.Fast,
                        easing = FastOutLinearInEasing
                    )
                ),
                modifier = Modifier.fillMaxWidth(),
                label = "topbar_context"
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    PlaybackContextPill(
                        text = playbackContext?.takeIf { it.isNotBlank() }
                            ?: "Играет из очереди"
                    )
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = lyricsVisible,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = ZyroAnim.Fast,
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = ZyroAnim.Fast,
                        easing = FastOutLinearInEasing
                    )
                ),
                modifier = Modifier.fillMaxWidth(),
                label = "topbar_chips"
            ) {
                LyricsSourceChips(
                    activeSource = activeLyricsSource,
                    syncedProvider = syncedProvider,
                    plainProvider = plainProvider,
                    onSetLyricsSource = onSetLyricsSource,
                    onSearchManual = onSearchLyricsManual,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        ZyroIconButton(
            icon = Icons.Default.MoreVert,
            size = 44.dp,
            iconSize = 24.dp,
            onClick = onMoreClick
        )
    }
}

@Composable
private fun PlaybackContextPill(text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(ZyroSurface.copy(alpha = 0.55f))
            .border(1.dp, ZyroGlassBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            tint = ZyroAccent.copy(alpha = 0.9f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        ZyroMarqueeText(
            text = text,
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = ZyroTextSecondary
            )
        )
    }
}