package com.zyro.player.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

// ============================================================
// PLASTIC GLASS DESIGN TOKENS
// ============================================================

// Глубокий «чернильный» фон с холодным подтоном.
val ZyroBackground = Color(0xFF070A0F)
val ZyroBackgroundTop = Color(0xFF0B1119)
val ZyroSurface = Color(0xFF161B24)
val ZyroSurfaceElevated = Color(0xFF1D2430)
val ZyroControlCardBg = Color(0xFF161B24)
val ZyroDialogBackground = Color(0xFF121722)

// Тёплый фирменный акцент + поддерживающие оттенки для градиентов.
val ZyroAccent = Color(0xFFFF8A3D)
val ZyroAccentSoft = Color(0xFFFFB067)
val ZyroAccentDeep = Color(0xFFE85D04)
val ZyroViolet = Color(0xFF8B5CF6)
val ZyroCyan = Color(0xFF4CC9F0)
val ZyroMint = Color(0xFF34D399)

val ZyroTextPrimary = Color(0xFFF4F6FB)
val ZyroTextSecondary = Color(0xFFF4F6FB).copy(alpha = 0.58f)

val ZyroGlassBorder = Color.White.copy(alpha = 0.10f)
val ZyroGlassBorderStrong = Color.White.copy(alpha = 0.18f)
val GlassAlphaLight = 0.12f
val GlassAlphaMedium = 0.07f
val GlassAlphaDark = 0.035f
val BorderAlpha = 0.10f

/** Destructive / error actions */
val ZyroError = Color(0xFFFF6B6B)
/** Success / offline-ready */
val ZyroSuccess = Color(0xFF34D399)
/** Unified scrim for bottom sheets & dialogs */
val ZyroScrim = Color(0xFF03050A).copy(alpha = 0.62f)

/** Shared corner radii — мягкие «пластиковые» скругления. */
object ZyroShapes {
    val XS = 10.dp
    val S = 14.dp
    val M = 18.dp
    val L = 24.dp
    val XL = 30.dp
    val XXL = 36.dp
}

/**
 * Верхняя градиентная подложка под плавающие прозрачные шапки.
 *
 * Работает так же, как градиент над нижней навигацией: у верха непрозрачный
 * фон, ниже он растворяется, поэтому контент, ушедший под шапку, затемняется
 * и не спорит с её кнопками и заголовком.
 */
@Composable
fun ZyroTopScrim(
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
    visible: Boolean = true
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "top_scrim_alpha"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                if (alpha > 0.001f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                ZyroBackground.copy(alpha = 0.98f * alpha),
                                ZyroBackground.copy(alpha = 0.90f * alpha),
                                ZyroBackground.copy(alpha = 0.48f * alpha),
                                Color.Transparent
                            )
                        )
                    )
                }
            }
    )
}

/**
 * Компактный бейдж «GO+» для треков каталога SoundCloud Go+.
 *
 * Тёплый фирменный градиент читается и на стеклянных карточках, и поверх
 * обложки, поэтому один и тот же бейдж используется во всех списках и плеере.
 */
@Composable
fun GoPlusBadge(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ZyroShapes.XS))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(ZyroAccent, ZyroAccentDeep)
                )
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "GO+",
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.4.sp,
            maxLines = 1
        )
    }
}

/**
 * Универсальная «пластиковая» стеклянная поверхность:
 * полупрозрачный градиент, светящаяся кромка и спекуляр-блик сверху.
 */
fun Modifier.zyroPlasticGlass(
    shape: Shape = RoundedCornerShape(ZyroShapes.XL),
    tint: Color = Color.Transparent,
    strong: Boolean = false
): Modifier {
    val topAlpha = if (strong) 0.16f else 0.11f
    val bottomAlpha = if (strong) 0.07f else 0.04f
    val middleColor =
        if (tint == Color.Transparent) Color.White.copy(alpha = 0.02f)
        else tint.copy(alpha = 0.14f)
    // Плотная тёмная основа: поверхности не «просвечивают» фоном.
    val baseFill = ZyroSurface.copy(alpha = if (strong) 0.82f else 0.68f)

    return this
        .clip(shape)
        .background(baseFill)
        .background(
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = topAlpha),
                    middleColor,
                    Color.White.copy(alpha = bottomAlpha)
                ),
                start = androidx.compose.ui.geometry.Offset.Zero,
                end = androidx.compose.ui.geometry.Offset(
                    Float.POSITIVE_INFINITY,
                    Float.POSITIVE_INFINITY
                )
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (strong) 0.28f else 0.20f),
                    Color.White.copy(alpha = 0.05f)
                )
            ),
            shape = shape
        )
        .drawWithContent {
            drawContent()
            // Спекуляр-блик по верхней кромке — эффект «пластика».
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = if (strong) 0.45f else 0.28f),
                        Color.Transparent
                    )
                ),
                start = androidx.compose.ui.geometry.Offset(size.width * 0.14f, 1f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.86f, 1f),
                strokeWidth = 1.2f
            )
        }
}

