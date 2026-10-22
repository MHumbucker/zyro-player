package com.zyro.player.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyro.player.data.local.LyricsEntity
import com.zyro.player.data.remote.LrcLine
import com.zyro.player.data.remote.LrcWord
import com.zyro.player.data.remote.LyricsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

@Composable
fun LyricsContent(
    lyrics: LyricsEntity?,
    currentPosition: Long,
    isLyricsLoading: Boolean,
    artworkUrl: String? = null,
    /** true — самостоятельная страница (превью); false — оверлей поверх плеера */
    showHeader: Boolean = true,
    /** true — рисует собственный размытый фон; false — прозрачный оверлей со scrim */
    drawBackground: Boolean = true,
    /** true — текст по левому краю (оверлей в рамках обложки) */
    leftAligned: Boolean = false,
    onSetLyricsSource: (String) -> Unit,
    onSearchManual: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onBackToPlayer: () -> Unit
) {
    val lrcLines = remember(lyrics?.lrcContent) {
        if (!lyrics?.lrcContent.isNullOrBlank()) LyricsRepository.parseLrc(lyrics!!.lrcContent) else emptyList()
    }

    val listState = rememberLazyListState()

    // Плавная позиция: плеер обновляет currentPosition раз в ~500 мс. Между
    // обновлениями линейно интерполируем, чтобы и подсветка слов, и смена
    // активной строки шли непрерывно (как smoothDrawPosition в kittytune).
    // При перемотке/смене трека — мгновенный переход без прокрутки всех строк.
    var smoothPosition by remember { mutableFloatStateOf(currentPosition.toFloat()) }
    LaunchedEffect(currentPosition) {
        val target = currentPosition.toFloat()
        if (abs(target - smoothPosition) > 1200f) {
            smoothPosition = target
        } else {
            animate(
                initialValue = smoothPosition,
                targetValue = target,
                animationSpec = tween(durationMillis = 500, easing = LinearEasing)
            ) { value, _ ->
                smoothPosition = value
            }
        }
    }

    val activeLrcIndex by remember(lrcLines) {
        derivedStateOf {
            if (lrcLines.isEmpty()) -1
            else {
                val idx = lrcLines.indexOfLast { it.timestampMs <= smoothPosition.toLong() }
                if (idx >= 0) idx else 0
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Фон: в оверлее — прозрачно (обложка плеера просвечивает), иначе размытая обложка.
        if (drawBackground) {
            if (!artworkUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(60.dp)
                        .alpha(0.55f)
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            } else {
                Box(Modifier.fillMaxSize().background(Color.Black))
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // === HEADER (только для самостоятельной страницы; в оверлее чипы живут в топбаре плеера) ===
            if (showHeader) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ZyroIconButton(
                        icon = Icons.Default.ArrowBack,
                        size = 44.dp,
                        iconSize = 24.dp,
                        onClick = onBackToPlayer
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LyricsSourceChips(
                            activeSource = lyrics?.activeSource,
                            syncedProvider = lyrics?.syncedProvider,
                            plainProvider = lyrics?.plainProvider,
                            onSetLyricsSource = onSetLyricsSource,
                            onSearchManual = onSearchManual,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.size(44.dp))
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // === CONTENT ===
            AnimatedContent(
                targetState = when {
                    isLyricsLoading -> "loading"
                    // Синхронизированный текст приоритетнее: показываем его сразу,
                    // если он есть. Простой текст — только когда синхронизации нет
                    // либо пользователь явно выбрал источник "Genius".
                    lyrics?.activeSource == "GENIUS" &&
                            !lyrics?.plainTextContent.isNullOrBlank() -> "plain"
                    lrcLines.isNotEmpty() -> "lrc"
                    !lyrics?.plainTextContent.isNullOrBlank() -> "plain"
                    else -> "empty"
                },
                transitionSpec = { zyroStateTransition() },
                label = "lyrics_state",
                modifier = Modifier.weight(1f)
            ) { state ->
                when (state) {
                    "loading" -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LyricsLoadingIndicator()
                    }

                    "lrc" -> LrcLyricsList(
                        lrcLines = lrcLines,
                        activeLrcIndex = activeLrcIndex,
                        positionMs = smoothPosition.toLong(),
                        listState = listState,
                        leftAligned = leftAligned,
                        onSeekTo = onSeekTo
                    )

                    "plain" -> PlainLyricsView(text = lyrics!!.plainTextContent)

                    else -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LyricsEmptyState(
                            message = if (
                                lyrics?.activeSource == "LRC" ||
                                !lyrics?.lrcContent.isNullOrBlank()
                            )
                                "Синхронизированные слова не найдены."
                            else
                                "Текст не найден.",
                            onAction = onSearchManual
                        )
                    }
                }
            }
        }

        // === FADE OVERLAYS ===

    }
}

@Composable
private fun LrcLyricsList(
    lrcLines: List<LrcLine>,
    activeLrcIndex: Int,
    positionMs: Long,
    listState: LazyListState,
    leftAligned: Boolean,
    onSeekTo: (Long) -> Unit
) {

    // Ручной скролл: пока пользователь листает сам — авто-следование отключено.
    // Через 3 секунды после последнего касания/инерции возвращаемся к авто-следованию.
    var isManualScrolling by remember { mutableStateOf(false) }
    var lastManualScrollTime by remember { mutableLongStateOf(0L) }
    var isUserTouching by remember { mutableStateOf(false) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    isManualScrolling = true
                    lastManualScrollTime = System.currentTimeMillis()
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (isManualScrolling) {
                    lastManualScrollTime = System.currentTimeMillis()
                }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(isManualScrolling) {

        if (!isManualScrolling) return@LaunchedEffect

        var lastActivity = lastManualScrollTime

        while (isActive) {

            if (isUserTouching || listState.isScrollInProgress) {
                lastActivity = System.currentTimeMillis()
                delay(100L)
                continue
            }

            val idle = System.currentTimeMillis() - lastActivity

            if (idle >= 3000L) break

            delay((3000L - idle).coerceAtLeast(50L))
        }

        if (!isUserTouching && !listState.isScrollInProgress) {
            isManualScrolling = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(
                            androidx.compose.ui.input.pointer.PointerEventPass.Initial
                        )
                        val anyPressed = event.changes.any { it.pressed }
                        if (anyPressed) {
                            isUserTouching = true
                            isManualScrolling = true
                            lastManualScrollTime = System.currentTimeMillis()
                        } else if (isUserTouching) {
                            isUserTouching = false
                            lastManualScrollTime = System.currentTimeMillis()
                        }
                    }
                }
            }
            .nestedScroll(nestedScrollConnection)
    ) {

        // Активная строка удерживается по центру области (как в kittytune).
        val halfHeight = maxHeight / 2
        val centerTop = halfHeight - 40.dp

        // Авто-следование: плавный сдвиг к активной строке (animateScrollToItem).
        LaunchedEffect(activeLrcIndex, isManualScrolling) {

            if (activeLrcIndex < 0 || isManualScrolling) return@LaunchedEffect

            val distance = abs(activeLrcIndex - listState.firstVisibleItemIndex)
            if (distance > 15) {
                listState.scrollToItem((activeLrcIndex - 2).coerceAtLeast(0))
            }
            listState.animateScrollToItem(index = activeLrcIndex)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .zyroFadeVerticalEdges(),
            horizontalAlignment = if (leftAligned) Alignment.Start else Alignment.CenterHorizontally,
            contentPadding = PaddingValues(
                top = centerTop,
                bottom = halfHeight
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            itemsIndexed(
                lrcLines,
                key = { _, line ->
                    line.timestampMs
                }
            ) { index, line ->

                LyricsLine(
                    text = line.text,
                    words = line.words,
                    positionMs = positionMs,
                    distance = index - activeLrcIndex,
                    leftAligned = leftAligned,
                    onClick = {
                        onSeekTo(line.timestampMs)
                    }
                )

            }

        }

    }

}

// ==========================================
// FADE EDGES — мягкое растворение строк у краёв (как в kittytune)
// ==========================================

/**
 * Плавно «растворяет» контент у верхнего и нижнего края, накладывая
 * вертикальную градиентную маску (BlendMode.DstIn). Эффект как у
 * fadeEdge в kittytune, но без зависимости от версии Foundation.
 */
private fun Modifier.zyroFadeVerticalEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.15f to Color.Black,
                0.85f to Color.Black,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }

// ==========================================
// ANIMATED LINE — как в kittytune (alpha + word-sync)
// ==========================================

@Composable
private fun LyricsLine(
    text: String,
    words: List<LrcWord> = emptyList(),
    positionMs: Long = 0L,
    distance: Int,
    leftAligned: Boolean = false,
    onClick: () -> Unit
) {

    val isActive = distance == 0

    // Анимация строк — точно как в kittytune: без масштабирования и без блюра,
    // только плавное изменение прозрачности. Активная строка — 1.0,
    // прошедшие — 0.45, будущие — 0.70. Плавный переход — tween(400).
    val targetAlpha = when {
        isActive -> 1f
        distance < 0 -> 0.45f
        else -> 0.70f
    }

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "lyrics_line_alpha"
    )

    val contentAlignment = if (leftAligned) Alignment.CenterStart else Alignment.Center
    val textAlign = if (leftAligned) TextAlign.Start else TextAlign.Center
    val horizontalPadding = if (leftAligned) 12.dp else 28.dp
    val lineFontSize = 22.sp
    // Неактивная строка — белый с alpha 0.5 (как в kittytune), поверх этого
    // накладывается плавная alpha-анимация.
    val lineColor = if (isActive) ZyroTextPrimary else ZyroTextPrimary.copy(alpha = 0.5f)

    // Word-sync: если у активной строки есть таймкоды слов — подсвечиваем по мере пропевания.
    val useWordSync = isActive && words.isNotEmpty()

    val wordSyncedText = remember(words) {
        words.joinToString("") { it.text }
    }

    val wordRanges = remember(words) {
        var acc = 0
        words.map { word ->
            val start = acc
            acc += word.text.length
            start to acc
        }
    }

    var wordTextLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = contentAlignment
    ) {

        if (useWordSync) {

            val sharedModifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { this.alpha = alpha }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onClick()
                }
                .padding(horizontal = horizontalPadding)

            Box(
                modifier = sharedModifier
            ) {
                // Базовый слой — приглушённый
                Text(
                    text = wordSyncedText,
                    color = ZyroTextPrimary.copy(alpha = 0.5f),
                    fontSize = lineFontSize,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = textAlign,
                    lineHeight = 31.sp,
                    modifier = Modifier.fillMaxWidth(),
                    onTextLayout = { wordTextLayout = it }
                )
                // Верхний слой — пропетые слова, обрезанные по clipPath
                Text(
                    text = wordSyncedText,
                    color = ZyroTextPrimary,
                    fontSize = lineFontSize,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = textAlign,
                    lineHeight = 31.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            val layoutResult = wordTextLayout ?: return@drawWithContent
                            val path = Path()
                            val safeLast = (wordSyncedText.length - 1).coerceAtLeast(0)

                            words.forEachIndexed { index, word ->
                                val range = wordRanges.getOrNull(index) ?: return@forEachIndexed
                                if (range.first >= range.second) return@forEachIndexed

                                when {
                                    positionMs >= word.endMs -> {
                                        for (c in range.first until range.second) {
                                            path.addRect(
                                                layoutResult.getBoundingBox(c.coerceIn(0, safeLast))
                                            )
                                        }
                                    }

                                    positionMs >= word.startMs -> {
                                        val span = (word.endMs - word.startMs).coerceAtLeast(1L)
                                        val progress =
                                            ((positionMs - word.startMs).toFloat() / span)
                                                .coerceIn(0f, 1f)
                                        val exact = progress * (range.second - range.first)
                                        val fully = exact.toInt()
                                        val fraction = exact - fully

                                        for (c in range.first until range.first + fully) {
                                            path.addRect(
                                                layoutResult.getBoundingBox(c.coerceIn(0, safeLast))
                                            )
                                        }

                                        val partial = range.first + fully
                                        if (partial < range.second) {
                                            val box =
                                                layoutResult.getBoundingBox(partial.coerceIn(0, safeLast))
                                            val x = box.left + (box.right - box.left) * fraction
                                            path.addRect(Rect(box.left, box.top, x, box.bottom))
                                        }
                                    }
                                }
                            }

                            clipPath(path) { this@drawWithContent.drawContent() }
                        }
                )
            }
        } else {

            Text(
                text = text,
                color = lineColor,
                fontSize = lineFontSize,
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                textAlign = textAlign,
                lineHeight = 31.sp,
                modifier = Modifier
                    .graphicsLayer { this.alpha = alpha }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onClick()
                    }
                    .padding(horizontal = horizontalPadding)
            )
        }
    }
}

