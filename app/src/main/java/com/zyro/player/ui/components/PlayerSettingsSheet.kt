package com.zyro.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.player.player.EqualizerState

private data class SpeedOption(val value: Float, val label: String)

private val speedOptions = listOf(
    SpeedOption(0.5f, "0,5×"),
    SpeedOption(0.75f, "0,75×"),
    SpeedOption(1f, "1×"),
    SpeedOption(1.25f, "1,25×"),
    SpeedOption(1.5f, "1,5×"),
    SpeedOption(2f, "2×")
)

/**
 * Шторка настроек звука из полноэкранного плеера.
 *
 * Сейчас здесь скорость воспроизведения и системный эквалайзер устройства
 * (включение + пресеты). Оформление — общая «пластиковая» стеклянная шторка.
 */
@Composable
fun PlayerSettingsSheet(
    visible: Boolean,
    trackTitle: String,
    playbackSpeed: Float,
    equalizerState: EqualizerState,
    onDismiss: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onEqualizerToggle: (Boolean) -> Unit,
    onPresetSelect: (Int) -> Unit
) {
    BackHandler(enabled = visible) { onDismiss() }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    AnimatedVisibility(
        visible = visible,
        enter = ZyroScrimEnter,
        exit = ZyroScrimExit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ZyroScrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(
                    animationSpec = tween(ZyroAnim.Medium, easing = FastOutSlowInEasing)
                ) { it } + fadeIn(tween(ZyroAnim.Fast)),
                exit = slideOutVertically(
                    animationSpec = tween(220, easing = FastOutLinearInEasing)
                ) { it } + fadeOut(tween(160))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = screenHeight * 0.86f)
                        .zyroPlasticGlass(
                            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                            strong = true
                        )
                        // Клик по самой шторке не должен её закрывать.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent()
                            }
                        }
                        .navigationBarsPadding()
                ) {
                    // Ручка-индикатор
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(ZyroTextSecondary.copy(alpha = 0.28f))
                        )
                    }

                    // Шапка
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Настройки звука",
                                color = ZyroTextPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                lineHeight = 27.sp
                            )
                            Text(
                                text = trackTitle,
                                color = ZyroTextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        ZyroIconButton(
                            icon = Icons.Default.Close,
                            onClick = onDismiss,
                            size = 40.dp,
                            iconSize = 20.dp,
                            contentDescription = "Закрыть"
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                            .padding(top = 10.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // === Скорость ===
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zyroPlasticGlass(shape = RoundedCornerShape(ZyroShapes.L))
                                .padding(16.dp)
                        ) {
                            SettingsSectionHeader(
                                icon = Icons.Default.FastForward,
                                title = "Скорость",
                                tint = ZyroCyan,
                                trailing = speedOptions
                                    .firstOrNull { kotlin.math.abs(it.value - playbackSpeed) < 0.01f }
                                    ?.label
                                    ?: "${playbackSpeed}×"
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                speedOptions.forEach { option ->
                                    val selected =
                                        kotlin.math.abs(option.value - playbackSpeed) < 0.01f
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(ZyroShapes.S))
                                            .background(
                                                if (selected) {
                                                    ZyroAccent
                                                } else {
                                                    Color.White.copy(alpha = 0.06f)
                                                }
                                            )
                                            .clickable { onSpeedChange(option.value) }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = option.label,
                                            color = if (selected) Color.White else ZyroTextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Скорость действует на все треки и сохраняется после перезапуска.",
                                color = ZyroTextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }

                        // === Эквалайзер ===
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zyroPlasticGlass(shape = RoundedCornerShape(ZyroShapes.L))
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(ZyroAccent.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = ZyroAccent,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Эквалайзер",
                                        color = ZyroTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = when {
                                            equalizerState.available -> {
                                                if (equalizerState.enabled) "Включён" else "Выключен"
                                            }
                                            !equalizerState.checked ->
                                                "Доступен после начала воспроизведения"
                                            else -> "Недоступен на устройстве"
                                        },
                                        color = ZyroTextSecondary,
                                        fontSize = 11.sp
                                    )
                                }

                                Switch(
                                    checked = equalizerState.enabled,
                                    onCheckedChange = onEqualizerToggle,
                                    enabled = equalizerState.available,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = ZyroAccent,
                                        uncheckedThumbColor = ZyroTextSecondary,
                                        uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                                        uncheckedBorderColor = Color.White.copy(alpha = 0.18f)
                                    )
                                )
                            }

                            if (equalizerState.available) {
                                if (equalizerState.presets.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "Пресеты",
                                        color = ZyroTextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        equalizerState.presets.forEach { preset ->
                                            val selected =
                                                preset.index == equalizerState.selectedPreset
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .background(
                                                        if (selected) {
                                                            ZyroAccent.copy(alpha = 0.24f)
                                                        } else {
                                                            Color.White.copy(alpha = 0.05f)
                                                        }
                                                    )
                                                    .border(
                                                        width = 1.dp,
                                                        color = if (selected) {
                                                            ZyroAccent.copy(alpha = 0.75f)
                                                        } else {
                                                            Color.White.copy(alpha = 0.08f)
                                                        },
                                                        shape = RoundedCornerShape(50)
                                                    )
                                                    .clickable {
                                                        if (!equalizerState.enabled) {
                                                            onEqualizerToggle(true)
                                                        }
                                                        onPresetSelect(preset.index)
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                            ) {
                                                Text(
                                                    text = localizePresetName(preset.name),
                                                    color = if (selected) {
                                                        ZyroAccent
                                                    } else {
                                                        ZyroTextPrimary
                                                    },
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Выберите пресет или включите эквалайзер — изменения применяются сразу.",
                                        color = ZyroTextSecondary,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                } else {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Пресеты эквалайзера недоступны на этом устройстве.",
                                        color = ZyroTextSecondary,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = if (!equalizerState.checked) {
                                        "Эквалайзер подключится, когда начнётся воспроизведение."
                                    } else {
                                        "Системный эквалайзер не поддерживается этим устройством."
                                    },
                                    color = ZyroTextSecondary,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    icon: ImageVector,
    title: String,
    tint: Color,
    trailing: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = title,
            color = ZyroTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = trailing,
                color = tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Локализует стандартные названия пресетов Android Equalizer. */
private fun localizePresetName(name: String): String = when (name.trim().lowercase()) {
    "normal" -> "Обычный"
    "classical" -> "Классика"
    "dance" -> "Танцы"
    "flat" -> "Плоский"
    "folk" -> "Фолк"
    "heavy metal" -> "Метал"
    "hip hop" -> "Хип-хоп"
    "jazz" -> "Джаз"
    "pop" -> "Поп"
    "rock" -> "Рок"
    else -> name
}
