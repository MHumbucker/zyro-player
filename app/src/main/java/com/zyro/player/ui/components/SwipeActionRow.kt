package com.zyro.player.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Строка со свайп-действиями.
 *
 * Свайп вправо вызывает [onSwipeRight], свайп влево — [onSwipeLeft].
 * Действие срабатывает только после пересечения порога и сопровождается
 * тактильным откликом. Если строка остаётся в списке, она плавно
 * возвращается на место.
 */
@Composable
fun ZyroSwipeRow(
    onSwipeRight: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    rightIcon: ImageVector = Icons.Default.PlaylistPlay,
    rightLabel: String = "Следующим",
    leftIcon: ImageVector = Icons.Default.Delete,
    leftLabel: String = "Удалить",
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    val maxOffset = with(density) { 136.dp.toPx() }

    val currentRight by rememberUpdatedState(onSwipeRight)
    val currentLeft by rememberUpdatedState(onSwipeLeft)
    val hasRight = onSwipeRight != null
    val hasLeft = onSwipeLeft != null

    var rawOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var armedDirection by remember { mutableIntStateOf(0) }

    // Во время свайпа строка должна двигаться строго за пальцем,
    // поэтому snap; после отпускания — мягкий spring обратно.
    val offset by animateFloatAsState(
        targetValue = rawOffset,
        animationSpec = if (isDragging) snap() else ZyroAnim.SpringSnappy,
        label = "swipe_row_offset"
    )
    val latestOffset by rememberUpdatedState(offset)

    val rightReveal = (offset / threshold).coerceIn(0f, 1f)
    val leftReveal = (-offset / threshold).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ZyroShapes.L))
            .then(
                if (hasRight || hasLeft) {
                    Modifier.pointerInput(hasRight, hasLeft, threshold, maxOffset) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                // Продолжаем с текущего визуального положения,
                                // если пользователь схватил строку во время возврата.
                                rawOffset = latestOffset
                                armedDirection = when {
                                    rawOffset >= threshold -> 1
                                    rawOffset <= -threshold -> -1
                                    else -> 0
                                }
                                isDragging = true
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                rawOffset = (rawOffset + dragAmount).coerceIn(
                                    if (hasLeft) -maxOffset else 0f,
                                    if (hasRight) maxOffset else 0f
                                )

                                val direction = when {
                                    rawOffset >= threshold -> 1
                                    rawOffset <= -threshold -> -1
                                    else -> 0
                                }
                                if (direction != 0 && direction != armedDirection) {
                                    haptics.performHapticFeedback(
                                        HapticFeedbackType.GestureThresholdActivate
                                    )
                                }
                                armedDirection = direction
                            },
                            onDragEnd = {
                                val action = when {
                                    rawOffset >= threshold -> currentRight
                                    rawOffset <= -threshold -> currentLeft
                                    else -> null
                                }
                                isDragging = false
                                rawOffset = 0f
                                if (action != null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    action()
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                rawOffset = 0f
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        if (hasRight) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = rightReveal }
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                ZyroAccentDeep,
                                ZyroAccent
                            )
                        )
                    )
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Icon(
                    imageVector = rightIcon,
                    contentDescription = null,
                    tint = ZyroBackground,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = rightLabel,
                    color = ZyroBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (hasLeft) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = leftReveal }
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                ZyroError,
                                Color(0xFFB93A3A)
                            )
                        )
                    )
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = leftLabel,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = leftIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offset }
        ) {
            content()
        }
    }
}
