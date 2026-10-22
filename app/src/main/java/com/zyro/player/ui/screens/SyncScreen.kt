package com.zyro.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage // Убедись, что добавил зависимость io.coil-kt:coil-compose в build.gradle
import com.zyro.player.R
import com.zyro.player.ui.components.ZyroError
import com.zyro.player.ui.components.ZyroAuroraBackground

@Composable
fun SyncScreen(
    onBackClick: () -> Unit,
    scIsConnected: Boolean = false,
    scUsername: String? = null,
    scAvatarUrl: String? = null,
    scFollowersCount: Int = 0,
    scTrackCount: Int = 0,
    scIsSyncing: Boolean = false,
    scIsLoading: Boolean = false,
    scSyncMessage: String? = null,
    scError: String? = null,
    onScLoginClick: () -> Unit = {},
    onScLogoutClick: () -> Unit = {},
    onScSyncClick: () -> Unit = {},
    onScEditProfileClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler { onBackClick() }

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
                    top = headerHeight + 16.dp,
                    bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SoundCloudSyncSection(
                        isConnected = scIsConnected,
                        username = scUsername,
                        avatarUrl = scAvatarUrl,
                        followersCount = scFollowersCount,
                        trackCount = scTrackCount,
                        isSyncing = scIsSyncing,
                        isLoading = scIsLoading,
                        syncMessage = scSyncMessage,
                        error = scError,
                        onLoginClick = onScLoginClick,
                        onLogoutClick = onScLogoutClick,
                        onSyncClick = onScSyncClick,
                        onEditProfileClick = onScEditProfileClick
                    )
                }
            }

            ZyroTopScrim(
                modifier = Modifier.align(Alignment.TopCenter),
                height = headerHeight + 16.dp,
                visible = isScrolled
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ZyroIconButton(
                    icon = Icons.Default.ArrowBack,
                    size = 44.dp,
                    iconSize = 24.dp,
                    onClick = onBackClick
                )
                Text(
                    text = "Синхронизация",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZyroTextPrimary
                )
                Spacer(modifier = Modifier.size(44.dp))
            }
        }
    }
}

@Composable
fun SoundCloudSyncSection(
    isConnected: Boolean,
    username: String?,
    avatarUrl: String?,
    followersCount: Int,
    trackCount: Int,
    isSyncing: Boolean,
    isLoading: Boolean,
    syncMessage: String?,
    error: String?,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onSyncClick: () -> Unit,
    onEditProfileClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SyncIcon(isSyncing = isSyncing || isLoading)

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "SoundCloud",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = ZyroTextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isConnected)
                "Ваши лайки, плейлисты и подписки будут синхронизированы с медиатекой."
            else
                "Войдите в аккаунт SoundCloud, чтобы импортировать библиотеку.",
            fontSize = 14.sp,
            color = ZyroTextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isConnected) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Аватарка
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = "Аватар профиля",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(ZyroTextPrimary.copy(alpha = 0.05f))
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Имя и статистика
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = username ?: "Пользователь",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZyroTextPrimary,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.People,
                                        contentDescription = "Подписчики",
                                        tint = ZyroTextSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "$followersCount",
                                        fontSize = 13.sp,
                                        color = ZyroTextSecondary
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Audiotrack,
                                        contentDescription = "Треки",
                                        tint = ZyroTextSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "$trackCount",
                                        fontSize = 13.sp,
                                        color = ZyroTextSecondary
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Аккаунт",
                            fontSize = 14.sp,
                            color = ZyroTextSecondary
                        )
                        Text(
                            text = "Не подключён",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ZyroTextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Статус синхронизации
                when {
                    isSyncing -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            val infiniteTransition = rememberInfiniteTransition(label = "dots")
                            repeat(3) { index ->
                                val alpha by infiniteTransition.animateFloat(
                                    initialValue = 0.3f,
                                    targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(500, delayMillis = index * 120),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "dot_$index"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .graphicsLayer { this.alpha = alpha }
                                        .background(ZyroAccent, CircleShape)
                                )
                                if (index < 2) Spacer(modifier = Modifier.width(6.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Синхронизация...",
                                fontSize = 14.sp,
                                color = ZyroAccent,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    isConnected -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.soundcloud_icon),
                                contentDescription = null,
                                tint = ZyroAccent,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = syncMessage ?: "Готов к синхронизации",
                                fontSize = 14.sp,
                                color = ZyroAccent,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.soundcloud_icon),
                                contentDescription = null,
                                tint = ZyroAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Войдите для доступа к библиотеке",
                                fontSize = 14.sp,
                                color = ZyroTextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (isConnected) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(ZyroAccent.copy(alpha = 0.12f))
                            .border(
                                1.dp,
                                ZyroAccent.copy(alpha = 0.25f),
                                RoundedCornerShape(22.dp)
                            )
                            .clickable(enabled = !isSyncing && !isLoading) {
                                onEditProfileClick()
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = ZyroAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Редактировать профиль",
                            color = ZyroAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val buttonEnabled = !isSyncing && !isLoading
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    if (buttonEnabled) ZyroAccent else ZyroTextPrimary.copy(alpha = 0.06f)
                )
                .border(
                    1.dp,
                    if (buttonEnabled) ZyroAccent else ZyroTextPrimary.copy(alpha = 0.10f),
                    RoundedCornerShape(26.dp)
                )
                .clickable(enabled = buttonEnabled) {
                    if (isConnected) onSyncClick() else onLoginClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        isSyncing || isLoading -> Icons.Default.CloudSync
                        isConnected -> Icons.Default.Sync
                        else -> Icons.AutoMirrored.Filled.Login
                    },
                    contentDescription = null,
                    tint = if (buttonEnabled) Color.White else ZyroTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = when {
                        isSyncing -> "Синхронизируется..."
                        isLoading -> "Подключение..."
                        isConnected -> "Синхронизировать библиотеку"
                        else -> "Войти через SoundCloud"
                    },
                    color = if (buttonEnabled) Color.White else ZyroTextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (isConnected) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(ZyroTextPrimary.copy(alpha = 0.06f))
                    .border(
                        1.dp,
                        ZyroTextPrimary.copy(alpha = 0.10f),
                        RoundedCornerShape(22.dp)
                    )
                    .clickable { onLogoutClick() },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = ZyroTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Выйти из аккаунта",
                        color = ZyroTextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        error?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = it,
                fontSize = 13.sp,
                color = ZyroError,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SyncIcon(isSyncing: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotate")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotate"
    )

    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        ZyroAccent.copy(alpha = 0.20f),
                        ZyroAccent.copy(alpha = 0.05f)
                    )
                )
            )
            .border(
                1.dp,
                ZyroAccent.copy(alpha = 0.25f),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudSync,
            contentDescription = null,
            tint = ZyroAccent,
            modifier = Modifier
                .size(40.dp)
                .then(if (isSyncing) Modifier.rotate(rotation) else Modifier)
        )
    }
}