package com.zyro.player.ui.screens

import androidx.compose.animation.AnimatedVisibility
import com.zyro.player.ui.components.ZyroScrimEnter
import com.zyro.player.ui.components.ZyroScrimExit
import com.zyro.player.ui.components.zyroSheetEnter
import com.zyro.player.ui.components.zyroSheetExit
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zyro.player.data.downloader.OfflineMetaStore
import com.zyro.player.data.remote.CommentInfo
import com.zyro.player.data.remote.Result
import com.zyro.player.data.remote.SoundCloudRepository
import com.zyro.player.ui.components.ZyroArtworkFallback
import com.zyro.player.ui.components.ZyroScrim
import com.zyro.player.ui.components.formatCommentTimestamp
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.Locale

// ============================================================
// ZYRO DESIGN
// ============================================================

private val ZyroBackground = Color(0xFF070A0F)
private val ZyroSurface = Color(0xFF161B24)
private val ZyroDialog = Color(0xFF121722)
private val ZyroAccent = Color(0xFFFF8A3D)
private val ZyroTextPrimary = Color(0xFFF4F6FB)
private val ZyroTextSecondary = Color(0xFFF4F6FB).copy(alpha = 0.58f)
private val ZyroGlassBorder = Color.White.copy(alpha = 0.10f)

// ============================================================
// COMMENTS BOTTOM SHEET
// ============================================================

