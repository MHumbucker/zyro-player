package com.zyro.player.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zyro.player.R
import java.io.File

val Jura = FontFamily(
    Font(R.font.jura_light, FontWeight.Light),
    Font(R.font.jura_regular, FontWeight.Normal),
    Font(R.font.jura_medium, FontWeight.Medium),
    Font(R.font.jura_semibold, FontWeight.SemiBold),
    Font(R.font.jura_bold, FontWeight.Bold)
)

/**
 * Типографика приложения с подставляемым семейством шрифтов.
 *
 * По умолчанию используется системный шрифт Android ([FontFamily.Default]);
 * фирменный [Jura] и пользовательский шрифт подставляются через `ZyroPlayerTheme`.
 */
fun zyroTypography(family: FontFamily): Typography = Typography(

    displayLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.3).sp
    ),

    displayMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.2).sp
    ),

    displaySmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),

    headlineLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),

    headlineMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),

    headlineSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),

    titleLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),

    titleMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),

    titleSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),

    bodyLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),

    bodyMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),

    bodySmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp
    ),

    labelLarge = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),

    labelMedium = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),

    labelSmall = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp
    )
)

/** Типографика по умолчанию (системный шрифт) — для превью и тестов. */
val Typography: Typography = zyroTypography(FontFamily.Default)

/**
 * Возвращает семейство шрифтов для текущего выбора пользователя.
 * Пользовательский шрифт читается из внутреннего файла (см. [saveCustomFont]).
 */
@Composable
fun rememberZyroFontFamily(mode: FontMode, customFontUri: String?): FontFamily {
    val context = LocalContext.current
    return when (mode) {
        FontMode.SYSTEM -> FontFamily.Default
        FontMode.JURA -> Jura
        FontMode.CUSTOM -> remember(customFontUri) {
            loadCustomFontFamily(context) ?: FontFamily.Default
        }
    }
}

private const val CUSTOM_FONT_FILE = "custom_ui_font"

/**
 * Копирует выбранный пользователем файл во внутреннее хранилище и проверяет,
 * что Android умеет его читать как шрифт.
 *
 * Копия нужна, чтобы шрифт переживал перезапуск без persistable-разрешения на URI.
 */
fun saveCustomFont(context: Context, uri: Uri): Boolean = runCatching {
    val target = File(context.filesDir, CUSTOM_FONT_FILE)
    val input = context.contentResolver.openInputStream(uri) ?: return false
    input.use { source ->
        target.outputStream().use { destination -> source.copyTo(destination) }
    }
    // Проверяем, что файл действительно является шрифтом.
    Typeface.createFromFile(target)
    true
}.getOrDefault(false)

/** Загружает ранее сохранённый пользовательский шрифт (или null, если его нет). */
fun loadCustomFontFamily(context: Context): FontFamily? = runCatching {
    val file = File(context.filesDir, CUSTOM_FONT_FILE)
    if (!file.exists()) return null
    FontFamily(Typeface.createFromFile(file))
}.getOrNull()

/** Отображаемое имя выбранного файла шрифта. */
fun customFontDisplayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "Свой шрифт"
}
