package com.zyro.player.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.zyro.player.R
import com.zyro.player.ui.components.GlassCard
import com.zyro.player.ui.components.SettingsGroupTitle
import com.zyro.player.ui.components.SettingsPreferences
import com.zyro.player.ui.components.SettingsToggleItem
import com.zyro.player.ui.components.ZyroAccent
import com.zyro.player.ui.components.ZyroAuroraBackground
import com.zyro.player.ui.components.ZyroBackground
import com.zyro.player.ui.components.ZyroGlassBorder
import com.zyro.player.ui.components.ZyroShapes
import com.zyro.player.ui.components.ZyroSuccess
import com.zyro.player.ui.components.ZyroSurface
import com.zyro.player.ui.components.ZyroTextPrimary
import com.zyro.player.ui.components.ZyroTextSecondary
import com.zyro.player.ui.theme.ThemeMode
import com.zyro.player.ui.viewmodel.SoundCloudAuthViewModel

private const val TOTAL_STEPS = 4
private const val LAST_STEP = TOTAL_STEPS - 1

/**
 * Полноэкранный онбординг первого запуска (4 шага).
 *
 * Показывается поверх [com.zyro.player.MainAppContent] и не создаёт новую Activity.
 * Настройки пишутся напрямую в [SettingsPreferences] — те же значения, что в `SettingsSheet`.
 * Авторизация SoundCloud переиспользует существующий `SoundCloudAuthScreen` через [onConnectSoundCloud].
 */