// ==========================================
// PLAIN LYRICS
// ==========================================

@Composable
private fun PlainLyricsView(text: String) {
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(vertical = 40.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = text,
                fontSize = 19.sp,
                color = ZyroTextPrimary.copy(alpha = 0.92f),
                lineHeight = 32.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}



// ==========================================
// LOADING — 3 пульсирующие точки
// ==========================================

@Composable
private fun LyricsLoadingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")

    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, delayMillis = index * 150, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$index"
            )
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, delayMillis = index * 150, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_alpha_$index"
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .background(ZyroAccent, CircleShape)
            )
            if (index < 2) Spacer(modifier = Modifier.width(8.dp))
        }

        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "Поиск слов...",
            color = ZyroTextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ==========================================
// EMPTY STATE — glassmorphism
// ==========================================

@Composable
private fun LyricsEmptyState(message: String, onAction: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "float")
    val floatY by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float"
    )

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Lyrics,
                contentDescription = null,
                tint = ZyroTextSecondary,
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer { translationY = floatY }
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = message,
                color = ZyroTextSecondary,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(32.dp))
            ZyroButton(
                text = "Найти вручную",
                icon = Icons.Default.Search,
                onClick = onAction
            )
        }
    }
}

// ==========================================
// SOURCE CHIPS ROW (используется в топбаре плеера и на странице)
// ==========================================