@Composable
fun TrackCommentsBottomSheet(
    trackUrl: String,
    repository: SoundCloudRepository,
    visible: Boolean,

    /** ID трека с SoundCloud, если уже известен — избавляет от лишнего resolve. */
    preferredTrackId: Long = 0L,

    // URL профиля SoundCloud пользователя.
    // Вызывается при нажатии на аватар или ник.
    onUserClick: (String, String, String) -> Unit,

    // Переход к таймкоду комментария.
    onSeekTo: (Long) -> Unit = {},

    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val sheetHeight = screenHeight * 2 / 3
    val context = LocalContext.current
    val offlineMetaStore = remember { OfflineMetaStore(context) }

    val comments = remember {
        mutableStateListOf<CommentInfo>()
    }

    var isLoading by remember {
        mutableStateOf(false)
    }

    var isLoadingMore by remember {
        mutableStateOf(false)
    }

    var hasMore by remember {
        mutableStateOf(false)
    }

    var error by remember {
        mutableStateOf<String?>(null)
    }

    var trackId by remember {
        mutableStateOf<Long?>(null)
    }

    val listState = rememberLazyListState()

    // ========================================================
    // ПЕРВИЧНАЯ ЗАГРУЗКА
    // ========================================================

    LaunchedEffect(visible, trackUrl, preferredTrackId) {

        if (!visible) {
            return@LaunchedEffect
        }

        if (preferredTrackId <= 0L && (trackUrl.isBlank() || !trackUrl.contains("soundcloud.com"))) {
            comments.clear()
            trackId = null
            hasMore = false
            isLoading = false
            error = "Некорректная ссылка на трек"
            return@LaunchedEffect
        }

        comments.clear()

        trackId = null
        hasMore = false

        error = null

        isLoading = true
        isLoadingMore = false

        // Если сети нет, показываем сохранённый снимок комментариев (офлайн-кеш).
        val fallbackToCached: (String) -> Unit = { message ->
            val cached = offlineMetaStore.loadComments(trackUrl)
            if (cached.isNotEmpty()) {
                comments.clear()
                comments.addAll(cached)
                hasMore = false
                error = null
            } else {
                error = message
            }
        }

        try {

            val resolvedId = if (preferredTrackId > 0L) {
                preferredTrackId
            } else {
                val resolveResult = repository.resolveTrackId(trackUrl)
                if (resolveResult !is Result.Success) {
                    val msg = if (resolveResult is Result.Error) resolveResult.message else "Неизвестная ошибка"
                    fallbackToCached("Не удалось определить трек: $msg")
                    return@LaunchedEffect
                }
                resolveResult.data
            }
            trackId = resolvedId

            val commentsResult = repository.fetchTrackComments(
                trackId = resolvedId,
                offset = 0,
                limit = 20
            )

            if (commentsResult !is Result.Success) {
                val msg = if (commentsResult is Result.Error) commentsResult.message else "Неизвестная ошибка"
                fallbackToCached("Ошибка загрузки комментариев: $msg")
                return@LaunchedEffect
            }

            val data = commentsResult.data
            comments.clear()
            comments.addAll(data.comments)

            hasMore = data.hasMore

            if (comments.isEmpty()) {
                error = "Комментариев пока нет"
            }

        } catch (e: Exception) {

            fallbackToCached(e.message ?: "Ошибка загрузки комментариев")

        } finally {

            isLoading = false
        }
    }

    // ========================================================
    // АВТОПОДГРУЗКА
    // ========================================================

    // Важно: эффект не должен перезапускаться от isLoadingMore.
    // Иначе установка флага отменяет сам запрос, и после первых 20
    // комментариев список навсегда остаётся неполным.
    LaunchedEffect(visible, trackId) {

        if (!visible || trackId == null) {
            return@LaunchedEffect
        }

        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            Triple(lastVisibleIndex, info.totalItemsCount, listState.firstVisibleItemScrollOffset)
        }
            .distinctUntilChanged()
            .collect { (lastVisibleIndex, totalItemsCount, _) ->

                val nearBottom = totalItemsCount > 0 &&
                        lastVisibleIndex >= totalItemsCount - 3

                if (
                    !nearBottom ||
                    isLoading ||
                    isLoadingMore ||
                    !hasMore ||
                    comments.isEmpty()
                ) {
                    return@collect
                }

                val currentTrackId = trackId ?: return@collect
                isLoadingMore = true

                try {
                    val result = repository.fetchTrackComments(
                        trackId = currentTrackId,
                        offset = comments.size,
                        limit = 20
                    )

                    if (result is Result.Success) {
                        val data = result.data

                        // Защита от дублей
                        val existingIds = comments.asSequence().map { it.id }.toHashSet()
                        val newComments = data.comments.filter { it.id !in existingIds }

                        if (newComments.isNotEmpty()) {
                            comments.addAll(newComments)
                        }

                        hasMore = data.hasMore

                        // Пустая страница или только дубли — дальше загружать нечего.
                        if (data.comments.isEmpty() || newComments.isEmpty()) {
                            hasMore = false
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Уже загруженные комментарии оставляем на экране.
                } finally {
                    isLoadingMore = false
                }
            }
    }

    // ========================================================
    // UI
    // ========================================================

    val sheetHeightPx = with(density) { sheetHeight.roundToPx() }

    AnimatedVisibility(
        visible = visible,
        enter = ZyroScrimEnter,
        exit = ZyroScrimExit
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    ZyroScrim
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {

            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = zyroSheetEnter(sheetHeightPx),
                exit = zyroSheetExit(sheetHeightPx)
            ) {

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(sheetHeight)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent()
                                }
                            }
                        }
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    ZyroDialog.copy(alpha = 0.97f),
                                    ZyroSurface.copy(alpha = 0.98f)
                                )
                            ),
                            shape = RoundedCornerShape(
                                topStart = 32.dp,
                                topEnd = 32.dp
                            )
                        )
                        .border(
                            width = 1.dp,
                            color = ZyroGlassBorder,
                            shape = RoundedCornerShape(
                                topStart = 32.dp,
                                topEnd = 32.dp
                            )
                        )
                        .clip(
                            RoundedCornerShape(
                                topStart = 32.dp,
                                topEnd = 32.dp
                            )
                        )
                ) {

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                    ) {

                        // ====================================================
                        // DRAG INDICATOR
                        // ====================================================

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {

                            Box(
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        ZyroTextSecondary.copy(alpha = 0.30f)
                                    )
                            )
                        }

                        // ====================================================
                        // HEADER
                        // ====================================================

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Column {

                                Text(
                                    text = "Комментарии",
                                    color = ZyroTextPrimary,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 28.sp
                                )

                                if (comments.isNotEmpty()) {

                                    Text(
                                        text = "${comments.size}${if (hasMore) "+" else ""}",
                                        color = ZyroTextSecondary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        color = ZyroSurface.copy(alpha = 0.60f),
                                        shape = CircleShape
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = ZyroGlassBorder,
                                        shape = CircleShape
                                    )
                            ) {

                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Закрыть",
                                    tint = ZyroTextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // ====================================================
                        // CONTENT
                        // ====================================================

                        when {

                            // Первичная загрузка
                            isLoading && comments.isEmpty() -> {

                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {

                                    CircularProgressIndicator(
                                        color = ZyroAccent,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            // Ошибка
                            error != null && comments.isEmpty() -> {

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {

                                    Text(
                                        text = error ?: "Ошибка загрузки",
                                        color = ZyroTextSecondary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            // Список комментариев
                            else -> {

                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(
                                        horizontal = 20.dp,
                                        vertical = 8.dp
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {

                                    items(
                                        items = comments,
                                        key = { it.id }
                                    ) { comment ->

                                        CommentItem(
                                            comment = comment,
                                            onUserClick = onUserClick,
                                            onSeekTo = { timestampMs ->
                                                onSeekTo(timestampMs.toLong())
                                                onDismiss()
                                            }
                                        )
                                    }

                                    // =================================================
                                    // LOADING MORE
                                    // =================================================

                                    if (isLoadingMore) {

                                        item(key = "comments_loader") {

                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {

                                                CircularProgressIndicator(
                                                    color = ZyroAccent,
                                                    strokeWidth = 2.dp,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }

                                    // =================================================
                                    // END
                                    // =================================================

                                    if (!hasMore && comments.isNotEmpty()) {

                                        item(key = "comments_end") {

                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 24.dp),
                                                contentAlignment = Alignment.Center
                                            ) {

                                                Text(
                                                    text = "Больше комментариев нет",
                                                    color = ZyroTextSecondary.copy(alpha = 0.40f),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// COMMENT ITEM
// ============================================================

@Composable
private fun CommentItem(
    comment: CommentInfo,
    onUserClick: (String, String, String) -> Unit,
    onSeekTo: (Int) -> Unit
) {

    val dateFormat = remember {
        SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("ru"))
    }

    val profileUrl = comment.userPermalink.trim()

    val hasProfile = profileUrl.isNotBlank()

    val userClickModifier =
        if (hasProfile) {
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onUserClick(profileUrl, comment.username, comment.userAvatar)
            }
        } else {
            Modifier
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = ZyroSurface.copy(alpha = 0.50f),
                shape = RoundedCornerShape(20.dp)
            )
            .border(
                width = 1.dp,
                color = ZyroGlassBorder,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(16.dp)
    ) {

        // ========================================================
        // АВАТАР
        // ========================================================

        AsyncImage(
            model = comment.userAvatar.ifBlank { ZyroArtworkFallback },
            contentDescription = "Профиль ${comment.username}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = ZyroGlassBorder,
                    shape = CircleShape
                )
                .then(userClickModifier)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // ========================================================
        // COMMENT CONTENT
        // ========================================================

        Column(
            modifier = Modifier.weight(1f)
        ) {

            // ====================================================
            // USERNAME + DATE
            // ====================================================

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Text(
                    text = comment.username,
                    color = ZyroTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .then(userClickModifier)
                )

                Spacer(modifier = Modifier.width(8.dp))

                comment.createdAt?.let { date ->

                    Text(
                        text = dateFormat.format(date),
                        color = ZyroTextSecondary.copy(alpha = 0.50f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            // ====================================================
            // TIMESTAMP
            // ====================================================

            if (comment.timestampMs > 0) {

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(ZyroAccent.copy(alpha = 0.10f))
                        .border(
                            width = 1.dp,
                            color = ZyroAccent.copy(alpha = 0.24f),
                            shape = RoundedCornerShape(9.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onSeekTo(comment.timestampMs)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = ZyroAccent,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = formatCommentTimestamp(comment.timestampMs),
                        color = ZyroAccent.copy(alpha = 0.95f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ====================================================
            // COMMENT TEXT
            // ====================================================

            Text(
                text = comment.body,
                color = ZyroTextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 20.sp
            )
        }
    }
}

