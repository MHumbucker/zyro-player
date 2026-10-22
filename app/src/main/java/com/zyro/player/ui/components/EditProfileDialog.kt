package com.zyro.player.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.zyro.player.data.remote.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Редактирование собственного профиля SoundCloud: аватар, баннер, имя,
 * username, описание, город и страна.
 *
 * Картинки отправляются сразу после выбора (как в официальном клиенте),
 * текстовые поля — по кнопке «Сохранить».
 */
@Composable
fun EditProfileDialog(
    user: UserInfo,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (
        username: String,
        firstName: String,
        lastName: String,
        description: String,
        city: String,
        countryCode: String
    ) -> Unit,
    onAvatarPicked: (String) -> Unit,
    onAvatarDeleted: () -> Unit,
    onBannerPicked: (String) -> Unit,
    onBannerDeleted: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var username by remember(user.id) { mutableStateOf(user.username) }
    var firstName by remember(user.id) { mutableStateOf(user.firstName) }
    var lastName by remember(user.id) { mutableStateOf(user.lastName) }
    var description by remember(user.id) { mutableStateOf(user.description) }
    var city by remember(user.id) { mutableStateOf(user.city) }
    var countryCode by remember(user.id) {
        mutableStateOf(
            user.countryCode.ifBlank {
                user.country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT).orEmpty()
            }
        )
    }

    var avatarModel by remember(user.id) {
        mutableStateOf<Any?>(user.avatarUrl.takeIf { it.isNotBlank() })
    }
    var bannerModel by remember(user.id) {
        mutableStateOf<Any?>(user.bannerUrl.takeIf { it.isNotBlank() })
    }
    var hasAvatar by remember(user.id) { mutableStateOf(user.avatarUrl.isNotBlank()) }
    var hasBanner by remember(user.id) { mutableStateOf(user.bannerUrl.isNotBlank()) }

    var localError by remember { mutableStateOf<String?>(null) }
    var showCountryPicker by remember { mutableStateOf(false) }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            avatarModel = uri
            hasAvatar = true
            localError = null
            scope.launch {
                val encoded = withContext(Dispatchers.IO) { encodeProfileImage(context, uri) }
                if (encoded == null) {
                    localError = "Не удалось прочитать изображение"
                } else {
                    onAvatarPicked(encoded)
                }
            }
        }
    }

    val bannerPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            bannerModel = uri
            hasBanner = true
            localError = null
            scope.launch {
                val encoded = withContext(Dispatchers.IO) { encodeProfileImage(context, uri) }
                if (encoded == null) {
                    localError = "Не удалось прочитать изображение"
                } else {
                    onBannerPicked(encoded)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
                .imePadding()
                .zyroPlasticGlass(shape = RoundedCornerShape(28.dp), strong = true)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Редактировать профиль",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = ZyroTextPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, enabled = !isSaving) {
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
                    BannerPicker(
                        model = bannerModel,
                        hasBanner = hasBanner,
                        enabled = !isSaving,
                        onPick = {
                            bannerPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        onDelete = {
                            bannerModel = null
                            hasBanner = false
                            localError = null
                            onBannerDeleted()
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    AvatarPicker(
                        model = avatarModel,
                        username = username,
                        hasAvatar = hasAvatar,
                        enabled = !isSaving,
                        onPick = {
                            avatarPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        onDelete = {
                            avatarModel = null
                            hasAvatar = false
                            localError = null
                            onAvatarDeleted()
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    ProfileTextField(
                        value = username,
                        onValueChange = { username = it.take(50) },
                        label = "Отображаемое имя",
                        singleLine = true
                    )
                    if (user.permalinkUrl.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = user.permalinkUrl.removePrefix("https://").removePrefix("http://"),
                            fontSize = 12.sp,
                            color = ZyroTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Ссылку профиля можно изменить только на сайте SoundCloud",
                            fontSize = 11.sp,
                            color = ZyroTextSecondary.copy(alpha = 0.75f)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProfileTextField(
                            value = firstName,
                            onValueChange = { firstName = it.take(35) },
                            label = "Имя",
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        ProfileTextField(
                            value = lastName,
                            onValueChange = { lastName = it.take(35) },
                            label = "Фамилия",
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    ProfileTextField(
                        value = description,
                        onValueChange = { description = it.take(4000) },
                        label = "О себе",
                        minLines = 3,
                        maxLines = 6
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    ProfileTextField(
                        value = city,
                        onValueChange = { city = it.take(100) },
                        label = "Город",
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    CountryField(
                        countryCode = countryCode,
                        onClick = { showCountryPicker = true },
                        enabled = !isSaving
                    )

                    val visibleError = localError ?: error
                    if (!visibleError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = visibleError,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp
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
                    TextButton(onClick = onDismiss, enabled = !isSaving) {
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
                            localError = null
                            onSave(
                                username.trim(),
                                firstName.trim(),
                                lastName.trim(),
                                description.trim(),
                                city.trim(),
                                countryCode
                            )
                        },
                        enabled = username.isNotBlank() && !isSaving,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ZyroAccent,
                            contentColor = ZyroBackground,
                            disabledContainerColor = ZyroAccent.copy(alpha = 0.28f),
                            disabledContentColor = ZyroBackground.copy(alpha = 0.55f)
                        )
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = ZyroBackground
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isSaving) "Сохраняем..." else "Сохранить",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }

    if (showCountryPicker) {
        CountryPickerDialog(
            selectedCode = countryCode,
            onDismiss = { showCountryPicker = false },
            onSelect = {
                countryCode = it
                showCountryPicker = false
            }
        )
    }
}

@Composable
private fun AvatarPicker(
    model: Any?,
    username: String,
    hasAvatar: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(ZyroSurfaceElevated)
                .border(1.dp, ZyroGlassBorder, CircleShape)
                .clickable(enabled = enabled, onClick = onPick),
            contentAlignment = Alignment.Center
        ) {
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = "Аватар",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = username.take(1).uppercase(Locale.getDefault()),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZyroTextSecondary
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(ZyroAccent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddPhotoAlternate,
                    contentDescription = null,
                    tint = ZyroBackground,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Аватар",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = ZyroTextPrimary
            )
            Text(
                text = "Квадратное фото лучше всего",
                fontSize = 12.sp,
                color = ZyroTextSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPick, enabled = enabled) {
                    Text(
                        text = if (hasAvatar) "Изменить" else "Загрузить",
                        color = ZyroAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (hasAvatar) {
                    TextButton(onClick = onDelete, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = ZyroTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Удалить",
                            color = ZyroTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerPicker(
    model: Any?,
    hasBanner: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ZyroSurfaceElevated)
                .border(1.dp, ZyroGlassBorder, RoundedCornerShape(20.dp))
                .clickable(enabled = enabled, onClick = onPick),
            contentAlignment = Alignment.Center
        ) {
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = "Баннер профиля",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = null,
                        tint = ZyroTextSecondary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Добавить баннер",
                        fontSize = 13.sp,
                        color = ZyroTextSecondary
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onPick, enabled = enabled) {
                Text(
                    text = if (hasBanner) "Заменить баннер" else "Загрузить баннер",
                    color = ZyroAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (hasBanner) {
                TextButton(onClick = onDelete, enabled = enabled) {
                    Text(
                        text = "Удалить",
                        color = ZyroTextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CountryField(
    countryCode: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    val name = countryDisplayName(countryCode)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, ZyroGlassBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = ZyroSurface.copy(alpha = 0.3f),
            contentColor = ZyroTextPrimary
        ),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Public,
            contentDescription = null,
            tint = ZyroTextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = name.ifBlank { "Страна" },
            color = if (name.isBlank()) ZyroTextSecondary else ZyroTextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = ZyroTextSecondary
        )
    }
}

@Composable
private fun ProfileTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = ZyroTextSecondary) },
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

@Composable
private fun CountryPickerDialog(
    selectedCode: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val allCountries = remember {
        Locale.getISOCountries()
            .map { code -> code to countryDisplayName(code) }
            .sortedBy { it.second.lowercase(Locale.getDefault()) }
    }
    val filtered = remember(query, allCountries) {
        val q = query.trim()
        if (q.isBlank()) {
            allCountries
        } else {
            allCountries.filter {
                it.first.equals(q, ignoreCase = true) ||
                    it.second.contains(q, ignoreCase = true)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f)
                .zyroPlasticGlass(shape = RoundedCornerShape(24.dp), strong = true)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Страна",
                        style = MaterialTheme.typography.titleLarge.copy(
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

                ProfileTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Поиск",
                    singleLine = true,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect("") }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Не указывать",
                                fontSize = 15.sp,
                                color = if (selectedCode.isBlank()) ZyroAccent else ZyroTextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    items(filtered, key = { it.first }) { (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (code == selectedCode) {
                                        ZyroAccent.copy(alpha = 0.14f)
                                    } else {
                                        androidx.compose.ui.graphics.Color.Transparent
                                    }
                                )
                                .clickable { onSelect(code) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = name,
                                fontSize = 15.sp,
                                color = ZyroTextPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = code,
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

private fun countryDisplayName(code: String): String {
    if (code.isBlank()) return ""
    return runCatching {
        Locale.Builder().setRegion(code.uppercase(Locale.ROOT)).build()
            .getDisplayCountry(Locale.getDefault())
    }.getOrDefault(code)
}

/**
 * Уменьшает картинку и кодирует её в JPEG/base64 — ровно то, что ожидает
 * `image_data` у мобильных ручек SoundCloud. Большие фото не отправляем:
 * иначе запрос долго висит и может отвалиться по таймауту.
 */
private fun encodeProfileImage(context: Context, uri: Uri, maxDimension: Int = 1600): String? {
    return try {
        val bitmap = decodeSampledBitmap(context, uri, maxDimension) ?: return null
        val output = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
        bitmap.recycle()
        if (!ok) return null
        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun decodeSampledBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
    // ImageDecoder учитывает EXIF-поворот и умеет сразу уменьшать картинку,
    // поэтому селфи с камеры не приезжает в профиль боком.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
        return runCatching {
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val largest = maxOf(width, height)
                if (largest > maxDimension) {
                    val scale = maxDimension.toFloat() / largest
                    decoder.setTargetSize(
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        }.getOrNull()
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null

    val largest = maxOf(decoded.width, decoded.height)
    if (largest <= maxDimension) return decoded
    val scale = maxDimension.toFloat() / largest
    val scaled = Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1),
        true
    )
    if (scaled !== decoded) decoded.recycle()
    return scaled
}