@Composable
fun LyricsSourceChips(
    activeSource: String?,
    onSetLyricsSource: (String) -> Unit,
    onSearchManual: () -> Unit,
    modifier: Modifier = Modifier,
    syncedProvider: String? = null,
    plainProvider: String? = null
) {
    // Короткие подписи + равные сегменты: чипы всегда помещаются в топбар
    // без горизонтального свайпа. Провайдер остаётся в contentDescription.
    val syncedDescription = syncedProvider
        ?.takeIf { it.isNotBlank() }
        ?.let { "Синхронизированный текст, $it" }
        ?: "Синхронизированный текст"
    val plainDescription = plainProvider
        ?.takeIf { it.isNotBlank() }
        ?.let { "Обычный текст, $it" }
        ?: "Обычный текст"

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(ZyroSurface.copy(alpha = 0.55f))
            .border(1.dp, ZyroGlassBorder, RoundedCornerShape(18.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LyricsSourceChip(
            text = "Синхр.",
            icon = Icons.Default.Timer,
            isSelected = activeSource == "LRC",
            contentDescription = syncedDescription,
            modifier = Modifier.weight(1f),
            onClick = { onSetLyricsSource("LRC") }
        )
        LyricsSourceChip(
            text = "Текст",
            icon = Icons.Default.MenuBook,
            isSelected = activeSource == "GENIUS",
            contentDescription = plainDescription,
            modifier = Modifier.weight(1f),
            onClick = { onSetLyricsSource("GENIUS") }
        )
        LyricsSourceChip(
            text = "Поиск",
            icon = Icons.Default.Search,
            isSelected = false,
            modifier = Modifier.weight(1f),
            onClick = onSearchManual
        )
    }
}

// ==========================================
// SOURCE CHIP — улучшенный ZyroChip для текстов
// ==========================================

@Composable
private fun LyricsSourceChip(
    text: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1f,
        animationSpec = ZyroAnim.SpringSnappy,
        label = "chip_scale"
    )
    val bgTop by animateColorAsState(
        targetValue = if (isSelected) ZyroAccent.copy(alpha = 0.24f) else Color.Transparent,
        animationSpec = ZyroAnim.TweenColorFast,
        label = "chip_bg_top"
    )
    val bgBottom by animateColorAsState(
        targetValue = if (isSelected) ZyroAccent.copy(alpha = 0.09f) else Color.Transparent,
        animationSpec = ZyroAnim.TweenColorFast,
        label = "chip_bg_bottom"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) ZyroAccent.copy(alpha = 0.48f) else Color.Transparent,
        animationSpec = ZyroAnim.TweenColorFast,
        label = "chip_border"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) ZyroAccent else ZyroTextPrimary.copy(alpha = 0.82f),
        animationSpec = ZyroAnim.TweenColorFast,
        label = "chip_content"
    )

    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            color = contentColor,
            fontSize = 12.sp,
            // Фиксированный lineHeight не даёт чипу раздуваться от bodyLarge.
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==========================================
// PREVIEWS
// ==========================================

