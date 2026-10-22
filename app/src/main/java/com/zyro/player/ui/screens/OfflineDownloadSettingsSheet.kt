package com.zyro.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Настройки скачивания в офлайн: что именно сохранять вместе с аудио.
 *
 * Экран открывается из [SettingsSheet] и использует ту же дизайн-систему
 * (аврора-фон, «пластиковые» карточки, крупные тумблеры).
 */
@Composable
fun OfflineDownloadSettingsSheet(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBackClick() }

    val context = LocalContext.current
    val prefs = remember { SettingsPreferences(context) }
    val haptic = LocalHapticFeedback.current

    var artwork by remember { mutableStateOf(prefs.offlineArtwork) }
    var lyrics by remember { mutableStateOf(prefs.offlineLyrics) }
    var comments by remember { mutableStateOf(prefs.offlineComments) }
    var maxComments by remember { mutableIntStateOf(prefs.offlineMaxComments) }

    fun tick() {
        if (prefs.hapticsEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    ZyroAuroraBackground(modifier = modifier) {
        var headerHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        val listState = rememberLazyListState()
        val isScrolled by remember {
            derivedStateOf {
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = headerHeight + 8.dp,
                    bottom = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { SettingsGroupTitle("Вместе с треком") }

                item {
                    SettingsToggleItem(
                        icon = Icons.Default.Image,
                        title = "Обложки",
                        subtitle = "Кешировать обложку, чтобы она была видна без сети",
                        checked = artwork,
                        onCheckedChange = { value ->
                            tick()
                            artwork = value
                            prefs.offlineArtwork = value
                        }
                    )
                }

                item {
                    SettingsToggleItem(
                        icon = Icons.Default.Subtitles,
                        title = "Тексты песен",
                        subtitle = "Искать и сохранять текст при загрузке трека",
                        checked = lyrics,
                        onCheckedChange = { value ->
                            tick()
                            lyrics = value
                            prefs.offlineLyrics = value
                        }
                    )
                }

                item {
                    SettingsToggleItem(
                        icon = Icons.Default.ChatBubbleOutline,
                        title = "Комментарии",
                        subtitle = "Сохранять комментарии для просмотра без сети",
                        checked = comments,
                        onCheckedChange = { value ->
                            tick()
                            comments = value
                            prefs.offlineComments = value
                        }
                    )
                }

                if (comments) {
                    item {
                        CommentsLimitCard(
                            value = maxComments,
                            onValueChange = { value ->
                                tick()
                                maxComments = value
                                prefs.offlineMaxComments = value
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(6.dp)) }
                item { SettingsGroupTitle("Как это работает") }

                item {
                    OfflineSettingsNote(
                        title = "Только новые загрузки",
                        text = "Настройки применяются к трекам, которые вы скачаете после " +
                            "изменения. Уже сохранённые треки не затрагиваются — при " +
                            "необходимости скачайте их заново."
                    )
                }

                item {
                    OfflineSettingsNote(
                        title = "Аудио — всегда",
                        text = "Сам трек скачивается в любом случае. Тумблеры выше отвечают " +
                            "только за дополнительные материалы. Если сети нет, они просто " +
                            "не добавятся, а загрузка продолжится."
                    )
                }

                item { Spacer(modifier = Modifier.height(100.dp)) }
            }

            ZyroTopScrim(
                modifier = Modifier.align(Alignment.TopCenter),
                height = headerHeight + 16.dp,
                visible = isScrolled
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                verticalAlignment = Alignment.CenterVertically
            ) {
                ZyroIconButton(
                    icon = Icons.Default.ArrowBack,
                    size = 44.dp,
                    iconSize = 24.dp,
                    contentDescription = "Назад",
                    onClick = onBackClick
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Скачивание в офлайн",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZyroTextPrimary
                    )
                    Text(
                        text = "Что сохранять вместе с треком",
                        fontSize = 12.sp,
                        color = ZyroTextSecondary
                    )
                }
            }
        }
    }
}

/**
 * Карточка лимита комментариев: шаг ±5, диапазон 5..200.
 */
@Composable
private fun CommentsLimitCard(
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zyroPlasticGlass(shape = RoundedCornerShape(ZyroShapes.L))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(ZyroShapes.S))
                .background(ZyroAccent.copy(alpha = 0.14f))
                .border(
                    1.dp,
                    ZyroAccent.copy(alpha = 0.20f),
                    RoundedCornerShape(ZyroShapes.S)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = ZyroAccent,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Максимум на трек",
                color = ZyroTextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "До $value комментариев",
                color = ZyroTextSecondary,
                fontSize = 12.sp
            )
        }

        val step = SettingsPreferences.COMMENT_STEP
        StepperButton(
            icon = Icons.Default.Remove,
            enabled = value > SettingsPreferences.MIN_MAX_COMMENTS,
            contentDescription = "Меньше комментариев",
            onClick = {
                onValueChange(
                    (value - step).coerceAtLeast(SettingsPreferences.MIN_MAX_COMMENTS)
                )
            }
        )
        Text(
            text = "$value",
            color = ZyroTextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp)
        )
        StepperButton(
            icon = Icons.Default.Add,
            enabled = value < SettingsPreferences.MAX_MAX_COMMENTS,
            contentDescription = "Больше комментариев",
            onClick = {
                onValueChange(
                    (value + step).coerceAtMost(SettingsPreferences.MAX_MAX_COMMENTS)
                )
            }
        )
    }
}

@Composable
private fun StepperButton(
    icon: ImageVector,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    val tint = if (enabled) ZyroAccent else ZyroTextSecondary.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(
                if (enabled) ZyroAccent.copy(alpha = 0.14f)
                else ZyroSurfaceElevated.copy(alpha = 0.35f)
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun OfflineSettingsNote(
    title: String,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zyroPlasticGlass(shape = RoundedCornerShape(ZyroShapes.L))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(ZyroViolet.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = ZyroViolet,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                color = ZyroTextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Text(
                text = text,
                color = ZyroTextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}
