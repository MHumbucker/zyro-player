package com.zyro.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zyro.player.data.remote.PlaylistInfo
import com.zyro.player.data.remote.TrackInfo

/**
 * Источник для экрана «Добавить из»: лайки, серверный или локальный плейлист.
 */
@Immutable
data class PlaylistAddSource(
    val key: String,
    val title: String,
    val subtitle: String,
    val isLikes: Boolean = false,
    val playlist: PlaylistInfo? = null,
    val localPlaylistId: Long? = null
)

/**
 * Полноэкранный выбор треков с чекбоксами. Показывает треки выбранного
 * источника и отдаёт отмеченные элементы наверх по кнопке «Добавить».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistAddTracksPicker(
    source: PlaylistAddSource,
    existingUrls: Set<String>,
    onDismiss: () -> Unit,
    loadTracks: suspend (PlaylistAddSource) -> List<TrackInfo>,
    onAddTracks: (List<TrackInfo>) -> Unit
) {
    var tracks by remember(source.key) { mutableStateOf<List<TrackInfo>>(emptyList()) }
    var isLoading by remember(source.key) { mutableStateOf(true) }
    var selectedUrls by remember(source.key) { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember(source.key) { mutableStateOf("") }

    LaunchedEffect(source.key) {
        isLoading = true
        tracks = loadTracks(source)
        isLoading = false
    }

    BackHandler(onBack = onDismiss)

    ZyroAuroraBackground {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Transparent
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box {
                    TopAppBar(
                        title = {
                        Column {
                            Text(
                                text = "Добавить",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = ZyroTextPrimary
                            )
                            Text(
                                text = source.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = ZyroTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = ZyroTextPrimary
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            enabled = selectedUrls.isNotEmpty(),
                            onClick = {
                                onAddTracks(tracks.filter { it.originalUrl in selectedUrls })
                            }
                        ) {
                            Text(
                                text = if (selectedUrls.isEmpty()) "Добавить"
                                else "Добавить (${selectedUrls.size})",
                                color = if (selectedUrls.isEmpty()) ZyroTextSecondary else ZyroAccent,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    placeholder = { Text("Поиск треков", color = ZyroTextSecondary) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = ZyroTextSecondary
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = ZyroAccent)
                        }
                    }

                    tracks.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Здесь пока нет треков",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ZyroTextSecondary
                            )
                        }
                    }

                    else -> {
                        val filtered = remember(tracks, query) {
                            if (query.isBlank()) {
                                tracks
                            } else {
                                tracks.filter {
                                    it.title.contains(query, ignoreCase = true) ||
                                        it.artist.contains(query, ignoreCase = true)
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                top = 4.dp,
                                bottom = 210.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(filtered, key = { it.originalUrl }) { track ->
                                val alreadyInPlaylist = track.originalUrl in existingUrls
                                val checked = track.originalUrl in selectedUrls
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(enabled = !alreadyInPlaylist) {
                                            selectedUrls = if (checked) {
                                                selectedUrls - track.originalUrl
                                            } else {
                                                selectedUrls + track.originalUrl
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = {
                                            selectedUrls = if (checked) {
                                                selectedUrls - track.originalUrl
                                            } else {
                                                selectedUrls + track.originalUrl
                                            }
                                        },
                                        enabled = !alreadyInPlaylist,
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = ZyroAccent,
                                            uncheckedColor = ZyroTextSecondary,
                                            checkmarkColor = Color.White
                                        )
                                    )
                                    AsyncImage(
                                        model = track.artworkUrl.ifBlank { ZyroArtworkFallback },
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (alreadyInPlaylist) ZyroTextSecondary else ZyroTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = track.artist.ifBlank { "Неизвестный исполнитель" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ZyroTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (alreadyInPlaylist) {
                                            Text(
                                                text = "Уже в плейлисте",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ZyroAccent
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
