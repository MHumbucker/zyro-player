package com.zyro.player.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

// ============================================================
// ZYRO UNIFIED ANIMATION TOKENS
// ============================================================

object ZyroAnim {
    // --- Durations (ms) ---
    const val Fast = 180
    const val Normal = 280
    const val Medium = 350
    const val Slow = 450

    // --- Springs ---
    val SpringSnappy = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
    val SpringSoft = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    val SpringBouncy = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )
    val SpringPress = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    // --- Tweens ---
    val TweenFast = tween<Float>(durationMillis = Fast, easing = FastOutSlowInEasing)
    val TweenNormal = tween<Float>(durationMillis = Normal, easing = FastOutSlowInEasing)
    val TweenMedium = tween<Float>(durationMillis = Medium, easing = FastOutSlowInEasing)
    val TweenSlow = tween<Float>(durationMillis = Slow, easing = FastOutSlowInEasing)

    val TweenColorFast = tween<androidx.compose.ui.graphics.Color>(
        durationMillis = Fast,
        easing = FastOutSlowInEasing
    )
    val TweenColorNormal = tween<androidx.compose.ui.graphics.Color>(
        durationMillis = Normal,
        easing = FastOutSlowInEasing
    )
}

// ============================================================
// CONTENT TRANSFORMS (for AnimatedContent)
// ============================================================

/**
 * Стандартный переход секций / табов:
 * fade + лёгкий vertical slide.
 */
fun zyroSectionTransition(): ContentTransform {
    return (fadeIn(animationSpec = tween(ZyroAnim.Medium)) +
            slideInVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { it / 4 }
            ).togetherWith(
            fadeOut(animationSpec = tween(ZyroAnim.Normal)) +
                    slideOutVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) { it / 4 }
            )
}

/**
 * Направленный переход (вперёд / назад).
 * Использовать в Library / nested navigation.
 *
 * @param forward true = вглубь, false = назад
 */
fun zyroDirectionalTransition(forward: Boolean): ContentTransform {
    val enterOffset = if (forward) 1 else -1
    val exitOffset = if (forward) -1 else 1

    return (fadeIn(animationSpec = tween(ZyroAnim.Medium)) +
            slideInHorizontally(
                animationSpec = tween(ZyroAnim.Medium, easing = FastOutSlowInEasing)
            ) { fullWidth -> fullWidth / 5 * enterOffset }
            ).togetherWith(
            fadeOut(animationSpec = tween(ZyroAnim.Normal)) +
                    slideOutHorizontally(
                        animationSpec = tween(ZyroAnim.Normal, easing = FastOutSlowInEasing)
                    ) { fullWidth -> fullWidth / 5 * exitOffset }
            )
}

/**
 * Горизонтальный таб-переход (Player ↔ Lyrics, SoundCloud tabs).
 * Направление зависит от targetState.
 */
fun zyroTabTransition(goingForward: Boolean): ContentTransform {
    return (fadeIn(animationSpec = tween(ZyroAnim.Normal)) +
            slideInHorizontally(
                animationSpec = tween(ZyroAnim.Normal, easing = FastOutSlowInEasing)
            ) { fullWidth -> if (goingForward) fullWidth else -fullWidth }
            ).togetherWith(
            fadeOut(animationSpec = tween(ZyroAnim.Normal)) +
                    slideOutHorizontally(
                        animationSpec = tween(ZyroAnim.Normal, easing = FastOutSlowInEasing)
                    ) { fullWidth -> if (goingForward) -fullWidth else fullWidth }
            )
}

/**
 * Смена трека в мини-плеере / full player.
 * Направление от свайпа (offsetX < 0 → next).
 */
fun zyroTrackChangeTransition(goingNext: Boolean): ContentTransform {
    return (fadeIn(animationSpec = tween(ZyroAnim.Normal)) +
            slideInHorizontally(
                animationSpec = tween(ZyroAnim.Normal)
            ) { width -> if (goingNext) width else -width }
            ).togetherWith(
            fadeOut(animationSpec = tween(ZyroAnim.Normal)) +
                    slideOutHorizontally(
                        animationSpec = tween(ZyroAnim.Normal)
                    ) { width -> if (goingNext) -width else width }
            )
}

/**
 * Fade-only (когда идёт жест свайпа — не конфликтовать с graphicsLayer).
 */
fun zyroFadeOnlyTransition(): ContentTransform {
    return fadeIn(animationSpec = tween(ZyroAnim.Medium))
        .togetherWith(fadeOut(animationSpec = tween(ZyroAnim.Normal)))
}

/**
 * Переход детальных экранов вглубь (главный → «Смотреть все», вложенные списки).
 * Лёгкий горизонтальный сдвиг + fade — как в референсе, без резких скачков.
 *
 * @param forward true = вглубь, false = назад
 */
fun zyroDetailTransition(forward: Boolean): ContentTransform {
    val enterOffset = if (forward) 1 else -1
    val exitOffset = if (forward) -1 else 1

    return (fadeIn(animationSpec = tween(ZyroAnim.Normal, easing = FastOutSlowInEasing)) +
            slideInHorizontally(
                animationSpec = tween(ZyroAnim.Normal, easing = FastOutSlowInEasing)
            ) { width -> (width * 0.15f).toInt() * enterOffset })
        .togetherWith(
            fadeOut(animationSpec = tween(ZyroAnim.Fast)) +
                    slideOutHorizontally(
                        animationSpec = tween(ZyroAnim.Fast, easing = FastOutSlowInEasing)
                    ) { width -> (width * 0.15f).toInt() * exitOffset }
        )
}

