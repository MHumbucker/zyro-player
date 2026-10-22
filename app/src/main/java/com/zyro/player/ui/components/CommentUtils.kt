package com.zyro.player.ui.components

import java.util.Locale

/**
 * Единый формат таймкода комментария.
 * До часа — `м:сс`, начиная с часа — `ч:мм:сс`.
 */
fun formatCommentTimestamp(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