@Preview(showBackground = true, device = "id:pixel_5", name = "LRC Mode")
@Composable
private fun PreviewLyricsLrc() {
    val lrc = """
        [00:08.00] Строка над активной
        [00:12.50] Активная строка прямо сейчас
        [00:17.00] Строка под активной
        [00:21.00] Ещё одна строка
        [00:25.50] И снова строка
        [00:30.00] Последняя строка
    """.trimIndent()

    LyricsContent(
        lyrics = LyricsEntity(
            lrcContent = lrc,
            activeSource = "LRC",
            plainTextContent = "null",
            trackOriginalUrl = "null",
            trackTitle = "Филя",
            artistName = "Квартирант",
            customUserContent = "null",
            lastUpdated = 100
        ),
        currentPosition = 12500L,
        isLyricsLoading = false,
        onSetLyricsSource = {},
        onSearchManual = {},
        onSeekTo = {},
        onBackToPlayer = {}
    )
}

@Preview(showBackground = true, device = "id:pixel_5", name = "Plain Text")
@Composable
private fun PreviewLyricsPlain() {
    LyricsContent(
        lyrics = LyricsEntity(
            lrcContent = "sfdf",
            activeSource = "GENIUS",
            plainTextContent = "Просто текст песни\nБез таймкодов\nТри строки\nДля превью",
            trackOriginalUrl = "null",
            trackTitle = "Филя",
            artistName = "Квартирант",
            customUserContent = "null",
            lastUpdated = 100
        ),
        currentPosition = 0L,
        isLyricsLoading = false,
        onSetLyricsSource = {},
        onSearchManual = {},
        onSeekTo = {},
        onBackToPlayer = {}
    )
}

