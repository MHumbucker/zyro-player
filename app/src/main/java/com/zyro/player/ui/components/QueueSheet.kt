package com.zyro.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zyro.player.data.remote.TrackInfo
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Очередь плеера.
 *
 * @param queue          Полная очередь от PlayerManager.
 * @param currentIndex   Индекс текущего трека в [queue].
 * @param currentTrack   Текущий трек (для шапки "Сейчас играет").
 * @param isShuffle      Состояние shuffle.
 * @param onCloseClick   Закрыть шторку.
 * @param onTrackSelect  Выбрать трек из очереди (index в полной queue).
 * @param onRemoveFromQueue Удалить из очереди (index в полной queue).
 * @param onMoveItem     Переместить трек (fromIndex/toIndex в полной queue).
 * @param onMoveFinished Вызывается после завершения drag (фиксирует порядок).
 * @param onClearQueue   Удалить все upcoming треки.
 * @param onToggleShuffle Переключить shuffle.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<TrackInfo>,
    currentIndex: Int,
    currentTrack: TrackInfo?,
    isShuffle: Boolean,
    onCloseClick: () -> Unit,
    onTrackSelect: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveItem: (fromIndex: Int, toIndex: Int) -> Unit,
    onMoveFinished: () -> Unit = {},
    onClearQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onCloseClick() }

    var showClearConfirm by remember { mutableStateOf(false) }

    // "Далее" — всё, что после currentIndex.
    // Список не дублируем локально: источником истины остаётся очередь плеера.
    val upcoming = remember(queue, currentIndex) {
        if (currentIndex >= 0 && currentIndex < queue.lastIndex) {
            queue.subList(currentIndex + 1, queue.size).toList()
        } else {
            emptyList()
        }
    }

    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val latestCurrentIndex by rememberUpdatedState(currentIndex)
    val latestOnMoveItem by rememberUpdatedState(onMoveItem)

    // from/to — индексы LazyColumn, а первым элементом идёт заголовок «Далее».
    // Вычитаем 1 и переводим в индексы полной очереди.
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val fromUpcoming = from.index - 1
        val toUpcoming = to.index - 1
        if (fromUpcoming in upcoming.indices && toUpcoming in upcoming.indices) {
            val base = latestCurrentIndex + 1
            latestOnMoveItem(base + fromUpcoming, base + toUpcoming)
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }

    ZyroAuroraBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // === HEADER ===
            QueueHeader(
                upcomingCount = upcoming.size,
                isShuffle = isShuffle,
                onToggleShuffle = onToggleShuffle,
                onClearQueue = { showClearConfirm = true },
                onCloseClick = onCloseClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            // === СЕЙЧАС ИГРАЕТ ===
            if (currentTrack != null) {
                NowPlayingCard(track = currentTrack)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // === ДАЛЕЕ ===
            if (upcoming.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = null,
                            tint = ZyroTextSecondary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Очередь пуста",
                            fontSize = 16.sp,
                            color = ZyroTextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "Далее",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZyroTextSecondary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }

                    itemsIndexed(
                        items = upcoming,
                        key = { _, track -> track.originalUrl }
                    ) { index, track ->
                        ReorderableItem(
                            state = reorderableState,
                            key = track.originalUrl
                        ) { isDragging ->
                            val elevation by animateDpAsState(
                                targetValue = if (isDragging) 12.dp else 0.dp,
                                animationSpec = tween(ZyroAnim.Fast),
                                label = "queue_item_elevation"
                            )

                            ZyroSwipeRow(
                                onSwipeRight = {
                                    if (index > 0) {
                                        val fullIndex = latestCurrentIndex + 1 + index
                                        latestOnMoveItem(fullIndex, latestCurrentIndex + 1)
                                        onMoveFinished()
                                    }
                                },
                                onSwipeLeft = {
                                    onRemoveFromQueue(currentIndex + 1 + index)
                                },
                                rightIcon = Icons.Default.VerticalAlignTop,
                                rightLabel = "В начало",
                                leftIcon = Icons.Default.Close,
                                leftLabel = "Удалить",
                                modifier = Modifier.shadow(
                                    elevation = elevation,
                                    shape = RoundedCornerShape(ZyroShapes.L),
                                    clip = false
                                )
                            ) {
                                QueueTrackRow(
                                    track = track,
                                    index = index,
                                    isDragging = isDragging,
                                    onClick = { onTrackSelect(currentIndex + 1 + index) },
                                    onRemove = { onRemoveFromQueue(currentIndex + 1 + index) },
                                    dragHandleModifier = Modifier.draggableHandle(
                                        onDragStarted = {
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.GestureThresholdActivate
                                            )
                                        },
                                        onDragStopped = {
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            onMoveFinished()
                                        }
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = ZyroSurfaceElevated,
            titleContentColor = ZyroTextPrimary,
            textContentColor = ZyroTextSecondary,
            title = {
                Text(
                    text = "Очистить очередь?",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            },
            text = {
                Text(
                    "Все треки после текущего будут удалены. Текущий трек останется."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearQueue()
                    }
                ) {
                    Text("Очистить", color = ZyroAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Отмена", color = ZyroTextSecondary)
                }
            }
        )
    }
}

// ==========================================
// HEADER
// ==========================================

@Composable
private fun QueueHeader(
    upcomingCount: Int,
    isShuffle: Boolean,
    onToggleShuffle: () -> Unit,
    onClearQueue: () -> Unit,
    onCloseClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = null,
                    tint = ZyroAccent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Очередь",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZyroTextPrimary
                )
                if (upcomingCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "($upcomingCount)",
                        fontSize = 16.sp,
                        color = ZyroTextSecondary
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Shuffle
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (isShuffle) ZyroAccent.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                        .clickable { onToggleShuffle() }
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (isShuffle) ZyroAccent else ZyroTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (upcomingCount > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Очистить",
                        fontSize = 14.sp,
                        color = ZyroTextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onClearQueue() }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Свернуть",
                        tint = ZyroTextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .padding(horizontal = 20.dp)
                .background(ZyroTextPrimary.copy(alpha = BorderAlpha))
        )
    }
}

// ==========================================
// NOW PLAYING
// ==========================================

@Composable
private fun NowPlayingCard(track: TrackInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .zyroPlasticGlass(
                shape = RoundedCornerShape(ZyroShapes.L),
                tint = ZyroAccent,
                strong = true
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.artworkUrl.ifBlank {
                ZyroArtworkFallback
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(ZyroShapes.S))
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.16f),
                    RoundedCornerShape(ZyroShapes.S)
                )
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "СЕЙЧАС ИГРАЕТ",
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ZyroAccent,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = track.title,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 16.sp,
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
            Text(
                text = track.artist,
                fontSize = 14.sp,
                color = ZyroTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(ZyroAccent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            PlayingIndicator()
        }
    }
}

@Composable
private fun PlayingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "playing")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(ZyroAccent, CircleShape)
    )
}

