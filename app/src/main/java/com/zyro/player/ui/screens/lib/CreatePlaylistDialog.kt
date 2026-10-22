package com.zyro.player.ui.screens

import androidx.compose.runtime.Composable
import com.zyro.player.domain.PlaylistDraft
import com.zyro.player.ui.components.PlaylistFormDialog

/**
 * Создание плейлиста. Для авторизованного пользователя доступны серверные
 * поля (приватность, жанр, теги); для локального — только название, описание
 * и обложка.
 */
@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (PlaylistDraft) -> Unit,
    showServerFields: Boolean = true
) {
    PlaylistFormDialog(
        dialogTitle = "Новый плейлист",
        initial = PlaylistDraft(
            title = "",
            isPublic = false
        ),
        onDismiss = onDismiss,
        onSave = onCreate,
        showServerFields = showServerFields,
        confirmLabel = "Создать"
    )
}
