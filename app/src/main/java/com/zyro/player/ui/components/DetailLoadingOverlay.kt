package com.zyro.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * Полноэкранный оверлей загрузки деталей (артист / альбом / плейлист).
 * Показывать поверх UI, пока `isDetailLoading == true`.
 */
@Composable
fun DetailLoadingOverlay(
    visible: Boolean,
    label: String = "Загрузка…",
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = ZyroAnim.TweenFast),
        exit = fadeOut(animationSpec = ZyroAnim.TweenFast),
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ZyroBackground.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(ZyroShapes.XL))
                    .background(ZyroSurfaceElevated.copy(alpha = 0.95f))
                    .border(
                        width = 1.dp,
                        color = ZyroGlassBorder,
                        shape = RoundedCornerShape(ZyroShapes.XL)
                    )
                    .padding(horizontal = 36.dp, vertical = 28.dp)
            ) {
                CircularProgressIndicator(
                    color = ZyroAccent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = label.ifBlank { "Загрузка…" },
                    color = ZyroTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Секунду…",
                    color = ZyroTextSecondary,
                    fontSize = 13.sp
                )
            }
        }
    }
}