// ==========================================
// QUEUE TRACK ROW
// ==========================================

@Composable
private fun QueueTrackRow(
    track: TrackInfo,
    index: Int,
    isDragging: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .zyroPlasticGlass(
                shape = RoundedCornerShape(ZyroShapes.L),
                tint = if (isDragging) ZyroAccent else Color.Transparent
            )
            .then(
                if (isDragging) {
                    Modifier.border(
                        1.dp,
                        ZyroAccent.copy(alpha = 0.4f),
                        RoundedCornerShape(ZyroShapes.L)
                    )
                } else Modifier
            )
            .clickable { onClick() }
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drag Handle
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "Перетащить",
            tint = if (isDragging) ZyroAccent else ZyroTextSecondary.copy(alpha = 0.35f),
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .size(32.dp)
                .then(dragHandleModifier)
        )

        // Index
        Text(
            text = "${index + 1}",
            fontSize = 13.sp,
            color = ZyroTextSecondary,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Artwork
        AsyncImage(
            model = track.artworkUrl.ifBlank {
                ZyroArtworkFallback
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(ZyroShapes.S))
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.14f),
                    RoundedCornerShape(ZyroShapes.S)
                )
        )

        Spacer(modifier = Modifier.width(14.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = track.title,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
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
                text = track.artist,
                fontSize = 13.sp,
                color = ZyroTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Remove
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Удалить",
                tint = ZyroTextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}