@Composable
fun OnboardingScreen(
    scState: SoundCloudAuthViewModel.ScSyncUiState,
    themeMode: ThemeMode,
    onConnectSoundCloud: () -> Unit,
    onSyncLibrary: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val settingsPrefs = remember { SettingsPreferences(context) }

    var step by rememberSaveable { mutableStateOf(0) }
    // Синхронизацию запускаем один раз, только если аккаунт подключили прямо в онбординге.
    var syncTriggered by remember { mutableStateOf(scState.isConnected) }

    // Локальные зеркала настроек, чтобы переключатели сразу писались в SettingsPreferences.
    var hq by remember { mutableStateOf(settingsPrefs.highQualityStream) }
    var gapless by remember { mutableStateOf(settingsPrefs.gaplessPlayback) }
    var crossfade by remember { mutableStateOf(settingsPrefs.crossfadeEnabled) }
    var normalize by remember { mutableStateOf(settingsPrefs.normalizeVolume) }
    var autoDl by remember { mutableStateOf(settingsPrefs.autoDownloadLiked) }
    var hapticsOn by remember { mutableStateOf(settingsPrefs.hapticsEnabled) }

    // Разрешение на уведомления (Android 13+).
    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    fun tick() {
        if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun goNext() {
        if (step < LAST_STEP) step++
    }

    // Первичная синхронизация библиотеки сразу после подключения аккаунта.
    LaunchedEffect(scState.isConnected) {
        if (scState.isConnected && !syncTriggered) {
            syncTriggered = true
            // onAuthCompleted() уже мог запустить синхронизацию — не дублируем её.
            if (!scState.isSyncing && !scState.isLoading) onSyncLibrary()
        }
    }

    BackHandler {
        // Пропустить онбординг нельзя: назад — только на предыдущий шаг,
        // с первого шага выход заблокирован.
        if (step > 0) {
            tick()
            step--
        }
    }

    ZyroAuroraBackground(
        modifier = modifier
            // Онбординг — полноэкранный слой: гасим касания, чтобы они не уходили
            // на главный экран и нижнюю навигацию под ним.
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Онбординг обязательный — кнопки «Пропустить» нет.
            Spacer(modifier = Modifier.height(52.dp))

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    (fadeIn(tween(300)) + slideInHorizontally(tween(300)) { w ->
                        if (forward) w / 4 else -w / 4
                    }).togetherWith(
                        fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { w ->
                            if (forward) -w / 4 else w / 4
                        }
                    )
                },
                label = "onboarding_step",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { currentStep ->
                when (currentStep) {
                    0 -> WelcomeStep()
                    1 -> SoundCloudStep(scState = scState)
                    2 -> PlaybackStep(
                        hq = hq,
                        gapless = gapless,
                        crossfade = crossfade,
                        normalize = normalize,
                        autoDl = autoDl,
                        hapticsOn = hapticsOn,
                        onHq = { hq = it; settingsPrefs.highQualityStream = it },
                        onGapless = { gapless = it; settingsPrefs.gaplessPlayback = it },
                        onCrossfade = { crossfade = it; settingsPrefs.crossfadeEnabled = it },
                        onNormalize = { normalize = it; settingsPrefs.normalizeVolume = it },
                        onAutoDl = { autoDl = it; settingsPrefs.autoDownloadLiked = it },
                        onHaptics = { hapticsOn = it; settingsPrefs.hapticsEnabled = it },
                        onToggle = { tick() }
                    )
                    else -> InterfaceStep(
                        themeMode = themeMode,
                        notifGranted = notifGranted,
                        onThemeModeChange = {
                            tick()
                            onThemeModeChange(it)
                        },
                        onRequestNotifications = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    )
                }
            }

            OnboardingDots(current = step, count = TOTAL_STEPS, modifier = Modifier.padding(top = 12.dp))

            Spacer(modifier = Modifier.height(18.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                when (step) {
                    0 -> {
                        OnboardingPrimaryButton(text = "Начать") {
                            tick()
                            goNext()
                        }
                    }

                    1 -> {
                        if (scState.isConnected) {
                            OnboardingPrimaryButton(text = "Далее") {
                                tick()
                                goNext()
                            }
                        } else {
                            OnboardingPrimaryButton(text = "Подключить SoundCloud") {
                                tick()
                                onConnectSoundCloud()
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            OnboardingSecondaryButton(text = "Продолжить без аккаунта") {
                                tick()
                                goNext()
                            }
                        }
                    }

                    2 -> {
                        OnboardingPrimaryButton(text = "Далее") {
                            tick()
                            goNext()
                        }
                    }

                    else -> {
                        OnboardingPrimaryButton(text = "Готово") {
                            tick()
                            onFinish()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

// ============================================================
// ШАГ 1 — Приветствие
// ============================================================

@Composable
private fun WelcomeStep() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(600),
        label = "welcome_alpha"
    )
    val offsetY = animateDpAsState(
        targetValue = if (shown) 0.dp else 28.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "welcome_offset"
    ).value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .graphicsLayer { this.alpha = alpha }
            .padding(top = offsetY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(128.dp),
            contentAlignment = Alignment.Center
        ) {
            // Мягкое акцентное свечение под логотипом.
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                ZyroAccent.copy(alpha = 0.30f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Image(
                painter = painterResource(id = R.drawable.zyro_logo),
                contentDescription = "Zyro Player",
                modifier = Modifier.size(104.dp)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Zyro Player",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = ZyroTextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Музыка SoundCloud без лишнего. Лайки, плейлисты и персональные волны — в одном месте.",
            fontSize = 15.sp,
            color = ZyroTextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}

// ============================================================
// ШАГ 2 — Подключение SoundCloud
// ============================================================

@Composable
private fun SoundCloudStep(scState: SoundCloudAuthViewModel.ScSyncUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(ZyroAccent.copy(alpha = 0.12f))
                .border(1.dp, ZyroAccent.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.soundcloud_icon),
                contentDescription = null,
                tint = ZyroAccent,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Подключите SoundCloud",
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = ZyroTextPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Импортируем лайки, плейлисты и подписки, чтобы рекомендации сразу стали вашими.",
            fontSize = 14.sp,
            color = ZyroTextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                if (scState.isConnected) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = scState.currentUser?.avatarUrl,
                            contentDescription = "Аватар профиля",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(ZyroTextPrimary.copy(alpha = 0.05f))
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = scState.username ?: "SoundCloud",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZyroTextPrimary,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CloudDone,
                                    contentDescription = null,
                                    tint = ZyroSuccess,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Подключено",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ZyroSuccess
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (scState.isSyncing || scState.isLoading) {
                            CircularProgressIndicator(
                                color = ZyroAccent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Синхронизация библиотеки…",
                                fontSize = 13.sp,
                                color = ZyroAccent
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = ZyroAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = scState.syncMessage ?: "Готово к синхронизации",
                                fontSize = 13.sp,
                                color = ZyroTextSecondary
                            )
                        }
                    }

                    scState.error?.let { err ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = err, fontSize = 12.sp, color = ZyroAccent)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(ZyroShapes.S))
                                .background(ZyroTextPrimary.copy(alpha = 0.06f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = ZyroTextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Аккаунт не подключён",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ZyroTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Поиск и плеер работают и в анонимном режиме",
                                fontSize = 12.sp,
                                color = ZyroTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// ШАГ 3 — Настройки воспроизведения
// ============================================================

@Composable
private fun PlaybackStep(
    hq: Boolean,
    gapless: Boolean,
    crossfade: Boolean,
    normalize: Boolean,
    autoDl: Boolean,
    hapticsOn: Boolean,
    onHq: (Boolean) -> Unit,
    onGapless: (Boolean) -> Unit,
    onCrossfade: (Boolean) -> Unit,
    onNormalize: (Boolean) -> Unit,
    onAutoDl: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Text(
            text = "Настройки воспроизведения",
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = ZyroTextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Все параметры можно изменить позже в настройках.",
            fontSize = 14.sp,
            color = ZyroTextSecondary,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(20.dp))

        val toggles = listOf(
            Triple(Icons.Default.HighQuality, "Высокое качество потока" to "Предпочитать HQ, если доступно", hq),
            Triple(Icons.Default.MusicNote, "Gapless playback" to "Без паузы между треками в очереди", gapless),
            Triple(Icons.Default.Shuffle, "Crossfade" to "Плавный переход между треками", crossfade),
            Triple(Icons.Default.VolumeUp, "Нормализация громкости" to "Выровнять уровень между треками", normalize),
            Triple(Icons.Default.Download, "Авто-загрузка лайков" to "Скачивать трек при добавлении в любимое", autoDl),
            Triple(Icons.Default.Vibration, "Вибрация (haptics)" to "Отклик на play, like, seek и свайпы", hapticsOn)
        )

        toggles.forEachIndexed { index, (icon, texts, checked) ->
            val (title, subtitle) = texts
            SettingsToggleItem(
                icon = icon,
                title = title,
                subtitle = subtitle,
                checked = checked,
                onCheckedChange = { value ->
                    onToggle()
                    when (index) {
                        0 -> onHq(value)
                        1 -> onGapless(value)
                        2 -> onCrossfade(value)
                        3 -> onNormalize(value)
                        4 -> onAutoDl(value)
                        else -> onHaptics(value)
                    }
                }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ============================================================
// ШАГ 4 — Интерфейс, тема и уведомления
// ============================================================

@Composable
private fun InterfaceStep(
    themeMode: ThemeMode,
    notifGranted: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit,
    onRequestNotifications: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Text(
            text = "Интерфейс и уведомления",
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = ZyroTextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Последние штрихи — тема приложения и доступ к уведомлениям плеера.",
            fontSize = 14.sp,
            color = ZyroTextSecondary,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupTitle("Тема оформления")
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ThemeOptionCard(
                icon = Icons.Default.DarkMode,
                label = "Тёмная",
                selected = themeMode == ThemeMode.DARK,
                modifier = Modifier.weight(1f),
                onClick = { onThemeModeChange(ThemeMode.DARK) }
            )
            ThemeOptionCard(
                icon = Icons.Default.LightMode,
                label = "Светлая",
                selected = themeMode == ThemeMode.LIGHT,
                modifier = Modifier.weight(1f),
                onClick = { onThemeModeChange(ThemeMode.LIGHT) }
            )
            ThemeOptionCard(
                icon = Icons.Default.BrightnessAuto,
                label = "Системная",
                selected = themeMode == ThemeMode.SYSTEM,
                modifier = Modifier.weight(1f),
                onClick = { onThemeModeChange(ThemeMode.SYSTEM) }
            )
        }

        Spacer(modifier = Modifier.height(22.dp))
        SettingsGroupTitle("Уведомления")
        Spacer(modifier = Modifier.height(8.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(ZyroShapes.S))
                        .background(ZyroAccent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = ZyroAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Уведомления о воспроизведении",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ZyroTextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (notifGranted) "Разрешены" else "Плеер на экране блокировки и в шторке",
                        fontSize = 12.sp,
                        color = ZyroTextSecondary
                    )
                }

                if (notifGranted) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = ZyroSuccess,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Text(
                        text = "Разрешить",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ZyroAccent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(ZyroAccent.copy(alpha = 0.12f))
                            .border(1.dp, ZyroAccent.copy(alpha = 0.30f), RoundedCornerShape(18.dp))
                            .clickable { onRequestNotifications() }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ThemeOptionCard(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(ZyroShapes.M))
            .background(if (selected) ZyroAccent.copy(alpha = 0.16f) else ZyroSurface.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = if (selected) ZyroAccent.copy(alpha = 0.55f) else ZyroGlassBorder,
                shape = RoundedCornerShape(ZyroShapes.M)
            )
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) ZyroAccent else ZyroTextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) ZyroAccent else ZyroTextSecondary
        )
    }
}

// ============================================================
// ОБЩИЕ ЭЛЕМЕНТЫ
// ============================================================

@Composable
private fun OnboardingDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width = animateDpAsState(
                targetValue = if (selected) 24.dp else 8.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "dot_$index"
            ).value

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(if (selected) ZyroAccent else ZyroTextPrimary.copy(alpha = 0.18f))
            )
        }
    }
}

@Composable
private fun OnboardingPrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(ZyroAccent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun OnboardingSecondaryButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .border(1.dp, ZyroGlassBorder, RoundedCornerShape(25.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = ZyroTextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