/**
 * Состояние контента (loading → content, empty → data).
 * Fade + subtle scale.
 */
fun zyroStateTransition(): ContentTransform {
    return (fadeIn(animationSpec = tween(ZyroAnim.Medium)) +
            scaleIn(
                initialScale = 0.97f,
                animationSpec = tween(ZyroAnim.Medium)
            )
            ).togetherWith(
            fadeOut(animationSpec = tween(ZyroAnim.Normal)) +
                    scaleOut(
                        targetScale = 0.97f,
                        animationSpec = tween(ZyroAnim.Normal)
                    )
            )
}

// ============================================================
// VISIBILITY TRANSITIONS (for AnimatedVisibility)
// ============================================================

/**
 * Появление модальных экранов деталей (артист/альбом/плейлист/плеер).
 * Мягкий подъём снизу + fade + лёгкий «пластиковый» scale.
 */
val ZyroModalEnter = slideInVertically(
    initialOffsetY = { it / 3 },
    animationSpec = spring(
        dampingRatio = 0.9f,
        stiffness = Spring.StiffnessMediumLow
    )
) + fadeIn(animationSpec = tween(ZyroAnim.Medium, easing = LinearOutSlowInEasing)) +
        scaleIn(
            initialScale = 0.97f,
            animationSpec = tween(ZyroAnim.Medium, easing = FastOutSlowInEasing)
        )

val ZyroModalExit = slideOutVertically(
    targetOffsetY = { it },
    animationSpec = tween(ZyroAnim.Normal, easing = FastOutLinearInEasing)
) + fadeOut(animationSpec = tween(ZyroAnim.Fast)) +
        scaleOut(
            targetScale = 0.98f,
            animationSpec = tween(ZyroAnim.Normal, easing = FastOutLinearInEasing)
        )

/**
 * AnimatedVisibility для полноэкранных деталей.
 *
 * Важно: при `visible = false` контент `AnimatedVisibility` продолжает
 * композироваться во время exit-анимации, но если снаружи стоял
 * `state?.let { ... }`, то содержимое уже пустое и экран исчезает без
 * анимации. Этот helper запоминает последнее не-null значение и держит его
 * до конца exit-перехода.
 */
@Composable
fun <T : Any> ZyroRetainedVisibility(
    value: T?,
    modifier: Modifier = Modifier,
    enter: EnterTransition = ZyroModalEnter,
    exit: ExitTransition = ZyroModalExit,
    content: @Composable (T) -> Unit
) {
    AnimatedVisibility(
        visible = value != null,
        modifier = modifier,
        enter = enter,
        exit = exit
    ) {
        var retained by remember { mutableStateOf(value) }
        if (value != null) retained = value
        retained?.let { content(it) }
    }
}

/** Появление history / панелей сверху */
val ZyroExpandVertically = fadeIn(
    animationSpec = spring(stiffness = Spring.StiffnessMedium)
) + expandVertically(
    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
)

val ZyroShrinkVertically = fadeOut(
    animationSpec = tween(ZyroAnim.Fast)
) + shrinkVertically(
    animationSpec = tween(ZyroAnim.Normal)
)

/** Scrim (затемнение) для bottom sheets */
val ZyroScrimEnter = fadeIn(
    animationSpec = spring(stiffness = Spring.StiffnessMedium)
)
val ZyroScrimExit = fadeOut(
    animationSpec = tween(ZyroAnim.Fast)
)

/**
 * Bottom sheet slide + bounce.
 * Передавать высоту листа в px через density.
 */
fun zyroSheetEnter(sheetHeightPx: Int) = slideInVertically(
    animationSpec = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
) { sheetHeightPx }

fun zyroSheetExit(sheetHeightPx: Int) = slideOutVertically(
    animationSpec = tween(ZyroAnim.Normal)
) { sheetHeightPx }

/** Full player sheet: slide up from bottom + fade */
val ZyroFullPlayerEnter = slideInVertically(
    animationSpec = tween(ZyroAnim.Medium, easing = FastOutSlowInEasing)
) { it } + fadeIn(animationSpec = tween(ZyroAnim.Normal))

val ZyroFullPlayerExit = slideOutVertically(
    animationSpec = tween(ZyroAnim.Normal, easing = FastOutSlowInEasing)
) { it } + fadeOut(animationSpec = tween(ZyroAnim.Fast))

// ============================================================
// MICRO-INTERACTIONS
// ============================================================

/**
 * Scale при нажатии (0.96–1.0).
 * Использовать с collectIsPressedAsState.
 */
@Composable
fun rememberPressScale(isPressed: Boolean): Float {
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = ZyroAnim.SpringPress,
        label = "press_scale"
    )
    return scale
}

/**
 * Scale выбранного чипа / элемента (1.0 → 1.06).
 */
@Composable
fun rememberSelectionScale(isSelected: Boolean): Float {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.06f else 1f,
        animationSpec = tween(ZyroAnim.Normal, easing = FastOutSlowInEasing),
        label = "selection_scale"
    )
    return scale
}

/**
 * Бесконечный pulse (playing indicator).
 */
@Composable
fun rememberPulseScale(
    minScale: Float = 0.7f,
    maxScale: Float = 1f,
    durationMs: Int = 550
): Float {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val scale by infinite.animateFloat(
        initialValue = minScale,
        targetValue = maxScale,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    return scale
}

/**
 * Modifier-хелпер: применяет graphicsLayer scale.
 */
fun Modifier.zyroScale(scale: Float): Modifier = this.graphicsLayer {
    scaleX = scale
    scaleY = scale
}