@Preview(showBackground = true, device = "id:pixel_5", name = "Loading")
@Composable
private fun PreviewLyricsLoading() {
    LyricsContent(
        lyrics = null,
        currentPosition = 0L,
        isLyricsLoading = true,
        onSetLyricsSource = {},
        onSearchManual = {},
        onSeekTo = {},
        onBackToPlayer = {}
    )
}

@Preview(showBackground = true, device = "id:pixel_5", name = "Empty")
@Composable
private fun PreviewLyricsEmpty() {
    LyricsContent(
        lyrics = LyricsEntity(
            lrcContent = "null",
            activeSource = "LRC",
            plainTextContent = "null",
            trackOriginalUrl = "null",
            trackTitle = "Филя",
            artistName = "Квартирант",
            customUserContent = "null",
            lastUpdated = 100
        ),
        currentPosition = 0L,
        isLyricsLoading = false,
        onSetLyricsSource = {},
        onSearchManual = {},
        onSeekTo = {},
        onBackToPlayer = {}
    )
}

@Preview(showBackground = true, name = "LyricsLine States")
@Composable
private fun PreviewLyricsLineStates() {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LyricsLine(text = "distance = 0 (active)", distance = 0, onClick = {})
        LyricsLine(text = "distance = 1", distance = 1, onClick = {})
        LyricsLine(text = "distance = 2", distance = 2, onClick = {})
        LyricsLine(text = "distance = 5", distance = 5, onClick = {})
    }
}