/** Placeholder artwork when URL is blank */
const val ZyroArtworkFallback =
    "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4"

/**
 * Живой фон приложения: глубокий градиент + мягкие цветные «пятна»
 * (оранжевое, фиолетовое, циановое), создающие глубину стекла.
 */
@Composable
fun ZyroAuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize().background(ZyroBackground)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        ZyroBackgroundTop,
                        ZyroBackground,
                        Color(0xFF05070B)
                    )
                )
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ZyroAccent.copy(alpha = 0.16f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.02f),
                    radius = w * 0.90f
                ),
                radius = w * 0.90f,
                center = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.02f)
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ZyroViolet.copy(alpha = 0.13f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(w * 0.98f, h * 0.22f),
                    radius = w * 0.80f
                ),
                radius = w * 0.80f,
                center = androidx.compose.ui.geometry.Offset(w * 0.98f, h * 0.22f)
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ZyroCyan.copy(alpha = 0.08f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(w * 0.20f, h * 1.02f),
                    radius = w * 0.95f
                ),
                radius = w * 0.95f,
                center = androidx.compose.ui.geometry.Offset(w * 0.20f, h * 1.02f)
            )
        }

        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(ZyroShapes.XL),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.zyroPlasticGlass(shape),
        content = content
    )
}

@Composable
fun ZyroButton(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = ZyroAnim.SpringPress,
        label = "button_press"
    )

    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(52.dp)
            .zyroPlasticGlass(
                shape = RoundedCornerShape(26.dp),
                strong = true
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ZyroTextPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = ZyroTextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ZyroIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    tint: Color = ZyroTextPrimary,
    glass: Boolean = true,
    contentDescription: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1f,
        animationSpec = ZyroAnim.SpringPress,
        label = "icon_button_press"
    )

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (glass) Modifier.zyroPlasticGlass(CircleShape)
                else Modifier.clip(CircleShape)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Однострочный текст, который плавно «прокручивается», если не помещается.
 *
 * Когда текст короткий — ведёт себя как обычный Text. Когда длинный — медленно
 * едет влево, останавливается, возвращается к началу и повторяет цикл.
 * Края маскируются градиентом, чтобы обрыв не выглядел резким.
 */
@Composable
fun ZyroMarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    velocity: Dp = 26.dp,
    initialDelayMillis: Int = 1600,
    repeatDelayMillis: Int = 2200,
    fadeWidth: Dp = 18.dp
) {
    val resolvedStyle = LocalTextStyle.current.merge(style)
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = constraints.maxWidth
        val textWidthPx = remember(text, resolvedStyle, maxWidthPx) {
            if (text.isBlank() || maxWidthPx == Constraints.Infinity) {
                0
            } else {
                textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = resolvedStyle,
                    maxLines = 1,
                    softWrap = false
                ).size.width
            }
        }
        val isOverflowing = textWidthPx > maxWidthPx

        val marqueeModifier = if (isOverflowing) {
            Modifier
                // Offscreen нужен, чтобы маска с прозрачными краями не выедала фон под текстом.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (size.width > 0f) {
                        val fadePx = fadeWidth.toPx().coerceAtMost(size.width / 3f)
                        val fadeFraction = fadePx / size.width
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    fadeFraction to Color.Black,
                                    1f - fadeFraction to Color.Black,
                                    1f to Color.Transparent
                                )
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    repeatDelayMillis = repeatDelayMillis,
                    initialDelayMillis = initialDelayMillis,
                    spacing = MarqueeSpacing(48.dp),
                    velocity = velocity
                )
        } else {
            Modifier
        }

        Text(
            text = text,
            style = resolvedStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = marqueeModifier
        )
    }
}

