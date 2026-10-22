package com.zyro.player.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.zyro.player.domain.PlaylistDraft
import java.io.File

/**
 * Единая форма создания и редактирования плейлиста.
 *
 * Для серверных плейлистов показывает приватность, жанр и теги. Для локальных
 * достаточно названия, описания и обложки — [showServerFields] = false.
 */
@Composable
fun PlaylistFormDialog(
    dialogTitle: String,
    initial: PlaylistDraft,
    onDismiss: () -> Unit,
    onSave: (PlaylistDraft) -> Unit,
    existingCoverUrl: String = "",
    showServerFields: Boolean = true,
    confirmLabel: String = "Сохранить"
) {
    var title by remember(initial) { mutableStateOf(initial.title) }
    var description by remember(initial) { mutableStateOf(initial.description) }
    var isPublic by remember(initial) { mutableStateOf(initial.isPublic) }
    var genre by remember(initial) { mutableStateOf(initial.genre) }
    var tags by remember(initial) { mutableStateOf(initial.tagList) }
    var coverUri by remember(initial) { mutableStateOf(initial.coverUri) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) coverUri = uri
    }

    val coverModel: Any? = coverUri ?: coverModelFromPath(existingCoverUrl)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f)
                .imePadding()
                .zyroPlasticGlass(
                    shape = RoundedCornerShape(28.dp),
                    strong = true
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dialogTitle,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = ZyroTextPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть",
                            tint = ZyroTextSecondary
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(top = 8.dp, bottom = 16.dp)
                ) {
                    CoverPicker(
                        model = coverModel,
                        onPick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    PlaylistTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = "Название",
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    PlaylistTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = "Описание (необязательно)",
                        minLines = 3,
                        maxLines = 5
                    )

                    if (showServerFields) {
                        Spacer(modifier = Modifier.height(16.dp))
                        PrivacyRow(
                            isPublic = isPublic,
                            onToggle = { isPublic = it }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        PlaylistTextField(
                            value = genre,
                            onValueChange = { genre = it },
                            label = "Жанр (необязательно)",
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        PlaylistTextField(
                            value = tags,
                            onValueChange = { tags = it },
                            label = "Теги через запятую (необязательно)",
                            placeholder = "house, chill, night",
                            singleLine = true
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Отмена",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = ZyroTextSecondary
                            )
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                PlaylistDraft(
                                    title = title.trim(),
                                    description = description.trim(),
                                    isPublic = isPublic,
                                    genre = genre.trim(),
                                    tagList = tags.trim(),
                                    coverUri = coverUri
                                )
                            )
                        },
                        enabled = title.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ZyroAccent,
                            contentColor = ZyroBackground,
                            disabledContainerColor = ZyroAccent.copy(alpha = 0.28f),
                            disabledContentColor = ZyroBackground.copy(alpha = 0.55f)
                        )
                    ) {
                        Text(
                            text = confirmLabel,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverPicker(
    model: Any?,
    onPick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(ZyroSurfaceElevated)
                .border(1.dp, ZyroGlassBorder, RoundedCornerShape(26.dp))
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center
        ) {
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = "Обложка плейлиста",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = ZyroTextSecondary,
                    modifier = Modifier.size(40.dp)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(ZyroAccent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = ZyroBackground,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onPick) {
            Text(
                text = if (model == null) "Выбрать обложку" else "Заменить обложку",
                color = ZyroAccent,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
private fun PrivacyRow(
    isPublic: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ZyroSurface.copy(alpha = 0.35f))
            .border(1.dp, ZyroGlassBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isPublic) Icons.Default.Public else Icons.Default.Lock,
            contentDescription = null,
            tint = ZyroAccent,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isPublic) "Публичный плейлист" else "Приватный плейлист",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = ZyroTextPrimary
                )
            )
            Text(
                text = if (isPublic) {
                    "Виден всем в SoundCloud"
                } else {
                    "Виден только вам"
                },
                style = MaterialTheme.typography.bodySmall,
                color = ZyroTextSecondary
            )
        }
        Switch(
            checked = isPublic,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ZyroBackground,
                checkedTrackColor = ZyroAccent,
                uncheckedThumbColor = ZyroTextSecondary,
                uncheckedTrackColor = ZyroSurfaceElevated,
                uncheckedBorderColor = ZyroGlassBorder
            )
        )
    }
}

@Composable
private fun PlaylistTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = ZyroTextSecondary) },
        placeholder = {
            if (placeholder.isNotBlank()) {
                Text(placeholder, color = ZyroTextSecondary.copy(alpha = 0.6f))
            }
        },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ZyroAccent,
            unfocusedBorderColor = ZyroGlassBorder,
            focusedTextColor = ZyroTextPrimary,
            unfocusedTextColor = ZyroTextPrimary,
            focusedContainerColor = ZyroSurface.copy(alpha = 0.3f),
            unfocusedContainerColor = ZyroSurface.copy(alpha = 0.3f),
            focusedLabelColor = ZyroAccent,
            unfocusedLabelColor = ZyroTextSecondary,
            cursorColor = ZyroAccent
        )
    )
}

/** Coil понимает file:// URI, но не голый абсолютный путь. */
private fun coverModelFromPath(path: String): Any? {
    if (path.isBlank()) return null
    return if (path.startsWith("/")) File(path) else path
}
