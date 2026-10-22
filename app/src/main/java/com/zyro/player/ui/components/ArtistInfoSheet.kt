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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.zyro.player.data.remote.UserInfo

/**
 * Шторка «Об артисте».
 *
 * Оформлена на общей дизайн-системе Zyro: та же стеклянная поверхность,
 * ручка и шапка, что у остальных шторок приложения. Высота ограничена,
 * поэтому длинная биография прокручивается внутри, а сама шторка всегда
 * прижата к низу экрана и не «прыгает» в зависимости от контента.
 */
@Composable
fun ArtistInfoSheet(
    visible: Boolean,
    artist: UserInfo,
    location: String,
    description: String,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = visible) { onDismiss() }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val uriHandler = LocalUriHandler.current
    val links = remember(description) { extractLinks(description) }

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
                                text = "Об артисте",
                                color = ZyroTextPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                lineHeight = 27.sp
                            )
                            Text(
                                text = artist.username.ifBlank { "Исполнитель" },
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ArtistIdentityCard(
                            artist = artist,
                            location = location
                        )

                        // Биография
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zyroPlasticGlass(shape = RoundedCornerShape(ZyroShapes.L))
                                .padding(16.dp)
                        ) {
                            SectionTitle(
                                icon = Icons.Outlined.Info,
                                text = "О себе",
                                tint = ZyroAccent
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = description.ifBlank { "Описание отсутствует." },
                                color = if (description.isBlank()) {
                                    ZyroTextSecondary
                                } else {
                                    ZyroTextPrimary
                                },
                                fontSize = 14.sp,
                                lineHeight = 21.sp
                            )
                        }

                        // Ссылки из описания
                        if (links.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .zyroPlasticGlass(shape = RoundedCornerShape(ZyroShapes.L))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SectionTitle(
                                    icon = Icons.Default.Link,
                                    text = "Ссылки",
                                    tint = ZyroCyan
                                )

                                links.forEach { url ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .zyroPlasticGlass(
                                                shape = RoundedCornerShape(ZyroShapes.M),
                                                tint = ZyroCyan
                                            )
                                            .clickable {
                                                runCatching { uriHandler.openUri(url) }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Link,
                                            contentDescription = null,
                                            tint = ZyroCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = url,
                                            color = ZyroCyan,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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

@Composable
private fun ArtistIdentityCard(
    artist: UserInfo,
    location: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zyroPlasticGlass(
                shape = RoundedCornerShape(ZyroShapes.L),
                tint = ZyroAccent
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtistInfoAvatar(
            url = artist.avatarUrl,
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = artist.username.ifBlank { "Исполнитель" },
                color = ZyroTextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ArtistInfoStat(
                    icon = Icons.Default.Person,
                    value = formatCount(artist.followersCount.toLong()),
                    label = "подписчиков",
                    tint = ZyroAccent
                )
                ArtistInfoStat(
                    icon = Icons.Default.PlayArrow,
                    value = formatCount(artist.trackCount.toLong()),
                    label = "треков",
                    tint = ZyroCyan
                )
            }

            if (location.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Place,
                        contentDescription = null,
                        tint = ZyroTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = location,
                        color = ZyroTextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistInfoStat(
    icon: ImageVector,
    value: String,
    label: String,
    tint: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = value,
                color = ZyroTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = label,
                color = ZyroTextSecondary,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun SectionTitle(
    icon: ImageVector,
    text: String,
    tint: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = text,
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ArtistInfoAvatar(
    url: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = ZyroTextSecondary.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxSize(0.48f)
        )
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private val artistLinkRegex =
    Regex("(https?://[\\w\\d:#@%/;\$()~_?\\+-=\\.\\&]+)")

private fun extractLinks(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    return artistLinkRegex.findAll(text)
        .map { it.value.trimEnd('.', ',', ')', ';') }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
}