@Composable
fun ZyroChip(text: String, icon: ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else if (isSelected) 1.04f else 1f,
        animationSpec = ZyroAnim.SpringSnappy,
        label = "chip_scale"
    )
    val bgTop by animateColorAsState(
        targetValue = if (isSelected) ZyroAccent.copy(alpha = 0.30f)
        else Color.White.copy(alpha = 0.09f),
        animationSpec = ZyroAnim.TweenColorFast,
        label = "chip_bg_top"
    )
    val bgBottom by animateColorAsState(
        targetValue = if (isSelected) ZyroAccent.copy(alpha = 0.10f)
        else Color.White.copy(alpha = 0.03f),
        animationSpec = ZyroAnim.TweenColorFast,
        label = "chip_bg_bottom"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) ZyroAccent.copy(alpha = 0.65f) else ZyroGlassBorder,
        animationSpec = ZyroAnim.TweenColorFast,
        label = "chip_border"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) ZyroAccent else ZyroTextPrimary.copy(alpha = 0.85f),
        animationSpec = ZyroAnim.TweenColorFast,
        label = "chip_content"
    )

    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
            .border(1.dp, borderColor, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZyroSearchLyricsDialog(
    initialTitle: String,
    initialArtist: String,
    onDismiss: () -> Unit,
    onSearch: (String, String) -> Unit
) {
    var titleState by remember { mutableStateOf(initialTitle) }
    var artistState by remember { mutableStateOf(initialArtist) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(ZyroShapes.XXL))
                .background(ZyroDialogBackground)
                .zyroPlasticGlass(
                    shape = RoundedCornerShape(ZyroShapes.XXL),
                    strong = true
                )
                .padding(24.dp)
        ) {
            Column {
                Text(
                    "Поиск текста",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZyroTextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = titleState,
                    onValueChange = { titleState = it },
                    label = { Text("Название трека", color = ZyroTextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ZyroShapes.M),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZyroAccent,
                        unfocusedBorderColor = ZyroTextPrimary.copy(alpha = BorderAlpha),
                        focusedTextColor = ZyroTextPrimary,
                        unfocusedTextColor = ZyroTextPrimary,
                        cursorColor = ZyroAccent
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = artistState,
                    onValueChange = { artistState = it },
                    label = { Text("Артист", color = ZyroTextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ZyroShapes.M),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZyroAccent,
                        unfocusedBorderColor = ZyroTextPrimary.copy(alpha = BorderAlpha),
                        focusedTextColor = ZyroTextPrimary,
                        unfocusedTextColor = ZyroTextPrimary,
                        cursorColor = ZyroAccent
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Отмена", color = ZyroTextSecondary, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(20.dp))
                    Button(
                        onClick = { onSearch(titleState, artistState) },
                        enabled = titleState.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ZyroAccent,
                            disabledContainerColor = ZyroTextPrimary.copy(alpha = GlassAlphaMedium)
                        ),
                        shape = RoundedCornerShape(ZyroShapes.L)
                    ) {
                        Text(
                            "Найти",
                            color = ZyroTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.getDefault(), "%d:%02d", min, sec)
}

// ============================================================
// MICRO-INTERACTIONS
// ============================================================

/**
 * Мягкое «нажатие» карточки: масштаб 1 → [pressedScale].
 * Pass тот же [interactionSource], что и в clickable.
 */
@Composable
fun Modifier.zyroPressEffect(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = ZyroAnim.SpringPress,
        label = "zyro_press_effect"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

// ============================================================
// SHIMMER / SKELETON
// ============================================================
@Composable
fun ZyroShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_x"
    )
    return Brush.linearGradient(
        colors = listOf(
            ZyroSurface.copy(alpha = 0.35f),
            ZyroTextPrimary.copy(alpha = 0.08f),
            ZyroSurface.copy(alpha = 0.35f)
        ),
        start = androidx.compose.ui.geometry.Offset(x - 200f, 0f),
        end = androidx.compose.ui.geometry.Offset(x, 400f)
    )
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = ZyroShapes.M
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(ZyroShimmerBrush())
    )
}

/** Skeleton for Home recommendations while loading */
@Composable
fun HomeRecommendationsShimmer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            cornerRadius = ZyroShapes.XL
        )
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ShimmerBox(
                    modifier = Modifier
                        .width(140.dp)
                        .height(16.dp),
                    cornerRadius = ZyroShapes.XS
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(2) {
                        ShimmerBox(
                            modifier = Modifier
                                .width(150.dp)
                                .height(140.dp),
                            cornerRadius = ZyroShapes.L
                        )
                    }
                }
            }
        }
    }
}

/** Offline badge overlay for artwork */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(ZyroSuccess)
            .border(1.dp, ZyroBackground, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "✓",
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}