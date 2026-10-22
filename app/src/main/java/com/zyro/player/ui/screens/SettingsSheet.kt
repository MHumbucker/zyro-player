package com.zyro.player.ui.components

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyro.player.ui.theme.FontMode
import com.zyro.player.ui.theme.customFontDisplayName
import com.zyro.player.ui.theme.saveCustomFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsSheet(
    onCloseClick: () -> Unit,
    onNavigateToSync: () -> Unit,
    onNavigateToOfflineSettings: () -> Unit = {},
    onClearCache: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    /** Сколько треков сейчас лежит в офлайн-кеше (для подписи и проверки «есть что удалять»). */
    offlineTrackCount: Int = 0,
    /** Полная очистка скачанных офлайн-треков (после подтверждения). */
    onClearOffline: () -> Unit = {},
    onRestartOnboarding: () -> Unit = {},
    fontMode: FontMode = FontMode.SYSTEM,
    customFontName: String? = null,
    onFontModeChange: (FontMode) -> Unit = {},
    onCustomFontPicked: (uri: String, displayName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    BackHandler { onCloseClick() }

    val context = LocalContext.current
    val prefs = remember { SettingsPreferences(context) }
    val haptic = LocalHapticFeedback.current

    var crossfade by remember { mutableStateOf(prefs.crossfadeEnabled) }
    var hapticsOn by remember { mutableStateOf(prefs.hapticsEnabled) }
    var hq by remember { mutableStateOf(prefs.highQualityStream) }
    var autoDl by remember { mutableStateOf(prefs.autoDownloadLiked) }
    var gapless by remember { mutableStateOf(prefs.gaplessPlayback) }
    var normalize by remember { mutableStateOf(prefs.normalizeVolume) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showClearOfflineConfirm by remember { mutableStateOf(false) }

    fun tick() {
        if (hapticsOn) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Выбор пользовательского файла шрифта (.ttf/.otf) через системный пикер.
    val scope = rememberCoroutineScope()
    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val displayName = customFontDisplayName(context, uri)
                val saved = withContext(Dispatchers.IO) { saveCustomFont(context, uri) }
                if (saved) {
                    tick()
                    onCustomFontPicked(uri.toString(), displayName)
                } else {
                    Toast.makeText(
                        context,
                        "Не удалось прочитать шрифт. Выберите файл .ttf или .otf",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
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
                item { SettingsGroupTitle("Аккаунт и данные") }

                item {
                    SettingsItem(
                        icon = Icons.Default.CloudSync,
                        iconTint = ZyroAccent,
                        title = "Синхронизация SoundCloud",
                        subtitle = "Библиотека, плейлисты и подписки",
                        onClick = {
                            tick()
                            onNavigateToSync()
                        }
                    )
                }

                item {
                    SettingsItem(
                        icon = Icons.Default.Storage,
                        iconTint = ZyroTextSecondary,
                        title = "Очистить кэш изображений",
                        subtitle = "Coil / обложки — освободить место",
                        onClick = {
                            tick()
                            onClearCache()
                        }
                    )
                }

                item {
                    SettingsItem(
                        icon = Icons.Default.DeleteOutline,
                        iconTint = ZyroError,
                        title = "Очистить историю прослушиваний",
                        subtitle = "Влияет на персональные рекомендации",
                        onClick = {
                            tick()
                            onClearHistory()
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
                item { SettingsGroupTitle("Офлайн") }

                item {
                    SettingsItem(
                        icon = Icons.Default.Download,
                        iconTint = ZyroAccent,
                        title = "Скачивание в офлайн",
                        subtitle = run {
                            val parts = buildList {
                                if (prefs.offlineArtwork) add("обложки")
                                if (prefs.offlineLyrics) add("тексты")
                                if (prefs.offlineComments) {
                                    add("комментарии до ${prefs.offlineMaxComments}")
                                }
                            }
                            if (parts.isEmpty()) "Только аудио"
                            else parts.joinToString(", ").replaceFirstChar { it.uppercase() }
                        },
                        onClick = {
                            tick()
                            onNavigateToOfflineSettings()
                        }
                    )
                }

                item {
                    SettingsItem(
                        icon = Icons.Default.DownloadDone,
                        iconTint = ZyroError,
                        title = "Удалить все офлайн-треки",
                        subtitle = if (offlineTrackCount > 0) {
                            "На устройстве: $offlineTrackCount трек(ов)"
                        } else {
                            "Офлайн-кеш пуст"
                        },
                        onClick = {
                            if (offlineTrackCount > 0) {
                                tick()
                                showClearOfflineConfirm = true
                            }
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
                item { SettingsGroupTitle("Воспроизведение") }

                item {
                    SettingsToggleItem(
                        icon = Icons.Default.MusicNote,
                        title = "Высокое качество потока",
                        subtitle = "Предпочитать HQ, если доступно",
                        checked = hq,
                        onCheckedChange = {
                            tick()
                            hq = it
                            prefs.highQualityStream = it
                        }
                    )
                }

                item {
                    SettingsToggleItem(
                        icon = Icons.Default.MusicNote,
                        title = "Gapless playback",
                        subtitle = "Без паузы между треками в очереди",
                        checked = gapless,
                        onCheckedChange = {
                            tick()
                            gapless = it
                            prefs.gaplessPlayback = it
                        }
                    )
                }

                item {
                    SettingsToggleItem(
                        icon = Icons.Default.MusicNote,
                        title = "Crossfade",
                        subtitle = "Плавный переход между треками",
                        checked = crossfade,
                        onCheckedChange = {
                            tick()
                            crossfade = it
                            prefs.crossfadeEnabled = it
                        }
                    )
                }

                item {
                    SettingsToggleItem(
                        icon = Icons.Default.VolumeUp,
                        title = "Нормализация громкости",
                        subtitle = "Выровнять уровень между треками",
                        checked = normalize,
                        onCheckedChange = {
                            tick()
                            normalize = it
                            prefs.normalizeVolume = it
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
                item { SettingsGroupTitle("Библиотека и офлайн") }

                item {
                    SettingsToggleItem(
                        icon = Icons.Default.Download,
                        title = "Авто-загрузка лайков",
                        subtitle = "Скачивать трек при добавлении в любимое",
                        checked = autoDl,
                        onCheckedChange = {
                            tick()
                            autoDl = it
                            prefs.autoDownloadLiked = it
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
                item { SettingsGroupTitle("Интерфейс") }

                item {
                    SettingsItem(
                        icon = Icons.Default.Notifications,
                        iconTint = ZyroAccent,
                        title = "Уведомления",
                        subtitle = "Разрешить плеер в шторке (нужно для Volumee и других)",
                        onClick = {
                            tick()
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            context.startActivity(intent)
                        }
                    )
                }

                item {
                    SettingsToggleItem(
                        icon = Icons.Default.Vibration,
                        title = "Вибрация (haptics)",
                        subtitle = "Отклик на play, like, seek и свайпы",
                        checked = hapticsOn,
                        onCheckedChange = {
                            hapticsOn = it
                            prefs.hapticsEnabled = it
                            if (it) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    )
                }

                item {
                    SettingsItem(
                        icon = Icons.Default.TextFields,
                        iconTint = ZyroAccent,
                        title = "Шрифт",
                        subtitle = when (fontMode) {
                            FontMode.SYSTEM -> "Системный"
                            FontMode.JURA -> "Jura"
                            FontMode.CUSTOM -> customFontName ?: "Свой шрифт"
                        },
                        onClick = {
                            tick()
                            showFontDialog = true
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
                item { SettingsGroupTitle("О приложении") }

                item {
                    SettingsItem(
                        icon = Icons.Default.AutoAwesome,
                        iconTint = ZyroAccent,
                        title = "Пройти знакомство снова",
                        subtitle = "Показать онбординг первого запуска",
                        onClick = {
                            tick()
                            onRestartOnboarding()
                        }
                    )
                }

                item {
                    SettingsItem(
                        icon = Icons.Default.Info,
                        iconTint = ZyroTextSecondary,
                        title = "Zyro Player",
                        subtitle = "Версия 1.0.0 · SoundCloud client",
                        showArrow = false,
                        onClick = {}
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
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Настройки",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZyroTextPrimary
                )
                ZyroIconButton(
                    icon = Icons.Default.Close,
                    size = 44.dp,
                    iconSize = 22.dp,
                    onClick = onCloseClick,
                    contentDescription = "Закрыть"
                )
            }
        }

        if (showFontDialog) {
            FontPickerDialog(
                fontMode = fontMode,
                customFontName = customFontName,
                onDismiss = { showFontDialog = false },
                onSelect = { mode ->
                    tick()
                    onFontModeChange(mode)
                },
                onPickFile = { fontPicker.launch(arrayOf("*/*")) }
            )
        }

        if (showClearOfflineConfirm) {
            AlertDialog(
                onDismissRequest = { showClearOfflineConfirm = false },
                containerColor = ZyroSurface,
                titleContentColor = ZyroTextPrimary,
                textContentColor = ZyroTextSecondary,
                title = {
                    Text(
                        text = "Удалить все офлайн-треки?",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Будут удалены все скачанные файлы " +
                            "($offlineTrackCount шт.) и списки офлайн-коллекций. " +
                            "Треки из «Любимых» и плейлистов останутся в библиотеке, " +
                            "но слушать их без интернета будет нельзя. " +
                            "Действие нельзя отменить."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            tick()
                            showClearOfflineConfirm = false
                            onClearOffline()
                        }
                    ) {
                        Text(
                            text = "Удалить",
                            color = ZyroError,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearOfflineConfirm = false }) {
                        Text(text = "Отмена", color = ZyroTextSecondary)
                    }
                }
            )
        }
    }
}

// ============================================================
// Выбор шрифта интерфейса
// ============================================================

@Composable
private fun FontPickerDialog(
    fontMode: FontMode,
    customFontName: String?,
    onDismiss: () -> Unit,
    onSelect: (FontMode) -> Unit,
    onPickFile: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZyroSurface,
        titleContentColor = ZyroTextPrimary,
        textContentColor = ZyroTextSecondary,
        title = {
            Text(
                text = "Шрифт интерфейса",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FontOptionRow(
                    title = "Системный",
                    subtitle = "Стандартный шрифт Android",
                    selected = fontMode == FontMode.SYSTEM,
                    onClick = { onSelect(FontMode.SYSTEM) }
                )
                FontOptionRow(
                    title = "Jura",
                    subtitle = "Фирменный шрифт Zyro",
                    selected = fontMode == FontMode.JURA,
                    onClick = { onSelect(FontMode.JURA) }
                )
                FontOptionRow(
                    title = "Свой шрифт",
                    subtitle = customFontName ?: "Файл .ttf или .otf",
                    selected = fontMode == FontMode.CUSTOM && customFontName != null,
                    onClick = {
                        if (customFontName != null) onSelect(FontMode.CUSTOM) else onPickFile()
                    }
                )

                if (customFontName != null) {
                    TextButton(onClick = onPickFile) {
                        Text(
                            text = "Выбрать другой файл…",
                            color = ZyroAccent,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Готово",
                    color = ZyroAccent,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

@Composable
private fun FontOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZyroShapes.M))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = ZyroTextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Text(
                text = subtitle,
                color = ZyroTextSecondary,
                fontSize = 12.sp
            )
        }

        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = ZyroAccent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun SettingsGroupTitle(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = ZyroTextSecondary,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp, start = 4.dp)
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    iconTint: Color = ZyroTextPrimary,
    title: String,
    subtitle: String,
    showArrow: Boolean = true,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zyroPressEffect(interactionSource)
            .zyroPlasticGlass(shape = RoundedCornerShape(ZyroShapes.L))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(ZyroShapes.S))
                .background(iconTint.copy(alpha = 0.14f))
                .border(
                    1.dp,
                    iconTint.copy(alpha = 0.20f),
                    RoundedCornerShape(ZyroShapes.S)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = ZyroTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, color = ZyroTextSecondary, fontSize = 12.sp)
        }
        if (showArrow) {
            Text("›", color = ZyroTextSecondary, fontSize = 22.sp)
        }
    }
}

@Composable
fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zyroPlasticGlass(shape = RoundedCornerShape(ZyroShapes.L))
            .padding(horizontal = 14.dp, vertical = 10.dp),
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
            Icon(icon, contentDescription = null, tint = ZyroAccent, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = ZyroTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, color = ZyroTextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ZyroAccent,
                uncheckedThumbColor = ZyroTextSecondary,
                uncheckedTrackColor = ZyroSurfaceElevated
            )
        )
    }
}
